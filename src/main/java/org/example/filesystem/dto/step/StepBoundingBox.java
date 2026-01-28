package org.example.filesystem.dto.step;

/**
 * 基于 {@code CARTESIAN_POINT} 坐标计算的轴对齐包围盒（尽力而为）。
 * <p>
 * 注意：这不是严格意义上的几何包围盒，只是对坐标点的粗略统计，能用于快速判断模型坐标范围与量级。
 *
 * @param minX       X 最小值
 * @param minY       Y 最小值
 * @param minZ       Z 最小值
 * @param maxX       X 最大值
 * @param maxY       Y 最大值
 * @param maxZ       Z 最大值
 * @param pointCount 用于统计的 CARTESIAN_POINT 数量
 */
public record StepBoundingBox(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        long pointCount
) {
}
