package org.example.filesystem.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code fs_prepare_write_file} 的返回结果（仅准备，不会真的写入）。
 *
 * @param token          用于后续确认写入的 token
 * @param rootId         根目录标识
 * @param path           相对 root 的路径（统一使用 / 分隔）
 * @param exists         目标文件是否存在
 * @param overwrite      是否允许覆盖（true 时确认阶段会覆盖）
 * @param bytes          待写入字节数
 * @param expectedSha256 可选：prepare 阶段计算的“旧文件”sha256（用于确认时做是否变更校验）
 * @param newSha256      待写入内容的 sha256
 * @param expiresAt      token 过期时间
 * @param warnings       风险提示/告警（例如将覆盖已有文件）
 */
public record FileWritePrepareResult(
        String token,
        String rootId,
        String path,
        boolean exists,
        boolean overwrite,
        long bytes,
        String expectedSha256,
        String newSha256,
        Instant expiresAt,
        List<String> warnings
) {
}
