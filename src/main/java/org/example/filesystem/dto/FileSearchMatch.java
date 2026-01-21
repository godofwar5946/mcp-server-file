package org.example.filesystem.dto;

/**
 * {@code fs_search} 的单条匹配结果。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>返回“行号 + 片段”，而不是返回整份文件内容，避免大文件在客户端/LLM 中被截断。</li>
 *   <li>尽量做到和 {@code grep -n} 类似的体验：快速定位、可直接用于后续按行 patch。</li>
 * </ul>
 *
 * @param path              文件路径（相对 root 的路径，统一使用 / 分隔）
 * @param lineNumber        匹配所在行号（1-based）
 * @param column            匹配起始列号（1-based；正则/文本的“第一个匹配”位置）
 * @param excerpt           命中的行片段（已应用最大长度限制；必要时会围绕匹配位置截取）
 * @param excerptTruncated  excerpt 是否发生截断
 * @param originalLineLength 原始行长度（仅在 excerptTruncated=true 时返回；否则为 null）
 */
public record FileSearchMatch(
        String path,
        int lineNumber,
        int column,
        String excerpt,
        boolean excerptTruncated,
        Integer originalLineLength
) {
}

