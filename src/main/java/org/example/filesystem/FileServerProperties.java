package org.example.filesystem;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * 文件系统 MCP Server 的业务配置（{@code app.fs.*}）。
 * <p>
 * 重点：
 * <ul>
 *   <li>通过 {@link #roots} 指定允许访问的根目录白名单，服务端只允许在这些目录内读写。</li>
 *   <li>通过各种 limit/bytes 配置控制返回体积与内存占用，避免深层目录/大文件导致性能问题。</li>
 *   <li>通过 {@link #allowSymlink} 控制是否允许符号链接/链接目录（默认不允许，防止路径逃逸）。</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "app.fs")
public class FileServerProperties {

    /**
     * 允许访问的根目录白名单。
     * <p>
     * 说明：
     * <ul>
     *   <li>每个 root 会自动分配一个 {@code rootId}（root0、root1...）。</li>
     *   <li>工具调用时可传入 rootId + 相对路径，或直接传入绝对路径（会自动匹配到最合适的 root）。</li>
     * </ul>
     */
    @NotNull
    private List<String> roots = List.of(".");

    /**
     * {@code fs_list_directory} 默认返回条数（分页大小）。
     */
    @Min(1)
    @Max(100_000)
    private int listDefaultLimit = 200;

    /**
     * {@code fs_list_directory} 允许的最大返回条数（上限保护）。
     */
    @Min(1)
    @Max(100_000)
    private int listMaxLimit = 5_000;

    /**
     * {@code fs_list_tree} 默认递归深度（0 表示只返回当前目录本身）。
     */
    @Min(0)
    @Max(1_000)
    private int treeDefaultDepth = 8;

    /**
     * {@code fs_list_tree} 允许的最大递归深度（上限保护）。
     */
    @Min(0)
    @Max(1_000)
    private int treeMaxDepth = 32;

    /**
     * {@code fs_list_tree} 默认最大条目数。
     */
    @Min(1)
    @Max(1_000_000)
    private int treeDefaultEntries = 10_000;

    /**
     * {@code fs_list_tree} 允许的最大条目数（上限保护）。
     * <p>
     * 注意：条目数过大可能导致响应体很大，建议按需调整。
     */
    @Min(1)
    @Max(1_000_000)
    private int treeMaxEntries = 50_000;

    /**
     * {@code fs_read_file} 读取文件的最大字节数（超过则截断）。
     */
    @NotNull
    private DataSize readMaxBytes = DataSize.ofMegabytes(1);

    /**
     * {@code fs_read_file_range} 默认读取字节数（分片大小）。
     * <p>
     * 说明：用于把文件拆成多个较小的片段返回，降低被客户端/LLM 截断的概率。
     */
    @NotNull
    private DataSize readRangeDefaultBytes = DataSize.ofKilobytes(64);

    /**
     * {@code fs_read_file_range} 单次允许读取的最大字节数（上限保护）。
     */
    @NotNull
    private DataSize readRangeMaxBytes = DataSize.ofKilobytes(256);

    /**
     * {@code fs_read_file_lines} 默认最大行数（分片大小）。
     */
    @Min(1)
    @Max(100_000)
    private int readLinesDefaultMaxLines = 200;

    /**
     * {@code fs_read_file_lines} 单次允许读取的最大行数（上限保护）。
     */
    @Min(1)
    @Max(100_000)
    private int readLinesMaxLines = 2_000;

    /**
     * {@code fs_search} 默认最大返回匹配数（命中条数上限）。
     * <p>
     * 说明：搜索结果只返回“行号 + 片段”，但如果命中条数过多仍然会造成返回体过大；因此需要上限保护。
     */
    @Min(1)
    @Max(1_000_000)
    private int searchDefaultMaxMatches = 200;

    /**
     * {@code fs_search} 允许的最大返回匹配数（上限保护）。
     */
    @Min(1)
    @Max(1_000_000)
    private int searchMaxMatches = 5_000;

    /**
     * {@code fs_search} 默认最大扫描文件数（用于目录递归搜索的上限保护）。
     */
    @Min(1)
    @Max(1_000_000)
    private int searchDefaultMaxFiles = 2_000;

    /**
     * {@code fs_search} 允许的最大扫描文件数（上限保护）。
     */
    @Min(1)
    @Max(1_000_000)
    private int searchMaxFiles = 20_000;

    /**
     * {@code fs_search} 默认最大搜索深度（仅对目录有效）。
     */
    @Min(0)
    @Max(10_000)
    private int searchDefaultMaxDepth = 20;

    /**
     * {@code fs_search} 允许的最大搜索深度（上限保护）。
     */
    @Min(0)
    @Max(10_000)
    private int searchMaxDepth = 100;

    /**
     * {@code fs_search} 单条匹配片段（excerpt）的最大字符数。
     * <p>
     * 说明：避免单行过长导致返回体膨胀或在客户端被截断。
     */
    @Min(20)
    @Max(100_000)
    private int searchMaxLineLength = 400;

    /**
     * {@code fs_prepare_patch_file} 单次允许的最大操作数（上限保护）。
     * <p>
     * 说明：避免传入超大 patch 规则导致服务端长时间计算或占用过多内存。
     */
    @Min(1)
    @Max(100_000)
    private int patchMaxOperations = 200;

    /**
     * 是否启用索引/目录缓存。
     * <p>
     * 说明：开启后会缓存部分“文件名 -> 路径”与“目录列表结果”，用于减少重复 IO、提高大目录/深层目录场景的响应速度。
     */
    private boolean cacheEnabled = true;

    /**
     * 文件名索引缓存的 TTL（有效期）。
     * <p>
     * 说明：索引命中可直接得到路径候选；过期后会自动清理，避免长期陈旧。
     */
    @NotNull
    private Duration cacheNameIndexTtl = Duration.ofMinutes(30);

    /**
     * 文件名索引缓存：最多缓存多少个“不同名称”的 key（上限保护）。
     */
    @Min(1)
    @Max(10_000_000)
    private int cacheNameIndexMaxNames = 200_000;

    /**
     * 单个文件名 key 最多保留多少条路径候选（同名文件可能存在多个目录）。
     */
    @Min(1)
    @Max(10_000)
    private int cacheNameIndexMaxPathsPerName = 50;

    /**
     * 目录列表缓存的 TTL（有效期）。
     * <p>
     * 注意：目录列表缓存是“性能优化”，不保证强一致；写入/创建文件后会尽量对相关目录做失效处理。
     */
    @NotNull
    private Duration cacheDirectoryTtl = Duration.ofMinutes(30);

    /**
     * 目录列表缓存：最多缓存多少条“目录列表请求结果”（上限保护）。
     * <p>
     * 说明：目录列表缓存以“请求参数”为维度（包含 offset/limit/glob 等），因此该值越大，命中率越高，但占用内存也越多。
     */
    @Min(1)
    @Max(1_000_000)
    private int cacheDirectoryMaxEntries = 10_000;

    /**
     * 在 {@code fs_prepare_write_file} 阶段对“已存在目标文件”计算 sha256 的最大文件大小。
     * <p>
     * 作用：用于确认阶段做“是否被外部修改”的校验；文件太大时跳过哈希校验避免性能问题。
     */
    @NotNull
    private DataSize hashMaxBytes = DataSize.ofMegabytes(32);

    /**
     * 单次待写入内容（pending token）的最大字节数。
     * <p>
     * 说明：prepare 阶段内容会暂存在内存中，必须设置上限避免撑爆内存。
     */
    @NotNull
    private DataSize pendingWriteMaxBytes = DataSize.ofMegabytes(5);

    /**
     * 待写入 token 的有效期（超过则失效，需要重新 prepare）。
     */
    @NotNull
    private Duration pendingWriteTtl = Duration.ofMinutes(10);

    /**
     * 是否默认包含隐藏文件/目录（如 .git、.idea）。
     * <p>
     * 安全考虑：默认 false。
     */
    private boolean includeHiddenByDefault = false;

    /**
     * 是否允许写入文件。
     * <p>
     * 说明：即使为 true，也必须走“两段式确认”（prepare -> confirm）才会写入。
     */
    private boolean allowWrite = true;

    /**
     * 是否允许访问符号链接（symlink）。
     * <p>
     * 安全建议：默认 false；若开启请确保 {@link #roots} 已经非常严格。
     */
    private boolean allowSymlink = false;

    public List<String> getRoots() {
        return roots;
    }

    public void setRoots(List<String> roots) {
        this.roots = roots;
    }

    public int getListDefaultLimit() {
        return listDefaultLimit;
    }

    public void setListDefaultLimit(int listDefaultLimit) {
        this.listDefaultLimit = listDefaultLimit;
    }

    public int getListMaxLimit() {
        return listMaxLimit;
    }

    public void setListMaxLimit(int listMaxLimit) {
        this.listMaxLimit = listMaxLimit;
    }

    public int getTreeDefaultDepth() {
        return treeDefaultDepth;
    }

    public void setTreeDefaultDepth(int treeDefaultDepth) {
        this.treeDefaultDepth = treeDefaultDepth;
    }

    public int getTreeMaxDepth() {
        return treeMaxDepth;
    }

    public void setTreeMaxDepth(int treeMaxDepth) {
        this.treeMaxDepth = treeMaxDepth;
    }

    public int getTreeDefaultEntries() {
        return treeDefaultEntries;
    }

    public void setTreeDefaultEntries(int treeDefaultEntries) {
        this.treeDefaultEntries = treeDefaultEntries;
    }

    public int getTreeMaxEntries() {
        return treeMaxEntries;
    }

    public void setTreeMaxEntries(int treeMaxEntries) {
        this.treeMaxEntries = treeMaxEntries;
    }

    public DataSize getReadMaxBytes() {
        return readMaxBytes;
    }

    public void setReadMaxBytes(DataSize readMaxBytes) {
        this.readMaxBytes = readMaxBytes;
    }

    public DataSize getReadRangeDefaultBytes() {
        return readRangeDefaultBytes;
    }

    public void setReadRangeDefaultBytes(DataSize readRangeDefaultBytes) {
        this.readRangeDefaultBytes = readRangeDefaultBytes;
    }

    public DataSize getReadRangeMaxBytes() {
        return readRangeMaxBytes;
    }

    public void setReadRangeMaxBytes(DataSize readRangeMaxBytes) {
        this.readRangeMaxBytes = readRangeMaxBytes;
    }

    public int getReadLinesDefaultMaxLines() {
        return readLinesDefaultMaxLines;
    }

    public void setReadLinesDefaultMaxLines(int readLinesDefaultMaxLines) {
        this.readLinesDefaultMaxLines = readLinesDefaultMaxLines;
    }

    public int getReadLinesMaxLines() {
        return readLinesMaxLines;
    }

    public void setReadLinesMaxLines(int readLinesMaxLines) {
        this.readLinesMaxLines = readLinesMaxLines;
    }

    public int getSearchDefaultMaxMatches() {
        return searchDefaultMaxMatches;
    }

    public void setSearchDefaultMaxMatches(int searchDefaultMaxMatches) {
        this.searchDefaultMaxMatches = searchDefaultMaxMatches;
    }

    public int getSearchMaxMatches() {
        return searchMaxMatches;
    }

    public void setSearchMaxMatches(int searchMaxMatches) {
        this.searchMaxMatches = searchMaxMatches;
    }

    public int getSearchDefaultMaxFiles() {
        return searchDefaultMaxFiles;
    }

    public void setSearchDefaultMaxFiles(int searchDefaultMaxFiles) {
        this.searchDefaultMaxFiles = searchDefaultMaxFiles;
    }

    public int getSearchMaxFiles() {
        return searchMaxFiles;
    }

    public void setSearchMaxFiles(int searchMaxFiles) {
        this.searchMaxFiles = searchMaxFiles;
    }

    public int getSearchDefaultMaxDepth() {
        return searchDefaultMaxDepth;
    }

    public void setSearchDefaultMaxDepth(int searchDefaultMaxDepth) {
        this.searchDefaultMaxDepth = searchDefaultMaxDepth;
    }

    public int getSearchMaxDepth() {
        return searchMaxDepth;
    }

    public void setSearchMaxDepth(int searchMaxDepth) {
        this.searchMaxDepth = searchMaxDepth;
    }

    public int getSearchMaxLineLength() {
        return searchMaxLineLength;
    }

    public void setSearchMaxLineLength(int searchMaxLineLength) {
        this.searchMaxLineLength = searchMaxLineLength;
    }

    public int getPatchMaxOperations() {
        return patchMaxOperations;
    }

    public void setPatchMaxOperations(int patchMaxOperations) {
        this.patchMaxOperations = patchMaxOperations;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public Duration getCacheNameIndexTtl() {
        return cacheNameIndexTtl;
    }

    public void setCacheNameIndexTtl(Duration cacheNameIndexTtl) {
        this.cacheNameIndexTtl = cacheNameIndexTtl;
    }

    public int getCacheNameIndexMaxNames() {
        return cacheNameIndexMaxNames;
    }

    public void setCacheNameIndexMaxNames(int cacheNameIndexMaxNames) {
        this.cacheNameIndexMaxNames = cacheNameIndexMaxNames;
    }

    public int getCacheNameIndexMaxPathsPerName() {
        return cacheNameIndexMaxPathsPerName;
    }

    public void setCacheNameIndexMaxPathsPerName(int cacheNameIndexMaxPathsPerName) {
        this.cacheNameIndexMaxPathsPerName = cacheNameIndexMaxPathsPerName;
    }

    public Duration getCacheDirectoryTtl() {
        return cacheDirectoryTtl;
    }

    public void setCacheDirectoryTtl(Duration cacheDirectoryTtl) {
        this.cacheDirectoryTtl = cacheDirectoryTtl;
    }

    public int getCacheDirectoryMaxEntries() {
        return cacheDirectoryMaxEntries;
    }

    public void setCacheDirectoryMaxEntries(int cacheDirectoryMaxEntries) {
        this.cacheDirectoryMaxEntries = cacheDirectoryMaxEntries;
    }

    public DataSize getHashMaxBytes() {
        return hashMaxBytes;
    }

    public void setHashMaxBytes(DataSize hashMaxBytes) {
        this.hashMaxBytes = hashMaxBytes;
    }

    public DataSize getPendingWriteMaxBytes() {
        return pendingWriteMaxBytes;
    }

    public void setPendingWriteMaxBytes(DataSize pendingWriteMaxBytes) {
        this.pendingWriteMaxBytes = pendingWriteMaxBytes;
    }

    public Duration getPendingWriteTtl() {
        return pendingWriteTtl;
    }

    public void setPendingWriteTtl(Duration pendingWriteTtl) {
        this.pendingWriteTtl = pendingWriteTtl;
    }

    public boolean isIncludeHiddenByDefault() {
        return includeHiddenByDefault;
    }

    public void setIncludeHiddenByDefault(boolean includeHiddenByDefault) {
        this.includeHiddenByDefault = includeHiddenByDefault;
    }

    public boolean isAllowWrite() {
        return allowWrite;
    }

    public void setAllowWrite(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    public boolean isAllowSymlink() {
        return allowSymlink;
    }

    public void setAllowSymlink(boolean allowSymlink) {
        this.allowSymlink = allowSymlink;
    }
}
