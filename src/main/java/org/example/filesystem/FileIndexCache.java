package org.example.filesystem;

import org.example.filesystem.dto.DirectoryListResult;
import org.example.filesystem.dto.FileEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件/目录索引缓存（内存版，带 TTL）。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>缓存“文件名 -> 路径列表”，让“按文件名定位路径”无需每次都递归扫描磁盘。</li>
 *   <li>缓存“目录列表结果”，让重复列目录请求尽量直接命中缓存，减少磁盘 IO。</li>
 *   <li>当缓存未命中或用户要求刷新时，再回退到本地扫描，并同步更新缓存。</li>
 * </ul>
 * <p>
 * 重要说明：
 * <ul>
 *   <li>这是一个“性能优化”缓存，不保证强一致；依赖 TTL 与写入时的失效/更新来降低陈旧风险。</li>
 *   <li>为避免内存无限增长，缓存有最大容量上限；超过上限会做清理/淘汰。</li>
 * </ul>
 */
public class FileIndexCache {

    private final boolean enabled;

    // 文件名索引：key 为“规范化后的文件名”（默认小写），value 为该名称对应的多个路径候选
    private final ConcurrentHashMap<String, NameBucket> nameIndex = new ConcurrentHashMap<>();
    private final AtomicInteger nameKeyCount = new AtomicInteger(0);
    private final AtomicLong nameRecordCounter = new AtomicLong(0);

    private final long nameIndexTtlMillis;
    private final int nameIndexMaxNames;
    private final int nameIndexMaxPathsPerName;

    // 目录列表缓存：缓存的是“某一次 fs_list_directory 请求的结果”（包含分页/过滤参数）
    private final TtlLruCache<DirectoryListCacheKey, DirectoryListResult> directoryListCache;

    public FileIndexCache(FileServerProperties properties) {
        this.enabled = properties.isCacheEnabled();
        this.nameIndexTtlMillis = safeToMillis(properties.getCacheNameIndexTtl(), Duration.ofMinutes(30));
        this.nameIndexMaxNames = Math.max(1, properties.getCacheNameIndexMaxNames());
        this.nameIndexMaxPathsPerName = Math.max(1, properties.getCacheNameIndexMaxPathsPerName());

        Duration dirTtl = Objects.requireNonNullElse(properties.getCacheDirectoryTtl(), Duration.ofMinutes(30));
        int dirMaxEntries = Math.max(1, properties.getCacheDirectoryMaxEntries());
        this.directoryListCache = new TtlLruCache<>(dirMaxEntries, dirTtl);
    }

    /**
     * 是否启用缓存（由 {@code app.fs.cache-enabled} 控制）。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 记录一个“已知的文件/目录路径”，用于文件名索引。
     *
     * @param rootId    root 标识
     * @param name      文件名/目录名（不含路径）
     * @param path      相对 root 的路径（统一使用 / 分隔）
     * @param directory 是否目录
     * @param file      是否普通文件
     */
    public void recordName(String rootId, String name, String path, boolean directory, boolean file) {
        if (!enabled) {
            return;
        }
        if (name == null || name.isBlank() || path == null || path.isBlank()) {
            return;
        }
        String key = normalizeNameKey(name);
        long now = System.currentTimeMillis();
        NameBucket bucket = nameIndex.compute(key, (k, existing) -> {
            NameBucket b = existing;
            if (b == null) {
                b = new NameBucket();
                nameKeyCount.incrementAndGet();
            }
            b.touch(now, nameIndexTtlMillis);
            b.put(new IndexedPath(rootId, name, path, directory, file, now), nameIndexMaxPathsPerName);
            return b;
        });

        // 轻量清理：不在每次写入都做 O(n) 的全表清理，避免影响批量遍历性能
        long c = nameRecordCounter.incrementAndGet();
        if (c % 2000 == 0) {
            cleanupNameIndexIfNeeded();
        }
    }

    /**
     * 通过文件名从索引中获取候选路径。
     *
     * @param name          文件名/目录名
     * @param caseSensitive 是否区分大小写（默认 false 更符合 Windows 习惯）
     * @param wantDirectory 是否仅返回目录（true/false/null 表示不过滤）
     * @param wantFile      是否仅返回文件（true/false/null 表示不过滤）
     */
    public List<IndexedPath> lookupByName(String name, boolean caseSensitive, Boolean wantDirectory, Boolean wantFile) {
        if (!enabled) {
            return List.of();
        }
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String key = normalizeNameKey(name);
        NameBucket bucket = nameIndex.get(key);
        if (bucket == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (bucket.isExpired(now)) {
            // 过期则移除（尽量不返回陈旧结果）
            if (nameIndex.remove(key, bucket)) {
                nameKeyCount.decrementAndGet();
            }
            return List.of();
        }
        bucket.touch(now, nameIndexTtlMillis);

        List<IndexedPath> all = bucket.snapshot();
        if ((wantDirectory == null || !wantDirectory) && (wantFile == null || !wantFile)) {
            if (!caseSensitive) {
                return all;
            }
            // caseSensitive=true：进一步按“原始名称”过滤（索引 key 默认小写）
            List<IndexedPath> filtered = new ArrayList<>(Math.min(all.size(), 32));
            for (IndexedPath p : all) {
                if (p != null && name.equals(p.name())) {
                    filtered.add(p);
                }
            }
            return filtered;
        }

        List<IndexedPath> filtered = new ArrayList<>(Math.min(all.size(), 32));
        for (IndexedPath p : all) {
            if (caseSensitive && (p == null || !name.equals(p.name()))) {
                continue;
            }
            if (wantDirectory != null && wantDirectory && !p.directory()) {
                continue;
            }
            if (wantFile != null && wantFile && !p.file()) {
                continue;
            }
            filtered.add(p);
        }
        return filtered;
    }

    /**
     * 获取目录列表缓存（命中则直接返回）。
     */
    public DirectoryListResult getDirectoryListCache(DirectoryListCacheKey key) {
        if (!enabled) {
            return null;
        }
        return directoryListCache.get(key);
    }

    /**
     * 写入目录列表缓存，并把其中的条目写入“文件名索引”。
     */
    public void putDirectoryListCache(DirectoryListCacheKey key, DirectoryListResult result) {
        if (!enabled || key == null || result == null) {
            return;
        }
        directoryListCache.put(key, result);

        // 将“当前这次列目录返回的条目”写入文件名索引（这是逐步增量索引，不会强制扫全目录）
        if (result.entries() != null) {
            for (FileEntry e : result.entries()) {
                if (e == null || e.name() == null || e.path() == null) {
                    continue;
                }
                recordName(key.rootId(), e.name(), e.path(), e.directory(), e.file());
            }
        }
    }

    /**
     * 失效某个目录下的所有“目录列表缓存”（例如写入/创建文件后，避免返回旧的目录列表）。
     */
    public void invalidateDirectoryLists(String rootId, String directoryPath) {
        if (!enabled) {
            return;
        }
        directoryListCache.invalidateByDirectory(rootId, directoryPath);
    }

    private void cleanupNameIndexIfNeeded() {
        if (!enabled) {
            return;
        }
        int current = nameKeyCount.get();
        if (current <= nameIndexMaxNames) {
            // 先做一次过期清理（可能会顺带降低 size）
            cleanupExpiredNameBuckets();
            return;
        }

        // 超出上限：先清理过期，再做少量淘汰（近似）
        cleanupExpiredNameBuckets();
        current = nameKeyCount.get();
        if (current <= nameIndexMaxNames) {
            return;
        }

        int toRemove = Math.max(1, current - nameIndexMaxNames);
        Iterator<Map.Entry<String, NameBucket>> it = nameIndex.entrySet().iterator();
        while (toRemove > 0 && it.hasNext()) {
            Map.Entry<String, NameBucket> e = it.next();
            it.remove();
            nameKeyCount.decrementAndGet();
            toRemove--;
        }
    }

    private void cleanupExpiredNameBuckets() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, NameBucket>> it = nameIndex.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, NameBucket> e = it.next();
            if (e.getValue().isExpired(now)) {
                it.remove();
                nameKeyCount.decrementAndGet();
            }
        }
    }

    private static String normalizeNameKey(String name) {
        // 文件名索引默认不区分大小写（更贴近 Windows 常见行为）
        return name.trim().toLowerCase();
    }

    private static long safeToMillis(Duration duration, Duration fallback) {
        Duration d = (duration == null) ? fallback : duration;
        try {
            long ms = d.toMillis();
            return Math.max(1, ms);
        } catch (Exception ignored) {
            return Math.max(1, fallback.toMillis());
        }
    }

    /**
     * 文件名索引的“单条记录”。
     *
     * @param rootId        root 标识
     * @param path          相对 root 的路径（统一使用 / 分隔）
     * @param directory     是否目录
     * @param file          是否普通文件
     * @param lastSeenAtMs  最后一次记录该路径的时间戳（毫秒）
     */
    public record IndexedPath(
            String rootId,
            String name,
            String path,
            boolean directory,
            boolean file,
            long lastSeenAtMs
    ) {
        public Instant lastSeenAt() {
            return Instant.ofEpochMilli(lastSeenAtMs);
        }
    }

    /**
     * 目录列表缓存 key：严格对应一次列目录请求的输入参数，避免“缓存不完整导致误导”。
     */
    public record DirectoryListCacheKey(
            String rootId,
            String directoryPath,
            boolean includeHidden,
            String glob,
            boolean onlyDirectories,
            boolean onlyFiles,
            int offset,
            int limit
    ) {
        public DirectoryListCacheKey {
            rootId = (rootId == null) ? "root0" : rootId;
            directoryPath = (directoryPath == null) ? "." : directoryPath;
            glob = (glob == null || glob.isBlank()) ? null : glob;
            offset = Math.max(0, offset);
            limit = Math.max(1, limit);
        }
    }

    /**
     * 某个文件名对应的多个路径候选（同名文件可能存在于不同目录）。
     */
    private static final class NameBucket {
        private final ConcurrentHashMap<String, IndexedPath> paths = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        private volatile long expiresAtMs;

        public void touch(long now, long ttlMillis) {
            this.expiresAtMs = now + ttlMillis;
        }

        public boolean isExpired(long now) {
            return now > expiresAtMs;
        }

        public void put(IndexedPath path, int maxPathsPerName) {
            String key = path.rootId() + "|" + path.path();
            IndexedPath prev = paths.put(key, path);
            if (prev == null) {
                order.add(key);
            }

            // 超过上限则移除最旧的路径候选
            while (paths.size() > maxPathsPerName) {
                String oldest = order.poll();
                if (oldest == null) {
                    break;
                }
                paths.remove(oldest);
            }
        }

        public List<IndexedPath> snapshot() {
            if (paths.isEmpty()) {
                return List.of();
            }
            return List.copyOf(paths.values());
        }
    }

    /**
     * 带 TTL 的 LRU 缓存（用于目录列表缓存）。
     * <p>
     * 目录列表缓存写入频率相对较低，因此使用 synchronized + LinkedHashMap（accessOrder=true）即可。
     */
    private static final class TtlLruCache<K, V> {
        private final int maxEntries;
        private final long ttlMillis;
        private final Object lock = new Object();

        // accessOrder=true：每次 get 会把条目移到末尾，实现近似 LRU
        private final LinkedHashMap<K, CacheValue<V>> map = new LinkedHashMap<>(128, 0.75f, true);

        public TtlLruCache(int maxEntries, Duration ttl) {
            this.maxEntries = Math.max(1, maxEntries);
            this.ttlMillis = safeToMillis(ttl, Duration.ofMinutes(10));
        }

        public V get(K key) {
            if (key == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            synchronized (lock) {
                CacheValue<V> value = map.get(key);
                if (value == null) {
                    return null;
                }
                if (value.expiresAtMs <= now) {
                    map.remove(key);
                    return null;
                }
                return value.value;
            }
        }

        public void put(K key, V value) {
            if (key == null || value == null) {
                return;
            }
            long now = System.currentTimeMillis();
            long expiresAt = now + ttlMillis;
            synchronized (lock) {
                map.put(key, new CacheValue<>(value, expiresAt));
                // 超出容量则淘汰最旧的条目
                while (map.size() > maxEntries) {
                    Iterator<Map.Entry<K, CacheValue<V>>> it = map.entrySet().iterator();
                    if (!it.hasNext()) {
                        break;
                    }
                    it.next();
                    it.remove();
                }
            }
        }

        public void invalidateByDirectory(String rootId, String directoryPath) {
            if (rootId == null || directoryPath == null) {
                return;
            }
            synchronized (lock) {
                Iterator<K> it = map.keySet().iterator();
                while (it.hasNext()) {
                    K k = it.next();
                    if (k instanceof DirectoryListCacheKey dk) {
                        if (rootId.equals(dk.rootId()) && directoryPath.equals(dk.directoryPath())) {
                            it.remove();
                        }
                    }
                }
            }
        }

        @SuppressWarnings("unused")
        public int size() {
            synchronized (lock) {
                return map.size();
            }
        }

        @SuppressWarnings("unused")
        public Map<K, V> snapshot() {
            synchronized (lock) {
                Map<K, V> out = new LinkedHashMap<>();
                for (Map.Entry<K, CacheValue<V>> e : map.entrySet()) {
                    out.put(e.getKey(), e.getValue().value);
                }
                return Collections.unmodifiableMap(out);
            }
        }

        private record CacheValue<V>(V value, long expiresAtMs) {
        }
    }
}
