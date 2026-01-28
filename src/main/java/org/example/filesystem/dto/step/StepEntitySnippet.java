package org.example.filesystem.dto.step;

/**
 * 单条 STEP 实体片段（尽力而为）。
 *
 * @param id   实体实例 id（'#' 后面的数字）
 * @param type 实体类型名（通常为大写）
 * @param text 实体文本（尽量把字符串字面量中的 \X2\...\X0\ 解码为真实中文）
 */
public record StepEntitySnippet(
        int id,
        String type,
        String text
) {
}
