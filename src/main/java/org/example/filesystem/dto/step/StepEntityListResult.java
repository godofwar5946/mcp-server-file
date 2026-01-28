package org.example.filesystem.dto.step;

import java.util.List;

/**
 * {@code fs_read_step_entities} 的返回结果（DATA 段实体分页列表，尽力而为）。
 *
 * @param rootId          根目录标识
 * @param path            统一后的路径（使用 '/' 分隔）
 * @param truncated       是否因为 maxBytes 只扫描了文件前半段
 * @param decodedWith     对文件字节流使用的解码字符集
 * @param scannedEntities 实际扫描到的实体数量（尽力而为）
 * @param scanTruncated   是否因为 maxEntities 上限提前停止扫描
 * @param offset          匹配偏移（0-based）
 * @param limit           返回上限
 * @param hasMore         是否还有更多匹配项（尽力而为）
 * @param nextOffset      hasMore=true 时建议下一次请求的 offset
 * @param entities        实体片段列表（包含 #id、实体类型、以及尽量解码中文后的文本）
 * @param warnings        非致命告警
 */
public record StepEntityListResult(
        String rootId,
        String path,
        boolean truncated,
        String decodedWith,
        int scannedEntities,
        boolean scanTruncated,
        int offset,
        int limit,
        boolean hasMore,
        Integer nextOffset,
        List<StepEntitySnippet> entities,
        List<String> warnings
) {
}
