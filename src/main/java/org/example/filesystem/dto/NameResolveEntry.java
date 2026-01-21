package org.example.filesystem.dto;

/**
 * {@code fs_find_by_name} 的候选路径条目。
 *
 * @param rootId    根目录标识
 * @param path      相对 root 的路径（统一使用 / 分隔）
 * @param directory 是否目录
 * @param file      是否普通文件
 */
public record NameResolveEntry(
        String rootId,
        String path,
        boolean directory,
        boolean file
) {
}

