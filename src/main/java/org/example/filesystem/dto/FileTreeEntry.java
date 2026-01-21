package org.example.filesystem.dto;

import java.time.Instant;

/**
 * 目录树条目（递归）。
 *
 * @param depth          深度（0 表示起始目录本身，1 表示其子级，依此类推）
 * @param name           名称（文件名/目录名）
 * @param path           相对 root 的路径（统一使用 / 分隔）
 * @param directory      是否为目录
 * @param file           是否为普通文件
 * @param symlink        是否为符号链接
 * @param sizeBytes      文件大小（目录为 null；includeMetadata=false 时为 null）
 * @param lastModifiedAt 最后修改时间（includeMetadata=false 时为 null）
 */
public record FileTreeEntry(
        int depth,
        String name,
        String path,
        boolean directory,
        boolean file,
        boolean symlink,
        Long sizeBytes,
        Instant lastModifiedAt
) {
}
