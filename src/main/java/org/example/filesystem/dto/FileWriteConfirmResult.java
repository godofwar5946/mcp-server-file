package org.example.filesystem.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code fs_confirm_write_file} 的返回结果（确认/取消）。
 *
 * @param token        token
 * @param rootId       根目录标识
 * @param path         相对 root 的路径（统一使用 / 分隔）
 * @param confirmed    是否确认（confirm=true）
 * @param written      是否实际写入成功
 * @param bytesWritten 实际写入字节数
 * @param sha256       写入内容的 sha256（同 prepare 的 newSha256）
 * @param wroteAt      写入时间
 * @param warnings     非致命告警/提示
 */
public record FileWriteConfirmResult(
        String token,
        String rootId,
        String path,
        boolean confirmed,
        boolean written,
        long bytesWritten,
        String sha256,
        Instant wroteAt,
        List<String> warnings
) {
}
