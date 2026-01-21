package org.example.filesystem.dto;

import java.util.List;

/**
 * {@code fs_search} 的返回结果。
 * <p>
 * 注意：为了避免大文件“整文件返回”导致客户端截断，这里只返回命中行的“行号 + 片段”。如需完整内容，可使用：
 * <ul>
 *   <li>{@code fs_read_file_lines}：按行分片读取</li>
 *   <li>{@code fs_read_file_range}：按字节分片读取（base64，适用于任意文件）</li>
 * </ul>
 *
 * @param rootId         根目录标识
 * @param basePath       本次搜索的起始路径（相对 root 的路径，统一使用 / 分隔）
 * @param query          搜索关键字/正则
 * @param regex          是否为正则搜索
 * @param caseSensitive  是否区分大小写
 * @param glob           目录搜索时的 glob 过滤（匹配相对路径；可为空）
 * @param maxMatches     本次实际使用的最大返回匹配数（已应用上限保护）
 * @param maxLineLength  单条匹配片段的最大字符数（已应用上限保护）
 * @param truncated      是否因为达到上限而提前停止（表示可能还有更多匹配未返回）
 * @param scannedFiles   实际扫描的文件数
 * @param returnedMatches 实际返回的匹配条数
 * @param matches        匹配列表
 * @param warnings       非致命告警（例如跳过无法解码的文件）
 */
public record FileSearchResult(
        String rootId,
        String basePath,
        String query,
        boolean regex,
        boolean caseSensitive,
        String glob,
        int maxMatches,
        int maxLineLength,
        boolean truncated,
        int scannedFiles,
        int returnedMatches,
        List<FileSearchMatch> matches,
        List<String> warnings
) {
}

