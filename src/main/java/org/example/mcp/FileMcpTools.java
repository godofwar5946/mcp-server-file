package org.example.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.filesystem.FileIndexCache;
import org.example.filesystem.FileServerProperties;
import org.example.filesystem.HashingUtils;
import org.example.filesystem.PendingFileWriteStore;
import org.example.filesystem.SecurePathResolver;
import org.example.filesystem.dto.AllowedRootsResult;
import org.example.filesystem.dto.DirectoryListResult;
import org.example.filesystem.dto.FileEntry;
import org.example.filesystem.dto.FilePatchPrepareResult;
import org.example.filesystem.dto.FileReadFilteredLine;
import org.example.filesystem.dto.FileReadFilteredResult;
import org.example.filesystem.dto.FileReadLinesResult;
import org.example.filesystem.dto.FileReadResult;
import org.example.filesystem.dto.FileReadRangeResult;
import org.example.filesystem.dto.FileSearchMatch;
import org.example.filesystem.dto.FileSearchResult;
import org.example.filesystem.dto.FileTreeEntry;
import org.example.filesystem.dto.FileTreeResult;
import org.example.filesystem.dto.FileWriteConfirmResult;
import org.example.filesystem.dto.FileWritePrepareResult;
import org.example.filesystem.dto.NameResolveEntry;
import org.example.filesystem.dto.NameResolveResult;
import org.example.filesystem.dto.StepModelInfoResult;
import org.example.filesystem.dto.step.StepEntityListResult;
import org.example.filesystem.step.StepDataAnalyzer;
import org.example.filesystem.step.StepModelInfoParser;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件系统 MCP 工具集合。
 * <p>
 * 提供能力：
 * <ul>
 *   <li>列出根目录白名单（{@code fs_list_roots}）。</li>
 *   <li>列目录（{@code fs_list_directory}）与递归目录树（{@code fs_list_tree}）。</li>
 *   <li>读文件（{@code fs_read_file}）。</li>
 *   <li>写文件（{@code fs_prepare_write_file} -> {@code fs_confirm_write_file} 两段式确认）。</li>
 * </ul>
 * <p>
 * 安全策略：
 * <ul>
 *   <li>仅允许访问 {@code app.fs.roots} 白名单目录范围内的路径。</li>
 *   <li>默认禁止 symlink/junction（{@code app.fs.allow-symlink=false}），防止路径逃逸。</li>
 *   <li>写入必须二次确认，且支持基于 sha256 的“是否被外部修改”校验。</li>
 * </ul>
 * <p>
 * 性能策略：
 * <ul>
 *   <li>目录列表支持分页与上限保护，避免一次性返回过大。</li>
 *   <li>目录树支持 {@code maxDepth/maxEntries} 上限，且可选择仅返回目录以减小响应体积。</li>
 *   <li>文件读取支持 {@code maxBytes} 截断，避免把大文件全部读入内存。</li>
 * </ul>
 */
@Component
public class FileMcpTools {

    /**
     * 常见文本文件扩展名列表：
     * <p>
     * 用于快速判断“更可能是文本”的文件；如果不是这些扩展名，则会进一步用 UTF-8 校验来决定返回 utf-8 或 base64。
     */
    private static final List<String> TEXT_EXTENSIONS = List.of(
            ".txt", ".md", ".json", ".yaml", ".yml", ".xml", ".java", ".kt", ".kts",
            ".properties", ".gradle", ".groovy", ".sql", ".html", ".htm", ".css", ".js",
            ".ts", ".tsx", ".jsx", ".sh", ".bat", ".cmd", ".ps1", ".toml", ".ini", ".csv",
            ".log", ".conf"
    );

    /**
     * STEP 模型信息解析的字节扫描上限。
     * <p>
     * 说明：
     * <ul>
     *   <li>解析装配/几何/尺寸等信息需要扫描 DATA 段，而 DATA 往往远大于 HEADER。</li>
     *   <li>默认扫描 16MB 以覆盖多数中小模型；超大模型可通过工具参数 maxBytes 提升。</li>
     *   <li>设置硬上限 128MB，避免一次调用把超大文件全部读入内存。</li>
     * </ul>
     */
    private static final long STEP_MODEL_INFO_DEFAULT_MAX_BYTES = 16L * 1024 * 1024;
    private static final long STEP_MODEL_INFO_MAX_BYTES = 128L * 1024 * 1024;

    /**
     * STEP DATA 段实体扫描上限（控制解析工作量）。
     * <p>
     * DATA 段实体通常形如：{@code #123=ENTITY_NAME(...);}，大型模型可能有数百万条。
     * 这里通过实体数量上限保护 CPU/内存与响应时间；需要更完整结果时可调大 maxEntities，但仍有硬上限保护。
     */
    private static final long STEP_DATA_DEFAULT_MAX_ENTITIES = 500_000L;
    private static final long STEP_DATA_MAX_ENTITIES = 5_000_000L;

    /**
     * patch/search 参数解析用的 JSON 解析器。
     * <p>
     * 这里使用默认配置即可：我们只需要解析入参，不做复杂的序列化定制。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final FileServerProperties properties;
    private final SecurePathResolver pathResolver;
    private final PendingFileWriteStore pendingWriteStore;
    private final FileIndexCache indexCache;

    public FileMcpTools(FileServerProperties properties, SecurePathResolver pathResolver, PendingFileWriteStore pendingWriteStore, FileIndexCache indexCache) {
        // properties：服务端配置（访问白名单、limit/bytes 上限等）
        this.properties = properties;
        // pathResolver：负责把用户输入的路径解析成“受控的绝对路径”，并做越界/链接逃逸校验
        this.pathResolver = pathResolver;
        // pendingWriteStore：两段式写入确认的 token 存储（内存版）
        this.pendingWriteStore = pendingWriteStore;
        // indexCache：文件名/目录列表缓存（用于性能优化）
        this.indexCache = indexCache;
    }

    @Tool(
            name = "fs_list_roots",
            description = "列出 MCP Server 允许访问的根目录（rootId + path）。"
    )
    /**
     * 返回服务端允许访问的根目录白名单。
     * <p>
     * 调用方后续访问文件/目录时，建议优先使用 rootId + 相对路径，避免绝对路径在不同环境不一致。
     */
    public AllowedRootsResult listRoots() {
        return new AllowedRootsResult(pathResolver.listRoots());
    }

    @Tool(
            name = "fs_list_directory",
            description = "列出目录下的文件/子目录（非递归，支持 limit/offset 分页；默认不包含隐藏文件）。"
    )
    /**
     * 列出目录内容（非递归）。
     * <p>
     * 性能建议：
     * <ul>
     *   <li>目录非常大时，请使用 {@code limit/offset} 分页。</li>
     *   <li>只关心某类文件时，请使用 {@code glob}（例如 {@code *.java}）减少返回体积。</li>
     * </ul>
     */
    public DirectoryListResult listDirectory(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(required = false, description = "目录路径（相对 rootId 或绝对路径；为空则为根目录）") String path,
            @ToolParam(required = false, description = "分页大小（默认 app.fs.list-default-limit，上限 app.fs.list-max-limit）") Integer limit,
            @ToolParam(required = false, description = "偏移量，从 0 开始") Integer offset,
            @ToolParam(required = false, description = "可选 glob 过滤（仅匹配文件名），例如 *.java") String glob,
            @ToolParam(required = false, description = "是否包含隐藏文件（默认 app.fs.include-hidden-by-default）") Boolean includeHidden,
            @ToolParam(required = false, description = "只返回目录（true/false）") Boolean onlyDirectories,
            @ToolParam(required = false, description = "只返回文件（true/false）") Boolean onlyFiles,
            @ToolParam(required = false, description = "是否强制刷新（true 时跳过缓存，直接读取本地并更新缓存；默认 false）") Boolean refresh
    ) {
        // 解析并校验路径：必须位于 roots 白名单范围内
        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path dir = resolved.absolutePath();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是目录：" + resolved.displayPath());
        }

        // 目录过滤选项
        boolean includeHiddenResolved = (includeHidden != null) ? includeHidden : properties.isIncludeHiddenByDefault();
        boolean onlyDirs = Boolean.TRUE.equals(onlyDirectories);
        boolean onlyFilesResolved = Boolean.TRUE.equals(onlyFiles);
        if (onlyDirs && onlyFilesResolved) {
            throw new IllegalArgumentException("参数冲突：onlyDirectories 和 onlyFiles 不能同时为 true");
        }

        // 分页参数（服务端会做上限保护）
        int resolvedOffset = (offset == null) ? 0 : Math.max(0, offset);
        int resolvedLimit = resolveLimit(limit);

        // 目录列表缓存：以“请求参数”为维度缓存结果，避免误导（例如 glob/offset/limit 不同导致结果不同）
        boolean refreshResolved = Boolean.TRUE.equals(refresh);
        String dirDisplayPathForCache = normalizeBasePath(resolved.displayPath());
        FileIndexCache.DirectoryListCacheKey cacheKey = new FileIndexCache.DirectoryListCacheKey(
                resolved.rootId(),
                dirDisplayPathForCache,
                includeHiddenResolved,
                glob,
                onlyDirs,
                onlyFilesResolved,
                resolvedOffset,
                resolvedLimit
        );
        if (!refreshResolved && indexCache != null && indexCache.isEnabled()) {
            DirectoryListResult cached = indexCache.getDirectoryListCache(cacheKey);
            if (cached != null) {
                List<String> warnings = new ArrayList<>();
                if (cached.warnings() != null) {
                    warnings.addAll(cached.warnings());
                }
                warnings.add("目录列表来自缓存（可能略有延迟）；如需刷新请设置 refresh=true。");
                return new DirectoryListResult(
                        cached.rootId(),
                        cached.path(),
                        cached.offset(),
                        cached.limit(),
                        cached.hasMore(),
                        cached.entries(),
                        warnings.isEmpty() ? null : warnings
                );
            }
        }

        // glob 仅匹配文件名（不会匹配完整路径），避免调用方写出复杂的跨平台路径分隔符匹配
        final PathMatcher matcher = (glob != null && !glob.isBlank())
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob)
                : null;

        List<FileEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean hasMore = false;
        int seen = 0;
        // 使用 DirectoryStream 做流式遍历（比一次性 list() 更省内存）
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (!includeHiddenResolved && isHidden(child, name)) {
                    continue;
                }
                if (matcher != null && !matcher.matches(Path.of(name))) {
                    continue;
                }

                boolean isDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                boolean isFile = Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS);
                if (onlyDirs && !isDirectory) {
                    continue;
                }
                if (onlyFilesResolved && !isFile) {
                    continue;
                }

                if (seen++ < resolvedOffset) {
                    continue;
                }
                if (entries.size() >= resolvedLimit) {
                    hasMore = true;
                    break;
                }

                // 尽量返回常用元信息（大小/修改时间等），如果读取失败则写入 warnings，不影响整体成功
                entries.add(toEntry(resolved.rootPath(), child, isDirectory, isFile, warnings));
            }
        } catch (IOException e) {
            throw new IllegalStateException("列出目录失败：" + resolved.displayPath(), e);
        }

        DirectoryListResult result = new DirectoryListResult(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                resolvedOffset,
                resolvedLimit,
                hasMore,
                entries,
                warnings.isEmpty() ? null : warnings
        );

        // 写入目录列表缓存，并将本次返回的条目增量写入“文件名索引”
        if (indexCache != null && indexCache.isEnabled()) {
            indexCache.putDirectoryListCache(cacheKey, result);
        }

        return result;
    }

    @Tool(
            name = "fs_list_tree",
            description = "递归列出目录树（支持 maxDepth/maxEntries；可通过 includeFiles=false 只返回目录以减少返回体积）。"
    )
    /**
     * 递归列出目录树。
     * <p>
     * 与 {@code fs_list_directory} 的区别：
     * <ul>
     *   <li>这里是递归遍历，适合“层级很多、希望一次性返回更多结构信息”的场景。</li>
     *   <li>为了避免返回体过大，必须设置 {@code maxDepth/maxEntries}（服务端也会做上限保护）。</li>
     * </ul>
     * <p>
     * 性能建议：
     * <ul>
     *   <li>如果只需要目录层级，请设置 {@code includeFiles=false}，会显著减少条目数量。</li>
     *   <li>只关心某类文件时，配合 {@code glob}（例如 {@code *.java}）减少返回体积。</li>
     *   <li>如果不需要 size/lastModified，请保持 {@code includeMetadata=false}，可降低系统调用开销。</li>
     * </ul>
     */
    public FileTreeResult listTree(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(required = false, description = "目录路径（相对 rootId 或绝对路径；为空则为根目录）") String path,
            @ToolParam(required = false, description = "最大深度（0=只返回当前目录；默认 app.fs.tree-default-depth，上限 app.fs.tree-max-depth）") Integer maxDepth,
            @ToolParam(required = false, description = "最大条目数（默认 app.fs.tree-default-entries，上限 app.fs.tree-max-entries）") Integer maxEntries,
            @ToolParam(required = false, description = "是否包含文件（默认 true；设为 false 仅返回目录）") Boolean includeFiles,
            @ToolParam(required = false, description = "是否包含隐藏文件（默认 app.fs.include-hidden-by-default）") Boolean includeHidden,
            @ToolParam(required = false, description = "可选 glob 过滤（仅匹配文件名），例如 *.java") String glob,
            @ToolParam(required = false, description = "是否包含元数据（size/lastModified，默认 false）") Boolean includeMetadata
    ) {
        // 解析并校验起始目录：必须位于 roots 白名单范围内
        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path baseDir = resolved.absolutePath();
        if (!Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是目录：" + resolved.displayPath());
        }

        // 遍历选项
        boolean includeHiddenResolved = (includeHidden != null) ? includeHidden : properties.isIncludeHiddenByDefault();
        boolean includeFilesResolved = (includeFiles == null) || includeFiles;
        boolean includeMetadataResolved = Boolean.TRUE.equals(includeMetadata);

        // 服务端对深度与条目数做上限保护，避免一次性返回过大导致性能/网络压力
        int resolvedMaxDepth = resolveTreeMaxDepth(maxDepth);
        int resolvedMaxEntries = resolveTreeMaxEntries(maxEntries);

        final PathMatcher matcher = (glob != null && !glob.isBlank())
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob)
                : null;

        List<FileTreeEntry> entries = new ArrayList<>(Math.min(resolvedMaxEntries, 1024));
        List<String> warnings = new ArrayList<>();
        boolean[] truncated = new boolean[]{false};

        // 安全：获取 root 的真实路径，用于后续 realPath 校验，防止 junction/symlink 逃逸
        Path rootPath = resolved.rootPath();
        Path rootReal;
        try {
            rootReal = rootPath.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("根目录无法解析：" + rootPath, e);
        }
        // 防循环：记录已经访问过的真实目录（realPath）。如果出现循环引用（比如链接回父目录），将跳过。
        Set<Path> visitedRealDirs = new HashSet<>();

        try {
            // walkFileTree 是深度优先遍历，适合快速构建“目录树”结构信息
            Files.walkFileTree(baseDir, EnumSet.noneOf(FileVisitOption.class), resolvedMaxDepth, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    int depth = depth(baseDir, dir);
                    if (depth > 0 && !includeHiddenResolved && isHidden(dir, fileName(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // 默认不允许访问符号链接目录（避免逃逸/循环）；如需允许请在配置中开启 app.fs.allow-symlink=true
                    if (attrs.isSymbolicLink() && !properties.isAllowSymlink()) {
                        warnings.add("已跳过符号链接目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    Path real;
                    try {
                        real = dir.toRealPath();
                    } catch (IOException e) {
                        warnings.add("目录无法解析，已跳过：" + normalizeBasePath(safeRel(rootPath, dir)) + "（" + e.getMessage() + "）");
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!real.startsWith(rootReal)) {
                        warnings.add("已跳过根目录范围外的目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!visitedRealDirs.add(real)) {
                        warnings.add("疑似存在循环引用，已跳过目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    if (entries.size() >= resolvedMaxEntries) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }

                    if (includeMetadataResolved) {
                        entries.add(new FileTreeEntry(
                                depth,
                                fileName(dir),
                                normalizeBasePath(safeRel(rootPath, dir)),
                                true,
                                false,
                                Files.isSymbolicLink(dir),
                                null,
                                attrs.lastModifiedTime() != null ? attrs.lastModifiedTime().toInstant() : null
                        ));
                    } else {
                        entries.add(new FileTreeEntry(
                                depth,
                                fileName(dir),
                                normalizeBasePath(safeRel(rootPath, dir)),
                                true,
                                false,
                                Files.isSymbolicLink(dir),
                                null,
                                null
                        ));
                    }

                    // 增量建立“文件名 -> 路径”索引：目录
                    if (indexCache != null && indexCache.isEnabled()) {
                        indexCache.recordName(
                                resolved.rootId(),
                                fileName(dir),
                                normalizeBasePath(safeRel(rootPath, dir)),
                                true,
                                false
                        );
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!includeFilesResolved) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = fileName(file);
                    if (!includeHiddenResolved && isHidden(file, name)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (matcher != null && !matcher.matches(Path.of(name))) {
                        return FileVisitResult.CONTINUE;
                    }

                    // 达到条目上限即终止遍历，返回 truncated=true
                    if (entries.size() >= resolvedMaxEntries) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }

                    int depth = depth(baseDir, file);
                    if (includeMetadataResolved) {
                        entries.add(new FileTreeEntry(
                                depth,
                                name,
                                normalizeBasePath(safeRel(rootPath, file)),
                                false,
                                attrs.isRegularFile(),
                                attrs.isSymbolicLink(),
                                attrs.isRegularFile() ? attrs.size() : null,
                                attrs.lastModifiedTime() != null ? attrs.lastModifiedTime().toInstant() : null
                        ));
                    } else {
                        entries.add(new FileTreeEntry(
                                depth,
                                name,
                                normalizeBasePath(safeRel(rootPath, file)),
                                false,
                                attrs.isRegularFile(),
                                attrs.isSymbolicLink(),
                                null,
                                null
                        ));
                    }

                    // 增量建立“文件名 -> 路径”索引：文件
                    if (indexCache != null && indexCache.isEnabled()) {
                        indexCache.recordName(
                                resolved.rootId(),
                                name,
                                normalizeBasePath(safeRel(rootPath, file)),
                                false,
                                attrs.isRegularFile()
                        );
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    warnings.add("访问失败：" + normalizeBasePath(safeRel(rootPath, file)) + "（" + exc.getMessage() + "）");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("遍历目录树失败：" + resolved.displayPath(), e);
        }

        String baseDisplayPath = normalizeBasePath(resolved.displayPath());
        return new FileTreeResult(
                resolved.rootId(),
                baseDisplayPath,
                resolvedMaxDepth,
                resolvedMaxEntries,
                truncated[0],
                entries,
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_read_file",
            description = "读取文件内容；文本文件返回 UTF-8 字符串，二进制文件返回 base64（支持 maxBytes 截断）。"
    )
    /**
     * 读取文件内容。
     * <p>
     * 规则：
     * <ul>
     *   <li>默认优先按文本返回（utf-8）；如果判定为二进制或 UTF-8 校验失败，则返回 base64。</li>
     *   <li>支持 {@code maxBytes} 截断读取：避免一次性读取大文件导致内存压力。</li>
     *   <li>可选返回 sha256（适合做缓存/对比，但对大文件会有额外开销）。</li>
     * </ul>
     */
    public FileReadResult readFile(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "文件路径（相对 rootId 或绝对路径）") String path,
            @ToolParam(required = false, description = "最大读取字节数（默认 app.fs.read-max-bytes，上限同配置）") Long maxBytes,
            @ToolParam(required = false, description = "是否强制 base64 输出（true/false）") Boolean asBase64,
            @ToolParam(required = false, description = "是否返回 sha256（true/false）") Boolean includeSha256
    ) {
        // 解析并校验路径：必须位于 roots 白名单范围内且文件存在
        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path file = resolved.absolutePath();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是普通文件：" + resolved.displayPath());
        }

        // 读取文件元信息（大小、修改时间）。内容读取会单独进行，并支持截断。
        long totalBytes;
        Instant lastModifiedAt;
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            totalBytes = attrs.size();
            lastModifiedAt = attrs.lastModifiedTime() != null ? attrs.lastModifiedTime().toInstant() : null;
        } catch (IOException e) {
            throw new IllegalStateException("读取文件属性失败：" + resolved.displayPath(), e);
        }

        // 读取内容（最多读取 maxBytes 字节）
        long resolvedMaxBytes = resolveReadMaxBytes(maxBytes);
        byte[] bytes = readUpTo(file, resolvedMaxBytes);
        boolean truncated = totalBytes > bytes.length;
        boolean forceBase64 = Boolean.TRUE.equals(asBase64);

        List<String> warnings = new ArrayList<>();
        String sha256 = null;
        if (Boolean.TRUE.equals(includeSha256)) {
            try {
                sha256 = HashingUtils.sha256Hex(file);
            } catch (IOException e) {
                warnings.add("计算文件 sha256 失败：" + e.getMessage());
            }
        }

        // 调用方要求强制 base64：直接按二进制返回
        if (forceBase64) {
            return new FileReadResult(
                    resolved.rootId(),
                    normalizeDisplayPath(resolved.displayPath()),
                    "base64",
                    true,
                    truncated,
                    totalBytes,
                    bytes.length,
                    sha256,
                    lastModifiedAt,
                    Base64.getEncoder().encodeToString(bytes),
                    warnings.isEmpty() ? null : warnings
            );
        }

        // 先通过扩展名做一个快速判断；如果不是常见文本文件，再用 UTF-8 严格校验兜底判断。
        boolean likelyText = isLikelyTextFile(file);
        if (!likelyText && !isValidUtf8(bytes)) {
            return new FileReadResult(
                    resolved.rootId(),
                    normalizeDisplayPath(resolved.displayPath()),
                    "base64",
                    true,
                    truncated,
                    totalBytes,
                    bytes.length,
                    sha256,
                    lastModifiedAt,
                    Base64.getEncoder().encodeToString(bytes),
                    warnings.isEmpty() ? null : warnings
            );
        }

        // 文本：以 UTF-8 解码（对不合法字符做替换），避免异常中断
        String text = decodeUtf8BestEffort(bytes);
        return new FileReadResult(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                "utf-8",
                false,
                truncated,
                totalBytes,
                bytes.length,
                sha256,
                lastModifiedAt,
                text,
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_read_step_model_info",
            description = "解析 STEP(.stp/.step) 文件的模型信息：HEADER( FILE_DESCRIPTION/FILE_NAME/FILE_SCHEMA ) + DATA(装配层级/零件BOM/尺寸与PMI摘要/几何与拓扑摘要)。支持中文（含 \\\\X2\\\\...\\\\X0\\\\ 编码）。"
    )
    public StepModelInfoResult readStepModelInfo(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "STEP 文件路径（.stp/.step，相对 rootId 或绝对路径）") String path,
            @ToolParam(required = false, description = "最大扫描字节数（默认 16MB，上限 128MB；仅用于解析，不会原样返回文件内容）") Long maxBytes,
            @ToolParam(required = false, description = "最大扫描实体数（默认 500000，上限 5000000；用于控制 DATA 段解析工作量）") Long maxEntities
    ) {
        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path file = resolved.absolutePath();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是普通文件：" + resolved.displayPath());
        }

        // STEP 文件扩展名校验：避免误把普通文本/二进制文件当 STEP 来解析。
        String fileName = file.getFileName() != null ? file.getFileName().toString() : resolved.displayPath();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".stp") && !lower.endsWith(".step")) {
            throw new IllegalArgumentException("不是 STEP 文件（仅支持 .stp/.step）：" + resolved.displayPath());
        }

        long totalBytes;
        try {
            totalBytes = Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件大小失败：" + resolved.displayPath(), e);
        }

        // 为了避免把超大 STEP 文件一次性读入内存，这里只读取前 maxBytes 字节用于解析。
        long resolvedMaxBytes = resolveStepModelInfoMaxBytes(maxBytes);
        byte[] bytes = readUpTo(file, resolvedMaxBytes);
        boolean truncated = totalBytes > bytes.length;

        List<String> warnings = new ArrayList<>();
        // STEP 文件在中文环境里可能是 UTF-8，也可能是 GBK/GB18030。
        // - UTF-8：直接解码
        // - 非 UTF-8：尝试用 GB18030 解码（替换非法字符），并在 warnings 里提示
        DecodedText decoded = decodeStepText(bytes, truncated, warnings);

        // 解析 HEADER：FILE_DESCRIPTION/FILE_NAME/FILE_SCHEMA + 少量 PRODUCT 名称（作为模型/零件名线索）
        StepModelInfoParser.StepModelInfo info = StepModelInfoParser.parse(decoded.text());
        if (info.warnings() != null) {
            warnings.addAll(info.warnings());
        }

        // 解析 DATA：抽取 BOM/装配/几何&拓扑摘要/尺寸&PMI 摘要。
        // 注意：这不是“完整 STEP 几何内核”，但能覆盖很多工程上常用的元信息提取需求。
        StepDataAnalyzer.Limits baseLimits = StepDataAnalyzer.Limits.defaults();
        long resolvedMaxEntities = resolveStepDataMaxEntities(maxEntities);
        StepDataAnalyzer.Limits dataLimits = new StepDataAnalyzer.Limits(
                resolvedMaxEntities,
                baseLimits.maxTopEntityTypes(),
                baseLimits.maxParts(),
                baseLimits.maxAssemblyDepth(),
                baseLimits.maxAssemblyNodes(),
                baseLimits.maxPmiSnippets(),
                baseLimits.maxMeasures()
        );
        StepDataAnalyzer.Analysis data = StepDataAnalyzer.analyze(decoded.text(), dataLimits);
        if (data.warnings() != null) {
            warnings.addAll(data.warnings());
        }
        if (truncated) {
            addWarningLimited(warnings, "已按 maxBytes 截断扫描，模型信息可能不完整；如需更多信息可增大 maxBytes。");
        }

        return new StepModelInfoResult(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                truncated,
                decoded.decodedWith(),
                info.fileDescriptions(),
                info.implementationLevel(),
                info.fileName(),
                info.timeStamp(),
                info.authors(),
                info.organizations(),
                info.preprocessorVersion(),
                info.originatingSystem(),
                info.authorization(),
                info.schemas(),
                info.productNames(),
                data.entitiesParsed(),
                data.entitiesTruncated(),
                data.topEntityTypes(),
                data.parts(),
                data.assemblyRelations(),
                data.assemblyTree(),
                data.geometry(),
                data.topology(),
                data.pmi(),
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_read_step_entities",
            description = "分页列出 STEP(.stp/.step) 的 DATA 段实体（可按实体类型关键字过滤）。返回的实体文本会尽量解码中文（含 \\\\X2\\\\...\\\\X0\\\\）。"
    )
    public StepEntityListResult readStepEntities(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "STEP 文件路径（.stp/.step，相对 rootId 或绝对路径）") String path,
            @ToolParam(required = false, description = "实体类型过滤关键字（大小写不敏感；例如 DIMENSION / B_SPLINE / PRODUCT / ADVANCED_FACE 等）") String typeContains,
            @ToolParam(required = false, description = "匹配偏移（0-based；默认 0）") Integer offset,
            @ToolParam(required = false, description = "返回条数（默认 50；上限 500）") Integer limit,
            @ToolParam(required = false, description = "最大扫描字节数（默认 16MB，上限 128MB）") Long maxBytes,
            @ToolParam(required = false, description = "最大扫描实体数（默认 500000，上限 5000000；用于控制 DATA 段扫描工作量）") Long maxEntities
    ) {
        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path file = resolved.absolutePath();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是普通文件：" + resolved.displayPath());
        }

        // 工具定位：
        // - fs_read_step_model_info：返回“摘要/结构化信息”（BOM/装配树/摘要计数等）
        // - fs_read_step_entities ：返回“原始实体片段”（用于更进一步的精细解析）
        //
        // 这里提供分页 + typeContains 过滤，避免一次性返回过多内容导致客户端截断。
        String fileName = file.getFileName() != null ? file.getFileName().toString() : resolved.displayPath();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".stp") && !lower.endsWith(".step")) {
            throw new IllegalArgumentException("不是 STEP 文件（仅支持 .stp/.step）：" + resolved.displayPath());
        }

        long totalBytes;
        try {
            totalBytes = Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件大小失败：" + resolved.displayPath(), e);
        }

        long resolvedMaxBytes = resolveStepModelInfoMaxBytes(maxBytes);
        byte[] bytes = readUpTo(file, resolvedMaxBytes);
        boolean truncated = totalBytes > bytes.length;

        List<String> warnings = new ArrayList<>();
        DecodedText decoded = decodeStepText(bytes, truncated, warnings);

        // 按实体类型关键字进行“包含匹配”过滤：
        // 例如 typeContains="DIMENSION" 会匹配 DIMENSIONAL_SIZE / DIMENSION_CURVE 等。
        StepDataAnalyzer.Limits baseLimits = StepDataAnalyzer.Limits.defaults();
        long resolvedMaxEntities = resolveStepDataMaxEntities(maxEntities);
        StepDataAnalyzer.Limits scanLimits = new StepDataAnalyzer.Limits(
                resolvedMaxEntities,
                baseLimits.maxTopEntityTypes(),
                baseLimits.maxParts(),
                baseLimits.maxAssemblyDepth(),
                baseLimits.maxAssemblyNodes(),
                baseLimits.maxPmiSnippets(),
                baseLimits.maxMeasures()
        );
        StepDataAnalyzer.EntityList list = StepDataAnalyzer.listEntities(decoded.text(), scanLimits, typeContains, offset, limit);
        if (list.warnings() != null) {
            warnings.addAll(list.warnings());
        }
        if (truncated) {
            addWarningLimited(warnings, "已按 maxBytes 截断扫描；如需更多实体请增大 maxBytes。");
        }

        return new StepEntityListResult(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                truncated,
                decoded.decodedWith(),
                list.scannedEntities(),
                list.entitiesTruncated(),
                list.offset(),
                list.limit(),
                list.hasMore(),
                list.nextOffset(),
                list.entities(),
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_read_file_lines",
            description = "按行读取文本文件（用于分片拉取完整内容，降低被客户端截断的概率）。"
    )
    /**
     * 按行读取文本文件（UTF-8）。
     * <p>
     * 背景：部分 MCP 客户端/LLM 会对超长工具输出进行截断展示，并插入诸如 “... chars truncated ...” 的占位文本。
     * 该占位文本不是原文件内容，如果直接把它当作文件内容再写回，会导致源码被写坏。
     * <p>
     * 使用建议：
     * <ul>
     *   <li>第一次调用不传 startLine：从第 1 行开始。</li>
     *   <li>根据返回的 hasMore/nextLine 继续分页读取，直到 hasMore=false。</li>
     * </ul>
     */
    public FileReadLinesResult readFileLines(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "文件路径（相对 rootId 或绝对路径）") String path,
            @ToolParam(required = false, description = "起始行号（1-based；默认 1）") Integer startLine,
            @ToolParam(required = false, description = "最大行数（默认 app.fs.read-lines-default-max-lines，上限 app.fs.read-lines-max-lines）") Integer maxLines
    ) {
        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path file = resolved.absolutePath();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是普通文件：" + resolved.displayPath());
        }

        int resolvedStartLine = (startLine == null) ? 1 : Math.max(1, startLine);
        int resolvedMaxLines = resolveReadLinesMaxLines(maxLines);

        // 如果看起来不像文本且 UTF-8 校验失败，则提示改用按字节范围读取（适用于二进制/非 UTF-8 文本）。
        if (!isLikelyTextFile(file)) {
            byte[] probe = readUpTo(file, 8192);
            if (!isValidUtf8(probe)) {
                throw new IllegalArgumentException("文件可能为二进制或非 UTF-8 文本，建议使用 fs_read_file_range 读取：" + resolved.displayPath());
            }
        }

        String eol = detectEol(file);
        List<String> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>(Math.min(resolvedMaxLines, 1024));
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int lineNo = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo < resolvedStartLine) {
                    continue;
                }
                lines.add(line);
                if (lines.size() >= resolvedMaxLines) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("按行读取文件失败：" + resolved.displayPath(), e);
        }

        boolean hasMore = lines.size() >= resolvedMaxLines;
        Integer nextLine = hasMore ? (resolvedStartLine + lines.size()) : null;
        return new FileReadLinesResult(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                resolvedStartLine,
                resolvedMaxLines,
                hasMore,
                nextLine,
                eol,
                lines,
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_read_file_filtered",
            description = "读取文本文件并支持按行号范围/关键字/正则筛选：返回行号+文本（可选上下文），避免整文件返回导致客户端截断。"
    )
    /**
     * 读取文本文件并按条件筛选返回（类似 CLI 的 sed/grep 组合用法）。
     * <p>
     * 典型用途：
     * <ul>
     *   <li>读取指定行号范围（例如查看第 120~180 行）。</li>
     *   <li>按关键字/正则筛选返回（类似 grep -n），并可带上下文（类似 grep -C）。</li>
     * </ul>
     * <p>
     * 性能策略：
     * <ul>
     *   <li>按行流式扫描，达到 maxLines 上限立刻停止，避免读取整文件。</li>
     *   <li>返回行数/单行长度均有上限保护，降低被客户端/LLM 截断的概率。</li>
     * </ul>
     * <p>
     * 重要说明：
     * <ul>
     *   <li>仅支持 UTF-8 文本；二进制或非 UTF-8 文件请使用 {@code fs_read_file_range}。</li>
     *   <li>如果设置了 contextBefore/contextAfter，可能会返回早于 startLine 的少量“上下文行”。</li>
     * </ul>
     */
    public FileReadFilteredResult readFileFiltered(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "文件路径（相对 rootId 或绝对路径）") String path,
            @ToolParam(required = false, description = "起始行号（1-based；默认 1）") Integer startLine,
            @ToolParam(required = false, description = "结束行号（1-based，包含；为空表示直到文件末尾）") Integer endLine,
            @ToolParam(required = false, description = "最大返回行数（默认 app.fs.read-lines-default-max-lines，上限 app.fs.read-lines-max-lines）") Integer maxLines,
            @ToolParam(required = false, description = "包含关键字过滤（与 regex 互斥）") String contains,
            @ToolParam(required = false, description = "正则过滤（与 contains 互斥）") String regex,
            @ToolParam(required = false, description = "正则 flags（支持 i/m/s/u；可选）") String flags,
            @ToolParam(required = false, description = "是否区分大小写（默认 true；对 contains 生效；对 regex 可配合 flags=i）") Boolean caseSensitive,
            @ToolParam(required = false, description = "是否取反匹配（默认 false；为 true 时将返回“不匹配”的行，上下文参数会被忽略）") Boolean invertMatch,
            @ToolParam(required = false, description = "命中行前置上下文行数（默认 0）") Integer contextBefore,
            @ToolParam(required = false, description = "命中行后置上下文行数（默认 0）") Integer contextAfter,
            @ToolParam(required = false, description = "单行最大返回字符数（默认 app.fs.search-max-line-length，上限同配置）") Integer maxLineLength
    ) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("参数错误：path 不能为空");
        }

        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path file = resolved.absolutePath();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是普通文件：" + resolved.displayPath());
        }

        // 如果看起来不像文本且 UTF-8 校验失败，则提示改用按字节范围读取（适用于二进制/非 UTF-8 文本）。
        if (!isLikelyTextFile(file)) {
            byte[] probe = readUpTo(file, 8192);
            if (!isValidUtf8(probe)) {
                throw new IllegalArgumentException("文件可能为二进制或非 UTF-8 文本，无法按文本筛选读取：" + resolved.displayPath());
            }
        }

        int resolvedStartLine = (startLine == null) ? 1 : Math.max(1, startLine);
        Integer resolvedEndLine = (endLine == null) ? null : Math.max(1, endLine);
        if (resolvedEndLine != null && resolvedEndLine < resolvedStartLine) {
            throw new IllegalArgumentException("参数错误：endLine 不能小于 startLine（" + resolvedStartLine + " > " + resolvedEndLine + "）");
        }

        int resolvedMaxLines = resolveReadLinesMaxLines(maxLines);
        int resolvedMaxLineLength = resolveSearchMaxLineLength(maxLineLength);

        String containsResolved = (contains == null || contains.isBlank()) ? null : contains;
        String regexResolved = (regex == null || regex.isBlank()) ? null : regex;
        if (containsResolved != null && regexResolved != null) {
            throw new IllegalArgumentException("参数冲突：contains 与 regex 不能同时指定");
        }

        boolean caseSensitiveResolved = (caseSensitive == null) || caseSensitive;
        boolean inverted = Boolean.TRUE.equals(invertMatch);

        int resolvedContextBefore = resolveContextLines(contextBefore);
        int resolvedContextAfter = resolveContextLines(contextAfter);

        String filterMode = "none";
        Pattern pattern = null;
        if (regexResolved != null) {
            filterMode = "regex";
            pattern = compilePattern(regexResolved, flags, !caseSensitiveResolved);
        } else if (containsResolved != null) {
            filterMode = "contains";
        }

        // 取反匹配或未提供过滤条件时，上下文没有意义：为避免误解，这里直接忽略。
        List<String> warnings = new ArrayList<>();
        if (inverted && (resolvedContextBefore > 0 || resolvedContextAfter > 0)) {
            addWarningLimited(warnings, "invertMatch=true 时已忽略 contextBefore/contextAfter。");
            resolvedContextBefore = 0;
            resolvedContextAfter = 0;
        }
        if ("none".equals(filterMode) && (resolvedContextBefore > 0 || resolvedContextAfter > 0)) {
            addWarningLimited(warnings, "未指定 contains/regex 时已忽略 contextBefore/contextAfter。");
            resolvedContextBefore = 0;
            resolvedContextAfter = 0;
        }

        String eol = detectEol(file);
        List<FileReadFilteredLine> out = new ArrayList<>(Math.min(resolvedMaxLines, 256));
        Map<Integer, Integer> indexByLine = new HashMap<>(Math.min(resolvedMaxLines, 256));

        ArrayDeque<LineBufferItem> beforeBuffer = (resolvedContextBefore > 0) ? new ArrayDeque<>(resolvedContextBefore) : null;
        int afterRemaining = 0;
        int lineNo = 0;
        int scannedLines = 0;
        int matchedLines = 0;
        boolean hasMore = false;
        Integer nextLine = null;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (resolvedEndLine != null && lineNo > resolvedEndLine) {
                    break;
                }
                scannedLines++;

                boolean withinScan = lineNo >= resolvedStartLine;

                boolean matched = false;
                int matchStart = -1;
                int matchEnd = -1;
                Integer column = null;
                if (!"none".equals(filterMode) && withinScan) {
                    if ("regex".equals(filterMode)) {
                        Matcher m = pattern.matcher(line);
                        if (m.find()) {
                            matched = true;
                            matchStart = m.start();
                            matchEnd = m.end();
                            column = matchStart + 1;
                        }
                    } else {
                        int idx = indexOf(line, containsResolved, caseSensitiveResolved);
                        if (idx >= 0) {
                            matched = true;
                            matchStart = idx;
                            matchEnd = Math.min(line.length(), idx + containsResolved.length());
                            column = matchStart + 1;
                        }
                    }
                }

                // 1) 无过滤：按行号范围直接返回
                if ("none".equals(filterMode)) {
                    if (withinScan) {
                        Excerpt ex = truncateLineHead(line, resolvedMaxLineLength);
                        emitFilteredLine(out, indexByLine, lineNo, false, null, ex);
                        if (out.size() >= resolvedMaxLines) {
                            hasMore = true;
                            nextLine = lineNo + 1;
                            break;
                        }
                    }
                    // 更新上下文缓冲（无过滤时不需要，但保持逻辑一致）
                    if (beforeBuffer != null) {
                        pushBeforeBuffer(beforeBuffer, lineNo, line, resolvedContextBefore);
                    }
                    continue;
                }

                // 2) 取反匹配：返回“不匹配”的行（不支持上下文）
                if (inverted) {
                    if (withinScan) {
                        if (matched) {
                            matchedLines++;
                        } else {
                            Excerpt ex = truncateLineHead(line, resolvedMaxLineLength);
                            emitFilteredLine(out, indexByLine, lineNo, false, null, ex);
                            if (out.size() >= resolvedMaxLines) {
                                hasMore = true;
                                nextLine = lineNo + 1;
                                break;
                            }
                        }
                    }
                    if (beforeBuffer != null) {
                        pushBeforeBuffer(beforeBuffer, lineNo, line, resolvedContextBefore);
                    }
                    continue;
                }

                // 3) 正常匹配：命中行 + 上下文
                if (withinScan && matched) {
                    matchedLines++;

                    // 先输出“距离命中行最近”的若干前置上下文行（容量不足时自动缩减，保证命中行优先返回）
                    int remainingCapacity = resolvedMaxLines - out.size();
                    if (remainingCapacity <= 0) {
                        hasMore = true;
                        nextLine = lineNo;
                        addWarningLimited(warnings, "已达到 maxLines 上限，可能导致上下文不完整；如需完整上下文请增大 maxLines。");
                        break;
                    }
                    int wantContext = Math.min(resolvedContextBefore, (beforeBuffer == null) ? 0 : beforeBuffer.size());
                    int contextToEmit = Math.min(wantContext, Math.max(0, remainingCapacity - 1));
                    if (contextToEmit > 0 && beforeBuffer != null) {
                        emitTailContext(beforeBuffer, contextToEmit, out, indexByLine, resolvedMaxLineLength);
                    }

                    Excerpt ex = buildExcerpt(line, matchStart, matchEnd, resolvedMaxLineLength);
                    emitFilteredLine(out, indexByLine, lineNo, true, column, ex);

                    afterRemaining = Math.max(afterRemaining, resolvedContextAfter);
                    if (out.size() >= resolvedMaxLines) {
                        hasMore = true;
                        nextLine = lineNo + 1;
                        addWarningLimited(warnings, "已达到 maxLines 上限，可能导致上下文不完整；如需完整上下文请增大 maxLines。");
                        break;
                    }
                } else if (afterRemaining > 0) {
                    if (withinScan) {
                        Excerpt ex = truncateLineHead(line, resolvedMaxLineLength);
                        emitFilteredLine(out, indexByLine, lineNo, false, null, ex);
                        if (out.size() >= resolvedMaxLines) {
                            hasMore = true;
                            nextLine = lineNo + 1;
                            addWarningLimited(warnings, "已达到 maxLines 上限，可能导致上下文不完整；如需完整上下文请增大 maxLines。");
                            break;
                        }
                    }
                    afterRemaining--;
                }

                if (beforeBuffer != null) {
                    pushBeforeBuffer(beforeBuffer, lineNo, line, resolvedContextBefore);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("筛选读取文件失败：" + resolved.displayPath(), e);
        }

        return new FileReadFilteredResult(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                resolvedStartLine,
                resolvedEndLine,
                resolvedMaxLines,
                filterMode,
                inverted,
                resolvedContextBefore,
                resolvedContextAfter,
                resolvedMaxLineLength,
                hasMore,
                nextLine,
                eol,
                scannedLines,
                out.size(),
                matchedLines,
                out,
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_read_file_range",
            description = "按字节范围读取文件（base64），用于分片拉取完整内容，规避客户端截断。"
    )
    /**
     * 按字节范围读取文件并以 base64 返回。
     * <p>
     * 适用于：
     * <ul>
     *   <li>任意二进制文件（图片、压缩包等）。</li>
     *   <li>文本文件较大且客户端可能截断展示时。</li>
     * </ul>
     */
    public FileReadRangeResult readFileRange(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "文件路径（相对 rootId 或绝对路径）") String path,
            @ToolParam(required = false, description = "起始偏移（字节，0-based；默认 0）") Long offset,
            @ToolParam(required = false, description = "最大读取字节数（默认 app.fs.read-range-default-bytes，上限 app.fs.read-range-max-bytes）") Integer maxBytes
    ) {
        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path file = resolved.absolutePath();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是普通文件：" + resolved.displayPath());
        }

        long totalBytes;
        try {
            totalBytes = Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件大小失败：" + resolved.displayPath(), e);
        }

        long resolvedOffset = (offset == null) ? 0L : Math.max(0L, offset);
        if (resolvedOffset > totalBytes) {
            resolvedOffset = totalBytes;
        }
        int resolvedMaxBytes = resolveReadRangeMaxBytes(maxBytes);

        byte[] bytes;
        try {
            bytes = readRangeBytes(file, resolvedOffset, resolvedMaxBytes);
        } catch (IOException e) {
            throw new IllegalStateException("按范围读取文件失败：" + resolved.displayPath(), e);
        }

        boolean hasMore = resolvedOffset + bytes.length < totalBytes;
        Long nextOffset = hasMore ? (resolvedOffset + bytes.length) : null;
        return new FileReadRangeResult(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                resolvedOffset,
                resolvedMaxBytes,
                totalBytes,
                bytes.length,
                hasMore,
                nextOffset,
                "base64",
                Base64.getEncoder().encodeToString(bytes),
                null
        );
    }

    @Tool(
            name = "fs_find_by_name",
            description = "按名称定位文件/目录：优先使用缓存（文件名索引），未命中或 refresh=true 时回退本地扫描并更新缓存。"
    )
    /**
     * 按“文件名/目录名”定位路径（类似 CLI 的 find/where）。
     * <p>
     * 与 {@code fs_search} 的区别：
     * <ul>
     *   <li>{@code fs_search} 是“按内容搜索”（文本/正则）。</li>
     *   <li>本工具是“按名称搜索”（文件名/目录名）。</li>
     * </ul>
     * <p>
     * 缓存策略：
     * <ul>
     *   <li>优先查服务端缓存（文件名索引），命中则直接返回候选路径。</li>
     *   <li>缓存未命中、或用户指定 {@code refresh=true} 时，回退到本地递归扫描并更新缓存。</li>
     * </ul>
     */
    public NameResolveResult findByName(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空表示搜索所有 roots）") String rootId,
            @ToolParam(required = false, description = "起始目录（相对 rootId 或绝对路径；为空表示从 root 开始）") String basePath,
            @ToolParam(description = "要查找的名称（仅文件名/目录名；不要包含路径分隔符）") String name,
            @ToolParam(required = false, description = "查找类型：any/file/directory（默认 any）") String type,
            @ToolParam(required = false, description = "是否区分大小写（默认 false）") Boolean caseSensitive,
            @ToolParam(required = false, description = "最大返回候选数（默认 app.fs.search-default-max-matches，上限 app.fs.search-max-matches）") Integer maxResults,
            @ToolParam(required = false, description = "最大搜索深度（目录扫描时生效；默认 app.fs.search-default-max-depth，上限 app.fs.search-max-depth）") Integer maxDepth,
            @ToolParam(required = false, description = "最大扫描文件数（目录扫描时生效；默认 app.fs.search-default-max-files，上限 app.fs.search-max-files）") Integer maxFiles,
            @ToolParam(required = false, description = "是否包含隐藏文件（默认 app.fs.include-hidden-by-default）") Boolean includeHidden,
            @ToolParam(required = false, description = "是否只使用缓存（true 时不做本地扫描；默认 false）") Boolean cacheOnly,
            @ToolParam(required = false, description = "是否强制刷新（true 时跳过缓存，直接本地扫描并更新缓存；默认 false）") Boolean refresh
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("参数错误：name 不能为空");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("参数错误：name 只能是文件名/目录名，不能包含路径分隔符：" + name);
        }

        String typeResolved = (type == null || type.isBlank()) ? "any" : type.trim().toLowerCase();
        Boolean wantDirectory = null;
        Boolean wantFile = null;
        switch (typeResolved) {
            case "any" -> {
                // 不过滤
            }
            case "file" -> wantFile = true;
            case "directory", "dir" -> wantDirectory = true;
            default -> throw new IllegalArgumentException("不支持的 type：" + type + "（请使用 any/file/directory）");
        }

        boolean caseSensitiveResolved = Boolean.TRUE.equals(caseSensitive);
        int resolvedMaxResults = resolveSearchMaxMatches(maxResults);
        int resolvedMaxDepth = resolveSearchMaxDepth(maxDepth);
        int resolvedMaxFiles = resolveSearchMaxFiles(maxFiles);
        boolean includeHiddenResolved = (includeHidden != null) ? includeHidden : properties.isIncludeHiddenByDefault();

        boolean cacheOnlyResolved = Boolean.TRUE.equals(cacheOnly);
        boolean refreshResolved = Boolean.TRUE.equals(refresh);

        List<String> warnings = new ArrayList<>();

        // 解析扫描范围：
        // - rootId 为空且 basePath 为空：搜索所有 roots
        // - 其它情况：只搜索解析后的单个目录范围
        boolean scanAllRoots = (rootId == null || rootId.isBlank()) && (basePath == null || basePath.isBlank());
        SecurePathResolver.ResolvedPath resolvedBase = null;
        if (!scanAllRoots) {
            resolvedBase = pathResolver.resolve(rootId, basePath, true);
            if (!Files.isDirectory(resolvedBase.absolutePath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("不是目录：" + resolvedBase.displayPath());
            }
        }

        // 1) 优先走缓存：命中则直接返回（可通过 refresh=true 强制跳过缓存）
        if (!refreshResolved && indexCache != null && indexCache.isEnabled()) {
            List<FileIndexCache.IndexedPath> cached = indexCache.lookupByName(name, caseSensitiveResolved, wantDirectory, wantFile);
            if (!cached.isEmpty()) {
                List<NameResolveEntry> out = new ArrayList<>(Math.min(cached.size(), resolvedMaxResults));
                String basePrefix = (resolvedBase == null) ? null : normalizeBasePath(resolvedBase.displayPath());
                for (FileIndexCache.IndexedPath p : cached) {
                    if (p == null) {
                        continue;
                    }
                    if (resolvedBase != null) {
                        if (!Objects.equals(resolvedBase.rootId(), p.rootId())) {
                            continue;
                        }
                        if (basePrefix != null && !isUnderBase(p.path(), basePrefix)) {
                            continue;
                        }
                    } else if (rootId != null && !rootId.isBlank()) {
                        // 仅指定 rootId（basePath 为空）时：只过滤 rootId，不过滤目录前缀
                        if (!rootId.trim().equals(p.rootId())) {
                            continue;
                        }
                    }

                    out.add(new NameResolveEntry(p.rootId(), normalizeDisplayPath(p.path()), p.directory(), p.file()));
                    if (out.size() >= resolvedMaxResults) {
                        warnings.add("结果过多，已达到 maxResults 上限（" + resolvedMaxResults + "），已截断。");
                        return new NameResolveResult(name, typeResolved, caseSensitiveResolved, true, false, 0, true, out, warnings.isEmpty() ? null : warnings);
                    }
                }
                if (!out.isEmpty()) {
                    return new NameResolveResult(name, typeResolved, caseSensitiveResolved, true, false, 0, false, out, warnings.isEmpty() ? null : warnings);
                }
            }
        }

        if (cacheOnlyResolved) {
            warnings.add("缓存未命中，且 cacheOnly=true：未执行本地扫描。可设置 refresh=true 或 cacheOnly=false 触发扫描。");
            return new NameResolveResult(name, typeResolved, caseSensitiveResolved, false, false, 0, false, List.of(), warnings);
        }

        // 2) 缓存未命中/强制刷新：回退本地扫描，并更新缓存
        List<NameResolveEntry> matches = new ArrayList<>();
        boolean[] truncated = new boolean[]{false};
        int[] scannedFiles = new int[]{0};

        if (scanAllRoots) {
            // 扫描所有 roots
            for (var r : pathResolver.listRoots()) {
                String rid = r.id();
                Path rp = Path.of(r.path()).toAbsolutePath().normalize();
                scanByNameInDirectory(
                        rid,
                        rp,
                        rp,
                        name,
                        caseSensitiveResolved,
                        includeHiddenResolved,
                        wantDirectory,
                        wantFile,
                        resolvedMaxDepth,
                        resolvedMaxFiles,
                        resolvedMaxResults,
                        matches,
                        scannedFiles,
                        truncated,
                        warnings
                );
                if (truncated[0] || matches.size() >= resolvedMaxResults) {
                    break;
                }
            }
        } else {
            // 扫描单个目录范围
            Path baseDir = resolvedBase.absolutePath();
            Path rootPath = resolvedBase.rootPath();
            scanByNameInDirectory(
                    resolvedBase.rootId(),
                    rootPath,
                    baseDir,
                    name,
                    caseSensitiveResolved,
                    includeHiddenResolved,
                    wantDirectory,
                    wantFile,
                    resolvedMaxDepth,
                    resolvedMaxFiles,
                    resolvedMaxResults,
                    matches,
                    scannedFiles,
                    truncated,
                    warnings
            );
        }

        if (truncated[0]) {
            warnings.add("已达到扫描/返回上限，结果可能不完整；可增大 maxDepth/maxFiles/maxResults 或缩小扫描范围后重试。");
        }

        return new NameResolveResult(
                name,
                typeResolved,
                caseSensitiveResolved,
                false,
                true,
                scannedFiles[0],
                truncated[0],
                matches,
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_search",
            description = "在文件或目录中搜索文本/正则：返回匹配行号+片段（类似 grep -n），不返回整文件内容，避免大文件导致客户端截断。"
    )
    /**
     * 搜索文件/目录中的文本（支持正则）。
     * <p>
     * 设计目标：
     * <ul>
     *   <li>返回“行号 + 片段”，而不是返回完整文件内容，避免大文件在 MCP 客户端/LLM 中被截断。</li>
     *   <li>支持对目录递归搜索（可通过 glob 限定只扫描某类文件，例如 **&#47;*.java）。</li>
     *   <li>支持 maxMatches/maxFiles/maxDepth 上限保护，避免一次搜索占用过多资源。</li>
     * </ul>
     * <p>
     * 重要说明：
     * <ul>
     *   <li>默认只对“看起来像文本”的文件进行搜索（依据常见扩展名）。</li>
     *   <li>遇到无法按 UTF-8 解码的文件会跳过，并在 warnings 中提示（目录搜索场景）。</li>
     * </ul>
     */
    public FileSearchResult search(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "文件或目录路径（相对 rootId 或绝对路径）") String path,
            @ToolParam(description = "搜索关键字/正则表达式") String query,
            @ToolParam(required = false, description = "是否使用正则（默认 false）") Boolean regex,
            @ToolParam(required = false, description = "是否区分大小写（默认 true）") Boolean caseSensitive,
            @ToolParam(required = false, description = "目录搜索时的 glob 过滤（匹配相对路径，支持 **/*.java；为空表示不过滤）") String glob,
            @ToolParam(required = false, description = "最大返回匹配数（默认 app.fs.search-default-max-matches，上限 app.fs.search-max-matches）") Integer maxMatches,
            @ToolParam(required = false, description = "最大搜索深度（仅对目录有效；默认 app.fs.search-default-max-depth，上限 app.fs.search-max-depth）") Integer maxDepth,
            @ToolParam(required = false, description = "最大扫描文件数（仅对目录有效；默认 app.fs.search-default-max-files，上限 app.fs.search-max-files）") Integer maxFiles,
            @ToolParam(required = false, description = "匹配片段最大字符数（默认 app.fs.search-max-line-length，上限同配置）") Integer maxLineLength,
            @ToolParam(required = false, description = "是否包含隐藏文件（默认 app.fs.include-hidden-by-default）") Boolean includeHidden
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("参数错误：query 不能为空");
        }

        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path base = resolved.absolutePath();

        boolean regexResolved = Boolean.TRUE.equals(regex);
        boolean caseSensitiveResolved = (caseSensitive == null) || caseSensitive;
        boolean includeHiddenResolved = (includeHidden != null) ? includeHidden : properties.isIncludeHiddenByDefault();
        int resolvedMaxMatches = resolveSearchMaxMatches(maxMatches);
        int resolvedMaxFiles = resolveSearchMaxFiles(maxFiles);
        int resolvedMaxDepth = resolveSearchMaxDepth(maxDepth);
        int resolvedMaxLineLength = resolveSearchMaxLineLength(maxLineLength);

        String queryText = query;
        final Pattern pattern;
        if (regexResolved) {
            int flags = caseSensitiveResolved ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            try {
                pattern = Pattern.compile(queryText, flags);
            } catch (Exception e) {
                throw new IllegalArgumentException("正则表达式不合法：" + e.getMessage(), e);
            }
        } else {
            pattern = null;
        }

        List<FileSearchMatch> matches = new ArrayList<>(Math.min(resolvedMaxMatches, 256));
        List<String> warnings = new ArrayList<>();

        // 单文件搜索：直接扫描该文件
        if (Files.isRegularFile(base, LinkOption.NOFOLLOW_LINKS)) {
            // 单文件搜索也写入文件名索引，便于后续按名称快速定位
            if (indexCache != null && indexCache.isEnabled()) {
                indexCache.recordName(
                        resolved.rootId(),
                        fileName(base),
                        normalizeDisplayPath(resolved.displayPath()),
                        false,
                        true
                );
            }
            if (!isLikelyTextFile(base)) {
                byte[] probe = readUpTo(base, 8192);
                if (!isValidUtf8(probe)) {
                    throw new IllegalArgumentException("文件可能为二进制或非 UTF-8 文本，无法按文本搜索：" + resolved.displayPath());
                }
            }
            boolean truncated = searchFileIntoMatches(
                    base,
                    normalizeDisplayPath(resolved.displayPath()),
                    pattern,
                    queryText,
                    regexResolved,
                    caseSensitiveResolved,
                    resolvedMaxMatches,
                    resolvedMaxLineLength,
                    matches,
                    warnings,
                    true
            );
            return new FileSearchResult(
                    resolved.rootId(),
                    normalizeBasePath(resolved.displayPath()),
                    queryText,
                    regexResolved,
                    caseSensitiveResolved,
                    null,
                    resolvedMaxMatches,
                    resolvedMaxLineLength,
                    truncated,
                    1,
                    matches.size(),
                    matches,
                    warnings.isEmpty() ? null : warnings
            );
        }

        // 目录搜索：递归遍历并搜索每个文件（上限保护：maxDepth/maxFiles/maxMatches）
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是文件或目录：" + resolved.displayPath());
        }

        final PathMatcher matcher = (glob != null && !glob.isBlank())
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob)
                : null;

        Path rootPath = resolved.rootPath();
        Path rootReal;
        try {
            rootReal = rootPath.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("根目录无法解析：" + rootPath, e);
        }
        Set<Path> visitedRealDirs = new HashSet<>();

        boolean[] truncated = new boolean[]{false};
        int[] scannedFiles = new int[]{0};

        try {
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), resolvedMaxDepth, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir != base && !includeHiddenResolved && isHidden(dir, fileName(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (attrs.isSymbolicLink() && !properties.isAllowSymlink()) {
                        addWarningLimited(warnings, "已跳过符号链接目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path real;
                    try {
                        real = dir.toRealPath();
                    } catch (IOException e) {
                        addWarningLimited(warnings, "目录无法解析，已跳过：" + normalizeBasePath(safeRel(rootPath, dir)) + "（" + e.getMessage() + "）");
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!real.startsWith(rootReal)) {
                        addWarningLimited(warnings, "已跳过根目录范围外的目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!visitedRealDirs.add(real)) {
                        addWarningLimited(warnings, "疑似存在循环引用，已跳过目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // 增量建立“文件名 -> 路径”索引：目录（用于后续按名称快速定位路径）
                    if (indexCache != null && indexCache.isEnabled()) {
                        indexCache.recordName(
                                resolved.rootId(),
                                fileName(dir),
                                normalizeBasePath(safeRel(rootPath, dir)),
                                true,
                                false
                        );
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!includeHiddenResolved && isHidden(file, fileName(file))) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (matcher != null) {
                        try {
                            Path rel = base.relativize(file);
                            if (!matcher.matches(rel)) {
                                return FileVisitResult.CONTINUE;
                            }
                        } catch (Exception ignored) {
                            // 兼容极端路径：relativize 失败时不做 glob 过滤
                        }
                    }

                    // 无论是否参与“内容搜索”，都把文件名/路径写入索引（用于按名称定位文件）
                    if (indexCache != null && indexCache.isEnabled()) {
                        indexCache.recordName(
                                resolved.rootId(),
                                fileName(file),
                                normalizeBasePath(safeRel(rootPath, file)),
                                false,
                                true
                        );
                    }

                    // 默认只搜索“看起来像文本”的文件，避免扫描二进制造成无意义开销
                    if (!isLikelyTextFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }

                    scannedFiles[0]++;
                    if (scannedFiles[0] > resolvedMaxFiles) {
                        truncated[0] = true;
                        addWarningLimited(warnings, "已达到最大扫描文件数上限（maxFiles=" + resolvedMaxFiles + "），提前结束搜索。");
                        return FileVisitResult.TERMINATE;
                    }

                    String displayPath = normalizeBasePath(safeRel(rootPath, file));
                    boolean fileTruncated = searchFileIntoMatches(
                            file,
                            displayPath,
                            pattern,
                            queryText,
                            regexResolved,
                            caseSensitiveResolved,
                            resolvedMaxMatches,
                            resolvedMaxLineLength,
                            matches,
                            warnings,
                            false
                    );
                    if (fileTruncated || matches.size() >= resolvedMaxMatches) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    addWarningLimited(warnings, "访问失败：" + normalizeBasePath(safeRel(rootPath, file)) + "（" + exc.getMessage() + "）");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("搜索目录失败：" + resolved.displayPath(), e);
        }

        return new FileSearchResult(
                resolved.rootId(),
                normalizeBasePath(resolved.displayPath()),
                queryText,
                regexResolved,
                caseSensitiveResolved,
                (matcher == null) ? null : glob,
                resolvedMaxMatches,
                resolvedMaxLineLength,
                truncated[0],
                scannedFiles[0],
                matches.size(),
                matches,
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_prepare_patch_file",
            description = "按规则对文本文件生成补丁写入（正则替换/插入/删除/按行处理）；不直接写入，返回 token；需再调用 fs_confirm_write_file(confirm=true) 才会真正写入。"
    )
    /**
     * 对“已存在的 UTF-8 文本文件”执行 patch 规则，并生成待写入内容。
     * <p>
     * 与 {@code fs_prepare_write_file} 的区别：
     * <ul>
     *   <li>这里由服务端读取目标文件并在内存中生成新内容，调用方无需先读出整文件再写回。</li>
     *   <li>非常适合“文件较大容易被客户端截断，但又希望像 CLI 一样快速做替换/插入/删除”的场景。</li>
     * </ul>
     * <p>
     * patch 入参格式：JSON 数组，每个元素是一条操作。支持的操作类型：
     * <ul>
     *   <li>{@code {"type":"regex_replace","pattern":"...","replacement":"...","flags":"i","maxReplacements":0}}</li>
     *   <li>{@code {"type":"insert_lines","atLine":10,"position":"before","text":"a\\nb"}}</li>
     *   <li>{@code {"type":"delete_lines","fromLine":10,"toLine":20}}</li>
     *   <li>{@code {"type":"line_regex","pattern":"...","action":"delete|replace|insert_before|insert_after","replacement":"...","text":"...","flags":"i","maxEdits":0,"occurrence":0}}</li>
     * </ul>
     * <p>
     * 安全性：
     * <ul>
     *   <li>不会直接写入，必须走 {@code fs_confirm_write_file} 二次确认。</li>
     *   <li>如果目标文件大小不超过 {@code app.fs.hash-max-bytes}，会计算 sha256 用于确认阶段“是否被外部修改”的校验。</li>
     * </ul>
     */
    public FilePatchPrepareResult preparePatchFile(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "目标文件路径（相对 rootId 或绝对路径）") String path,
            @ToolParam(description = "patch 规则（JSON 数组，支持 regex_replace/insert_lines/delete_lines/line_regex）") String patch
    ) {
        if (!properties.isAllowWrite()) {
            throw new IllegalStateException("已禁止写入：配置 app.fs.allow-write=false");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("参数错误：path 不能为空");
        }
        if (patch == null || patch.isBlank()) {
            throw new IllegalArgumentException("参数错误：patch 不能为空");
        }

        SecurePathResolver.ResolvedPath resolved = pathResolver.resolve(rootId, path, true);
        Path target = resolved.absolutePath();
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("不是普通文件：" + resolved.displayPath());
        }

        long fileSize;
        try {
            fileSize = Files.size(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件大小失败：" + resolved.displayPath(), e);
        }

        // 补丁在服务端内存中生成新内容，并会暂存到 pending store，因此受 pending-write-max-bytes 限制。
        long maxPendingBytes = properties.getPendingWriteMaxBytes().toBytes();
        if (fileSize > maxPendingBytes) {
            throw new IllegalArgumentException(
                    "文件过大，当前配置 pending-write-max-bytes=" + maxPendingBytes + " 字节，无法在服务端安全生成补丁写入："
                            + resolved.displayPath()
            );
        }

        byte[] originalBytes;
        try {
            originalBytes = Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败：" + resolved.displayPath(), e);
        }
        if (!isValidUtf8(originalBytes)) {
            throw new IllegalArgumentException("文件不是 UTF-8 文本，无法按文本 patch：" + resolved.displayPath());
        }

        boolean endsWithNewline = originalBytes.length > 0 && originalBytes[originalBytes.length - 1] == (byte) '\n';
        String eol = detectEol(target);
        String originalText = new String(originalBytes, StandardCharsets.UTF_8);

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(originalText))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            // StringReader 理论上不会抛 IOException，但这里保留兜底
            throw new IllegalStateException("解析文本内容失败：" + resolved.displayPath(), e);
        }

        JsonNode rootNode;
        try {
            rootNode = OBJECT_MAPPER.readTree(patch);
        } catch (Exception e) {
            throw new IllegalArgumentException("patch 不是合法的 JSON：" + e.getMessage(), e);
        }
        if (rootNode == null || !rootNode.isArray()) {
            throw new IllegalArgumentException("patch 格式错误：必须是 JSON 数组");
        }
        if (rootNode.size() > properties.getPatchMaxOperations()) {
            throw new IllegalArgumentException("patch 操作数过多：" + rootNode.size() + "（上限 " + properties.getPatchMaxOperations() + "）");
        }

        int operations = 0;
        int replacements = 0;
        int insertedLines = 0;
        int deletedLines = 0;
        List<String> summaries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (JsonNode op : rootNode) {
            operations++;
            String type = requiredText(op, "type");
            switch (type) {
                case "regex_replace" -> {
                    String patternText = requiredText(op, "pattern");
                    String replacement = requiredTextAllowEmpty(op, "replacement");
                    if (containsNewline(replacement)) {
                        throw new IllegalArgumentException("regex_replace 的 replacement 不允许包含换行符（请改用 insert_lines/line_regex 实现多行变更）");
                    }
                    String flagsText = optionalText(op, "flags");
                    int maxReplacements = optionalPositiveInt(op, "maxReplacements", 0);
                    Pattern compiled = compilePattern(patternText, flagsText, false);

                    int remaining = maxReplacements;
                    int opReplacements = 0;
                    int changedLineCount = 0;
                    for (int i = 0; i < lines.size(); i++) {
                        if (maxReplacements > 0 && remaining <= 0) {
                            break;
                        }
                        ReplaceResult rr = replaceAndCount(lines.get(i), compiled, replacement, remaining);
                        if (rr.count() > 0) {
                            opReplacements += rr.count();
                            remaining = (maxReplacements > 0) ? Math.max(0, remaining - rr.count()) : remaining;
                            if (!rr.text().equals(lines.get(i))) {
                                lines.set(i, rr.text());
                                changedLineCount++;
                            }
                        }
                    }
                    replacements += opReplacements;
                    summaries.add("regex_replace：替换次数=" + opReplacements + "，影响行数=" + changedLineCount);
                }
                case "insert_lines" -> {
                    int atLine = requiredPositiveInt(op, "atLine");
                    String position = optionalText(op, "position");
                    String text = requiredTextAllowEmpty(op, "text");
                    List<String> insert = splitToLines(text);
                    if (insert.isEmpty()) {
                        summaries.add("insert_lines：插入 0 行（忽略）");
                        break;
                    }

                    String pos = (position == null || position.isBlank()) ? "before" : position.trim().toLowerCase();
                    int index;
                    if ("before".equals(pos)) {
                        if (atLine < 1 || atLine > lines.size() + 1) {
                            throw new IllegalArgumentException("insert_lines 的 atLine 超出范围：atLine=" + atLine + "，允许范围 1.." + (lines.size() + 1));
                        }
                        index = atLine - 1;
                    } else if ("after".equals(pos)) {
                        if (atLine < 1 || atLine > lines.size()) {
                            throw new IllegalArgumentException("insert_lines 的 atLine 超出范围（after 只能插在现有行之后）：atLine=" + atLine + "，允许范围 1.." + lines.size());
                        }
                        index = atLine;
                    } else {
                        throw new IllegalArgumentException("insert_lines 的 position 不支持：" + position + "（请使用 before/after）");
                    }

                    lines.addAll(index, insert);
                    insertedLines += insert.size();
                    summaries.add("insert_lines：插入行数=" + insert.size() + "，位置=" + pos + "，atLine=" + atLine);
                }
                case "delete_lines" -> {
                    int fromLine = requiredPositiveInt(op, "fromLine");
                    int toLine = optionalPositiveInt(op, "toLine", fromLine);
                    if (toLine < fromLine) {
                        throw new IllegalArgumentException("delete_lines 的 toLine 不能小于 fromLine：" + fromLine + ".." + toLine);
                    }
                    if (fromLine < 1 || fromLine > lines.size()) {
                        throw new IllegalArgumentException("delete_lines 的 fromLine 超出范围：fromLine=" + fromLine + "，允许范围 1.." + lines.size());
                    }
                    if (toLine > lines.size()) {
                        throw new IllegalArgumentException("delete_lines 的 toLine 超出范围：toLine=" + toLine + "，允许范围 1.." + lines.size());
                    }
                    int count = toLine - fromLine + 1;
                    lines.subList(fromLine - 1, toLine).clear();
                    deletedLines += count;
                    summaries.add("delete_lines：删除行数=" + count + "，范围=" + fromLine + ".." + toLine);
                }
                case "line_regex" -> {
                    String patternText = requiredText(op, "pattern");
                    String action = requiredText(op, "action").trim().toLowerCase();
                    String flagsText = optionalText(op, "flags");
                    int maxEdits = optionalPositiveInt(op, "maxEdits", 0);
                    int occurrence = optionalPositiveInt(op, "occurrence", 0);
                    Pattern compiled = compilePattern(patternText, flagsText, false);

                    int edits = 0;
                    int matchCount = 0;
                    int localInserted = 0;
                    int localDeleted = 0;

                    for (int i = 0; i < lines.size(); i++) {
                        if (maxEdits > 0 && edits >= maxEdits) {
                            break;
                        }
                        String line = lines.get(i);
                        Matcher m = compiled.matcher(line);
                        if (!m.find()) {
                            continue;
                        }
                        matchCount++;
                        if (occurrence > 0 && matchCount != occurrence) {
                            continue;
                        }

                        switch (action) {
                            case "delete" -> {
                                lines.remove(i);
                                i--;
                                edits++;
                                localDeleted++;
                                if (occurrence > 0) {
                                    i = lines.size(); // 提前结束
                                }
                            }
                            case "replace" -> {
                                String replacement = requiredTextAllowEmpty(op, "replacement");
                                List<String> replLines = splitToLines(replacement);
                                if (replLines.isEmpty()) {
                                    // 替换为空视为删除该行
                                    lines.remove(i);
                                    i--;
                                    edits++;
                                    localDeleted++;
                                    if (occurrence > 0) {
                                        i = lines.size();
                                    }
                                    break;
                                }
                                // 支持“用多行替换一行”：先删原行，再插入新行
                                lines.remove(i);
                                lines.addAll(i, replLines);
                                i += replLines.size() - 1;
                                edits++;
                                localDeleted++;
                                localInserted += replLines.size();
                                if (occurrence > 0) {
                                    i = lines.size();
                                }
                            }
                            case "insert_before" -> {
                                String text = requiredTextAllowEmpty(op, "text");
                                List<String> insert = splitToLines(text);
                                if (!insert.isEmpty()) {
                                    lines.addAll(i, insert);
                                    i += insert.size(); // 跳过插入内容，避免重复触发
                                    edits++;
                                    localInserted += insert.size();
                                }
                                if (occurrence > 0) {
                                    i = lines.size();
                                }
                            }
                            case "insert_after" -> {
                                String text = requiredTextAllowEmpty(op, "text");
                                List<String> insert = splitToLines(text);
                                if (!insert.isEmpty()) {
                                    lines.addAll(i + 1, insert);
                                    i += insert.size(); // 跳过插入内容
                                    edits++;
                                    localInserted += insert.size();
                                }
                                if (occurrence > 0) {
                                    i = lines.size();
                                }
                            }
                            default -> throw new IllegalArgumentException("line_regex 的 action 不支持：" + action + "（请使用 delete/replace/insert_before/insert_after）");
                        }
                    }

                    insertedLines += localInserted;
                    deletedLines += localDeleted;
                    summaries.add("line_regex：action=" + action + "，命中行数=" + matchCount + "，编辑次数=" + edits + "，插入行=" + localInserted + "，删除行=" + localDeleted);
                }
                default -> throw new IllegalArgumentException("不支持的 patch 操作 type：" + type);
            }
        }

        String newText = joinLines(lines, eol, endsWithNewline);
        byte[] newBytes = newText.getBytes(StandardCharsets.UTF_8);
        if (newBytes.length > maxPendingBytes) {
            throw new IllegalArgumentException("补丁生成后的内容过大：" + newBytes.length + " 字节（上限 " + maxPendingBytes + "）");
        }

        boolean changed = !Arrays.equals(originalBytes, newBytes);
        if (!changed) {
            warnings.add("补丁未产生任何变化：无需确认写入。");
        }

        // 计算旧文件 sha256（可选）：用于 confirm 阶段做“是否被外部修改”校验
        String expectedSha256 = null;
        try {
            if (fileSize <= properties.getHashMaxBytes().toBytes()) {
                expectedSha256 = HashingUtils.sha256Hex(target);
            } else {
                warnings.add("现有文件过大，跳过 sha256 校验（避免性能开销）。");
            }
        } catch (IOException e) {
            warnings.add("计算现有文件 sha256 失败，跳过校验：" + e.getMessage());
        }

        String newSha256 = HashingUtils.sha256Hex(newBytes);

        // changed=false 时不创建 token，避免无意义写入导致文件时间戳变化
        if (!changed) {
            return new FilePatchPrepareResult(
                    null,
                    resolved.rootId(),
                    normalizeDisplayPath(resolved.displayPath()),
                    false,
                    true,
                    newBytes.length,
                    expectedSha256,
                    newSha256,
                    null,
                    operations,
                    replacements,
                    insertedLines,
                    deletedLines,
                    summaries.isEmpty() ? null : summaries,
                    warnings.isEmpty() ? null : warnings
            );
        }

        PendingFileWriteStore.PendingFileWrite pending = pendingWriteStore.create(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                target,
                newBytes,
                true,
                false,
                true,
                expectedSha256,
                newSha256
        );

        return new FilePatchPrepareResult(
                pending.token(),
                pending.rootId(),
                pending.displayPath(),
                true,
                true,
                newBytes.length,
                expectedSha256,
                newSha256,
                pending.expiresAt(),
                operations,
                replacements,
                insertedLines,
                deletedLines,
                summaries.isEmpty() ? null : summaries,
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_prepare_write_file",
            description = "准备写入文件（不直接写入）：返回 token + 风险提示；需再调用 fs_confirm_write_file 才会真正写入。"
    )
    /**
     * 准备写入文件（第一阶段）。
     * <p>
     * 该方法只做“校验 + 生成 token”，不会落盘写入，避免模型/调用方误操作直接改动文件。
     * <p>
     * 重要说明：
     * <ul>
     *   <li>写入内容会暂存在内存中（由 {@link PendingFileWriteStore} 保存），因此必须受 {@code app.fs.pending-write-max-bytes} 限制。</li>
     *   <li>如果目标文件已存在，并且文件大小不超过 {@code app.fs.hash-max-bytes}，会在 prepare 阶段计算 sha256，
     *       以便 confirm 阶段校验“文件是否被外部改动”。</li>
     * </ul>
     */
    public FileWritePrepareResult prepareWriteFile(
            @ToolParam(required = false, description = "rootId（可从 fs_list_roots 获取；为空默认 root0）") String rootId,
            @ToolParam(description = "目标文件路径（相对 rootId 或绝对路径）") String path,
            @ToolParam(description = "要写入的内容") String content,
            @ToolParam(required = false, description = "content 的编码：utf-8 或 base64（默认 utf-8）") String encoding,
            @ToolParam(required = false, description = "是否覆盖已存在文件（默认 false）") Boolean overwrite,
            @ToolParam(required = false, description = "是否自动创建父目录（默认 false）") Boolean createParents,
            @ToolParam(required = false, description = "是否允许内容包含客户端截断占位文本（如 “… chars truncated …”）；默认 false（建议保持 false）") Boolean allowTruncationMarker
    ) {
        if (!properties.isAllowWrite()) {
            throw new IllegalStateException("已禁止写入：配置 app.fs.allow-write=false");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("参数错误：path 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("参数错误：content 不能为空");
        }

        // 重要保护：部分 MCP 客户端/LLM 会在展示超长工具输出时插入 “... chars truncated ...” 等占位文本。
        // 该占位文本不是原文件内容，如果再把它当作“文件内容”写回，会导致源码/文件被写坏。
        // 因此这里默认拒绝包含该占位文本的写入；如确实需要写入该文本，请显式设置 allowTruncationMarker=true（不推荐）。
        String resolvedEncoding = (encoding == null || encoding.isBlank()) ? "utf-8" : encoding.trim().toLowerCase();
        boolean isUtf8Text = "utf-8".equals(resolvedEncoding) || "utf8".equals(resolvedEncoding);
        boolean truncationMarkerDetected = isUtf8Text && containsClientTruncationMarker(content);
        if (truncationMarkerDetected && !Boolean.TRUE.equals(allowTruncationMarker)) {
            throw new IllegalArgumentException(
                    "检测到写入内容中包含 “chars truncated” 截断占位文本。该文本通常来自客户端/LLM 对超长工具输出的截断展示，不是原文件内容。"
                            + "为避免写坏文件，已拒绝写入。请改用 fs_read_file_lines 或 fs_read_file_range 分片读取获取完整内容后再写入；"
                            + "如确实需要写入该占位文本，请显式设置 allowTruncationMarker=true。"
            );
        }

        // 解析写入目标：允许目标文件不存在，但路径必须位于 roots 白名单范围内
        SecurePathResolver.ResolvedPath resolved = pathResolver.resolveForWrite(rootId, path);
        Path target = resolved.absolutePath();

        boolean overwriteResolved = Boolean.TRUE.equals(overwrite);
        boolean createParentsResolved = Boolean.TRUE.equals(createParents);

        // 校验父目录：不存在时是否允许自动创建
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("目标路径无效：" + resolved.displayPath());
        }
        boolean parentExists = Files.exists(parent, LinkOption.NOFOLLOW_LINKS);
        if (parentExists && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("父路径不是目录：" + normalizeDisplayPath(parent.toString()));
        }
        if (!parentExists && !createParentsResolved) {
            throw new IllegalArgumentException("父目录不存在；请设置 createParents=true 自动创建：" + normalizeDisplayPath(parent.toString()));
        }

        // 解码内容（utf-8/base64），并做大小上限保护
        byte[] bytes = decodeInputContent(content, encoding);
        long maxPendingBytes = properties.getPendingWriteMaxBytes().toBytes();
        if (bytes.length > maxPendingBytes) {
            throw new IllegalArgumentException("内容过大：" + bytes.length + " 字节（上限 " + maxPendingBytes + "）");
        }

        List<String> warnings = new ArrayList<>();
        if (truncationMarkerDetected) {
            warnings.add("写入内容包含 “... chars truncated ...” 等截断占位文本：这通常表示你拿到的不是完整原文件内容，继续写入可能导致文件损坏。");
        }

        // 目标文件存在性与覆盖策略
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists) {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("目标路径是目录，无法写入文件：" + resolved.displayPath());
            }
            if (!overwriteResolved) {
                throw new IllegalArgumentException("目标文件已存在；请设置 overwrite=true 以覆盖：" + resolved.displayPath());
            }
            warnings.add("目标文件已存在，确认后将覆盖。");
        } else {
            warnings.add("目标文件不存在，确认后将创建。");
        }

        // 如果文件存在且大小合适，则计算旧文件 sha256，confirm 阶段可用来校验“是否被外部修改”
        String expectedSha256 = null;
        if (exists) {
            try {
                long size = Files.size(target);
                if (size <= properties.getHashMaxBytes().toBytes()) {
                    expectedSha256 = HashingUtils.sha256Hex(target);
                } else {
                    warnings.add("现有文件过大，跳过 sha256 校验（避免性能开销）。");
                }
            } catch (IOException e) {
                warnings.add("计算现有文件 sha256 失败，跳过校验：" + e.getMessage());
            }
        }

        // 待写入内容 sha256：用于给调用方展示“将写入的内容指纹”，也可用于外部校验
        String newSha256 = HashingUtils.sha256Hex(bytes);
        PendingFileWriteStore.PendingFileWrite pending = pendingWriteStore.create(
                resolved.rootId(),
                normalizeDisplayPath(resolved.displayPath()),
                target,
                bytes,
                overwriteResolved,
                createParentsResolved,
                exists,
                expectedSha256,
                newSha256
        );

        return new FileWritePrepareResult(
                pending.token(),
                pending.rootId(),
                pending.displayPath(),
                exists,
                overwriteResolved,
                bytes.length,
                expectedSha256,
                newSha256,
                pending.expiresAt(),
                warnings.isEmpty() ? null : warnings
        );
    }

    @Tool(
            name = "fs_confirm_write_file",
            description = "确认或取消写入文件：confirm=true 才会写入；confirm=false 则取消并丢弃 token。"
    )
    /**
     * 确认写入文件（第二阶段）。
     * <p>
     * 行为：
     * <ul>
     *   <li>{@code confirm=false}：取消写入，并立即删除 token。</li>
     *   <li>{@code confirm=true}：执行写入（尽可能采用原子写入），并删除 token。</li>
     * </ul>
     * <p>
     * 安全性：
     * <ul>
     *   <li>如果 prepare 阶段计算了旧文件 sha256，会在这里进行校验，防止“确认前文件已被外部改动”。</li>
     *   <li>默认禁止写入到符号链接目标（避免逃逸）。</li>
     * </ul>
     */
    public FileWriteConfirmResult confirmWriteFile(
            @ToolParam(description = "fs_prepare_write_file 或 fs_prepare_patch_file 返回的 token") String token,
            @ToolParam(required = false, description = "是否确认写入（true 写入 / false 取消；默认 false）") Boolean confirm
    ) {
        // 先 peek 一次：用于在 confirm=false 时也能返回 token 对应的信息
        PendingFileWriteStore.PendingFileWrite peek = pendingWriteStore.get(token);
        if (peek == null) {
            throw new IllegalArgumentException("token 无效或已过期");
        }
        boolean confirmed = Boolean.TRUE.equals(confirm);
        if (!confirmed) {
            pendingWriteStore.remove(token);
            return new FileWriteConfirmResult(
                    peek.token(),
                    peek.rootId(),
                    peek.displayPath(),
                    false,
                    false,
                    0,
                    null,
                    null,
                    List.of("已取消写入（confirm=false）")
            );
        }

        // confirm=true：取出并删除 token（避免重复确认）
        PendingFileWriteStore.PendingFileWrite pending = pendingWriteStore.remove(token);
        if (pending == null) {
            throw new IllegalArgumentException("token 无效或已过期");
        }

        // 重新做一次路径解析（防止路径环境变化，且确保仍在 roots 白名单范围内）
        SecurePathResolver.ResolvedPath resolvedNow = pathResolver.resolve(pending.rootId(), pending.targetFile().toString(), false);
        Path target = resolvedNow.absolutePath();

        List<String> warnings = new ArrayList<>();

        // 默认禁止写入到符号链接目标路径（避免逃逸/覆盖非预期文件）
        if (!properties.isAllowSymlink() && Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("不允许写入到符号链接目标路径：" + pending.displayPath());
        }

        // 校验目标文件存在性与是否被外部修改
        if (pending.expectExists()) {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("确认失败：目标文件在 prepare 后发生变化（原本应存在）");
            }
            if (pending.expectedSha256() != null) {
                try {
                    String currentSha = HashingUtils.sha256Hex(target);
                    if (!pending.expectedSha256().equalsIgnoreCase(currentSha)) {
                        throw new IllegalStateException("确认失败：目标文件内容已被修改（sha256 不一致）");
                    }
                } catch (IOException e) {
                    warnings.add("校验现有文件 sha256 失败，将继续执行：" + e.getMessage());
                }
            }
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("确认失败：目标文件在 prepare 后发生变化（现在已存在）");
        }

        // 按需创建父目录
        if (pending.createParents()) {
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                throw new IllegalStateException("创建父目录失败：" + target.getParent(), e);
            }
        } else if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("父目录不存在：" + normalizeDisplayPath(target.getParent().toString()));
        }

        // 写入尽量采用“临时文件 -> move 替换”的方式，降低部分写入导致文件损坏的风险
        try {
            writeAtomically(target, pending.bytes(), pending.overwrite());
        } catch (IOException e) {
            throw new IllegalStateException("写入文件失败：" + pending.displayPath(), e);
        }

        // 写入成功后同步更新索引/缓存：
        // - 文件名索引：记录该文件名 -> 路径
        // - 目录列表缓存：失效父目录下的所有缓存（避免继续返回旧目录列表）
        if (indexCache != null && indexCache.isEnabled()) {
            try {
                indexCache.recordName(
                        pending.rootId(),
                        fileName(target),
                        normalizeDisplayPath(pending.displayPath()),
                        false,
                        true
                );

                Path parent = target.getParent();
                if (parent != null) {
                    String parentRel = normalizeBasePath(safeRel(resolvedNow.rootPath(), parent));
                    // 父目录也写入索引（可能是刚创建出来的目录）
                    indexCache.recordName(
                            pending.rootId(),
                            fileName(parent),
                            parentRel,
                            true,
                            false
                    );
                    indexCache.invalidateDirectoryLists(pending.rootId(), parentRel);
                }
            } catch (Exception e) {
                // 缓存更新失败不应影响写入成功：只作为告警提示
                warnings.add("写入后更新缓存失败（不影响写入结果）：" + e.getMessage());
            }
        }

        return new FileWriteConfirmResult(
                pending.token(),
                pending.rootId(),
                pending.displayPath(),
                true,
                true,
                pending.bytes().length,
                pending.newSha256(),
                Instant.now(),
                warnings.isEmpty() ? null : warnings
        );
    }

    private int resolveLimit(Integer limit) {
        // 目录列表的分页上限保护：避免一次性返回过多条目
        int resolved = (limit == null) ? properties.getListDefaultLimit() : limit;
        resolved = Math.max(1, resolved);
        resolved = Math.min(resolved, properties.getListMaxLimit());
        return resolved;
    }

    private int resolveTreeMaxDepth(Integer maxDepth) {
        // 目录树的深度上限保护
        int resolved = (maxDepth == null) ? properties.getTreeDefaultDepth() : maxDepth;
        resolved = Math.max(0, resolved);
        return Math.min(resolved, properties.getTreeMaxDepth());
    }

    private int resolveTreeMaxEntries(Integer maxEntries) {
        // 目录树条目数上限保护：避免生成超大响应体
        int resolved = (maxEntries == null) ? properties.getTreeDefaultEntries() : maxEntries;
        resolved = Math.max(1, resolved);
        return Math.min(resolved, properties.getTreeMaxEntries());
    }

    private long resolveReadMaxBytes(Long maxBytes) {
        // 文件读取上限保护：避免大文件导致内存压力
        long resolved = (maxBytes == null) ? properties.getReadMaxBytes().toBytes() : maxBytes;
        resolved = Math.max(1, resolved);
        return Math.min(resolved, properties.getReadMaxBytes().toBytes());
    }

    private long resolveStepModelInfoMaxBytes(Long maxBytes) {
        long resolved = (maxBytes == null) ? STEP_MODEL_INFO_DEFAULT_MAX_BYTES : maxBytes;
        resolved = Math.max(1, resolved);
        return Math.min(resolved, STEP_MODEL_INFO_MAX_BYTES);
    }

    private long resolveStepDataMaxEntities(Long maxEntities) {
        long resolved = (maxEntities == null) ? STEP_DATA_DEFAULT_MAX_ENTITIES : maxEntities;
        resolved = Math.max(1L, resolved);
        return Math.min(resolved, STEP_DATA_MAX_ENTITIES);
    }

    private int resolveReadRangeMaxBytes(Integer maxBytes) {
        // 分片读取（按字节范围）上限保护：避免一次返回过大被客户端截断/占用过多内存
        long resolved = (maxBytes == null) ? properties.getReadRangeDefaultBytes().toBytes() : maxBytes.longValue();
        resolved = Math.max(1L, resolved);
        resolved = Math.min(resolved, properties.getReadRangeMaxBytes().toBytes());
        return (int) Math.min(resolved, Integer.MAX_VALUE);
    }

    private int resolveReadLinesMaxLines(Integer maxLines) {
        // 分片读取（按行）上限保护：避免一次返回过多行导致响应体过大
        int resolved = (maxLines == null) ? properties.getReadLinesDefaultMaxLines() : maxLines;
        resolved = Math.max(1, resolved);
        return Math.min(resolved, properties.getReadLinesMaxLines());
    }

    private int resolveSearchMaxMatches(Integer maxMatches) {
        // 搜索命中条数上限保护：避免返回体过大
        int resolved = (maxMatches == null) ? properties.getSearchDefaultMaxMatches() : maxMatches;
        resolved = Math.max(1, resolved);
        return Math.min(resolved, properties.getSearchMaxMatches());
    }

    private int resolveSearchMaxFiles(Integer maxFiles) {
        // 搜索扫描文件数上限保护：避免递归目录时耗时过长
        int resolved = (maxFiles == null) ? properties.getSearchDefaultMaxFiles() : maxFiles;
        resolved = Math.max(1, resolved);
        return Math.min(resolved, properties.getSearchMaxFiles());
    }

    private int resolveSearchMaxDepth(Integer maxDepth) {
        // 搜索递归深度上限保护：避免深层目录导致遍历耗时过长
        int resolved = (maxDepth == null) ? properties.getSearchDefaultMaxDepth() : maxDepth;
        resolved = Math.max(0, resolved);
        return Math.min(resolved, properties.getSearchMaxDepth());
    }

    private int resolveSearchMaxLineLength(Integer maxLineLength) {
        // 匹配片段最大长度上限保护：避免超长单行导致返回体膨胀/截断
        int resolved = (maxLineLength == null) ? properties.getSearchMaxLineLength() : maxLineLength;
        resolved = Math.max(20, resolved);
        return Math.min(resolved, properties.getSearchMaxLineLength());
    }

    private static boolean isHidden(Path path, String name) {
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return name.startsWith(".");
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return (name != null) ? name.toString() : path.toString();
    }

    private static int depth(Path baseDir, Path path) {
        try {
            return baseDir.relativize(path).getNameCount();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safeRel(Path root, Path path) {
        try {
            return root.relativize(path.toAbsolutePath().normalize()).toString();
        } catch (Exception e) {
            return path.toString();
        }
    }

    private static FileEntry toEntry(Path root, Path child, boolean isDirectory, boolean isFile, List<String> warnings) {
        String name = child.getFileName().toString();
        boolean isSymlink = Files.isSymbolicLink(child);
        Long sizeBytes = null;
        Instant lastModifiedAt = null;
        try {
            BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isRegularFile()) {
                sizeBytes = attrs.size();
            }
            if (attrs.lastModifiedTime() != null) {
                lastModifiedAt = attrs.lastModifiedTime().toInstant();
            }
        } catch (IOException e) {
            warnings.add("读取文件属性失败：" + name + "（" + e.getMessage() + "）");
        }
        String rel;
        try {
            rel = root.relativize(child.toAbsolutePath().normalize()).toString();
        } catch (Exception e) {
            rel = name;
        }
        return new FileEntry(name, normalizeDisplayPath(rel), isDirectory, isFile, isSymlink, sizeBytes, lastModifiedAt);
    }

    private static String detectEol(Path file) {
        // 仅用于提示调用方“拼接多次分片结果”时尽量保持原文件的换行风格：
        // - 优先识别 \r\n（Windows 常见）
        // - 否则识别 \n（Linux/macOS 常见）
        // 只探测文件开头一小段，避免大文件带来性能开销；探测失败则默认返回 \n。
        final int probeBytes = 64 * 1024;
        byte[] buffer = new byte[probeBytes];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.read(buffer);
        } catch (IOException e) {
            return "\n";
        }
        if (read <= 0) {
            return "\n";
        }
        for (int i = 0; i < read; i++) {
            if (buffer[i] == '\n') {
                if (i > 0 && buffer[i - 1] == '\r') {
                    return "\r\n";
                }
                return "\n";
            }
        }
        return "\n";
    }

    private static byte[] readRangeBytes(Path file, long offset, int maxBytes) throws IOException {
        // 用随机访问方式读取文件的一段字节（比 InputStream.skip 更适合大 offset 场景）。
        if (maxBytes <= 0) {
            return new byte[0];
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(maxBytes);
            int read = channel.read(buffer);
            if (read <= 0) {
                return new byte[0];
            }
            return Arrays.copyOf(buffer.array(), read);
        }
    }

    private static byte[] readUpTo(Path file, long maxBytes) {
        // 流式读取：最多读取 maxBytes 字节，避免大文件读取带来的内存/时间开销
        try (InputStream in = Files.newInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, 1024 * 1024));
            byte[] buffer = new byte[8192];
            long remaining = maxBytes;
            int read;
            while (remaining > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) >= 0) {
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败：" + file, e);
        }
    }

    private static String decodeUtf8BestEffort(byte[] bytes) {
        // 对非法 UTF-8 字节用“替换字符”兜底，保证不会因为编码问题抛异常导致工具失败
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean isValidUtf8(byte[] bytes) {
        // 严格校验 UTF-8：遇到非法序列直接判定为非文本，改用 base64 返回
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private record DecodedText(String text, String decodedWith) {
    }

    private static DecodedText decodeStepText(byte[] bytes, boolean truncated, List<String> warnings) {
        if (bytes == null || bytes.length == 0) {
            return new DecodedText("", "utf-8");
        }
        if (isValidUtf8(bytes)) {
            return new DecodedText(decodeUtf8BestEffort(bytes), "utf-8");
        }

        // STEP 文件扫描通常会按 maxBytes 截断；如果原文件是 UTF-8，则截断点可能刚好落在多字节字符中间，
        // 这会导致严格的 UTF-8 校验失败。
        // 这里做一个“前缀 UTF-8”判断：允许末尾存在不完整的 UTF-8 序列（UNDERFLOW），但不允许中间出现非法字节序列（ERROR）。
        if (truncated && isUtf8PrefixAllowTruncatedTail(bytes)) {
            addWarningLimited(warnings, "STEP 内容被 maxBytes 截断，末尾可能存在不完整 UTF-8 序列；已按 UTF-8 尽力解码。");
            return new DecodedText(decodeUtf8BestEffort(bytes), "utf-8");
        }

        Charset charset;
        try {
            charset = Charset.forName("GB18030");
        } catch (Exception e) {
            charset = Charset.defaultCharset();
        }

        var decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            text = new String(bytes, charset);
        }

        addWarningLimited(warnings, "文件不是有效 UTF-8，已使用 " + charset.name() + " 尝试解码。");
        return new DecodedText(text, charset.name().toLowerCase(Locale.ROOT));
    }

    private static boolean isUtf8PrefixAllowTruncatedTail(byte[] bytes) {
        // 说明：
        // - endOfInput=false 会把“末尾不完整序列”视为 UNDERFLOW（可接受），而不是 ERROR。
        // - 这样可以区分“真正的非 UTF-8 文件”（中间会出现 ERROR）与“UTF-8 但被截断”的情况。
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes);
            CharBuffer out = CharBuffer.allocate(4096);
            while (true) {
                CoderResult r = decoder.decode(in, out, false);
                if (r.isError()) {
                    return false;
                }
                if (r.isOverflow()) {
                    out.clear();
                    continue;
                }
                // UNDERFLOW：输入耗尽（或末尾不完整），都认为“前缀是合法 UTF-8”。
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLikelyTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        for (String ext : TEXT_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean searchFileIntoMatches(
            Path file,
            String displayPath,
            Pattern pattern,
            String query,
            boolean regex,
            boolean caseSensitive,
            int maxMatches,
            int maxLineLength,
            List<FileSearchMatch> matches,
            List<String> warnings,
            boolean strict
    ) {
        // 按行扫描文件，遇到命中则追加到 matches；一旦达到 maxMatches 立刻停止读取（性能优化）。
        int remaining = maxMatches - matches.size();
        if (remaining <= 0) {
            return true;
        }

        int lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                int matchStart;
                int matchEnd;
                if (regex) {
                    Matcher m = pattern.matcher(line);
                    if (!m.find()) {
                        continue;
                    }
                    matchStart = m.start();
                    matchEnd = m.end();
                } else {
                    matchStart = indexOf(line, query, caseSensitive);
                    if (matchStart < 0) {
                        continue;
                    }
                    matchEnd = Math.min(line.length(), matchStart + query.length());
                }

                Excerpt excerpt = buildExcerpt(line, matchStart, matchEnd, maxLineLength);
                matches.add(new FileSearchMatch(
                        normalizeDisplayPath(displayPath),
                        lineNo,
                        matchStart + 1,
                        excerpt.text(),
                        excerpt.truncated(),
                        excerpt.originalLength()
                ));

                if (matches.size() >= maxMatches) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 目录搜索时：尽量不中断整体搜索；单文件搜索时：直接抛出错误
            if (strict) {
                throw new IllegalStateException("搜索文件失败：" + displayPath + "（" + e.getMessage() + "）", e);
            }
            addWarningLimited(warnings, "跳过无法搜索的文件：" + normalizeDisplayPath(displayPath) + "（" + e.getMessage() + "）");
        }

        return false;
    }

    private void scanByNameInDirectory(
            String rootId,
            Path rootPath,
            Path baseDir,
            String name,
            boolean caseSensitive,
            boolean includeHidden,
            Boolean wantDirectory,
            Boolean wantFile,
            int maxDepth,
            int maxFiles,
            int maxResults,
            List<NameResolveEntry> matches,
            int[] scannedFiles,
            boolean[] truncated,
            List<String> warnings
    ) {
        // 说明：
        // - 本方法用于缓存未命中时的“回退扫描”，并在扫描过程中增量更新文件名索引。
        // - 为避免深层目录/大量文件导致长时间扫描，这里受 maxDepth/maxFiles/maxResults 上限保护。
        if (truncated[0] || matches.size() >= maxResults) {
            return;
        }

        boolean allowDir = !Boolean.TRUE.equals(wantFile);
        boolean allowFile = !Boolean.TRUE.equals(wantDirectory);

        Path rootReal;
        try {
            rootReal = rootPath.toRealPath();
        } catch (IOException e) {
            addWarningLimited(warnings, "根目录无法解析，已跳过：" + rootPath + "（" + e.getMessage() + "）");
            return;
        }

        Set<Path> visitedRealDirs = new HashSet<>();
        try {
            Files.walkFileTree(baseDir, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir != baseDir && !includeHidden && isHidden(dir, fileName(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (attrs.isSymbolicLink() && !properties.isAllowSymlink()) {
                        addWarningLimited(warnings, "已跳过符号链接目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    Path real;
                    try {
                        real = dir.toRealPath();
                    } catch (IOException e) {
                        addWarningLimited(warnings, "目录无法解析，已跳过：" + normalizeBasePath(safeRel(rootPath, dir)) + "（" + e.getMessage() + "）");
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!real.startsWith(rootReal)) {
                        addWarningLimited(warnings, "已跳过根目录范围外的目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!visitedRealDirs.add(real)) {
                        addWarningLimited(warnings, "疑似存在循环引用，已跳过目录：" + normalizeBasePath(safeRel(rootPath, dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // 增量建立索引：目录
                    if (indexCache != null && indexCache.isEnabled()) {
                        indexCache.recordName(
                                rootId,
                                fileName(dir),
                                normalizeBasePath(safeRel(rootPath, dir)),
                                true,
                                false
                        );
                    }

                    // 目录名命中（仅在允许返回目录时记录）
                    if (allowDir && equalsName(fileName(dir), name, caseSensitive)) {
                        matches.add(new NameResolveEntry(rootId, normalizeBasePath(safeRel(rootPath, dir)), true, false));
                        if (matches.size() >= maxResults) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!includeHidden && isHidden(file, fileName(file))) {
                        return FileVisitResult.CONTINUE;
                    }

                    scannedFiles[0]++;
                    if (scannedFiles[0] > maxFiles) {
                        truncated[0] = true;
                        addWarningLimited(warnings, "已达到最大扫描文件数上限（maxFiles=" + maxFiles + "），提前结束扫描。");
                        return FileVisitResult.TERMINATE;
                    }

                    // 增量建立索引：文件
                    if (indexCache != null && indexCache.isEnabled()) {
                        indexCache.recordName(
                                rootId,
                                fileName(file),
                                normalizeBasePath(safeRel(rootPath, file)),
                                false,
                                true
                        );
                    }

                    // 文件名命中（仅在允许返回文件时记录）
                    if (allowFile && equalsName(fileName(file), name, caseSensitive)) {
                        matches.add(new NameResolveEntry(rootId, normalizeBasePath(safeRel(rootPath, file)), false, true));
                        if (matches.size() >= maxResults) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    addWarningLimited(warnings, "访问失败：" + normalizeBasePath(safeRel(rootPath, file)) + "（" + exc.getMessage() + "）");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            addWarningLimited(warnings, "扫描目录失败：" + normalizeBasePath(safeRel(rootPath, baseDir)) + "（" + e.getMessage() + "）");
        }
    }

    private record Excerpt(String text, boolean truncated, Integer originalLength) {
    }

    private static Excerpt buildExcerpt(String line, int matchStart, int matchEnd, int maxLineLength) {
        // 生成“围绕匹配位置”的片段：既控制长度，又尽量把命中内容包含在片段中。
        if (line == null) {
            return new Excerpt("", false, null);
        }
        int originalLength = line.length();
        if (originalLength <= maxLineLength) {
            return new Excerpt(line, false, null);
        }

        // prefix/suffix 各预留 1 个省略号字符（…），保证最终长度不超过 maxLineLength
        int prefix = 1;
        int suffix = 1;
        int available = Math.max(1, maxLineLength - prefix - suffix);

        int desiredStart = matchStart - available / 2;
        int start = Math.max(0, Math.min(desiredStart, originalLength - available));
        int end = Math.min(originalLength, start + available);

        boolean hasPrefix = start > 0;
        boolean hasSuffix = end < originalLength;

        String core = line.substring(start, end);
        StringBuilder sb = new StringBuilder(maxLineLength);
        if (hasPrefix) {
            sb.append('…');
        } else {
            // 不需要前缀省略号时，回收一个字符的容量给 core
            //（这里不再二次截取 core，避免过度复杂；保持实现简单即可）
        }
        sb.append(core);
        if (hasSuffix) {
            sb.append('…');
        }

        int usedLength = sb.length();
        if (usedLength > maxLineLength) {
            sb.setLength(maxLineLength);
        }
        return new Excerpt(sb.toString(), true, originalLength);
    }

    private static Excerpt truncateLineHead(String line, int maxLineLength) {
        // 非命中行/上下文行的“行截断”策略：默认保留行首，末尾用省略号表示截断。
        if (line == null) {
            return new Excerpt("", false, null);
        }
        int originalLength = line.length();
        if (originalLength <= maxLineLength) {
            return new Excerpt(line, false, null);
        }
        if (maxLineLength <= 1) {
            return new Excerpt("…", true, originalLength);
        }
        return new Excerpt(line.substring(0, maxLineLength - 1) + "…", true, originalLength);
    }

    private record LineBufferItem(int lineNumber, String text) {
    }

    private static void pushBeforeBuffer(ArrayDeque<LineBufferItem> buffer, int lineNumber, String text, int maxSize) {
        if (buffer == null || maxSize <= 0) {
            return;
        }
        buffer.addLast(new LineBufferItem(lineNumber, text));
        while (buffer.size() > maxSize) {
            buffer.removeFirst();
        }
    }

    private static void emitTailContext(
            ArrayDeque<LineBufferItem> beforeBuffer,
            int contextToEmit,
            List<FileReadFilteredLine> out,
            Map<Integer, Integer> indexByLine,
            int maxLineLength
    ) {
        if (beforeBuffer == null || contextToEmit <= 0) {
            return;
        }
        int size = beforeBuffer.size();
        int skip = Math.max(0, size - contextToEmit);
        int i = 0;
        for (LineBufferItem item : beforeBuffer) {
            if (i++ < skip) {
                continue;
            }
            Excerpt ex = truncateLineHead(item.text(), maxLineLength);
            emitFilteredLine(out, indexByLine, item.lineNumber(), false, null, ex);
        }
    }

    private static void emitFilteredLine(
            List<FileReadFilteredLine> out,
            Map<Integer, Integer> indexByLine,
            int lineNumber,
            boolean match,
            Integer column,
            Excerpt excerpt
    ) {
        Integer idx = indexByLine.get(lineNumber);
        if (idx != null) {
            // 同一行可能先以“上下文行”形式被加入，后又被识别为“命中行”；此时需要升级为命中行信息。
            FileReadFilteredLine existing = out.get(idx);
            if (match && !existing.match()) {
                out.set(idx, new FileReadFilteredLine(
                        lineNumber,
                        true,
                        column,
                        excerpt.text(),
                        excerpt.truncated(),
                        excerpt.originalLength()
                ));
            } else if (existing.match() && existing.column() == null && column != null) {
                // 补充命中列号（通常不会发生，但保留兜底）
                out.set(idx, new FileReadFilteredLine(
                        lineNumber,
                        true,
                        column,
                        existing.text(),
                        existing.textTruncated(),
                        existing.originalLineLength()
                ));
            }
            return;
        }

        out.add(new FileReadFilteredLine(
                lineNumber,
                match,
                match ? column : null,
                excerpt.text(),
                excerpt.truncated(),
                excerpt.originalLength()
        ));
        indexByLine.put(lineNumber, out.size() - 1);
    }

    private static int resolveContextLines(Integer value) {
        // 上下文行数上限保护：避免异常参数导致输出/内存膨胀。
        int resolved = (value == null) ? 0 : Math.max(0, value);
        return Math.min(resolved, 200);
    }

    private static int indexOf(String text, String token, boolean caseSensitive) {
        if (text == null || token == null) {
            return -1;
        }
        if (caseSensitive) {
            return text.indexOf(token);
        }
        return indexOfIgnoreCase(text, token);
    }

    private static int indexOfIgnoreCase(String text, String token) {
        int textLength = text.length();
        int tokenLength = token.length();
        if (tokenLength == 0) {
            return 0;
        }
        if (tokenLength > textLength) {
            return -1;
        }
        for (int i = 0; i <= textLength - tokenLength; i++) {
            if (text.regionMatches(true, i, token, 0, tokenLength)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equalsName(String a, String b, boolean caseSensitive) {
        if (a == null || b == null) {
            return false;
        }
        return caseSensitive ? a.equals(b) : a.equalsIgnoreCase(b);
    }

    private static boolean isUnderBase(String candidatePath, String basePath) {
        // 判断 candidatePath 是否位于 basePath 之下（用于缓存结果的二次过滤）。
        // 注意：这里的 path 都是“相对 root 的路径”，并且统一用 / 分隔。
        String base = normalizeBasePath(basePath);
        if (base == null || base.isBlank() || ".".equals(base)) {
            return true;
        }
        String p = normalizeBasePath(candidatePath);
        if (p.equals(base)) {
            return true;
        }
        return p.startsWith(base + "/");
    }

    private static void addWarningLimited(List<String> warnings, String message) {
        // 避免 warnings 因为“跳过大量文件”而膨胀到非常大（反过来又导致输出被截断）。
        final int maxWarnings = 50;
        if (warnings == null) {
            return;
        }
        if (warnings.size() < maxWarnings) {
            warnings.add(message);
            return;
        }
        if (warnings.size() == maxWarnings) {
            warnings.add("告警过多，已省略后续告警…");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null) {
            throw new IllegalArgumentException("patch 格式错误：操作对象不能为空");
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("patch 格式错误：缺少字段 " + field);
        }
        String text = v.asText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("patch 格式错误：字段 " + field + " 不能为空");
        }
        return text;
    }

    private static String requiredTextAllowEmpty(JsonNode node, String field) {
        if (node == null) {
            throw new IllegalArgumentException("patch 格式错误：操作对象不能为空");
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("patch 格式错误：缺少字段 " + field);
        }
        return v.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String text = v.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    private static int requiredPositiveInt(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("patch 格式错误：缺少字段 " + field);
        }
        int value = v.asInt(Integer.MIN_VALUE);
        if (value <= 0) {
            throw new IllegalArgumentException("patch 格式错误：字段 " + field + " 必须为正整数");
        }
        return value;
    }

    private static int optionalPositiveInt(JsonNode node, String field, int defaultValue) {
        JsonNode v = (node == null) ? null : node.get(field);
        if (v == null || v.isNull()) {
            return defaultValue;
        }
        int value = v.asInt(Integer.MIN_VALUE);
        if (value < 0) {
            throw new IllegalArgumentException("patch 格式错误：字段 " + field + " 不能为负数");
        }
        return value;
    }

    private static boolean containsNewline(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitToLines(String text) {
        // 把一段文本按换行符切成“行列表”（不包含行尾换行符）。
        // 规则：
        // - text="" 表示插入/替换 1 行空行（而不是 0 行）。
        // - 如果 text 以换行符结尾，则去掉最后一个“空字符串”尾元素，避免意外多插入一行。
        if (text == null) {
            return List.of();
        }
        if (text.isEmpty()) {
            return List.of("");
        }
        String[] parts = text.split("\\R", -1);
        int len = parts.length;
        if (len > 0 && parts[len - 1].isEmpty() && containsNewline(text.substring(text.length() - 1))) {
            // 兼容末尾换行：例如 "a\n" -> ["a", ""]，去掉最后的空元素
            len--;
        }
        List<String> result = new ArrayList<>(Math.max(1, len));
        for (int i = 0; i < len; i++) {
            result.add(parts[i]);
        }
        return result;
    }

    private static String joinLines(List<String> lines, String eol, boolean endsWithNewline) {
        // 把“行列表”拼回文本，尽量保持原文件换行风格，并尽量保留“是否以换行结尾”的特征。
        if (lines == null || lines.isEmpty()) {
            return endsWithNewline ? eol : "";
        }
        String resolvedEol = (eol == null || eol.isEmpty()) ? "\n" : eol;
        StringBuilder sb = new StringBuilder(Math.min(1024 * 1024, lines.size() * 64));
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) {
                sb.append(resolvedEol);
            }
        }
        if (endsWithNewline) {
            sb.append(resolvedEol);
        }
        return sb.toString();
    }

    private static Pattern compilePattern(String pattern, String flagsText, boolean caseInsensitiveByDefault) {
        int flags = caseInsensitiveByDefault ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0;
        if (flagsText != null && !flagsText.isBlank()) {
            for (int i = 0; i < flagsText.length(); i++) {
                char c = Character.toLowerCase(flagsText.charAt(i));
                switch (c) {
                    case 'i' -> flags |= (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                    case 'm' -> flags |= Pattern.MULTILINE;
                    case 's' -> flags |= Pattern.DOTALL;
                    case 'u' -> flags |= Pattern.UNICODE_CASE;
                    case ' ' -> {
                        // 忽略空格，便于写成 "i m"
                    }
                    default -> throw new IllegalArgumentException("不支持的正则 flags：" + c + "（支持 i/m/s/u）");
                }
            }
        }
        try {
            return Pattern.compile(pattern, flags);
        } catch (Exception e) {
            throw new IllegalArgumentException("正则表达式不合法：" + e.getMessage(), e);
        }
    }

    private record ReplaceResult(String text, int count) {
    }

    private static ReplaceResult replaceAndCount(String input, Pattern pattern, String replacement, int maxReplacements) {
        // 替换并统计替换次数：
        // - maxReplacements<=0 表示不限制
        // - replacement 使用 Java 的 Matcher 替换语法（支持 $1 等引用）
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return new ReplaceResult(input, 0);
        }
        matcher.reset();

        int count = 0;
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            count++;
            if (maxReplacements > 0 && count > maxReplacements) {
                // 超过上限：从当前 match 起不再替换，直接把剩余内容原样追加
                matcher.appendTail(sb);
                return new ReplaceResult(sb.toString(), count - 1);
            }
            matcher.appendReplacement(sb, replacement);
            if (maxReplacements > 0 && count >= maxReplacements) {
                matcher.appendTail(sb);
                return new ReplaceResult(sb.toString(), count);
            }
        }
        matcher.appendTail(sb);
        return new ReplaceResult(sb.toString(), count);
    }

    private static byte[] decodeInputContent(String content, String encoding) {
        // 写入内容支持两种编码：
        // - utf-8：直接把字符串按 UTF-8 编码成字节
        // - base64：解码得到原始字节（适合写二进制文件）
        String resolvedEncoding = (encoding == null || encoding.isBlank()) ? "utf-8" : encoding.trim().toLowerCase();
        return switch (resolvedEncoding) {
            case "utf-8", "utf8" -> content.getBytes(StandardCharsets.UTF_8);
            case "base64" -> {
                try {
                    yield Base64.getDecoder().decode(content);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("base64 内容不合法", e);
                }
            }
            default -> throw new IllegalArgumentException("不支持的 encoding：" + encoding + "（请使用 utf-8 或 base64）");
        };
    }

    private static boolean containsClientTruncationMarker(String content) {
        // 只识别常见英文占位文本关键词，尽量避免误判正常源码/文本。
        // 典型场景：UI/客户端在输出过长时插入 “… chars truncated …” 或 “… 2310 chars truncated …”。
        if (content == null || content.isEmpty()) {
            return false;
        }
        return containsIgnoreCase(content, "chars truncated")
                || containsIgnoreCase(content, "characters truncated");
    }

    private static boolean containsIgnoreCase(String text, String token) {
        if (text == null || token == null) {
            return false;
        }
        int textLength = text.length();
        int tokenLength = token.length();
        if (tokenLength == 0) {
            return true;
        }
        if (tokenLength > textLength) {
            return false;
        }
        for (int i = 0; i <= textLength - tokenLength; i++) {
            if (text.regionMatches(true, i, token, 0, tokenLength)) {
                return true;
            }
        }
        return false;
    }

    private static void writeAtomically(Path target, byte[] bytes, boolean overwrite) throws IOException {
        // 尽量原子写入：
        // 1) 先写到同目录的临时文件（保证与目标文件在同一文件系统内）
        // 2) 再通过 move 原子替换到目标路径（ATOMIC_MOVE 可能不支持，则降级为普通 move）
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("目标路径无效：" + target);
        }
        Path tmp = Files.createTempFile(parent, "mcp-write-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                if (overwrite) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException e) {
                if (overwrite) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(tmp, target);
                }
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
        }
    }

    private static String normalizeDisplayPath(String path) {
        if (path == null) {
            return null;
        }
        return path.replace('\\', '/');
    }

    private static String normalizeBasePath(String displayPath) {
        String normalized = normalizeDisplayPath(displayPath);
        if (normalized == null || normalized.isBlank()) {
            return ".";
        }
        return normalized;
    }
}
