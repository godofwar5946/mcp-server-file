package org.example.filesystem.dto;

import java.util.List;

/**
 * {@code fs_read_file_range} 的返回结果（按字节范围分片读取）。
 * <p>
 * 说明：
 * <ul>
 *   <li>返回内容固定为 base64，适用于任意文件类型（含二进制）。</li>
 *   <li>调用方可通过 {@code offset/maxBytes} 多次调用，拼接得到完整字节流。</li>
 * </ul>
 *
 * @param rootId        根目录标识
 * @param path          相对 root 的路径（统一使用 / 分隔）
 * @param offset        本次读取的起始偏移（字节，0-based）
 * @param maxBytes      本次实际使用的最大字节数（已应用上限保护）
 * @param totalBytes    文件总字节数
 * @param returnedBytes 实际返回的字节数
 * @param hasMore       是否还有更多数据
 * @param nextOffset    如果 hasMore=true，下次建议的 offset
 * @param encoding      返回内容编码（固定为 base64）
 * @param content       base64 内容
 * @param warnings      非致命告警
 */
public record FileReadRangeResult(
        String rootId,
        String path,
        long offset,
        int maxBytes,
        long totalBytes,
        int returnedBytes,
        boolean hasMore,
        Long nextOffset,
        String encoding,
        String content,
        List<String> warnings
) {
}

