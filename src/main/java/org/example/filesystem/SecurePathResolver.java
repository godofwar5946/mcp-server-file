package org.example.filesystem;

import org.example.filesystem.dto.AllowedRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 安全路径解析器：把用户传入的路径解析成“受控的绝对路径”，并确保它不会逃逸出允许访问的根目录白名单。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>仅允许访问 {@code app.fs.roots} 配置的目录（白名单）。</li>
 *   <li>阻止路径穿越（例如 {@code ../}）导致访问根目录之外的文件。</li>
 *   <li>默认禁止符号链接（symlink）/junction 造成的“路径逃逸”。</li>
 * </ul>
 * <p>
 * 注意：
 * <ul>
 *   <li>Windows 下 junction 不是 symlink，但同样可能造成逃逸；因此这里通过 realPath 校验路径是否仍在 root 内。</li>
 *   <li>对于“写入目标文件”，目标文件可能尚不存在，因此只对已存在的父目录链路做安全校验。</li>
 * </ul>
 */
public class SecurePathResolver {

    private final FileServerProperties properties;
    private final List<Root> roots;

    public SecurePathResolver(FileServerProperties properties) {
        this.properties = properties;
        this.roots = normalizeRoots(properties);
    }

    public List<AllowedRoot> listRoots() {
        List<AllowedRoot> result = new ArrayList<>(roots.size());
        for (Root root : roots) {
            result.add(new AllowedRoot(root.id(), root.rootPath().toString()));
        }
        return result;
    }

    public ResolvedPath resolve(String rootId, String inputPath, boolean requireExists) {
        if (roots.isEmpty()) {
            throw new IllegalStateException("未配置允许访问的根目录（app.fs.roots）");
        }

        Path rawPath = (inputPath == null || inputPath.isBlank()) ? null : Path.of(inputPath);
        Root selectedRoot;
        Path absolute;

        // 1) 如果传入的是绝对路径：需要找到“最匹配”的 root（路径层级最长的 root）。
        // 2) 如果是相对路径：从 rootId 指定的 root 解析；若 rootId 为空，默认选 root0。
        if (rawPath != null && rawPath.isAbsolute()) {
            absolute = rawPath.toAbsolutePath().normalize();
            selectedRoot = (rootId == null || rootId.isBlank()) ? findBestRootForAbsolute(absolute) : findRootById(rootId);
        } else {
            selectedRoot = (rootId == null || rootId.isBlank()) ? roots.getFirst() : findRootById(rootId);
            absolute = (rawPath == null) ? selectedRoot.rootPath() : selectedRoot.rootPath().resolve(rawPath).normalize();
        }

        // 先做一次“字符串层面”的 startsWith 校验，快速挡掉明显的越界路径。
        if (!absolute.startsWith(selectedRoot.rootPath())) {
            throw new IllegalArgumentException("路径不在允许访问的根目录范围内：" + inputPath);
        }

        validateWithinRoot(selectedRoot, absolute, requireExists);

        return new ResolvedPath(selectedRoot.id(), selectedRoot.rootPath(), absolute, displayPath(selectedRoot, absolute));
    }

    public ResolvedPath resolveForWrite(String rootId, String inputPath) {
        return resolve(rootId, inputPath, false);
    }

    private void validateWithinRoot(Root root, Path absolute, boolean requireExists) {
        Path rootReal;
        try {
            rootReal = root.rootPath().toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("根目录不存在或无法解析：" + root.rootPath(), e);
        }

        if (requireExists && !Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("路径不存在：" + absolute);
        }

        // 对 root -> 目标路径 的逐级目录进行 realPath 校验，防止中间某一级是 junction/symlink，导致后续路径逃逸。
        Path current = root.rootPath();
        Path relative = root.rootPath().relativize(absolute);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            if (!properties.isAllowSymlink() && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("不允许访问符号链接路径：" + current);
            }
            try {
                Path realCurrent = current.toRealPath();
                if (!realCurrent.startsWith(rootReal)) {
                    throw new IllegalArgumentException("路径通过链接/junction 逃逸出根目录：" + current);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("路径无法解析：" + current, e);
            }
        }

        // 目标路径存在时再做一次最终 realPath 校验（对文件/目录本体）。
        if (requireExists) {
            try {
                Path realTarget = absolute.toRealPath();
                if (!realTarget.startsWith(rootReal)) {
                    throw new IllegalArgumentException("路径通过链接/junction 逃逸出根目录：" + absolute);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("路径无法解析：" + absolute, e);
            }
        }
    }

    private Root findRootById(String rootId) {
        for (Root root : roots) {
            if (root.id().equals(rootId)) {
                return root;
            }
        }
        throw new IllegalArgumentException("未知的 rootId：" + rootId);
    }

    private Root findBestRootForAbsolute(Path absolute) {
        return roots.stream()
                .filter(r -> absolute.startsWith(r.rootPath()))
                .max(Comparator.comparingInt(r -> r.rootPath().getNameCount()))
                .orElseThrow(() -> new IllegalArgumentException("路径不在允许访问的根目录范围内：" + absolute));
    }

    private static List<Root> normalizeRoots(FileServerProperties properties) {
        List<String> configured = properties.getRoots();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        List<Root> result = new ArrayList<>(configured.size());
        for (int i = 0; i < configured.size(); i++) {
            String value = Objects.requireNonNull(configured.get(i), "配置项 app.fs.roots[" + i + "] 不能为空");
            Path path = Path.of(value).toAbsolutePath().normalize();
            result.add(new Root("root" + i, path));
        }
        return result;
    }

    private static String displayPath(Root root, Path absolute) {
        try {
            return root.rootPath().relativize(absolute).toString();
        } catch (Exception e) {
            return absolute.toString();
        }
    }

    private record Root(String id, Path rootPath) {
    }

    public record ResolvedPath(String rootId, Path rootPath, Path absolutePath, String displayPath) {
    }
}
