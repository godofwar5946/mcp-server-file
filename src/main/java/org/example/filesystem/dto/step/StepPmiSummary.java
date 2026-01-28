package org.example.filesystem.dto.step;

import java.util.List;

/**
 * PMI / 尺寸 / 公差 / 标注 摘要（尽力而为）。
 *
 * @param typeCounts PMI/尺寸/公差相关实体计数（尽力而为）
 * @param measures   抽取到的数值度量（尽力而为）
 * @param snippets   示例实体片段（尽力而为，受上限控制）
 */
public record StepPmiSummary(
        List<StepEntityTypeCount> typeCounts,
        List<StepMeasureItem> measures,
        List<StepEntitySnippet> snippets
) {
}
