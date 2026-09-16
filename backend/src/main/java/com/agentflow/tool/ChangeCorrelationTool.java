package com.agentflow.tool;

import com.agentflow.signoz.SigNozMcpClient;
import com.agentflow.signoz.TraceDigest;
import com.agentflow.signoz.TraceFetcher;
import com.agentflow.signoz.TraceSpan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变更关联工具（gitlab.changes，只读）：故障排查时回答「谁改坏的」。
 *
 * 给定 traceId（自动取涉及服务与故障时间，推荐）或 services + time，
 * 拉取各服务映射的 GitLab 项目在故障前 N 小时的提交，按可疑度排序输出——
 * 规则透明可解释：时间接近度、提交信息语义（修复/回滚类）、失败点服务、流水线失败。
 *
 * 服务名↔GitLab 项目映射的解析优先级：
 * 显式 mapping 参数（自动固化到映射表）＞「服务映射」面板配置 ＞ 按服务名搜索 GitLab 推断；
 * 推断歧义或未命中时出候选卡反问，用户点选后带 mapping 重跑并固化，越用越准。
 */
@Component
public class ChangeCorrelationTool implements Tool {

    private static final Pattern TRACE_ID = Pattern.compile("\\b([0-9a-fA-F]{32})\\b");
    /** 显式映射：svc=group/proj（第二段必须带斜杠，避免误吞普通 key=value） */
    private static final Pattern MAPPING = Pattern.compile("([A-Za-z0-9_.-]+)\\s*=\\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+)");
    private static final Pattern FIX_WORDS = Pattern.compile("fix|bug|patch|hotfix|修复|临时|兼容|revert|回滚|补偿", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPT_WORDS = Pattern.compile("perf|优化|性能|sql|索引|migration|迁移|缓存|refactor|重构|配置", Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter OUT_TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter FULL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 一条已解析的服务映射：source = 本次指定并已保存 / 已配置 / 推断 */
    record Mapped(String service, long projectId, String projectPath, String source) {
    }

    /** 一条候选提交（评分前） */
    record RawCommit(String sha, String title, String author, OffsetDateTime time, Mapped mapped) {
    }

    private final GitLabTool gitLab;
    private final ServiceProjectStore store;
    private final TraceFetcher traceFetcher;
    private final SigNozMcpClient signozClient;
    private final int defaultWindowHours;
    private final int maxProjects;
    private final int maxCommits;

    public ChangeCorrelationTool(GitLabTool gitLab,
                                 ServiceProjectStore store,
                                 TraceFetcher traceFetcher,
                                 SigNozMcpClient signozClient,
                                 @Value("${agentflow.correlate.window-hours:48}") int windowHours,
                                 @Value("${agentflow.correlate.max-projects:5}") int maxProjects,
                                 @Value("${agentflow.correlate.max-commits:10}") int maxCommits) {
        this.gitLab = gitLab;
        this.store = store;
        this.traceFetcher = traceFetcher;
        this.signozClient = signozClient;
        this.defaultWindowHours = clamp(windowHours <= 0 ? 48 : windowHours, 1, 168);
        this.maxProjects = Math.max(1, maxProjects);
        this.maxCommits = Math.max(1, maxCommits);
    }

    @Override
    public String name() {
        return "gitlab.changes";
    }

    @Override
    public String description() {
        if (!gitLab.isConfigured()) {
            return "变更关联（当前未配置 GitLab Token：请在工作台 → GitLab 账户 配置后使用）";
        }
        return "变更关联：链路分析定位到故障后，回答「谁改坏的」——拉取涉及服务对应的 GitLab 项目"
                + "在故障时间前的提交，按可疑度排序（时间接近、修复/回滚类提交、失败点服务、流水线失败加权），"
                + "每条附理由。推荐传 traceId（自动取涉及服务与故障时间），也可 services + time 独立使用。只读。";
    }

    @Override
    public String argsHint() {
        return "{\"traceId\": \"链路 ID（推荐，自动解析涉及服务与故障时间）\", "
                + "\"services\": \"服务名，多个逗号分隔（与 traceId 二选一）\", "
                + "\"time\": \"故障时间 yyyy-MM-dd HH:mm（services 方式时建议给出，默认当前时间）\", "
                + "\"hours\": \"回溯小时数，默认 48，上限 168\", "
                + "\"mapping\": \"可选显式映射：svc=group/proj,svc2=team/repo2（会保存到服务映射）\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (!gitLab.isConfigured()) {
            return ToolResult.note("未配置 GitLab Access Token（工作台 → GitLab 账户 添加，或在 .env 配置 GITLAB_TOKEN 后重启），无法关联变更");
        }
        try {
            return run(args, userCommand);
        } catch (Exception ex) {
            return ToolResult.note("变更关联失败：" + ex.getMessage());
        }
    }

    private ToolResult run(Map<String, Object> args, String userCommand) throws Exception {
        // 1. 解析服务与故障时间：traceId 自动解析优先级最高，显式 services/time 参数可覆盖
        String traceId = resolveTraceId(str(args.get("traceId")), userCommand);
        List<String> services = splitServices(str(args.get("services")));
        OffsetDateTime failureTime = parseTimeArg(str(args.get("time")));
        String failureService = null;

        if (traceId != null) {
            if (!signozClient.isConfigured()) {
                return ToolResult.note("未配置 SigNoz MCP 地址，无法按 traceId 解析服务与故障时间；"
                        + "请改用 services + time 参数（如「查 pay-service 昨天 14 点前的变更」）");
            }
            TraceFetcher.Fetched fetched = traceFetcher.fetch(traceId, str(args.get("timeRange")));
            if (!fetched.found()) {
                return ToolResult.note("未在 SigNoz 查到 trace " + traceId + "，无法关联变更；可直接用 services 参数指定服务重试");
            }
            List<TraceSpan> spans = fetched.spans();
            if (services.isEmpty()) {
                services = servicesOf(spans);
            }
            if (failureTime == null) {
                failureTime = earliest(spans);
            }
            TraceSpan origin = TraceDigest.errorOrigin(spans);
            if (origin != null && origin.service() != null && !origin.service().isBlank()) {
                failureService = origin.service();
            }
        }
        if (services.isEmpty()) {
            return ToolResult.note("缺少服务名：传 traceId 自动解析，或用 services 参数（如 pay-service,order-service）");
        }
        if (failureTime == null) {
            failureTime = OffsetDateTime.now();
        }
        int hours = clampHours(str(args.get("hours")));
        OffsetDateTime since = failureTime.minusHours(hours);

        // 2. 解析服务→项目映射；歧义时出候选卡反问（点选后带 mapping 重跑并固化）
        Map<String, String> explicit = parseMappings(str(args.get("mapping")), userCommand);
        List<Mapped> mapped = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Map<String, String>> clarifyOptions = new ArrayList<>();
        String clarifyService = null;

        for (String svc : services) {
            String path = explicit.get(svc);
            if (path != null) {
                JsonNode p = gitLab.projectByPath(path);
                if (p == null) {
                    skipped.add(svc + "（GitLab 上未找到 " + path + "）");
                    continue;
                }
                store.save(svc, p.path("id").asLong(), path);
                mapped.add(new Mapped(svc, p.path("id").asLong(), path, "本次指定并已保存"));
                continue;
            }
            ServiceProjectStore.ServiceProject sp = store.find(svc);
            if (sp != null) {
                JsonNode p = gitLab.projectByPath(sp.projectPath());
                if (p != null) {
                    mapped.add(new Mapped(svc, p.path("id").asLong(), sp.projectPath(), "已配置"));
                    continue;
                }
                // 配置里的项目已不存在（改名/删除），落到自动推断
            }
            JsonNode results = gitLab.searchProjects(svc);
            List<JsonNode> exact = new ArrayList<>();
            for (JsonNode p : results) {
                String name = p.path("name").asText("");
                String pwn = p.path("path_with_namespace").asText("");
                if (name.equalsIgnoreCase(svc) || pwn.endsWith("/" + svc) || pwn.equalsIgnoreCase(svc)) {
                    exact.add(p);
                }
            }
            if (exact.size() == 1) {
                JsonNode p = exact.get(0);
                mapped.add(new Mapped(svc, p.path("id").asLong(),
                        p.path("path_with_namespace").asText(p.path("name").asText()), "推断"));
                continue;
            }
            if (clarifyService == null && (!exact.isEmpty() || !results.isEmpty())) {
                // 只对第一个歧义服务出卡；其余歧义服务先跳过并说明，避免多张卡来回问
                clarifyService = svc;
                List<JsonNode> cands = exact.isEmpty() ? new ArrayList<>() : exact;
                if (cands.isEmpty()) {
                    for (JsonNode p : results) {
                        cands.add(p);
                    }
                }
                for (JsonNode p : cands.subList(0, Math.min(4, cands.size()))) {
                    String pwn = p.path("path_with_namespace").asText(p.path("name").asText(""));
                    clarifyOptions.add(Map.of("label", pwn, "action", clarifyAction(traceId, services, svc, pwn)));
                }
            } else if (exact.isEmpty() && results.isEmpty()) {
                skipped.add(svc + "（GitLab 搜不到同名项目，可在工作台 → 服务映射 手动配置）");
            } else {
                skipped.add(svc + "（映射不唯一，可在工作台 → 服务映射 手动配置）");
            }
        }

        if (mapped.isEmpty()) {
            if (!clarifyOptions.isEmpty()) {
                return ToolResult.withClarify("服务「" + clarifyService + "」还没配置 GitLab 项目映射，请选择对应项目（选择后映射会被保存，下次不再询问）：",
                        new ToolResult.Clarify("服务「" + clarifyService + "」对应哪个 GitLab 项目？", clarifyOptions));
            }
            return ToolResult.note("没有可用的服务→项目映射：" + String.join("；", skipped)
                    + "。请在工作台 → 服务映射 面板配置后重试。");
        }
        if (!clarifyOptions.isEmpty()) {
            // 部分服务已解析、部分歧义：仍出卡确认（准确度优先），已解析的映射信息带进提示
            String resolved = mapped.stream().map(m -> m.service() + "→" + m.projectPath()).reduce((a, b) -> a + "，" + b).orElse("");
            return ToolResult.withClarify("已解析映射：" + resolved + "。服务「" + clarifyService
                            + "」映射不唯一，请选择对应项目（选择后保存，下次不再询问）：",
                    new ToolResult.Clarify("服务「" + clarifyService + "」对应哪个 GitLab 项目？", clarifyOptions));
        }

        // 3. 拉提交与流水线（项目去重、封顶 maxProjects）
        List<Mapped> uniqueProjects = dedupeByProject(mapped, maxProjects);
        Set<String> failedPipelines = new LinkedHashSet<>();
        List<RawCommit> commits = new ArrayList<>();
        List<String> fetchErrors = new ArrayList<>();
        for (Mapped m : uniqueProjects) {
            try {
                for (JsonNode c : gitLab.fetchProjectCommits(m.projectId(), since, failureTime)) {
                    OffsetDateTime t = parseIso(c.path("created_at").asText(""));
                    if (t == null) {
                        continue;
                    }
                    commits.add(new RawCommit(c.path("id").asText(""), c.path("title").asText(""),
                            c.path("author_name").asText(""), t, m));
                }
            } catch (Exception ex) {
                fetchErrors.add(m.projectPath() + "：" + ex.getMessage());
            }
            try {
                for (JsonNode p : gitLab.fetchPipelines(m.projectId(), 10)) {
                    OffsetDateTime t = parseIso(p.path("updated_at").asText(""));
                    boolean failed = "failed".equals(p.path("status").asText(""))
                            || "canceled".equals(p.path("status").asText(""));
                    if (failed && t != null && !t.isBefore(since) && !t.isAfter(failureTime)) {
                        failedPipelines.add(m.projectPath());
                    }
                }
            } catch (Exception ignored) {
                // 流水线查不到不影响提交关联
            }
        }

        // 4. 评分排序：规则透明，每条提交附理由；分数相同按时间近的在前
        commits.sort((a, b) -> b.time().compareTo(a.time()));
        record Scored(Map<String, Object> item, int score, OffsetDateTime time) {
        }
        List<Scored> scored = new ArrayList<>();
        for (RawCommit c : commits) {
            long minutesBefore = Duration.between(c.time().toInstant(), failureTime.toInstant()).toMinutes();
            if (minutesBefore < 0) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            boolean isFailureService = c.mapped().service().equals(failureService);
            boolean pipelineFailed = failedPipelines.contains(c.mapped().projectPath());
            int score = scoreCommit(minutesBefore, c.title(), isFailureService, pipelineFailed, reasons);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", score);
            item.put("sha", c.sha().length() > 7 ? c.sha().substring(0, 7) : c.sha());
            item.put("title", c.title());
            item.put("author", c.author());
            item.put("time", c.time().format(OUT_TS));
            item.put("project", c.mapped().projectPath());
            item.put("service", c.mapped().service());
            item.put("reasons", reasons);
            item.put("pipeline", pipelineFailed ? "failed" : "");
            scored.add(new Scored(item, score, c.time()));
        }
        scored.sort((a, b) -> {
            int byScore = Integer.compare(b.score(), a.score());
            return byScore != 0 ? byScore : b.time().compareTo(a.time());
        });
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(maxCommits, scored.size()))) {
            s.item().put("rank", ranked.size() + 1);
            ranked.add(s.item());
        }

        // 5. 输出
        Map<String, Object> result = new LinkedHashMap<>();
        if (traceId != null) {
            result.put("traceId", traceId);
        }
        result.put("failureTime", failureTime.format(FULL_TS));
        result.put("windowHours", hours);
        if (failureService != null) {
            result.put("failureService", failureService);
        }
        List<Map<String, Object>> serviceMaps = new ArrayList<>();
        for (Mapped m : uniqueProjects) {
            serviceMaps.add(Map.of("service", m.service(), "project", m.projectPath(), "source", m.source()));
        }
        result.put("services", serviceMaps);
        result.put("commitCount", commits.size());
        result.put("commits", ranked);

        List<String> lines = new ArrayList<>();
        lines.add("回溯窗口 " + since.format(OUT_TS) + " ~ " + failureTime.format(OUT_TS)
                + "（" + hours + " 小时）" + (failureService == null ? "" : " · 失败点服务 " + failureService));
        for (Mapped m : uniqueProjects) {
            lines.add("服务映射 " + m.service() + " → " + m.projectPath() + "（" + m.source() + "）");
        }
        if (!skipped.isEmpty()) {
            lines.add("未关联：" + String.join("；", skipped));
        }
        if (!fetchErrors.isEmpty()) {
            lines.add("部分项目拉取失败：" + String.join("；", fetchErrors));
        }
        for (Map<String, Object> c : ranked) {
            lines.add("#" + c.get("rank") + " [" + c.get("score") + "分] " + c.get("sha") + " · " + c.get("author")
                    + " · " + c.get("time") + " · " + c.get("title")
                    + "（" + joinReasons(c) + "）");
        }
        if (ranked.isEmpty()) {
            lines.add("窗口内没有相关提交——故障可能不是代码变更引起，或相关仓库未映射（可用 hours 参数扩大回溯窗口）");
        }

        String summary = ranked.isEmpty()
                ? "变更关联：窗口 " + hours + " 小时内无相关提交"
                : "变更关联 " + ranked.size() + " 项（窗口 " + hours + " 小时）· 最可疑 "
                        + ranked.get(0).get("sha") + " " + ranked.get(0).get("title")
                        + "（" + ranked.get(0).get("score") + " 分）";
        return new ToolResult("changes", result, lines, summary);
    }

    /* ---------- 澄清卡重跑指令 ---------- */

    private static String clarifyAction(String traceId, List<String> services, String svc, String projectPath) {
        StringBuilder sb = new StringBuilder("变更关联 ");
        if (traceId != null) {
            sb.append("traceId ").append(traceId);
        } else {
            sb.append(String.join(",", services));
        }
        sb.append(" 映射 ").append(svc).append("=").append(projectPath);
        return sb.toString();
    }

    /* ---------- 纯逻辑（单测覆盖） ---------- */

    /**
     * 可疑度评分（透明规则），理由写入 reasons：
     * 距故障 &lt;6h +3 / &lt;24h +2 / &lt;48h +1；修复/回滚类提交 +2、性能/结构变更 +1；
     * 失败点服务 +2、其他涉及服务 +1；窗口内流水线失败 +1。
     */
    static int scoreCommit(long minutesBefore, String title, boolean isFailureService,
                           boolean pipelineFailed, List<String> reasons) {
        int score = 0;
        if (minutesBefore < 360) {
            score += 3;
            reasons.add("距故障" + fmtHours(minutesBefore));
        } else if (minutesBefore < 1440) {
            score += 2;
            reasons.add("距故障" + fmtHours(minutesBefore));
        } else if (minutesBefore < 2880) {
            score += 1;
            reasons.add("距故障" + fmtHours(minutesBefore));
        } else {
            reasons.add("距故障" + fmtHours(minutesBefore));
        }
        String t = title == null ? "" : title;
        if (FIX_WORDS.matcher(t).find()) {
            score += 2;
            reasons.add("修复/回滚类提交");
        } else if (OPT_WORDS.matcher(t).find()) {
            score += 1;
            reasons.add("性能/结构变更");
        }
        if (isFailureService) {
            score += 2;
            reasons.add("失败点服务");
        } else {
            score += 1;
            reasons.add("涉及服务");
        }
        if (pipelineFailed) {
            score += 1;
            reasons.add("窗口内流水线失败");
        }
        return score;
    }

    static String fmtHours(long minutes) {
        if (minutes < 60) {
            return minutes + "分钟";
        }
        return Math.round(minutes / 60.0) + "小时";
    }

    /** 故障时间参数：支持 yyyy-MM-dd[ HH:mm[:ss]] 与 ISO 偏移时间；日期按本机时区 */
    static OffsetDateTime parseTimeArg(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim();
        try {
            return OffsetDateTime.parse(v, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ignored) {
        }
        for (String p : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-M-d H:m:s", "yyyy-M-d H:m", "yyyy-MM-dd"}) {
            try {
                LocalDateTime dt = LocalDateTime.parse(v, DateTimeFormatter.ofPattern(p));
                return dt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
            } catch (Exception ignored) {
            }
        }
        try {
            LocalDate d = LocalDate.parse(v);
            return d.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 服务列表参数：逗号/顿号/空白分隔，去空去重保序 */
    static List<String> splitServices(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String p : s.split("[,，、;；\\s]+")) {
            String v = p.trim();
            if (!v.isEmpty()) {
                seen.add(v);
            }
        }
        out.addAll(seen);
        return out;
    }

    /** 显式映射解析：来自 mapping 参数与用户指令里的「svc=group/proj」字样 */
    static Map<String, String> parseMappings(String mappingArg, String userCommand) {
        Map<String, String> out = new LinkedHashMap<>();
        if (mappingArg != null && !mappingArg.isBlank()) {
            for (String part : mappingArg.split("[,，;；\\s]+")) {
                Matcher m = MAPPING.matcher(part.trim());
                if (m.matches()) {
                    out.put(m.group(1), m.group(2));
                }
            }
        }
        if (userCommand != null) {
            Matcher m = MAPPING.matcher(userCommand);
            while (m.find()) {
                out.putIfAbsent(m.group(1), m.group(2));
            }
        }
        return out;
    }

    private static int clampHours(String s) {
        int h = defaultParse(s);
        if (h <= 0) {
            return 48;
        }
        return clamp(h, 1, 168);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    private static int defaultParse(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String resolveTraceId(String explicit, String userCommand) {
        String source = explicit != null && !explicit.isBlank() ? explicit : userCommand;
        if (source == null) {
            return null;
        }
        Matcher m = TRACE_ID.matcher(source);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private static List<String> servicesOf(List<TraceSpan> spans) {
        Set<String> set = new LinkedHashSet<>();
        for (TraceSpan s : spans) {
            if (s.service() != null && !s.service().isBlank()) {
                set.add(s.service());
            }
        }
        return new ArrayList<>(set);
    }

    private static OffsetDateTime earliest(List<TraceSpan> spans) {
        OffsetDateTime min = null;
        for (TraceSpan s : spans) {
            OffsetDateTime t = parseIso(s.timestamp());
            if (t != null && (min == null || t.isBefore(min))) {
                min = t;
            }
        }
        return min == null ? OffsetDateTime.now() : min;
    }

    private static OffsetDateTime parseIso(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(ts.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /** 同一项目被多个服务映射时只拉一次；超过上限截断（保序） */
    private static List<Mapped> dedupeByProject(List<Mapped> mapped, int cap) {
        Set<Long> seen = new LinkedHashSet<>();
        List<Mapped> out = new ArrayList<>();
        for (Mapped m : mapped) {
            if (seen.add(m.projectId())) {
                out.add(m);
            }
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    private static String joinReasons(Map<String, Object> item) {
        Object r = item.get("reasons");
        if (r instanceof List<?> list) {
            return String.join("、", list.stream().map(String::valueOf).toList());
        }
        return "";
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
