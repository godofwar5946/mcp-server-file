package org.example.filesystem.dto.step;

/**
 * 装配关系（尽力而为）。
 * <p>
 * 常见来自：
 * <ul>
 *   <li>{@code NEXT_ASSEMBLY_USAGE_OCCURRENCE}：装配使用关系（最常见）</li>
 *   <li>{@code ASSEMBLY_COMPONENT_USAGE}：装配组件使用关系</li>
 *   <li>{@code PRODUCT_DEFINITION_RELATIONSHIP}：较泛化的关系（有时也能表示装配）</li>
 * </ul>
 *
 * @param relationEntityId        关系实体 id（例如 NAUO 的实例 id）
 * @param relationType            关系实体类型名（大写）
 * @param parentProductDefinition 父级 PRODUCT_DEFINITION id
 * @param childProductDefinition  子级 PRODUCT_DEFINITION id
 * @param referenceDesignator     引用标号（RefDes，例如 "R1"；若存在）
 * @param name                    关系名称（若存在）
 * @param description             关系描述（若存在）
 */
public record StepAssemblyRelation(
        Integer relationEntityId,
        String relationType,
        Integer parentProductDefinition,
        Integer childProductDefinition,
        String referenceDesignator,
        String name,
        String description
) {
}
