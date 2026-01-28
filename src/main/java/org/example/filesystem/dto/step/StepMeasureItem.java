package org.example.filesystem.dto.step;

/**
 * 数值度量项（尽力而为），例如 {@code MEASURE_REPRESENTATION_ITEM}。
 *
 * @param id          实体实例 id
 * @param name        条目名称
 * @param measureType 度量类型（例如 LENGTH_MEASURE）
 * @param value       可解析的数值（无法解析则为 null）
 * @param unitRef     单位实体引用（若存在；例如指向 LENGTH_UNIT 等）
 */
public record StepMeasureItem(
        int id,
        String name,
        String measureType,
        Double value,
        Integer unitRef
) {
}
