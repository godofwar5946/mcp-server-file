package org.example.filesystem.dto.step;

import java.util.List;

/**
 * 装配树节点（尽力而为）。
 *
 * @param productDefinitionId PRODUCT_DEFINITION id
 * @param referenceDesignator 父节点指向当前节点的引用标号（根节点为空）
 * @param part                当前节点对应的零件信息（尽力而为）
 * @param children            子装配/子零件
 */
public record StepAssemblyNode(
        Integer productDefinitionId,
        String referenceDesignator,
        StepPartInfo part,
        List<StepAssemblyNode> children
) {
}
