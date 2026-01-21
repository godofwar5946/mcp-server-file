package org.example.filesystem.dto;

import java.util.List;

/**
 * {@code fs_find_by_name} 的返回结果。
 * <p>
 * 行为说明：
 * <ul>
 *   <li>优先从服务端缓存（文件名索引）返回候选路径。</li>
 *   <li>缓存未命中或指定 {@code refresh=true} 时，会回退到本地扫描并更新缓存。</li>
 * </ul>
 *
 * @param name           要查找的文件名/目录名
 * @param type           查找类型：any/file/directory
 * @param caseSensitive  是否区分大小写
 * @param usedCache      本次是否使用了缓存结果
 * @param refreshed      本次是否执行了本地扫描刷新
 * @param scannedFiles   本地扫描时实际扫描的文件数（未扫描则为 0）
 * @param truncated      是否因为达到 maxResults/maxFiles/maxDepth 上限而提前停止（可能还有更多候选未返回）
 * @param matches        候选路径列表
 * @param warnings       非致命告警
 */
public record NameResolveResult(
        String name,
        String type,
        boolean caseSensitive,
        boolean usedCache,
        boolean refreshed,
        int scannedFiles,
        boolean truncated,
        List<NameResolveEntry> matches,
        List<String> warnings
) {
}

