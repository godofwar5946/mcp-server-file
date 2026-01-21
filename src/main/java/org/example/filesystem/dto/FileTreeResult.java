package org.example.filesystem.dto;

import java.util.List;

/**
 * {@code fs_list_tree} 的返回结果。
 *
 * @param rootId     根目录标识
 * @param path       起始目录相对 root 的路径（统一使用 / 分隔）
 * @param maxDepth   本次实际使用的最大深度（已应用上限保护）
 * @param maxEntries 本次实际使用的最大条目数（已应用上限保护）
 * @param truncated  是否因超出 maxEntries 被截断
 * @param entries    目录树条目列表（按遍历顺序）
 * @param warnings   非致命告警（例如跳过了符号链接目录、部分目录无法访问等）
 */
public record FileTreeResult(
        String rootId,
        String path,
        Integer maxDepth,
        Integer maxEntries,
        boolean truncated,
        List<FileTreeEntry> entries,
        List<String> warnings
) {
}
