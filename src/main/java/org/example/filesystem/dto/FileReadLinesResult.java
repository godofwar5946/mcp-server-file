package org.example.filesystem.dto;

import java.util.List;

/**
 * {@code fs_read_file_lines} 的返回结果（按行分片读取）。
 * <p>
 * 使用场景：
 * <ul>
 *   <li>部分 MCP 客户端/LLM 会对超长工具输出进行截断展示，并插入诸如 “... chars truncated ...” 的占位文本。</li>
 *   <li>该占位文本不是原文件内容，如果再把它写回文件会导致源码损坏。</li>
 *   <li>因此提供“按行分片读取”，让单次返回更小、更不容易被截断。</li>
 * </ul>
 *
 * @param rootId    根目录标识
 * @param path      相对 root 的路径（统一使用 / 分隔）
 * @param startLine 本次读取的起始行号（1-based）
 * @param maxLines  本次实际使用的最大行数（已应用上限保护）
 * @param hasMore   是否还有更多行（提示调用方继续分页读取）
 * @param nextLine  如果 hasMore=true，下次建议的 startLine（1-based）
 * @param eol       建议换行符（"\n" 或 "\r\n"），用于拼接时尽量保持一致
 * @param lines     行内容列表（不含行尾换行符）
 * @param warnings  非致命告警
 */
public record FileReadLinesResult(
        String rootId,
        String path,
        int startLine,
        int maxLines,
        boolean hasMore,
        Integer nextLine,
        String eol,
        List<String> lines,
        List<String> warnings
) {
}

