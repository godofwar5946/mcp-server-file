package org.example.filesystem.dto;

import java.util.List;

/**
 * {@code fs_read_file_filtered} 的返回结果（读取时可按行号范围/关键字/正则过滤）。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>读取时可做筛选，尽量避免把大文件“整文件返回”导致 MCP 客户端/LLM 截断。</li>
 *   <li>返回结构尽量贴近 CLI 使用体验：行号 + 文本。</li>
 *   <li>支持分页：当达到 {@code maxLines} 上限时，返回 {@code hasMore/nextLine} 供继续拉取。</li>
 * </ul>
 *
 * @param rootId        根目录标识
 * @param path          相对 root 的路径（统一使用 / 分隔）
 * @param startLine     本次扫描的起始行号（1-based）
 * @param endLine       本次扫描的结束行号（1-based，包含；若为空表示直到文件末尾）
 * @param maxLines      本次实际使用的最大返回行数（已应用上限保护）
 * @param filterMode    过滤模式：none/contains/regex
 * @param inverted      是否取反匹配（invertMatch=true）
 * @param contextBefore 命中行的前置上下文行数（仅在非取反且提供过滤条件时生效）
 * @param contextAfter  命中行的后置上下文行数（仅在非取反且提供过滤条件时生效）
 * @param maxLineLength 单行最大返回字符数（用于控制响应体体积）
 * @param hasMore       是否还有更多内容可继续拉取（达到 maxLines 或被 endLine 限制时可能为 true）
 * @param nextLine      如果 hasMore=true，下次建议的 startLine（1-based）
 * @param eol           建议换行符（"\n" 或 "\r\n"）
 * @param scannedLines  本次实际扫描的行数（用于性能观察；包含被跳过的行）
 * @param returnedLines 实际返回的行数
 * @param matchedLines  实际命中的行数（不含上下文行）
 * @param lines         返回行列表
 * @param warnings      非致命告警
 */
public record FileReadFilteredResult(
        String rootId,
        String path,
        int startLine,
        Integer endLine,
        int maxLines,
        String filterMode,
        boolean inverted,
        int contextBefore,
        int contextAfter,
        int maxLineLength,
        boolean hasMore,
        Integer nextLine,
        String eol,
        int scannedLines,
        int returnedLines,
        int matchedLines,
        List<FileReadFilteredLine> lines,
        List<String> warnings
) {
}

