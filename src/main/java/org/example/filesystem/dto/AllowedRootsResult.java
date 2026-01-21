package org.example.filesystem.dto;

import java.util.List;

/**
 * {@code fs_list_roots} 的返回结果。
 *
 * @param roots 允许访问的根目录白名单
 */
public record AllowedRootsResult(List<AllowedRoot> roots) {
}
