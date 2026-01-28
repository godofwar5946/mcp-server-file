package org.example.filesystem.dto.step;

import java.util.List;

/**
 * 几何摘要（尽力而为）。
 *
 * @param boundingBox                 基于 CARTESIAN_POINT 计算的粗略包围盒（可为空）
 * @param preciseGeometryDetected     是否检测到“精确几何”实体（B-Rep/NURBS 等，尽力而为）
 * @param tessellatedGeometryDetected 是否检测到“网格/三角化”相关实体（尽力而为）
 * @param typeCounts                  常见几何相关实体类型计数（尽力而为）
 */
public record StepGeometrySummary(
        StepBoundingBox boundingBox,
        boolean preciseGeometryDetected,
        boolean tessellatedGeometryDetected,
        List<StepEntityTypeCount> typeCounts
) {
}
