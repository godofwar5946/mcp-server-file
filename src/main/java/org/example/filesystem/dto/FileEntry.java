package org.example.filesystem.dto;

import java.time.Instant;

/**
 * 目录列表项（非递归）。
 *
 * @param name           名称（文件名/目录名）
 * @param path           相对 root 的路径（统一使用 / 分隔）
 * @param directory      是否为目录
 * @param file           是否为普通文件
 * @param symlink        是否为符号链接
 * @param sizeBytes      文件大小（目录为 null）
 * @param lastModifiedAt 最后修改时间
 */
public record FileEntry(
        String name,
        String path,
        boolean directory,
        boolean file,
        boolean symlink,
        Long sizeBytes,
        Instant lastModifiedAt
) {
}
