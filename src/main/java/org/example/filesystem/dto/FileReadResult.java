package org.example.filesystem.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code fs_read_file} 的返回结果。
 *
 * @param rootId         根目录标识
 * @param path           相对 root 的路径（统一使用 / 分隔）
 * @param encoding       返回内容的编码：utf-8 或 base64
 * @param binary         是否为二进制（base64）
 * @param truncated      是否被截断（超出 maxBytes）
 * @param totalBytes     文件总字节数（文件大小）
 * @param returnedBytes  实际返回的字节数
 * @param sha256         可选：文件 sha256（includeSha256=true 时返回）
 * @param lastModifiedAt 最后修改时间
 * @param content        内容（utf-8 文本或 base64 字符串）
 * @param warnings       非致命告警
 */
public record FileReadResult(
        String rootId,
        String path,
        String encoding,
        boolean binary,
        boolean truncated,
        long totalBytes,
        long returnedBytes,
        String sha256,
        Instant lastModifiedAt,
        String content,
        List<String> warnings
) {
}
