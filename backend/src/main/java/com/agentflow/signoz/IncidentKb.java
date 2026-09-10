package com.agentflow.signoz;

import com.agentflow.engine.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故障案例知识库（docs/incidents/）：结构化 frontmatter 的 Markdown 案例 + index.yaml 索引。
 *
 * 设计要点：
 * - 指纹等"可从链路推导"的字段由程序生成，人只写判断类字段（标题/根因/处置/验证），
 *   保证同一故障模式的指纹稳定一致，检索才靠得住。
 * - 同一指纹多次发生只累加 occurrences，不重复建档；同一 trace 重复归档是幂等的。
 * - 案例正文可能被人手工编辑过，更新时只改 frontmatter 与「关联」区，不重写正文。
 */
@Component
public class IncidentKb {

    private static final Logger log = LoggerFactory.getLogger(IncidentKb.class);

    private static final DateTimeFormatter LOCAL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Pattern SLUG_BAD = Pattern.compile("[^a-z0-9]+");

    private final Path root;
    private final Object writeLock = new Object();

    public IncidentKb(@Value("${agentflow.signoz.kb-path:./docs/incidents}") String kbPath) {
        String raw = kbPath == null || kbPath.isBlank() ? "./docs/incidents" : kbPath.trim();
        this.root = Path.of(StoragePaths.resolve(raw));
    }

    public Path root() {
        return root;
    }

    /** 一条索引记录（frontmatter 的索引投影） */
    public record CaseEntry(String id, String title, String file, TraceDigest.Fingerprint fingerprint,
                            String confidence, int occurrences, String firstSeen, String lastSeen,
                            List<String> services, String owner) {
    }

    public enum Strength {
        /** 异常类或识别特征一致：基本可判定同一故障模式 */
        STRONG,
        /** span 位置一致或关键词命中 ≥2：需按本次证据复核 */
        MEDIUM,
        /** 仅服务重叠或单个关键词命中：只作参考线索 */
        WEAK,
        NONE
    }

    public record Match(Strength strength, CaseEntry entry) {
        public boolean actionable() {
            return strength == Strength.STRONG || strength == Strength.MEDIUM;
        }
    }

    public record ArchiveResult(String id, String caseFile, int occurrences, boolean created,
                                boolean alreadyRecorded) {
    }

    /** 案例正文里的判断类字段（索引不存这些，命中后按需读取案例文件） */
    public record CaseDetail(String rootCause, List<String> fix, String verification) {
    }

    /** 读取命中案例的根因与处置方案，供摘要直接复用历史经验 */
    public CaseDetail detail(CaseEntry e) {
        if (e == null || e.file() == null || e.file().isBlank()) {
            return new CaseDetail("", List.of(), "");
        }
        try {
            Path file = root.resolve(e.file());
            if (!Files.isRegularFile(file)) {
                return new CaseDetail("", List.of(), "");
            }
            String raw = frontmatter(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Object> fm = raw == null ? Map.of() : loadYaml(raw);
            return new CaseDetail(strOrEmpty(fm.get("root_cause")), strList(fm.get("fix")),
                    strOrEmpty(fm.get("verification")));
        } catch (Exception ex) {
            log.warn("读取案例 {} 失败：{}", e.id(), ex.getMessage());
            return new CaseDetail("", List.of(), "");
        }
    }

    public boolean exists() {
        return Files.isRegularFile(indexPath());
    }

    public Path indexPath() {
        return root.resolve("index.yaml");
    }

    /* ================= 读取与匹配（Step 0） ================= */

    public List<CaseEntry> entries() {
        if (!exists()) {
            return List.of();
        }
        try {
            Map<String, Object> doc = loadYaml(Files.readString(indexPath(), StandardCharsets.UTF_8));
            Object cases = doc.get("cases");
            List<CaseEntry> out = new ArrayList<>();
            if (cases instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        CaseEntry e = toEntry(m);
                        if (e != null) {
                            out.add(e);
                        }
                    }
                }
            }
            return out;
        } catch (Exception ex) {
            log.warn("读取故障案例索引失败：{}", ex.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static CaseEntry toEntry(Map<?, ?> m) {
        String id = str(m.get("id"));
        if (id == null) {
            return null;
        }
        TraceDigest.Fingerprint fp = new TraceDigest.Fingerprint("", "", "", "", List.of());
        Object fpObj = m.get("fingerprint");
        if (fpObj instanceof Map<?, ?> f) {
            fp = new TraceDigest.Fingerprint(
                    strOrEmpty(f.get("error_class")),
                    strOrEmpty(f.get("signature")),
                    strOrEmpty(f.get("span")),
                    strOrEmpty(f.get("status")),
                    strList(f.get("keywords")));
        }
        return new CaseEntry(id, strOrEmpty(m.get("title")), strOrEmpty(m.get("file")), fp,
                strOrEmpty(m.get("confidence")),
                m.get("occurrences") instanceof Number n ? n.intValue() : 1,
                strOrEmpty(m.get("first_seen")), strOrEmpty(m.get("last_seen")),
                strList(m.get("services")), strOrEmpty(m.get("owner")));
    }

    /**
     * 按指纹匹配已有案例，取最强的一条。traceServices 为本次链路涉及的服务（用于预过滤）。
     * 匹配容忍度刻意放宽：包名前缀、大小写、异常类简名差异都不应导致漏配。
     */
    public Match match(TraceDigest.Fingerprint fp, List<String> traceServices) {
        Match best = new Match(Strength.NONE, null);
        for (CaseEntry e : entries()) {
            Match cur = matchOne(fp, e, traceServices);
            if (rank(cur.strength()) > rank(best.strength())) {
                best = cur;
            }
            if (best.strength() == Strength.STRONG) {
                break;
            }
        }
        return best;
    }

    private static int rank(Strength s) {
        return switch (s) {
            case NONE -> 0;
            case WEAK -> 1;
            case MEDIUM -> 2;
            case STRONG -> 3;
        };
    }

    /**
     * 通用异常：全系统随处可见，对上了也不能说明是同一个故障，
     * 只能贡献微弱的分数（见 matchOne）。
     * 清单外的异常（如 DMException、UnknownHostException）自带明确语义，权重更高。
     */
    private static final Set<String> GENERIC_EXCEPTIONS = Set.of(
            "NullPointerException", "IllegalStateException", "IllegalArgumentException",
            "UnsupportedOperationException", "RuntimeException", "Exception", "Throwable",
            "TimeoutException", "SocketTimeoutException", "ConnectTimeoutException",
            "IOException", "BadRequestException", "NotFoundException", "ForbiddenException",
            "DataAccessException", "AssertionError", "OutOfMemoryError");

    static boolean isGenericException(String errorClass) {
        String simple = simpleClass(errorClass).toLowerCase(Locale.ROOT);
        return GENERIC_EXCEPTIONS.stream().anyMatch(g -> g.toLowerCase(Locale.ROOT).equals(simple));
    }

    /**
     * 多信号计分（替代单信号短路，避免"一个通用异常对上就算强命中"的误报）：
     *
     *   识别特征（DAO 方法/主表）一致  +3   —— 单独即可强命中，辨识度最高
     *   特定异常类一致                +2
     *   通用异常类一致                +1
     *   失败 span 服务+操作一致       +2
     *   每个关键词命中                +1（封顶 +2）
     *   服务有交集                    +0.5
     *
     *   强 ≥3（要么高辨识特征，要么至少两个独立信号）；中 ≥2；弱 >0。
     * 另有两条护栏：服务零交集的案例最多弱命中（跨业务域不算）；
     * 退化成 "服务 / 操作" 形态的 signature 不参与高权重（它只是个位置）。
     */
    private static Match matchOne(TraceDigest.Fingerprint fp, CaseEntry e, List<String> traceServices) {
        TraceDigest.Fingerprint other = e.fingerprint();
        boolean serviceOverlap = serviceOverlap(traceServices, e);
        double score = 0;

        boolean signatureIsLocation = fp.signature() != null && fp.signature().contains(" / ");
        if (!signatureIsLocation && similar(fp.signature(), other.signature(), 4)) {
            score += 3;
        }
        if (sameException(fp.errorClass(), other.errorClass())) {
            score += isGenericException(fp.errorClass()) ? 1 : 2;
        }
        if (sameSpan(fp.span(), other.span())) {
            score += 2;
        }
        // 关键词计分要排除异常简名：异常类已经计过分，同一证据不能重复计
        score += Math.min(2, overlapExcludingException(fp.keywords(), other.keywords(),
                fp.errorClass(), other.errorClass()));
        if (serviceOverlap) {
            score += 0.5;
        }

        Strength s;
        if (!serviceOverlap && score > 0) {
            // 与本次链路无服务交集：无论分数多高都只作参考，不进报告
            s = Strength.WEAK;
        } else if (score >= 3) {
            s = Strength.STRONG;
        } else if (score >= 2) {
            s = Strength.MEDIUM;
        } else if (score > 0) {
            s = Strength.WEAK;
        } else {
            s = Strength.NONE;
        }
        return s == Strength.NONE ? new Match(Strength.NONE, null) : new Match(s, e);
    }

    /** 本次链路服务 与 案例服务（索引 services + 指纹 span 里的服务）是否有交集 */
    private static boolean serviceOverlap(List<String> traceServices, CaseEntry e) {
        if (traceServices == null || traceServices.isEmpty()) {
            return true; // 未提供服务信息时不做预过滤，退回纯指纹匹配
        }
        Set<String> norm = new LinkedHashSet<>();
        for (String s : traceServices) {
            norm.add(s.toLowerCase(Locale.ROOT));
        }
        for (String s : e.services()) {
            if (!s.isBlank() && norm.contains(s.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        String caseService = splitSpan(e.fingerprint().span())[0];
        return !caseService.isEmpty() && norm.contains(caseService.toLowerCase(Locale.ROOT));
    }

    private static boolean similar(String a, String b, int minLen) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.trim().toLowerCase(Locale.ROOT);
        String y = b.trim().toLowerCase(Locale.ROOT);
        if (x.length() < minLen || y.length() < minLen) {
            return false;
        }
        return x.contains(y) || y.contains(x);
    }

    private static boolean sameException(String a, String b) {
        String x = simpleClass(a);
        String y = simpleClass(b);
        if (x.isEmpty() || y.isEmpty() || x.startsWith("未上报") || y.startsWith("未上报")) {
            return false;
        }
        return x.equalsIgnoreCase(y) || x.toLowerCase(Locale.ROOT).contains(y.toLowerCase(Locale.ROOT));
    }

    private static String simpleClass(String fqcn) {
        if (fqcn == null) {
            return "";
        }
        String s = fqcn.trim();
        int paren = s.indexOf('(');
        if (paren > 0) {
            s = s.substring(0, paren).trim();
        }
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }

    /** 比较 "服务 / 操作" 里的服务与操作 */
    private static boolean sameSpan(String a, String b) {
        String[] x = splitSpan(a);
        String[] y = splitSpan(b);
        return !x[0].isEmpty() && x[0].equalsIgnoreCase(y[0]) && !x[1].isEmpty() && x[1].equalsIgnoreCase(y[1]);
    }

    private static String[] splitSpan(String s) {
        if (s == null) {
            return new String[]{"", ""};
        }
        int i = s.indexOf(" / ");
        return i < 0 ? new String[]{s.trim(), ""} : new String[]{s.substring(0, i).trim(), s.substring(i + 3).trim()};
    }

    /** 关键词交集计数，跳过两侧任一异常类的简名（该证据已由 sameException 计分） */
    private static int overlapExcludingException(List<String> a, List<String> b, String ex1, String ex2) {
        Set<String> excluded = new LinkedHashSet<>();
        for (String ex : List.of(ex1, ex2)) {
            String s = simpleClass(ex).toLowerCase(Locale.ROOT);
            if (!s.isBlank()) {
                excluded.add(s);
            }
        }
        Set<String> norm = new LinkedHashSet<>();
        for (String s : b == null ? List.<String>of() : b) {
            norm.add(s.toLowerCase(Locale.ROOT));
        }
        int n = 0;
        for (String s : a == null ? List.<String>of() : a) {
            String k = s.toLowerCase(Locale.ROOT);
            if (!k.isBlank() && !excluded.contains(k) && norm.contains(k)) {
                n++;
            }
        }
        return n;
    }

    /** 命中信息渲染成摘要里的一段（含历史处置，便于直接复用） */
    public String renderMatchSection(Match m) {
        if (!m.actionable()) {
            return null;
        }
        CaseDetail d = detail(m.entry());
        StringBuilder sb = new StringBuilder();
        sb.append("知识库命中[").append(m.strength() == Strength.STRONG ? "强" : "中").append("]：")
                .append(m.entry().id()).append(" · ").append(m.entry().title())
                .append("（历史第 ").append(m.entry().occurrences()).append(" 次，最近 ")
                .append(m.entry().lastSeen()).append("）");
        if (d.rootCause() != null && !d.rootCause().isBlank()) {
            sb.append("\n历史根因：").append(TraceDigest.oneLine(d.rootCause(), 200));
        }
        if (!d.fix().isEmpty()) {
            List<String> top = d.fix().size() > 3 ? d.fix().subList(0, 3) : d.fix();
            sb.append("\n历史处置：").append(String.join("；", top));
        }
        sb.append("\n注意：指纹一致不等于根因必然相同，本次判定以当前链路证据为准");
        return sb.toString();
    }

    /* ================= 归档（Step 5） ================= */

    /**
     * 归档一次分析：指纹命中则累加次数，未命中则建档。
     * 同一 trace 重复归档幂等（不重复计数）。
     */
    public ArchiveResult archive(String traceId,
                                 TraceDigest.Fingerprint fp,
                                 List<TraceSpan> spans,
                                 String title, String symptom, String rootCause, String confidence,
                                 List<String> fix, String verification) throws IOException {
        synchronized (writeLock) {
            TimeInfo time = timeInfo(spans);
            List<CaseEntry> cases = new ArrayList<>(entries());
            Match hit = match(fp, servicesOf(spans));
            if (hit.actionable() && hit.entry() != null) {
                return updateExisting(hit.entry(), traceId, fp, spans, time, rootCause, fix, cases);
            }
            return createNew(traceId, fp, spans, time, title, symptom, rootCause, confidence, fix, verification, cases);
        }
    }

    private ArchiveResult updateExisting(CaseEntry entry, String traceId, TraceDigest.Fingerprint fp,
                                         List<TraceSpan> spans, TimeInfo time, String rootCause,
                                         List<String> fix, List<CaseEntry> cases) throws IOException {
        Path file = root.resolve(entry.file());
        if (!Files.isRegularFile(file)) {
            throw new IOException("索引指向的案例文件不存在：" + entry.file());
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String frontRaw = frontmatter(text);
        Map<String, Object> fm = frontRaw == null ? new LinkedHashMap<>() : loadYaml(frontRaw);
        List<String> traceIds = strList(fm.get("trace_ids"));
        if (traceIds.contains(traceId)) {
            return new ArchiveResult(entry.id(), entry.file(), entry.occurrences(), false, true);
        }
        traceIds.add(traceId);
        fm.put("trace_ids", traceIds);
        int occurrences = (fm.get("occurrences") instanceof Number n ? n.intValue() : entry.occurrences()) + 1;
        fm.put("occurrences", occurrences);
        fm.put("last_seen", time.lastSeen());
        if (rootCause != null && !rootCause.isBlank()) {
            fm.put("root_cause", rootCause);
        }
        // 指纹是案例的身份，不改写：一是避免同一案例被不同次匹配的细微差异带着漂移，
        // 二是索引里存的是原指纹，改写会让案例文件与索引静默不一致（match 只读索引）。
        // 需要修正指纹时人工编辑该文件——案例库本来就是可 review 的 Markdown。
        putIfAbsentList(fm, "fix", fix);
        String body = bodyOf(text);
        body = insertTraceRef(body, traceRefLine(traceId, spans, time));
        writeFile(file, dumpFrontmatter(fm) + body);
        updateIndexEntry(cases, entry.id(), occurrences, time.lastSeen());
        writeIndex(cases);
        return new ArchiveResult(entry.id(), entry.file(), occurrences, false, false);
    }

    private ArchiveResult createNew(String traceId, TraceDigest.Fingerprint fp, List<TraceSpan> spans,
                                    TimeInfo time, String title, String symptom, String rootCause,
                                    String confidence, List<String> fix, String verification,
                                    List<CaseEntry> cases) throws IOException {
        String id = nextId(cases, time.datePrefix());
        String slug = slugify(fp.signature());
        String fileName = "cases/" + id + (slug.isEmpty() ? "" : "-" + slug) + ".md";
        String displayTitle = title == null || title.isBlank() ? fp.signature() + " 失败" : title;
        String caseText = buildCaseFile(id, displayTitle, fp, spans, time, symptom, rootCause,
                confidence, fix, verification, traceId);
        writeFile(root.resolve(fileName), caseText);
        CaseEntry entry = new CaseEntry(id, displayTitle, fileName, fp,
                confidence == null || confidence.isBlank() ? "span推断" : confidence,
                1, time.firstSeen(), time.lastSeen(),
                servicesOf(spans), null);
        cases.add(entry);
        writeIndex(cases);
        return new ArchiveResult(id, fileName, 1, true, false);
    }

    /** 日期取故障发生日（first_seen），同日多例序号递增 */
    static String nextId(List<CaseEntry> cases, String datePrefix) {
        int max = 0;
        for (CaseEntry e : cases) {
            if (e.id() != null && e.id().startsWith("INC-" + datePrefix + "-")) {
                String tail = e.id().substring(("INC-" + datePrefix + "-").length());
                try {
                    max = Math.max(max, Integer.parseInt(tail));
                } catch (NumberFormatException ignored) {
                    // 手工编号不规范时忽略，不影响新建
                }
            }
        }
        return String.format("INC-%s-%03d", datePrefix, max + 1);
    }

    private String buildCaseFile(String id, String title, TraceDigest.Fingerprint fp, List<TraceSpan> spans,
                                 TimeInfo time, String symptom, String rootCause, String confidence,
                                 List<String> fix, String verification, String traceId) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", id);
        fm.put("title", title);
        fm.put("fingerprint", fingerprintMap(fp));
        fm.put("symptom", blankTo(symptom, "未填写"));
        fm.put("root_cause", blankTo(rootCause, "未填写"));
        fm.put("confidence", blankTo(confidence, "span推断"));
        fm.put("fix", fix == null || fix.isEmpty() ? List.of("未填写") : fix);
        fm.put("verification", blankTo(verification, "未填写"));
        fm.put("occurrences", 1);
        fm.put("first_seen", time.firstSeen());
        fm.put("last_seen", time.lastSeen());
        fm.put("trace_ids", List.of(traceId));
        fm.put("services", servicesOf(spans));

        StringBuilder sb = new StringBuilder();
        sb.append(dumpFrontmatter(fm));
        sb.append("\n# ").append(title).append("\n\n");
        sb.append("## 现象\n\n").append(blankTo(symptom, "未填写（归档时未提供现象描述）")).append("\n\n");
        sb.append("## 根因\n\n").append(blankTo(rootCause, "未填写（归档时未确认根因）")).append("\n\n");
        sb.append("## 关键证据\n\n").append(evidenceTable(spans)).append('\n');
        sb.append("## 处置步骤\n\n").append(numbered(fix)).append("\n\n");
        sb.append("## 验证方式\n\n").append(blankTo(verification, "未填写")).append("\n\n");
        sb.append("## 修订记录\n\n").append("<!-- 仅当后续证据推翻或修正本案例结论时追加 -->").append("\n\n");
        // 新建文件时直接写已存在的标题，不要再走 insertTraceRef（那会重复插入一个 ## 关联）
        sb.append("## 关联\n\n- ").append(traceRefLine(traceId, spans, time)).append('\n');
        return sb.toString();
    }

    /** 关键证据表：只从链路真实数据里取，不编造 */
    private static String evidenceTable(List<TraceSpan> spans) {
        TraceSpan origin = TraceDigest.errorOrigin(spans);
        StringBuilder sb = new StringBuilder();
        sb.append("| 证据 | 说明 |\n|---|---|\n");
        if (origin == null) {
            TraceSpan root = TraceDigest.mainRoot(spans);
            sb.append("| 无报错 span | 本次为成功链路，归档原因见「现象」 |\n");
            if (root != null) {
                sb.append("| 总耗时 ").append(TraceDigest.fmt(root.durationMs())).append("ms | 最慢 span：")
                        .append(root.label()).append(" |\n");
            }
            return sb.toString();
        }
        sb.append("| ").append(escapeCell(TraceDigest.oneLine(origin.statusMessage(), 200)))
                .append(" | 失败源头异常原文 |\n");
        sb.append("| ").append(escapeCell(origin.label())).append(" | 失败 span：")
                .append(TraceDigest.fmt(origin.durationMs())).append("ms，状态 ")
                .append(escapeCell(origin.statusCode())).append(" |\n");
        List<TraceSpan> path = TraceDigest.pathToRoot(origin, spans);
        sb.append("| 传播路径深度 ").append(path.size()).append(" 层 | ")
                .append(escapeCell(String.join(" → ", path.stream().map(TraceSpan::service).toList())))
                .append(" |\n");
        long sqlOk = spans.stream().filter(s -> !s.hasError() && s.name().contains("[SQL]")).count();
        if (sqlOk > 0) {
            sb.append("| ").append(sqlOk).append(" 个 [SQL] span 无错误 | 排除 SQL 语句本身的问题 |\n");
        }
        return sb.toString();
    }

    private static String escapeCell(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }

    private static String numbered(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "1. 未填写";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(items.get(i)).append('\n');
        }
        return sb.toString().trim();
    }

    /** 把本次 trace 追加到「关联」区（案例正文可能被人工编辑过，只插这一行，不重写正文） */
    static String insertTraceRef(String body, String line) {
        String ref = "- " + line;
        if (body == null) {
            body = "";
        }
        Matcher m = Pattern.compile("(?m)^##\\s*关联\\s*$").matcher(body);
        if (m.find()) {
            int insertAt = body.indexOf('\n', m.end());
            if (insertAt < 0) {
                return body + "\n\n" + ref + "\n";
            }
            return body.substring(0, insertAt + 1) + "\n" + ref + "\n" + body.substring(insertAt + 1);
        }
        return body + "\n\n## 关联\n\n" + ref + "\n";
    }

    private static String traceRefLine(String traceId, List<TraceSpan> spans, TimeInfo time) {
        String env = "";
        for (TraceSpan s : spans) {
            if (s.env() != null && !s.env().isBlank()) {
                env = s.env();
                break;
            }
        }
        return "trace `" + traceId + "`（" + time.firstSeen() + (env.isEmpty() ? "" : "，" + env) + "）";
    }

    /* ================= 索引读写 ================= */

    private void updateIndexEntry(List<CaseEntry> cases, String id, int occurrences, String lastSeen) {
        for (int i = 0; i < cases.size(); i++) {
            if (id.equals(cases.get(i).id())) {
                CaseEntry e = cases.get(i);
                cases.set(i, new CaseEntry(e.id(), e.title(), e.file(), e.fingerprint(), e.confidence(),
                        occurrences, e.firstSeen(), lastSeen, e.services(), e.owner()));
                return;
            }
        }
    }

    private void writeIndex(List<CaseEntry> cases) throws IOException {
        cases.sort(Comparator.comparing(CaseEntry::lastSeen, Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("updated", java.time.LocalDate.now().toString());
        List<Map<String, Object>> list = new ArrayList<>();
        for (CaseEntry e : cases) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("title", e.title());
            m.put("file", e.file());
            m.put("fingerprint", fingerprintMap(e.fingerprint()));
            m.put("confidence", e.confidence());
            m.put("occurrences", e.occurrences());
            m.put("first_seen", e.firstSeen());
            m.put("last_seen", e.lastSeen());
            m.put("services", e.services());
            if (e.owner() != null && !e.owner().isBlank()) {
                m.put("owner", e.owner());
            }
            list.add(m);
        }
        doc.put("cases", list);
        writeFile(indexPath(), INDEX_HEADER + dump(doc));
    }

    private static final String INDEX_HEADER = """
            # 故障案例索引 —— Agent 检索入口
            #
            # 用途：分析 trace 前先在本文件按 fingerprint 匹配（强/中匹配才打开案例文件读全文），
            #       命中则复用历史根因与处置方案，不重复推理。
            # 维护：由 signoz-analyzing-traces skill 与 AgentFlow 的 signoz.case 工具同步；
            #       手工新增案例时也要同步本文件。
            # 排序：cases 按 last_seen 倒序。
            # 结构说明见同目录 README.md。

            """;

    private static Map<String, Object> fingerprintMap(TraceDigest.Fingerprint fp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error_class", fp.errorClass());
        m.put("signature", fp.signature());
        m.put("span", fp.span());
        m.put("status", fp.status());
        m.put("keywords", fp.keywords() == null ? List.of() : fp.keywords());
        return m;
    }

    /* ================= 文件与 YAML 基础操作 ================= */

    private static void writeFile(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** 取以 --- 包裹的 frontmatter 内容（无则返回 null） */
    static String frontmatter(String text) {
        if (text == null || !text.startsWith("---")) {
            return null;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        return text.substring(text.indexOf('\n', 3) + 1, end + 1);
    }

    /** 取 frontmatter 之后的正文（无 frontmatter 时原样返回） */
    static String bodyOf(String text) {
        if (text == null || !text.startsWith("---")) {
            return text == null ? "" : text;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return text;
        }
        int lineEnd = text.indexOf('\n', end + 1);
        return lineEnd < 0 ? "" : text.substring(lineEnd + 1);
    }

    private static String dumpFrontmatter(Map<String, Object> fm) {
        return "---\n" + dump(fm) + "---\n";
    }

    private static String dump(Object doc) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        opts.setSplitLines(false);
        return new Yaml(opts).dump(doc);
    }

    private static Map<String, Object> loadYaml(String text) {
        LoaderOptions lo = new LoaderOptions();
        lo.setAllowDuplicateKeys(false);
        Object o = new Yaml(new SafeConstructor(lo)).load(text);
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    /* ================= 时间与服务 ================= */

    record TimeInfo(String firstSeen, String lastSeen, String datePrefix) {
    }

    /** 时间取链路里最早/最晚 span 时间，用本机时区呈现 */
    static TimeInfo timeInfo(List<TraceSpan> spans) {
        Instant min = null;
        Instant max = null;
        for (TraceSpan s : spans) {
            Instant i = parseInstant(s.timestamp());
            if (i == null) {
                continue;
            }
            if (min == null || i.isBefore(min)) {
                min = i;
            }
            if (max == null || i.isAfter(max)) {
                max = i;
            }
        }
        if (min == null) {
            String now = LOCAL_TS.format(java.time.OffsetDateTime.now());
            return new TimeInfo(now, now, java.time.LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        }
        String first = LOCAL_TS.format(min.atZone(ZoneId.systemDefault()));
        String last = LOCAL_TS.format(max.atZone(ZoneId.systemDefault()));
        // 必须先落到 LocalDate：直接在带时区的对象上用 BASIC_ISO_DATE 会把偏移量也格式化进去
        String date = DateTimeFormatter.BASIC_ISO_DATE
                .format(min.atZone(ZoneId.systemDefault()).toLocalDate());
        return new TimeInfo(first, last, date);
    }

    static Instant parseInstant(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(ts.trim());
        } catch (Exception ex) {
            try {
                return java.time.OffsetDateTime.parse(ts.trim()).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** 链路涉及服务（去重、保序），匹配预过滤与案例 services 字段共用 */
    static List<String> servicesOf(List<TraceSpan> spans) {
        Set<String> set = new LinkedHashSet<>();
        for (TraceSpan s : spans) {
            if (s.service() != null && !s.service().isBlank()) {
                set.add(s.service());
            }
        }
        return new ArrayList<>(set);
    }

    /** 案例文件名用的语义短名：从识别特征派生，非 ASCII 就留空 */
    static String slugify(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String slug = SLUG_BAD.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = slug.replaceAll("^-+|-+$", "");
        // 上限放宽到 60：DAO 方法名这类特征整体保留，截在单词中间反而不可读
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return slug;
    }

    /* ================= 小工具 ================= */

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static void putIfAbsentList(Map<String, Object> fm, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (strList(fm.get(key)).isEmpty()) {
            fm.put(key, values);
        }
    }

    /**
     * 取字符串值。手工编辑的 YAML 里未加引号的时间戳会被 snakeyaml 解析成 Date，
     * 直接 toString 会变成 "Tue Sep 08 09:48:45 CST 2026"，这里统一还原成 ISO 本地时间。
     */
    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof java.util.Date d) {
            return LOCAL_TS.format(d.toInstant().atZone(ZoneId.systemDefault()));
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String strOrEmpty(Object o) {
        String s = str(o);
        return s == null ? "" : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object i : list) {
                if (i != null) {
                    out.add(String.valueOf(i));
                }
            }
        }
        return out;
    }
}
