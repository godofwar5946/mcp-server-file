package org.example.filesystem.step;

import org.example.filesystem.dto.step.StepAssemblyNode;
import org.example.filesystem.dto.step.StepAssemblyRelation;
import org.example.filesystem.dto.step.StepAssemblyTree;
import org.example.filesystem.dto.step.StepBoundingBox;
import org.example.filesystem.dto.step.StepEntitySnippet;
import org.example.filesystem.dto.step.StepEntityTypeCount;
import org.example.filesystem.dto.step.StepGeometrySummary;
import org.example.filesystem.dto.step.StepMeasureItem;
import org.example.filesystem.dto.step.StepPartInfo;
import org.example.filesystem.dto.step.StepPmiSummary;
import org.example.filesystem.dto.step.StepTopologySummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * STEP（ISO-10303-21）DATA 段分析器（尽力而为）。
 * <p>
 * 这不是一个“完整 STEP 内核/几何内核”，也不会尝试把所有拓扑/几何实体完整还原为可计算的 B-Rep/NURBS 数据结构。
 * 本类更偏向“工程化信息提取”，用于 MCP/LLM 场景快速读取以下信息：
 * <ul>
 *   <li><b>装配层级</b>：解析 {@code NEXT_ASSEMBLY_USAGE_OCCURRENCE}/{@code ASSEMBLY_COMPONENT_USAGE} 等关系，构建装配树</li>
 *   <li><b>零件/BOM</b>：通过 {@code PRODUCT_DEFINITION -> PRODUCT_DEFINITION_FORMATION -> PRODUCT} 链抽取零件编号/名称/描述</li>
 *   <li><b>几何摘要</b>：统计常见几何实体类型；从 {@code CARTESIAN_POINT} 粗略计算包围盒（尽力而为）</li>
 *   <li><b>拓扑摘要</b>：统计常见拓扑实体（FACE/EDGE/SHELL/BREP 等）数量（尽力而为）</li>
 *   <li><b>尺寸/PMI 摘要</b>：统计 DIMENSION/TOLERANCE/ANNOTATION 等相关实体；提取部分数值型 measure（尽力而为）</li>
 * </ul>
 * <p>
 * 重要限制与说明：
 * <ul>
 *   <li>STEP 文件可能非常大；本类所有扫描均受 {@link Limits} 限制，避免 CPU/内存占用失控。</li>
 *   <li>“越详细越好”在工程上意味着“需要可分页/可筛选地读取实体”。因此本项目同时提供 {@code fs_read_step_entities} 工具，
 *       允许按实体类型分页拉取 DATA 实体原文，用于进一步精确分析。</li>
 *   <li>不同 STEP AP（AP203/AP214/AP242 等）和不同导出器生成的数据结构差异很大；本类采用“尽力而为+摘要”的策略。</li>
 * </ul>
 */
public final class StepDataAnalyzer {

    private StepDataAnalyzer() {
    }

    /**
     * 解析/扫描时的上限控制（防止单次工具调用解析超大 STEP 文件导致响应过大或耗时过长）。
     * <p>
     * 说明：
     * <ul>
     *   <li>{@code maxEntities}：扫描 DATA 段最多解析多少条 {@code #id=TYPE(...);} 实体</li>
     *   <li>{@code maxTopEntityTypes}：返回的“实体类型计数”最多保留多少个类型（按数量降序）</li>
     *   <li>{@code maxParts}：最多返回多少条零件信息（PRODUCT_DEFINITION 链）</li>
     *   <li>{@code maxAssemblyDepth/maxAssemblyNodes}：装配树展开深度与节点数上限（避免循环/爆炸）</li>
     *   <li>{@code maxPmiSnippets/maxMeasures}：PMI 摘要中最多返回多少条示例实体/measure 数值</li>
     * </ul>
     */
    public record Limits(
            long maxEntities,
            int maxTopEntityTypes,
            int maxParts,
            int maxAssemblyDepth,
            int maxAssemblyNodes,
            int maxPmiSnippets,
            int maxMeasures
    ) {
        public static Limits defaults() {
            // 默认值偏保守：足以覆盖多数中小型 STEP；大模型可通过工具参数放宽 maxEntities/maxBytes。
            return new Limits(
                    500_000L,
                    200,
                    20_000,
                    30,
                    10_000,
                    200,
                    2_000
            );
        }
    }

    /**
     * DATA 段整体分析结果（摘要/结构化信息）。
     * <p>
     * 该结果适合“快速理解模型”；若需要某一类信息的更完整细节（例如完整的几何曲面/拓扑结构/PMI 标注），
     * 推荐结合 {@code fs_read_step_entities} 按类型分页读取实体原文，再进行针对性解析。
     */
    public record Analysis(
            int entitiesParsed,
            boolean entitiesTruncated,
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

    /**
     * DATA 段实体分页列表（用于“详细读取”）。
     * <p>
     * 典型用法：通过 typeContains 过滤关键字（如 DIMENSION/B_SPLINE/ADVANCED_FACE），结合 offset/limit 分页获取实体片段。
     */
    public record EntityList(
            int scannedEntities,
            boolean entitiesTruncated,
            int offset,
            int limit,
            boolean hasMore,
            Integer nextOffset,
            List<StepEntitySnippet> entities,
            List<String> warnings
    ) {
    }

    public static Analysis analyze(String stepText, Limits limits) {
        Limits resolvedLimits = (limits == null) ? Limits.defaults() : limits;
        List<String> warnings = new ArrayList<>();
        if (stepText == null || stepText.isBlank()) {
            warnings.add("STEP 内容为空，无法解析 DATA 段。");
            return new Analysis(0, false, null, null, null, null, null, null, null, warnings);
        }

        // STEP 物理文件的 DATA 段通常为：
        //   DATA;
        //     #1=...;
        //     #2=...;
        //   ENDSEC;
        //
        // 这里使用简单的字符串扫描定位 "DATA;"，随后按 '#'+分号 的结构逐条解析实体。
        int dataStart = indexOfIgnoreCase(stepText, "DATA;");
        if (dataStart < 0) {
            warnings.add("未找到 DATA 段，无法解析装配/几何/尺寸等信息。");
            return new Analysis(0, false, null, null, null, null, null, null, null, warnings);
        }

        // typeCounts：用于构建“实体类型分布”摘要（比如 ADVANCED_FACE 有多少个）
        Map<String, Integer> typeCounts = new HashMap<>(4096);
        // products / formations / productDefinitions：用于抽取零件信息（编号/名称/描述）
        Map<Integer, Product> products = new HashMap<>();
        Map<Integer, ProductDefinitionFormation> formations = new HashMap<>();
        Map<Integer, ProductDefinition> productDefinitions = new HashMap<>();
        // assemblyRelations：用于构建装配层级（父子 PRODUCT_DEFINITION 关系）
        List<StepAssemblyRelation> assemblyRelations = new ArrayList<>();
        // measures/pmiSnippets：用于尺寸/PMI 的数值/示例抽取（不做完整几何标注还原）
        List<StepMeasureItem> measures = new ArrayList<>();
        List<StepEntitySnippet> pmiSnippets = new ArrayList<>();
        // bbox：仅基于 CARTESIAN_POINT 粗略统计包围盒（并非严格几何包围盒）
        BoundingBoxAccumulator bbox = new BoundingBoxAccumulator();

        int cursor = dataStart + "DATA;".length();
        int parsed = 0;
        boolean entitiesTruncated = false;

        while (cursor < stepText.length()) {
            // 以 DATA 段的 ENDSEC 作为终止条件（尽力而为：允许文件不规范但尽量解析）
            int endsec = indexOfIgnoreCase(stepText, "ENDSEC", cursor);
            if (endsec >= 0 && endsec == cursorSkipWs(stepText, cursor)) {
                break;
            }

            int entityStart = stepText.indexOf('#', cursor);
            if (entityStart < 0) {
                break;
            }
            cursor = entityStart;

            // 解析形如：#123=ENTITY_NAME(arg1,arg2,...);
            // - 支持括号嵌套、字符串字面量（含 '' 转义），并忽略字符串中的括号/逗号/分号。
            EntityParseResult entity = parseEntity(stepText, cursor);
            if (entity == null) {
                cursor = cursor + 1;
                continue;
            }

            cursor = entity.nextIndex;
            parsed++;
            // 实体总数上限保护：避免超大 STEP 文件导致解析耗时不可控
            if (parsed > resolvedLimits.maxEntities) {
                entitiesTruncated = true;
                warnings.add("DATA 段实体数量超过上限（maxEntities=" + resolvedLimits.maxEntities + "），已提前停止解析。");
                break;
            }

            // 统计实体类型数量，后续用于输出摘要（topEntityTypes/geometry/topology/pmi 中都会用到）
            typeCounts.merge(entity.typeUpper, 1, Integer::sum);

            // 基于 CARTESIAN_POINT 计算包围盒：仅用于快速判断模型量级/坐标范围，不保证严谨。
            if ("CARTESIAN_POINT".equals(entity.typeUpper)) {
                tryAddCartesianPointToBbox(entity.argsText, bbox);
            }

            // 针对若干关键实体做“结构化提取”（BOM/装配/尺寸数值等）
            switch (entity.typeUpper) {
                case "PRODUCT" -> {
                    Product p = parseProduct(entity.id, entity.argsText);
                    if (p != null) {
                        products.put(entity.id, p);
                    }
                }
                case "PRODUCT_DEFINITION_FORMATION",
                     "PRODUCT_DEFINITION_FORMATION_WITH_SPECIFIED_SOURCE" -> {
                    ProductDefinitionFormation f = parseProductDefinitionFormation(entity.id, entity.argsText);
                    if (f != null) {
                        formations.put(entity.id, f);
                    }
                }
                case "PRODUCT_DEFINITION" -> {
                    ProductDefinition pd = parseProductDefinition(entity.id, entity.argsText);
                    if (pd != null) {
                        productDefinitions.put(entity.id, pd);
                    }
                }
                case "NEXT_ASSEMBLY_USAGE_OCCURRENCE",
                     "ASSEMBLY_COMPONENT_USAGE",
                     "PRODUCT_DEFINITION_RELATIONSHIP" -> {
                    StepAssemblyRelation rel = parseAssemblyRelation(entity.id, entity.typeUpper, entity.argsText);
                    if (rel != null) {
                        assemblyRelations.add(rel);
                    }
                }
                case "MEASURE_REPRESENTATION_ITEM" -> {
                    StepMeasureItem m = parseMeasureRepresentationItem(entity.id, entity.argsText);
                    if (m != null && measures.size() < resolvedLimits.maxMeasures) {
                        measures.add(m);
                    }
                }
                default -> {
                }
            }

            // PMI/DIMENSION 等相关实体可能很多；这里只保留有限条示例，避免输出体积过大。
            if (pmiSnippets.size() < resolvedLimits.maxPmiSnippets && isPmiType(entity.typeUpper)) {
                pmiSnippets.add(new StepEntitySnippet(entity.id, entity.typeUpper, normalizeEntityText(entity.rawText)));
            }
        }

        List<StepPartInfo> parts = buildParts(productDefinitions, formations, products, resolvedLimits, warnings);
        StepAssemblyTree tree = buildAssemblyTree(parts, assemblyRelations, resolvedLimits, warnings);
        StepBoundingBox boundingBox = bbox.toBoundingBox();

        List<StepEntityTypeCount> topTypes = topCounts(typeCounts, resolvedLimits.maxTopEntityTypes);
        StepGeometrySummary geometry = buildGeometrySummary(typeCounts, boundingBox);
        StepTopologySummary topology = buildTopologySummary(typeCounts);
        StepPmiSummary pmi = buildPmiSummary(typeCounts, measures, pmiSnippets);

        return new Analysis(
                parsed,
                entitiesTruncated,
                topTypes.isEmpty() ? null : topTypes,
                parts.isEmpty() ? null : parts,
                assemblyRelations.isEmpty() ? null : assemblyRelations,
                tree,
                geometry,
                topology,
                pmi,
                warnings.isEmpty() ? null : warnings
        );
    }

    public static EntityList listEntities(String stepText, Limits limits, String typeContains, Integer offset, Integer limit) {
        Limits resolvedLimits = (limits == null) ? Limits.defaults() : limits;
        List<String> warnings = new ArrayList<>();
        if (stepText == null || stepText.isBlank()) {
            warnings.add("STEP 内容为空，无法解析 DATA 段。");
            return new EntityList(0, false, 0, 0, false, null, null, warnings);
        }

        // 与 analyze() 不同，listEntities() 只做“实体级分页输出”，不做复杂关联构建。
        // 目的：允许调用方按需读取某类实体的“原文片段”，以便做更精确的几何/拓扑/PMI 解析。
        int dataStart = indexOfIgnoreCase(stepText, "DATA;");
        if (dataStart < 0) {
            warnings.add("未找到 DATA 段。");
            return new EntityList(0, false, 0, 0, false, null, null, warnings);
        }

        int resolvedOffset = (offset == null) ? 0 : Math.max(0, offset);
        int resolvedLimit = (limit == null) ? 50 : Math.max(1, Math.min(500, limit));
        // typeContains 为“包含匹配”而不是等值匹配，便于像 DIMENSION/GEOMETRIC_TOLERANCE 这种族群过滤。
        String filter = (typeContains == null || typeContains.isBlank()) ? null : typeContains.trim().toUpperCase(Locale.ROOT);

        int cursor = dataStart + "DATA;".length();
        int scanned = 0;
        int matched = 0;
        boolean entitiesTruncated = false;
        boolean hasMore = false;
        Integer nextOffset = null;
        List<StepEntitySnippet> out = new ArrayList<>(Math.min(resolvedLimit, 200));

        while (cursor < stepText.length()) {
            // 以 DATA 段 ENDSEC 作为终止条件（尽力而为）
            int endsec = indexOfIgnoreCase(stepText, "ENDSEC", cursor);
            if (endsec >= 0 && endsec == cursorSkipWs(stepText, cursor)) {
                break;
            }

            int entityStart = stepText.indexOf('#', cursor);
            if (entityStart < 0) {
                break;
            }
            cursor = entityStart;

            // 解析一条实体：#id=TYPE(...);
            EntityParseResult entity = parseEntity(stepText, cursor);
            if (entity == null) {
                cursor = cursor + 1;
                continue;
            }
            cursor = entity.nextIndex;
            scanned++;
            if (scanned > resolvedLimits.maxEntities) {
                entitiesTruncated = true;
                warnings.add("实体扫描超过上限（maxEntities=" + resolvedLimits.maxEntities + "），已提前停止。");
                break;
            }

            if (filter == null || entity.typeUpper.contains(filter)) {
                if (matched >= resolvedOffset && out.size() < resolvedLimit) {
                    out.add(new StepEntitySnippet(entity.id, entity.typeUpper, normalizeEntityText(entity.rawText)));
                }
                matched++;
                if (out.size() >= resolvedLimit) {
                    hasMore = true;
                    nextOffset = resolvedOffset + out.size();
                    break;
                }
            }
        }

        return new EntityList(
                scanned,
                entitiesTruncated,
                resolvedOffset,
                resolvedLimit,
                hasMore,
                nextOffset,
                out.isEmpty() ? null : out,
                warnings.isEmpty() ? null : warnings
        );
    }

    private static int cursorSkipWs(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return i;
            }
            i++;
        }
        return i;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return indexOfIgnoreCase(text, needle, 0);
    }

    private static int indexOfIgnoreCase(String text, String needle, int fromIndex) {
        if (text == null || needle == null) {
            return -1;
        }
        String n = needle.toLowerCase(Locale.ROOT);
        int limit = text.length() - n.length();
        for (int i = Math.max(0, fromIndex); i <= limit; i++) {
            if (text.regionMatches(true, i, n, 0, n.length())) {
                return i;
            }
        }
        return -1;
    }

    private record EntityParseResult(int id, String typeUpper, String argsText, String rawText, int nextIndex) {
    }

    private static EntityParseResult parseEntity(String text, int startIndex) {
        // 解析一条 DATA 实体语句（尽力而为）：
        //   #123=ENTITY_NAME(arg1,arg2,...);
        //
        // 关键点：
        // - 支持括号嵌套：ENTITY_NAME((...),TYPE(...),...)
        // - 支持字符串字面量：'text'，其中 '' 表示转义单引号
        // - findMatchingParen/findStatementEnd 会忽略字符串内部的括号/分号，避免误判
        int i = startIndex;
        if (i >= text.length() || text.charAt(i) != '#') {
            return null;
        }
        i++;
        int id = 0;
        int digits = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!Character.isDigit(c)) {
                break;
            }
            id = id * 10 + (c - '0');
            digits++;
            i++;
        }
        if (digits == 0) {
            return null;
        }
        i = cursorSkipWs(text, i);
        if (i >= text.length() || text.charAt(i) != '=') {
            return null;
        }
        i++;
        i = cursorSkipWs(text, i);
        int typeStart = i;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                i++;
                continue;
            }
            break;
        }
        if (i == typeStart) {
            return null;
        }
        String typeUpper = text.substring(typeStart, i).toUpperCase(Locale.ROOT);
        i = cursorSkipWs(text, i);
        if (i >= text.length() || text.charAt(i) != '(') {
            return null;
        }
        int openParen = i;
        int closeParen = findMatchingParen(text, openParen);
        if (closeParen < 0) {
            return null;
        }
        String argsText = text.substring(openParen + 1, closeParen);
        int semicolon = findStatementEnd(text, closeParen + 1);
        if (semicolon < 0) {
            semicolon = closeParen + 1;
        }
        String raw = text.substring(startIndex, Math.min(text.length(), semicolon + 1));
        int next = Math.min(text.length(), semicolon + 1);
        return new EntityParseResult(id, typeUpper, argsText, raw, next);
    }

    private static int findMatchingParen(String text, int openParenIndex) {
        // 从某个 '(' 开始，找到与之匹配的 ')'。
        // 注意：STEP 的参数列表中允许出现字符串，字符串内部可能包含括号/逗号/分号，因此需要 inString 状态跳过。
        int depth = 0;
        boolean inString = false;
        for (int i = openParenIndex; i < text.length(); i++) {
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
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findStatementEnd(String text, int fromIndex) {
        // 从指定位置开始，找到该实体语句的结束分号 ';'。
        // 同样要忽略字符串字面量内部内容，以及括号嵌套（避免参数列表内的分号误判）。
        boolean inString = false;
        int depth = 0;
        for (int i = Math.max(0, fromIndex); i < text.length(); i++) {
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
            if (c == ';' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private interface StepValue {
    }

    private record StepNull() implements StepValue {
    }

    private record StepString(String value) implements StepValue {
    }

    private record StepRef(int id) implements StepValue {
    }

    private record StepEnum(String value) implements StepValue {
    }

    private record StepNumber(String raw, Double value) implements StepValue {
    }

    private record StepList(List<StepValue> items) implements StepValue {
    }

    private record StepTyped(String typeUpper, List<StepValue> args) implements StepValue {
    }

    private static List<StepValue> parseArgs(String argsText) {
        // STEP 参数表达式的最小解析器（尽力而为）：
        // - 字符串：'text'（支持 '' 转义；并解码 \X2\...\X0\ 中文转义）
        // - 引用：#123
        // - 列表：(a,b,c) 以及嵌套列表
        // - 枚举：.NAME.
        // - 数字：1 / -1.2 / 1.0E-3 / 1.0D+2
        // - 复杂类型：TYPE_NAME(...)
        // - 空值占位：$ 或 *
        //
        // 目标并不是覆盖 STEP 全语法，而是为“抽取零件/装配/尺寸摘要”提供稳定的结构化读数入口。
        if (argsText == null) {
            return List.of();
        }
        Parser p = new Parser(argsText);
        return p.parseArgs();
    }

    private static final class Parser {
        // 纯字符串解析器，不依赖正则，尽量降低对异常输入的敏感度。
        final String text;
        int i = 0;

        Parser(String text) {
            this.text = text;
        }

        List<StepValue> parseArgs() {
            List<StepValue> out = new ArrayList<>();
            skipWs();
            if (eof()) {
                return out;
            }
            while (!eof()) {
                StepValue v = parseValue();
                out.add(v);
                skipWs();
                if (peek() == ',') {
                    i++;
                    skipWs();
                    continue;
                }
                break;
            }
            return out;
        }

        StepValue parseValue() {
            skipWs();
            if (eof()) {
                return new StepNull();
            }
            char c = peek();
            if (c == '$' || c == '*') {
                i++;
                return new StepNull();
            }
            if (c == '#') {
                i++;
                Integer id = parseInt();
                return (id == null) ? new StepNull() : new StepRef(id);
            }
            if (c == '\'') {
                return new StepString(parseString());
            }
            if (c == '(') {
                i++;
                List<StepValue> items = new ArrayList<>();
                skipWs();
                if (peek() == ')') {
                    i++;
                    return new StepList(items);
                }
                while (!eof()) {
                    StepValue v = parseValue();
                    items.add(v);
                    skipWs();
                    if (peek() == ',') {
                        i++;
                        skipWs();
                        continue;
                    }
                    if (peek() == ')') {
                        i++;
                        break;
                    }
                    break;
                }
                return new StepList(items);
            }
            if (c == '.') {
                return new StepEnum(parseEnum());
            }
            if (isIdentStart(c)) {
                String ident = parseIdent().toUpperCase(Locale.ROOT);
                skipWs();
                if (peek() == '(') {
                    i++;
                    List<StepValue> inner = new ArrayList<>();
                    skipWs();
                    if (peek() == ')') {
                        i++;
                        return new StepTyped(ident, inner);
                    }
                    while (!eof()) {
                        StepValue v = parseValue();
                        inner.add(v);
                        skipWs();
                        if (peek() == ',') {
                            i++;
                            skipWs();
                            continue;
                        }
                        if (peek() == ')') {
                            i++;
                            break;
                        }
                        break;
                    }
                    return new StepTyped(ident, inner);
                }
                return new StepEnum(ident);
            }
            if (c == '+' || c == '-' || Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            i++;
            return new StepNull();
        }

        StepNumber parseNumber() {
            int start = i;
            while (!eof()) {
                char c = peek();
                if (Character.isDigit(c) || c == '+' || c == '-' || c == '.' || c == 'E' || c == 'e' || c == 'D' || c == 'd') {
                    i++;
                    continue;
                }
                break;
            }
            String raw = text.substring(start, i);
            Double v = null;
            try {
                String normalized = raw.replace('D', 'E').replace('d', 'E');
                v = Double.parseDouble(normalized);
            } catch (Exception ignored) {
            }
            return new StepNumber(raw, v);
        }

        String parseEnum() {
            if (peek() != '.') {
                return "";
            }
            int start = i;
            i++;
            while (!eof() && peek() != '.') {
                i++;
            }
            if (!eof() && peek() == '.') {
                i++;
            }
            return text.substring(start, i);
        }

        String parseIdent() {
            int start = i;
            i++;
            while (!eof()) {
                char c = peek();
                if (Character.isLetterOrDigit(c) || c == '_') {
                    i++;
                    continue;
                }
                break;
            }
            return text.substring(start, i);
        }

        String parseString() {
            if (peek() != '\'') {
                return "";
            }
            i++;
            StringBuilder raw = new StringBuilder();
            while (!eof()) {
                char c = peek();
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        raw.append('\'');
                        i += 2;
                        continue;
                    }
                    break;
                }
                raw.append(c);
                i++;
            }
            if (!eof() && peek() == '\'') {
                i++;
            }
            return StepModelInfoParser.decodeStepEscapes(raw.toString());
        }

        Integer parseInt() {
            int start = i;
            int v = 0;
            int digits = 0;
            while (!eof() && Character.isDigit(peek())) {
                v = v * 10 + (peek() - '0');
                digits++;
                i++;
            }
            if (digits == 0) {
                i = start;
                return null;
            }
            return v;
        }

        void skipWs() {
            while (!eof() && Character.isWhitespace(peek())) {
                i++;
            }
        }

        boolean eof() {
            return i >= text.length();
        }

        char peek() {
            return eof() ? '\0' : text.charAt(i);
        }

        static boolean isIdentStart(char c) {
            return Character.isLetter(c) || c == '_';
        }
    }

    private static String asString(List<StepValue> args, int idx) {
        if (args == null || idx < 0 || idx >= args.size()) {
            return null;
        }
        StepValue v = args.get(idx);
        if (v instanceof StepString s) {
            return s.value;
        }
        if (v instanceof StepEnum e) {
            return e.value;
        }
        return null;
    }

    private static Integer asRef(List<StepValue> args, int idx) {
        if (args == null || idx < 0 || idx >= args.size()) {
            return null;
        }
        StepValue v = args.get(idx);
        if (v instanceof StepRef r) {
            return r.id;
        }
        return null;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String normalizeEntityText(String rawEntityText) {
        if (rawEntityText == null || rawEntityText.isEmpty() || rawEntityText.indexOf('\'') < 0) {
            return rawEntityText;
        }
        StringBuilder out = new StringBuilder(rawEntityText.length());
        for (int i = 0; i < rawEntityText.length(); i++) {
            char c = rawEntityText.charAt(i);
            if (c != '\'') {
                out.append(c);
                continue;
            }
            i++;
            StringBuilder raw = new StringBuilder();
            while (i < rawEntityText.length()) {
                char ch = rawEntityText.charAt(i);
                if (ch == '\'') {
                    if (i + 1 < rawEntityText.length() && rawEntityText.charAt(i + 1) == '\'') {
                        raw.append('\'');
                        i += 2;
                        continue;
                    }
                    break;
                }
                raw.append(ch);
                i++;
            }
            String decoded = StepModelInfoParser.decodeStepEscapes(raw.toString());
            out.append('\'').append(escapeStepString(decoded)).append('\'');
        }
        return out.toString();
    }

    private static String escapeStepString(String text) {
        if (text == null || text.isEmpty() || text.indexOf('\'') < 0) {
            return text;
        }
        return text.replace("'", "''");
    }

    private static boolean isPmiType(String typeUpper) {
        if (typeUpper == null || typeUpper.isEmpty()) {
            return false;
        }
        if (typeUpper.contains("DIMENSION")) {
            return true;
        }
        if (typeUpper.contains("TOLERANCE")) {
            return true;
        }
        if (typeUpper.contains("GEOMETRIC_TOLERANCE")) {
            return true;
        }
        if (typeUpper.contains("DATUM")) {
            return true;
        }
        if (typeUpper.contains("ANNOTATION") || typeUpper.contains("DRAUGHTING") || typeUpper.contains("CALLOUT")) {
            return true;
        }
        return typeUpper.contains("TEXT_LITERAL");
    }

    private static boolean isGeometryType(String typeUpper) {
        if (typeUpper == null) {
            return false;
        }
        return switch (typeUpper) {
            case "CARTESIAN_POINT",
                 "DIRECTION",
                 "VECTOR",
                 "AXIS2_PLACEMENT_3D",
                 "AXIS2_PLACEMENT_2D",
                 "LINE",
                 "CIRCLE",
                 "ELLIPSE",
                 "PLANE",
                 "CYLINDRICAL_SURFACE",
                 "CONICAL_SURFACE",
                 "SPHERICAL_SURFACE",
                 "TOROIDAL_SURFACE",
                 "B_SPLINE_CURVE_WITH_KNOTS",
                 "B_SPLINE_SURFACE_WITH_KNOTS",
                 "RATIONAL_B_SPLINE_CURVE",
                 "RATIONAL_B_SPLINE_SURFACE",
                 "TRIMMED_CURVE",
                 "SURFACE_OF_REVOLUTION",
                 "SURFACE_OF_LINEAR_EXTRUSION" -> true;
            default -> typeUpper.contains("B_SPLINE") || typeUpper.contains("NURBS");
        };
    }

    private static boolean isTopologyType(String typeUpper) {
        if (typeUpper == null) {
            return false;
        }
        return switch (typeUpper) {
            case "VERTEX_POINT",
                 "EDGE_CURVE",
                 "ORIENTED_EDGE",
                 "EDGE_LOOP",
                 "FACE_OUTER_BOUND",
                 "ADVANCED_FACE",
                 "CLOSED_SHELL",
                 "OPEN_SHELL",
                 "MANIFOLD_SOLID_BREP",
                 "BREP_WITH_VOIDS",
                 "SHELL_BASED_SURFACE_MODEL" -> true;
            default -> typeUpper.endsWith("_BREP") || typeUpper.contains("SHELL") || typeUpper.contains("FACE") || typeUpper.contains("EDGE");
        };
    }

    private static boolean isPreciseGeometryMarker(String typeUpper) {
        return "ADVANCED_BREP_SHAPE_REPRESENTATION".equals(typeUpper)
                || "MANIFOLD_SOLID_BREP".equals(typeUpper)
                || typeUpper.contains("B_SPLINE")
                || typeUpper.contains("SURFACE")
                || typeUpper.contains("BREP");
    }

    private static boolean isTessellatedGeometryMarker(String typeUpper) {
        return typeUpper.contains("TESSELLATED")
                || typeUpper.contains("TRIANGULATED")
                || typeUpper.contains("POLYLINE")
                || typeUpper.contains("FACETED");
    }

    private static Product parseProduct(int entityId, String argsText) {
        List<StepValue> args = parseArgs(argsText);
        String idText = asString(args, 0);
        String name = asString(args, 1);
        String desc = asString(args, 2);
        return new Product(entityId, idText, name, desc);
    }

    private static ProductDefinitionFormation parseProductDefinitionFormation(int entityId, String argsText) {
        List<StepValue> args = parseArgs(argsText);
        Integer productRef = asRef(args, 2);
        return new ProductDefinitionFormation(entityId, productRef);
    }

    private static ProductDefinition parseProductDefinition(int entityId, String argsText) {
        List<StepValue> args = parseArgs(argsText);
        String idText = asString(args, 0);
        String desc = asString(args, 1);
        Integer formationRef = asRef(args, 2);
        return new ProductDefinition(entityId, idText, desc, formationRef);
    }

    private static StepAssemblyRelation parseAssemblyRelation(int entityId, String relationTypeUpper, String argsText) {
        List<StepValue> args = parseArgs(argsText);

        if ("NEXT_ASSEMBLY_USAGE_OCCURRENCE".equals(relationTypeUpper) || "ASSEMBLY_COMPONENT_USAGE".equals(relationTypeUpper)) {
            String name = asString(args, 1);
            String desc = asString(args, 2);
            Integer parent = asRef(args, 3);
            Integer child = asRef(args, 4);
            String refDes = asString(args, 5);
            if (parent == null || child == null) {
                return null;
            }
            return new StepAssemblyRelation(entityId, relationTypeUpper, parent, child, blankToNull(refDes), blankToNull(name), blankToNull(desc));
        }

        if ("PRODUCT_DEFINITION_RELATIONSHIP".equals(relationTypeUpper)) {
            String name = asString(args, 1);
            String desc = asString(args, 2);
            Integer parent = asRef(args, 3);
            Integer child = asRef(args, 4);
            if (parent == null || child == null) {
                return null;
            }
            return new StepAssemblyRelation(entityId, relationTypeUpper, parent, child, null, blankToNull(name), blankToNull(desc));
        }

        return null;
    }

    private static StepMeasureItem parseMeasureRepresentationItem(int entityId, String argsText) {
        // MEASURE_REPRESENTATION_ITEM('name', LENGTH_MEASURE(10.0), #unit)
        List<StepValue> args = parseArgs(argsText);
        String name = asString(args, 0);
        StepValue v = args.size() > 1 ? args.get(1) : null;
        Integer unitRef = asRef(args, 2);
        if (v instanceof StepTyped typed) {
            Double number = null;
            if (!typed.args.isEmpty()) {
                StepValue first = typed.args.getFirst();
                if (first instanceof StepNumber n) {
                    number = n.value;
                }
            }
            return new StepMeasureItem(entityId, blankToNull(name), typed.typeUpper, number, unitRef);
        }
        return null;
    }

    private static void tryAddCartesianPointToBbox(String argsText, BoundingBoxAccumulator bbox) {
        // CARTESIAN_POINT('',(x,y,z))
        List<StepValue> args = parseArgs(argsText);
        if (args.size() < 2) {
            return;
        }
        StepValue coordsValue = args.get(1);
        if (!(coordsValue instanceof StepList list)) {
            return;
        }
        double[] xyz = new double[]{0d, 0d, 0d};
        int dim = Math.min(3, list.items.size());
        for (int idx = 0; idx < dim; idx++) {
            StepValue v = list.items.get(idx);
            if (v instanceof StepNumber n && n.value != null) {
                xyz[idx] = n.value;
            } else {
                return;
            }
        }
        bbox.add(xyz[0], xyz[1], xyz[2]);
    }

    private static List<StepEntityTypeCount> topCounts(Map<String, Integer> counts, int max) {
        if (counts == null || counts.isEmpty() || max <= 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(max)
                .map(e -> new StepEntityTypeCount(e.getKey(), e.getValue()))
                .toList();
    }

    private static List<StepPartInfo> buildParts(
            Map<Integer, ProductDefinition> productDefinitions,
            Map<Integer, ProductDefinitionFormation> formations,
            Map<Integer, Product> products,
            Limits limits,
            List<String> warnings
    ) {
        // 零件信息抽取（尽力而为）：
        // PRODUCT_DEFINITION( id, description, formation, frame_of_reference )
        // PRODUCT_DEFINITION_FORMATION( id, description, of_product )
        // PRODUCT( id, name, description, frame_of_reference_list )
        //
        // 实践中很多 CAD 导出的“零件编号/料号”会落在 PRODUCT.id 或 PRODUCT.name 上；这里分别映射到：
        // - partNumber = PRODUCT.id
        // - name       = PRODUCT.name
        // - description= PRODUCT.description
        if (productDefinitions == null || productDefinitions.isEmpty()) {
            return List.of();
        }
        List<StepPartInfo> out = new ArrayList<>(Math.min(productDefinitions.size(), limits.maxParts));
        int count = 0;
        for (Map.Entry<Integer, ProductDefinition> e : productDefinitions.entrySet()) {
            if (count >= limits.maxParts) {
                warnings.add("零件（PRODUCT_DEFINITION）数量超过上限（maxParts=" + limits.maxParts + "），已截断。");
                break;
            }
            count++;
            Integer pdId = e.getKey();
            ProductDefinition pd = e.getValue();

            Integer productId = null;
            String partNumber = null;
            String name = null;
            String desc = null;

            if (pd.formationRef != null) {
                ProductDefinitionFormation f = formations.get(pd.formationRef);
                if (f != null && f.productRef != null) {
                    productId = f.productRef;
                    Product p = products.get(productId);
                    if (p != null) {
                        partNumber = blankToNull(p.idText);
                        name = blankToNull(p.name);
                        desc = blankToNull(p.description);
                    }
                }
            }

            out.add(new StepPartInfo(
                    pdId,
                    blankToNull(pd.idText),
                    blankToNull(pd.description),
                    productId,
                    partNumber,
                    name,
                    desc
            ));
        }
        return out;
    }

    private static StepAssemblyTree buildAssemblyTree(
            List<StepPartInfo> parts,
            List<StepAssemblyRelation> relations,
            Limits limits,
            List<String> warnings
    ) {
        if (relations == null || relations.isEmpty()) {
            return null;
        }

        // 装配关系优先选择更“标准”的 NAUO/ACU（父子装配用得最多），如果缺失再退回使用所有关系。
        List<StepAssemblyRelation> primary = relations.stream()
                .filter(r -> r != null && ("NEXT_ASSEMBLY_USAGE_OCCURRENCE".equals(r.relationType()) || "ASSEMBLY_COMPONENT_USAGE".equals(r.relationType())))
                .toList();
        List<StepAssemblyRelation> effectiveRelations = primary.isEmpty() ? relations : primary;

        Map<Integer, StepPartInfo> partByPd = new HashMap<>(Math.min(parts == null ? 0 : parts.size(), 8192));
        if (parts != null) {
            for (StepPartInfo p : parts) {
                if (p != null && p.productDefinitionId() != null) {
                    partByPd.put(p.productDefinitionId(), p);
                }
            }
        }

        Map<Integer, List<Edge>> childrenByParent = new HashMap<>();
        Set<Integer> children = new HashSet<>();
        for (StepAssemblyRelation r : effectiveRelations) {
            Integer parent = r.parentProductDefinition();
            Integer child = r.childProductDefinition();
            if (parent == null || child == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(new Edge(child, r.referenceDesignator()));
            children.add(child);
        }

        // 根节点推断：优先选择“作为父节点出现，但从未作为子节点出现”的 PRODUCT_DEFINITION；
        // 若无法推断（例如仅有循环/或数据缺失），则退化为把所有 parent 都作为根。
        Set<Integer> roots = new LinkedHashSet<>();
        for (Integer parent : childrenByParent.keySet()) {
            if (!children.contains(parent)) {
                roots.add(parent);
            }
        }
        if (roots.isEmpty()) {
            roots.addAll(childrenByParent.keySet());
        }

        // 节点预算：避免深层装配或循环关系导致无限展开
        NodeBudget budget = new NodeBudget(limits.maxAssemblyNodes);
        List<StepAssemblyNode> rootNodes = new ArrayList<>(Math.min(roots.size(), 64));
        for (Integer root : roots) {
            StepAssemblyNode node = buildNodeRecursive(
                    root,
                    null,
                    childrenByParent,
                    partByPd,
                    0,
                    limits.maxAssemblyDepth,
                    budget,
                    new HashSet<>(),
                    warnings
            );
            if (node != null) {
                rootNodes.add(node);
            }
            if (budget.truncated) {
                break;
            }
        }

        if (budget.truncated) {
            warnings.add("装配树已按 maxAssemblyNodes=" + limits.maxAssemblyNodes + " 截断。");
        }
        return new StepAssemblyTree(rootNodes, budget.truncated, limits.maxAssemblyDepth, limits.maxAssemblyNodes);
    }

    private static StepAssemblyNode buildNodeRecursive(
            Integer pdId,
            String refDesFromParent,
            Map<Integer, List<Edge>> childrenByParent,
            Map<Integer, StepPartInfo> partByPd,
            int depth,
            int maxDepth,
            NodeBudget budget,
            Set<Integer> path,
            List<String> warnings
    ) {
        if (pdId == null) {
            return null;
        }
        if (budget.truncated) {
            return null;
        }
        // 深度上限：防止异常装配层级导致递归过深
        if (depth > maxDepth) {
            budget.truncated = true;
            return new StepAssemblyNode(pdId, refDesFromParent, partByPd.get(pdId), List.of());
        }
        // 循环检测：部分 STEP 文件可能存在“互相引用/循环装配”，用 path 集合防止无限递归
        if (!path.add(pdId)) {
            warnings.add("检测到装配关系循环引用（PRODUCT_DEFINITION #" + pdId + "），已停止向下展开该分支。");
            return new StepAssemblyNode(pdId, refDesFromParent, partByPd.get(pdId), List.of());
        }
        // 节点数上限：防止装配树节点爆炸（例如一个装配引用成千上万子件）
        if (!budget.tryConsume()) {
            budget.truncated = true;
            return new StepAssemblyNode(pdId, refDesFromParent, partByPd.get(pdId), List.of());
        }

        List<Edge> edges = childrenByParent.get(pdId);
        if (edges == null || edges.isEmpty()) {
            path.remove(pdId);
            return new StepAssemblyNode(pdId, refDesFromParent, partByPd.get(pdId), List.of());
        }

        List<StepAssemblyNode> children = new ArrayList<>(Math.min(edges.size(), 64));
        for (Edge e : edges) {
            StepAssemblyNode child = buildNodeRecursive(
                    e.childPdId,
                    e.referenceDesignator,
                    childrenByParent,
                    partByPd,
                    depth + 1,
                    maxDepth,
                    budget,
                    path,
                    warnings
            );
            if (child != null) {
                children.add(child);
            }
            if (budget.truncated) {
                break;
            }
        }

        path.remove(pdId);
        return new StepAssemblyNode(pdId, refDesFromParent, partByPd.get(pdId), children);
    }

    private static StepGeometrySummary buildGeometrySummary(Map<String, Integer> typeCounts, StepBoundingBox bbox) {
        if (typeCounts == null || typeCounts.isEmpty()) {
            return null;
        }

        boolean precise = false;
        boolean tessellated = false;
        Map<String, Integer> selected = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
            String t = e.getKey();
            int c = e.getValue();
            if (isGeometryType(t)) {
                selected.put(t, c);
            }
            if (!precise && isPreciseGeometryMarker(t)) {
                precise = true;
            }
            if (!tessellated && isTessellatedGeometryMarker(t)) {
                tessellated = true;
            }
        }

        List<StepEntityTypeCount> counts = topCounts(selected, 200);
        return new StepGeometrySummary(bbox, precise, tessellated, counts.isEmpty() ? null : counts);
    }

    private static StepTopologySummary buildTopologySummary(Map<String, Integer> typeCounts) {
        if (typeCounts == null || typeCounts.isEmpty()) {
            return null;
        }
        Map<String, Integer> selected = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
            if (isTopologyType(e.getKey())) {
                selected.put(e.getKey(), e.getValue());
            }
        }
        List<StepEntityTypeCount> counts = topCounts(selected, 200);
        return new StepTopologySummary(counts.isEmpty() ? null : counts);
    }

    private static StepPmiSummary buildPmiSummary(Map<String, Integer> typeCounts, List<StepMeasureItem> measures, List<StepEntitySnippet> snippets) {
        if ((typeCounts == null || typeCounts.isEmpty()) && (measures == null || measures.isEmpty()) && (snippets == null || snippets.isEmpty())) {
            return null;
        }
        Map<String, Integer> selected = new LinkedHashMap<>();
        if (typeCounts != null) {
            for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
                if (isPmiType(e.getKey())) {
                    selected.put(e.getKey(), e.getValue());
                }
            }
        }
        List<StepEntityTypeCount> counts = topCounts(selected, 200);
        return new StepPmiSummary(
                counts.isEmpty() ? null : counts,
                (measures == null || measures.isEmpty()) ? null : measures,
                (snippets == null || snippets.isEmpty()) ? null : snippets
        );
    }

    private record Product(int id, String idText, String name, String description) {
    }

    private record ProductDefinitionFormation(int id, Integer productRef) {
    }

    private record ProductDefinition(int id, String idText, String description, Integer formationRef) {
    }

    private record Edge(Integer childPdId, String referenceDesignator) {
    }

    private static final class NodeBudget {
        final int maxNodes;
        int used = 0;
        boolean truncated = false;

        NodeBudget(int maxNodes) {
            this.maxNodes = Math.max(1, maxNodes);
        }

        boolean tryConsume() {
            used++;
            return used <= maxNodes;
        }
    }

    private static final class BoundingBoxAccumulator {
        boolean initialized = false;
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;
        long count = 0;

        void add(double x, double y, double z) {
            if (!initialized) {
                initialized = true;
                minX = maxX = x;
                minY = maxY = y;
                minZ = maxZ = z;
            } else {
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            count++;
        }

        StepBoundingBox toBoundingBox() {
            if (!initialized || count <= 0) {
                return null;
            }
            return new StepBoundingBox(minX, minY, minZ, maxX, maxY, maxZ, count);
        }
    }
}
