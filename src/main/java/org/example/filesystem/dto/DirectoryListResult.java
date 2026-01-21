package org.example.filesystem.dto;

import java.util.List;

/**
 * {@code fs_list_directory} 的返回结果。
 *
 * @param rootId    根目录标识
 * @param path      当前目录相对 root 的路径（统一使用 / 分隔）
 * @param offset    分页偏移
 * @param limit     分页大小
 * @param hasMore   是否还有更多数据（用于分页）
 * @param entries   条目列表
 * @param warnings  非致命告警（例如读取某些条目属性失败）
 */
public record DirectoryListResult(
        String rootId,
        String path,
        Integer offset,
        Integer limit,
        boolean hasMore,
        List<FileEntry> entries,
        List<String> warnings
) {
}
