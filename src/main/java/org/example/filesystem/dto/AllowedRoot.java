package org.example.filesystem.dto;

/**
 * 允许访问的根目录信息。
 *
 * @param id   根目录标识（root0、root1...）
 * @param path 根目录的绝对路径
 */
public record AllowedRoot(String id, String path) {
}
