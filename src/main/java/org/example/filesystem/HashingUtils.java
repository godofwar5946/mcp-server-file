package org.example.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 哈希工具类：用于计算 sha256（十六进制字符串）。
 * <p>
 * 说明：
 * <ul>
 *   <li>用于在“写入确认”流程中做内容指纹，避免 token 确认时目标文件已被外部修改。</li>
 *   <li>对文件的哈希计算采用流式读取，避免一次性把大文件读入内存。</li>
 * </ul>
 */
public final class HashingUtils {

    private static final HexFormat HEX = HexFormat.of();

    private HashingUtils() {
    }

    public static String sha256Hex(byte[] bytes) {
        return HEX.formatHex(sha256(bytes));
    }

    public static String sha256Hex(Path file) throws IOException {
        return HEX.formatHex(sha256(file));
    }

    private static byte[] sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        return digest.digest(bytes);
    }

    private static byte[] sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256 摘要算法（MessageDigest）", e);
        }
    }
}
