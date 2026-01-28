package org.example.filesystem.dto.step;

/**
 * 零件信息（尽力而为）。
 * <p>
 * 通常通过以下链路抽取：
 * {@code PRODUCT_DEFINITION -> PRODUCT_DEFINITION_FORMATION -> PRODUCT}。
 * <p>
 * 不同 CAD/导出器的字段含义可能不同：
 * <ul>
 *   <li>料号/零件编号：经常落在 {@code PRODUCT.id}</li>
 *   <li>零件名称：经常落在 {@code PRODUCT.name}</li>
 *   <li>描述：经常落在 {@code PRODUCT.description}</li>
 * </ul>
 *
 * @param productDefinitionId          PRODUCT_DEFINITION 实体 id
 * @param productDefinitionIdentifier  PRODUCT_DEFINITION.id（常见为空或内部编号）
 * @param productDefinitionDescription PRODUCT_DEFINITION.description
 * @param productId                    PRODUCT 实体 id
 * @param partNumber                   PRODUCT.id（常用作料号/零件编号）
 * @param name                         PRODUCT.name
 * @param description                  PRODUCT.description
 */
public record StepPartInfo(
        Integer productDefinitionId,
        String productDefinitionIdentifier,
        String productDefinitionDescription,
        Integer productId,
        String partNumber,
        String name,
        String description
) {
}
