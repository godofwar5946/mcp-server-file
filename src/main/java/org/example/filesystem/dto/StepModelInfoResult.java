package org.example.filesystem.dto;

import org.example.filesystem.dto.step.StepAssemblyRelation;
import org.example.filesystem.dto.step.StepAssemblyTree;
import org.example.filesystem.dto.step.StepEntityTypeCount;
import org.example.filesystem.dto.step.StepGeometrySummary;
import org.example.filesystem.dto.step.StepPartInfo;
import org.example.filesystem.dto.step.StepPmiSummary;
import org.example.filesystem.dto.step.StepTopologySummary;

import java.util.List;

/**
 * {@code fs_read_step_model_info} 的返回结果。
 * <p>
 * 该结果同时包含：
 * <ul>
 *   <li>HEADER 段（FILE_*）信息：用于识别文件来源/模式/AP 版本等</li>
 *   <li>DATA 段摘要信息：用于快速获取装配层级、BOM、几何/拓扑/PMI 的“概览”</li>
 * </ul>
 *
 * @param rootId                根目录标识
 * @param path                  统一后的路径（使用 '/' 分隔）
 * @param truncated             是否因为 maxBytes 只扫描了文件前半段
 * @param decodedWith           对文件字节流使用的解码字符集（例如 {@code utf-8}/{@code gb18030}）
 * @param fileDescriptions      {@code FILE_DESCRIPTION} 的 description 列表
 * @param implementationLevel   {@code FILE_DESCRIPTION} 的实现级别（implementation level）
 * @param fileName              {@code FILE_NAME} 的 name
 * @param timeStamp             {@code FILE_NAME} 的 time_stamp
 * @param authors               {@code FILE_NAME} 的 author 列表
 * @param organizations         {@code FILE_NAME} 的 organization 列表
 * @param preprocessorVersion   {@code FILE_NAME} 的 preprocessor_version
 * @param originatingSystem     {@code FILE_NAME} 的 originating_system
 * @param authorization         {@code FILE_NAME} 的 authorization
 * @param schemas               {@code FILE_SCHEMA} 列表（例如 AP214/AP242）
 * @param productNames          从 DATA 段抽取的 {@code PRODUCT(...)} name（仅作为“名称线索”，非完整 BOM）
 * @param dataEntitiesParsed    DATA 段解析/扫描的实体数量（尽力而为）
 * @param dataEntitiesTruncated DATA 段是否因为 maxEntities 等上限提前停止（尽力而为）
 * @param topEntityTypes        实体类型计数 Top 列表（尽力而为）
 * @param parts                 零件/BOM 列表（尽力而为，来源于 PRODUCT_DEFINITION 链）
 * @param assemblyRelations     装配关系表（尽力而为，用于追溯 parent/child 关系）
 * @param assemblyTree          装配树（尽力而为，可能为空/可能被上限截断）
 * @param geometry              几何摘要（尽力而为，例如包围盒、精确几何/三角网格特征等）
 * @param topology              拓扑摘要（尽力而为，例如 FACE/EDGE/BREP 等计数）
 * @param pmi                   尺寸/公差/标注（PMI）摘要（尽力而为，包含计数、数值 measure、部分示例实体）
 * @param warnings              非致命告警（解析不完整/截断/异常输入等提示）
 */
public record StepModelInfoResult(
        String rootId,
        String path,
        boolean truncated,
        String decodedWith,
        List<String> fileDescriptions,
        String implementationLevel,
        String fileName,
        String timeStamp,
        List<String> authors,
        List<String> organizations,
        String preprocessorVersion,
        String originatingSystem,
        String authorization,
        List<String> schemas,
        List<String> productNames,
        Integer dataEntitiesParsed,
        Boolean dataEntitiesTruncated,
        List<StepEntityTypeCount> topEntityTypes,
        List<StepPartInfo> parts,
        List<StepAssemblyRelation> assemblyRelations,
        StepAssemblyTree assemblyTree,
        StepGeometrySummary geometry,
        StepTopologySummary topology,
        StepPmiSummary pmi,
        List<String> warnings
) {
}
