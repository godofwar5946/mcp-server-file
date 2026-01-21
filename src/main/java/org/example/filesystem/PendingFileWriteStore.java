package org.example.filesystem;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认的“文件写入请求”存储（内存版）。
 * <p>
 * 工作流：
 * <ol>
 *   <li>{@code fs_prepare_write_file}：生成 token，并把待写入内容（字节数组）暂存在内存。</li>
 *   <li>{@code fs_confirm_write_file}：用户明确 {@code confirm=true} 后，用 token 取出内容并真正写入。</li>
 * </ol>
 * <p>
 * 安全/性能考虑：
 * <ul>
 *   <li>每个 token 有 TTL，超时自动失效，避免长期占用内存。</li>
 *   <li>限制单条待写入内容最大字节数，避免异常请求撑爆内存。</li>
 *   <li>仅用于单实例/单进程场景；如果需要多实例共享 token，请替换为 Redis/数据库等外部存储。</li>
 * </ul>
 */
public class PendingFileWriteStore {

    private final Duration ttl;
    private final long maxBytesPerItem;
    private final ConcurrentHashMap<String, PendingFileWrite> store = new ConcurrentHashMap<>();

    public PendingFileWriteStore(Duration ttl, long maxBytesPerItem) {
        this.ttl = ttl;
        this.maxBytesPerItem = maxBytesPerItem;
    }

    public PendingFileWrite create(
            String rootId,
            String displayPath,
            Path targetFile,
            byte[] bytes,
            boolean overwrite,
            boolean createParents,
            boolean expectExists,
            String expectedSha256,
            String newSha256
    ) {
        cleanupExpired();
        if (bytes.length > maxBytesPerItem) {
            throw new IllegalArgumentException("待确认写入内容过大：" + bytes.length + " 字节（上限 " + maxBytesPerItem + "）");
        }
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        PendingFileWrite pending = new PendingFileWrite(
                token,
                rootId,
                displayPath,
                targetFile,
                bytes,
                overwrite,
                createParents,
                expectExists,
                expectedSha256,
                newSha256,
                now,
                now.plus(ttl)
        );
        store.put(token, pending);
        return pending;
    }

    public PendingFileWrite get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        PendingFileWrite pending = store.get(token);
        if (pending == null) {
            return null;
        }
        if (pending.isExpired()) {
            store.remove(token);
            return null;
        }
        return pending;
    }

    public PendingFileWrite remove(String token) {
        PendingFileWrite pending = store.remove(token);
        if (pending == null) {
            return null;
        }
        return pending.isExpired() ? null : pending;
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        for (Map.Entry<String, PendingFileWrite> entry : store.entrySet()) {
            PendingFileWrite value = entry.getValue();
            if (value.expiresAt().isBefore(now)) {
                store.remove(entry.getKey());
            }
        }
    }

    public record PendingFileWrite(
            String token,
            String rootId,
            String displayPath,
            Path targetFile,
            byte[] bytes,
            boolean overwrite,
            boolean createParents,
            boolean expectExists,
            String expectedSha256,
            String newSha256,
            Instant createdAt,
            Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
