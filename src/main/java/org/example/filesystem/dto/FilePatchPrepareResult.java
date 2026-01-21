package org.example.filesystem.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code fs_prepare_patch_file} 的返回结果（仅准备，不会真的写入）。
 * <p>
 * 该工具用于在服务端直接对“文本文件”执行替换/插入/删除等编辑规则，
 * 生成待写入内容并返回 token；调用方随后必须调用 {@code fs_confirm_write_file(confirm=true)} 才会落盘。
 * <p>
 * 特殊情况：如果补丁未产生任何变化（{@code changed=false}），服务端不会创建 token（{@code token/expiresAt} 为 null），
 * 以避免无意义写入导致文件时间戳变化。
 *
 * @param token         用于后续确认写入的 token
 * @param rootId        根目录标识
 * @param path          相对 root 的路径（统一使用 / 分隔）
 * @param changed       本次 patch 是否导致内容发生变化
 * @param overwrite     是否覆盖写入（修改既有文件时固定为 true）
 * @param bytes         待写入字节数
 * @param expectedSha256 可选：prepare 阶段计算的“旧文件”sha256（用于确认时做是否变更校验）
 * @param newSha256     待写入内容的 sha256
 * @param expiresAt     token 过期时间
 * @param operations    本次应用的操作数
 * @param replacements  正则替换产生的替换次数（跨操作累计）
 * @param insertedLines 插入的行数（跨操作累计）
 * @param deletedLines  删除的行数（跨操作累计）
 * @param summaries     每个操作的简要结果说明（便于快速确认）
 * @param warnings      风险提示/告警
 */
public record FilePatchPrepareResult(
        String token,
        String rootId,
        String path,
        boolean changed,
        boolean overwrite,
        long bytes,
        String expectedSha256,
        String newSha256,
        Instant expiresAt,
        int operations,
        int replacements,
        int insertedLines,
        int deletedLines,
        List<String> summaries,
        List<String> warnings
) {
}
