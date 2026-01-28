package org.example.filesystem.dto.step;

/**
 * STEP 实体类型计数项（尽力而为）。
 *
 * @param type  实体类型名（通常为大写，如 ADVANCED_FACE）
 * @param count 在扫描范围内出现次数
 */
public record StepEntityTypeCount(
        String type,
        int count
) {
}
