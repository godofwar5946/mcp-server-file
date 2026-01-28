package org.example.filesystem.step;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STEP（ISO-10303-21，STEP Physical File）头部/模型信息解析器（尽力而为）。
 * <p>
 * 设计目标：在不引入“完整 STEP 引擎”的前提下，快速、稳定地提取对业务最有价值的“模型元信息”，便于 MCP/LLM
 * 在不拉取整文件内容的情况下理解模型。
 * <p>
 * 当前解析范围（尽力而为）：
 * <ul>
 *   <li>HEADER 段：{@code FILE_DESCRIPTION}/{@code FILE_NAME}/{@code FILE_SCHEMA}</li>
 *   <li>DATA 段：抽取少量 {@code PRODUCT(...)} 名称（作为“模型/零件名”线索）</li>
 * </ul>
 * <p>
 * 中文/非 ASCII 处理：
 * <ul>
 *   <li>STEP 字符串常见转义：{@code \X2\...\X0\}（UCS-2 十六进制）或 {@code \X4\...\X0\}（UCS-4 十六进制）</li>
 *   <li>本解析器会把上述转义解码为真实 Unicode 字符，避免中文以转义形式返回导致“看起来乱码”</li>
 * </ul>
 * <p>
 * 注意：本类不是通用/完整的 STEP 语法解析器；当文件非常规、包含复杂嵌套或缺失分号时，解析结果可能不完整，
 * 但会尽量通过 warnings 提示调用方。
 */
public final class StepModelInfoParser {

    private StepModelInfoParser() {
    }

    // STEP 物理文件结构通常为：
    // ISO-10303-21;
    // HEADER; ... ENDSEC;
    // DATA;   ... ENDSEC;
    // END-ISO-10303-21;
    private static final Pattern HEADER_START = Pattern.compile("(?is)\\bHEADER\\b\\s*;");
    private static final Pattern DATA_START = Pattern.compile("(?is)\\bDATA\\b\\s*;");
    private static final Pattern ENDSEC = Pattern.compile("(?is)\\bENDSEC\\b\\s*;");

    private static final Pattern FILE_DESCRIPTION = Pattern.compile("(?is)\\bFILE_DESCRIPTION\\b\\s*\\(");
    private static final Pattern FILE_NAME = Pattern.compile("(?is)\\bFILE_NAME\\b\\s*\\(");
    private static final Pattern FILE_SCHEMA = Pattern.compile("(?is)\\bFILE_SCHEMA\\b\\s*\\(");
    private static final Pattern PRODUCT = Pattern.compile("(?is)\\bPRODUCT\\b\\s*\\(");

    public record StepModelInfo(
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
            List<String> warnings
    ) {
    }

    public static StepModelInfo parse(String stepText) {
        List<String> warnings = new ArrayList<>();
        if (stepText == null || stepText.isBlank()) {
            warnings.add("STEP 内容为空，无法解析。");
            return new StepModelInfo(null, null, null, null, null, null, null, null, null, null, null, warnings);
        }

        // 1) 先提取 HEADER 段（只在 HEADER 内找 FILE_*）
        // 2) 再从全文中抽取部分 PRODUCT 名称作为补充线索（不是严格意义的“零件BOM”）
        String header = extractHeaderSection(stepText, warnings);

        List<String> fileDescriptions = null;
        String implementationLevel = null;

        String fileName = null;
        String timeStamp = null;
        List<String> authors = null;
        List<String> organizations = null;
        String preprocessorVersion = null;
        String originatingSystem = null;
        String authorization = null;

        List<String> schemas = null;
        List<String> productNames = extractProductNames(stepText, warnings);

        if (header != null) {
            // FILE_DESCRIPTION((description_list), 'implementation_level')
            String fileDescriptionArgs = extractStatementArgs(header, FILE_DESCRIPTION);
            if (fileDescriptionArgs != null) {
                List<String> args = splitTopLevelArgs(fileDescriptionArgs);
                if (args.size() >= 1) {
                    fileDescriptions = extractStringLiterals(args.get(0));
                }
                if (args.size() >= 2) {
                    implementationLevel = firstStringLiteral(args.get(1));
                }
            } else {
                warnings.add("未找到 FILE_DESCRIPTION。");
            }

            // FILE_NAME(name, time_stamp, author, organization, preprocessor_version, originating_system, authorization)
            String fileNameArgs = extractStatementArgs(header, FILE_NAME);
            if (fileNameArgs != null) {
                List<String> args = splitTopLevelArgs(fileNameArgs);
                if (args.size() >= 1) {
                    fileName = firstStringLiteral(args.get(0));
                }
                if (args.size() >= 2) {
                    timeStamp = firstStringLiteral(args.get(1));
                }
                if (args.size() >= 3) {
                    authors = extractStringLiterals(args.get(2));
                }
                if (args.size() >= 4) {
                    organizations = extractStringLiterals(args.get(3));
                }
                if (args.size() >= 5) {
                    preprocessorVersion = firstStringLiteral(args.get(4));
                }
                if (args.size() >= 6) {
                    originatingSystem = firstStringLiteral(args.get(5));
                }
                if (args.size() >= 7) {
                    authorization = firstStringLiteral(args.get(6));
                }
            } else {
                warnings.add("未找到 FILE_NAME。");
            }

            // FILE_SCHEMA((schema_name_list))
            String fileSchemaArgs = extractStatementArgs(header, FILE_SCHEMA);
            if (fileSchemaArgs != null) {
                List<String> args = splitTopLevelArgs(fileSchemaArgs);
                if (!args.isEmpty()) {
                    schemas = extractStringLiterals(args.get(0));
                }
            } else {
                warnings.add("未找到 FILE_SCHEMA。");
            }
        }

        return new StepModelInfo(
                emptyToNull(fileDescriptions),
                blankToNull(implementationLevel),
                blankToNull(fileName),
                blankToNull(timeStamp),
                emptyToNull(authors),
                emptyToNull(organizations),
                blankToNull(preprocessorVersion),
                blankToNull(originatingSystem),
                blankToNull(authorization),
                emptyToNull(schemas),
                emptyToNull(productNames),
                warnings.isEmpty() ? null : warnings
        );
    }

    private static String extractHeaderSection(String stepText, List<String> warnings) {
        Matcher start = HEADER_START.matcher(stepText);
        if (!start.find()) {
            warnings.add("未找到 HEADER 段，文件可能不是有效的 STEP 物理文件。");
            return null;
        }

        // HEADER 从 "HEADER;" 之后开始，到第一个 "ENDSEC;" 结束
        int headerStart = start.end();
        Matcher end = ENDSEC.matcher(stepText);
        end.region(headerStart, stepText.length());
        if (!end.find()) {
            warnings.add("未找到 HEADER 段的 ENDSEC;，解析结果可能不完整。");
            return stepText.substring(headerStart);
        }
        return stepText.substring(headerStart, end.start());
    }

    private static List<String> extractProductNames(String stepText, List<String> warnings) {
        Matcher data = DATA_START.matcher(stepText);
        int start = 0;
        if (data.find()) {
            start = data.end();
        }

        // 为了避免对超大 STEP 文件做“全量解析”，这里只做有限扫描：
        // - 最多扫描若干个 PRODUCT(...) 实体
        // - 最多收集若干个不同的 name
        final int maxUniqueNames = 10;
        final int maxEntitiesToScan = 200;
        Set<String> names = new LinkedHashSet<>();

        Matcher m = PRODUCT.matcher(stepText);
        m.region(start, stepText.length());
        int scanned = 0;
        while (m.find()) {
            scanned++;
            if (scanned > maxEntitiesToScan) {
                warnings.add("PRODUCT 解析已达到扫描上限，已提前停止。");
                break;
            }

            int openParen = m.end() - 1;
            String argsText = extractParenContent(stepText, openParen);
            if (argsText == null) {
                continue;
            }
            List<String> args = splitTopLevelArgs(argsText);
            if (args.isEmpty()) {
                continue;
            }
            String name = firstStringLiteral(args.get(0));
            if (name != null && !name.isBlank()) {
                names.add(name);
                if (names.size() >= maxUniqueNames) {
                    break;
                }
            }
        }
        return names.isEmpty() ? null : new ArrayList<>(names);
    }

    private static String extractStatementArgs(String header, Pattern statementStartPattern) {
        Matcher m = statementStartPattern.matcher(header);
        if (!m.find()) {
            return null;
        }
        int openParen = m.end() - 1;
        return extractParenContent(header, openParen);
    }

    private static String extractParenContent(String text, int openParenIndex) {
        if (text == null || openParenIndex < 0 || openParenIndex >= text.length() || text.charAt(openParenIndex) != '(') {
            return null;
        }

        // 从某个 '(' 开始，找到与之匹配的 ')'，并忽略字符串字面量中的括号/逗号等字符。
        // 这样可以正确处理类似：FILE_NAME('a(b)', (...)) 这种情况。
        int depth = 0;
        boolean inString = false;
        int contentStart = -1;
        for (int i = openParenIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++; // escaped quote
                    } else {
                        inString = false;
                    }
                }
                continue;
            }

            if (c == '\'') {
                inString = true;
                continue;
            }
            if (c == '(') {
                depth++;
                if (depth == 1) {
                    contentStart = i + 1;
                }
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth == 0 && contentStart >= 0) {
                    return text.substring(contentStart, i);
                }
            }
        }
        return null;
    }

    private static List<String> splitTopLevelArgs(String argsText) {
        if (argsText == null) {
            return List.of();
        }
        String text = argsText.trim();
        if (text.isEmpty()) {
            return List.of();
        }

        // 按“最外层逗号”切分参数：
        // - depth 用于处理嵌套括号：(...,(...),...)
        // - inString 用于跳过字符串字面量内部的逗号/括号
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }

            if (c == '\'') {
                inString = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (c == ',' && depth == 0) {
                out.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(text.substring(start).trim());
        return out;
    }

    private static String firstStringLiteral(String text) {
        List<String> all = extractStringLiterals(text);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<String> extractStringLiterals(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\'') {
                continue;
            }
            i++; // skip opening quote
            StringBuilder raw = new StringBuilder();
            while (i < text.length()) {
                char ch = text.charAt(i);
                if (ch == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        raw.append('\'');
                        i += 2;
                        continue;
                    }
                    break;
                }
                raw.append(ch);
                i++;
            }
            out.add(decodeStepEscapes(raw.toString()));
        }
        return out;
    }

    static String decodeStepEscapes(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\\') < 0) {
            return value;
        }

        // STEP 字符串的常见编码/转义形式：
        // 1) \X2\....\X0\  : UCS-2（每 4 位十六进制表示一个 16-bit code unit）
        // 2) \X4\....\X0\  : UCS-4（每 8 位十六进制表示一个 code point）
        // 3) \X\hh         : 单字节十六进制（较少见）
        //
        // 示例：
        //   '\X2\4E2D6587\X0\' -> "中文"
        StringBuilder out = new StringBuilder(value.length());
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 2 >= len) {
                out.append(c);
                continue;
            }
            char x = value.charAt(i + 1);
            if (x != 'X' && x != 'x') {
                out.append(c);
                continue;
            }

            // \X2\...\X0\ 或 \X4\...\X0\
            char mode = value.charAt(i + 2);
            if ((mode == '2' || mode == '4') && i + 3 < len && value.charAt(i + 3) == '\\') {
                int seqStart = i + 4;
                int endMarker = indexOfEndMarker(value, seqStart);
                if (endMarker > 0) {
                    String decoded = decodeHexSequence(value.substring(seqStart, endMarker), mode);
                    if (decoded != null) {
                        out.append(decoded);
                        // i 最终会自增 1，因此这里 +3 让它落在 "\X0\" 之后
                        i = endMarker + 3;
                        continue;
                    }
                }
            }

            // \X\hh（单字节十六进制）
            if (mode == '\\' && i + 4 < len) {
                int b = hexByte(value.charAt(i + 3), value.charAt(i + 4));
                if (b >= 0) {
                    out.append((char) b);
                    i += 4;
                    continue;
                }
            }

            out.append(c);
        }
        return out.toString();
    }

    private static int indexOfEndMarker(String text, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i + 3 < text.length(); i++) {
            if (text.charAt(i) != '\\') {
                continue;
            }
            char x = text.charAt(i + 1);
            if (x != 'X' && x != 'x') {
                continue;
            }
            if (text.charAt(i + 2) != '0') {
                continue;
            }
            if (text.charAt(i + 3) != '\\') {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static String decodeHexSequence(String hexText, char mode) {
        if (hexText == null || hexText.isEmpty()) {
            return "";
        }
        StringBuilder hex = new StringBuilder(hexText.length());
        for (int i = 0; i < hexText.length(); i++) {
            char c = hexText.charAt(i);
            if (isHex(c)) {
                hex.append(c);
            }
        }

        int group = (mode == '4') ? 8 : 4;
        int usable = hex.length() - (hex.length() % group);
        if (usable <= 0) {
            return null;
        }

        StringBuilder out = new StringBuilder(usable / group);
        for (int i = 0; i < usable; i += group) {
            int codePoint;
            try {
                codePoint = Integer.parseInt(hex.substring(i, i + group), 16);
            } catch (Exception e) {
                return null;
            }
            if (mode == '4') {
                if (!Character.isValidCodePoint(codePoint)) {
                    continue;
                }
                out.append(Character.toChars(codePoint));
            } else {
                out.append((char) codePoint);
            }
        }
        return out.toString();
    }

    private static int hexByte(char hi, char lo) {
        int a = hexValue(hi);
        int b = hexValue(lo);
        if (a < 0 || b < 0) {
            return -1;
        }
        return (a << 4) | b;
    }

    private static boolean isHex(char c) {
        return hexValue(c) >= 0;
    }

    private static int hexValue(char c) {
        char lower = Character.toLowerCase(c);
        if (lower >= '0' && lower <= '9') {
            return lower - '0';
        }
        if (lower >= 'a' && lower <= 'f') {
            return 10 + (lower - 'a');
        }
        return -1;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static List<String> emptyToNull(List<String> value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
