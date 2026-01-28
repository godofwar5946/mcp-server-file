package org.example.filesystem.dto.step;

import java.util.List;

/**
 * 装配树结果（尽力而为）。
 *
 * @param roots     根节点列表（可能有多个根）
 * @param truncated 是否因为上限（深度/节点数）导致装配树被截断
 * @param maxDepth  本次使用的最大深度
 * @param maxNodes  本次使用的最大节点数
 */
public record StepAssemblyTree(
        List<StepAssemblyNode> roots,
        boolean truncated,
        Integer maxDepth,
        Integer maxNodes
) {
}
