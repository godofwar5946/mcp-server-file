package org.example.filesystem.dto;

/**
 * {@code fs_read_file_filtered} 的单行结果。
 * <p>
 * 说明：
 * <ul>
 *   <li>始终返回原文件行号（1-based），便于后续配合 {@code fs_prepare_patch_file} 做按行插入/删除。</li>
 *   <li>为避免超长单行导致响应体膨胀，可对返回的 text 做长度限制（见 {@code maxLineLength}）。</li>
 * </ul>
 *
 * @param lineNumber        行号（1-based）
 * @param match            该行是否命中（如果未提供过滤条件，则恒为 false）
 * @param column           命中起始列号（1-based；仅 match=true 时有值）
 * @param text             行文本（不含行尾换行符；可能被截断）
 * @param textTruncated    text 是否发生截断
 * @param originalLineLength 原始行长度（仅 textTruncated=true 时返回；否则为 null）
 */
public record FileReadFilteredLine(
        int lineNumber,
        boolean match,
        Integer column,
        String text,
        boolean textTruncated,
        Integer originalLineLength
) {
}

