package org.example.filesystem.dto.step;

import java.util.List;

/**
 * 拓扑摘要（尽力而为）。
 *
 * @param typeCounts 常见拓扑相关实体类型计数（尽力而为）
 */
public record StepTopologySummary(
        List<StepEntityTypeCount> typeCounts
) {
}
