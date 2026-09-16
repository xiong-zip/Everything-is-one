package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公司内部 GitLab 只读查询工具：项目 / Issue / 合并请求 / 流水线。
 * 凭证优先用「GitLab 账户」里保存的 Access Token（保存即生效），
 * 未配置账户时回退环境变量 GITLAB_URL / GITLAB_TOKEN（见 .env）。只做 GET 查询。
 */
@Component
public class GitLabTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GitLabTool.class);

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String envToken;
    private final GitLabAccountStore accountStore;

    public GitLabTool(ToolHttpClient toolHttpClient,
                      GitLabAccountStore accountStore,
                      @Value("${agentflow.gitlab.base-url:http://gitlab.zoesoft.com.cn}") String baseUrl,
                      @Value("${agentflow.gitlab.token:${GITLAB_TOKEN:}}") String token) {
        this.restClient = toolHttpClient.restClient();
        this.accountStore = accountStore;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.envToken = token == null ? "" : token.trim();
    }

    /** 当前生效 token：「GitLab 账户」配置的账户优先，未配置时回退 .env 的 GITLAB_TOKEN */
    String currentToken() {
        String t = accountStore.activeToken();
        return t == null || t.isBlank() ? envToken : t.trim();
    }

    /** 当前生效 token 的指纹（仅在内存中用于缓存 key，切换账户后变化即缓存失效） */
    public String tokenFingerprint() {
        String t = currentToken();
        return t.isBlank() ? "none" : String.valueOf(t.hashCode());
    }

    @Override
    public String name() {
        return "gitlab.query";
    }

    @Override
    public String description() {
        return "查询公司内部 GitLab：我的提交记录（type=mine；day=today/week 返回按项目分组的逐条提交，适合生成工作日报/周报）、项目列表、Issue、合并请求（MR）、流水线状态（只读）";
    }

    @Override
    public String argsHint() {
        return "{\"project\": \"项目名（issues/mrs/pipelines 时必填）\", \"type\": \"mine|projects|issues|mrs|pipelines\", "
                + "\"day\": \"today|yesterday|week|lastweek（mine 时可选）\", "
                + "\"since\": \"yyyy-MM-dd（mine 时可选，与 until 搭配）\", \"until\": \"yyyy-MM-dd\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (currentToken().isEmpty()) {
            return ToolResult.note("未配置 GitLab Access Token（右上角「工作台 → GitLab 账户」添加，或在 .env 配置 GITLAB_TOKEN 后重启）");
        }
        try {
            String type = inferType(str(args.get("type")), userCommand);
            String project = str(args.get("project"));

            if ("mine".equals(type)) {
                Window w = windowFrom(args, userCommand);
                if (w != null) {
                    return listMineCommits(w);
                }
                return listMine(args, userCommand);
            }            if ("projects".equals(type) || project == null) {
                return listProjects(userCommand);
            }
            JsonNode proj = findProject(project);
            if (proj == null) {
                return ToolResult.note("GitLab 上未找到项目「" + project + "」，可在指令中写完整路径或换个关键词");
            }
            String name = proj.path("name_with_namespace").asText(proj.path("name").asText());
            return switch (type) {
                case "issues" -> listIssues(proj.path("id").asLong(), name);
                case "mrs" -> listMergeRequests(proj.path("id").asLong(), name);
                default -> listPipelines(proj.path("id").asLong(), name);
            };
        } catch (Exception ex) {
            log.warn("GitLab 查询失败: {}", ex.getMessage());
            return ToolResult.note("GitLab 查询失败：" + ex.getMessage());
        }
    }

    /* ---------- 效能统计 ---------- */

    public boolean isConfigured() {
        return !currentToken().isEmpty();
    }

    /**
     * 近 days 天的每日提交数（推送事件 commit_count 聚合），供效能热力图。
     * 分页拉取时间窗内推送事件，返回 [{date: "yyyy-MM-dd", count: n}]（仅非零日）。
     */
    /**
     * 按天统计贡献数，口径与 GitLab 贡献日历一致：每个事件（push/MR/评论等）计 1 个贡献。
     * 注意不能累加 push 事件的 commit_count：分支同步/整支推送会一次带上大量他人提交，
     * 与 GitLab 页面显示的贡献数差异巨大（实测 4/8：事件数 57 = GitLab 显示，commit_count 累加为 193）。
     */
    public List<Map<String, Object>> dailyCommitCounts(int days) throws Exception {
        if (currentToken().isEmpty()) {
            return List.of();
        }
        JsonNode me = getJson("/api/v4/user");
        long uid = me.path("id").asLong();
        java.time.LocalDate afterDate = java.time.LocalDate.now().minusDays(days - 1);
        String after = afterDate.toString();
        Map<String, Integer> counts = new TreeMap<>();
        // 一年事件量可达数千条（活跃日单日 50+），100 页 × 100 条上限防止截断
        for (int page = 1; page <= 100; page++) {
            // after 严格晚于该日（排除当天），往前多退一天，边界由下方日期比较卡准
            JsonNode events = getJson("/api/v4/users/" + uid + "/events?after=" + afterDate.minusDays(1)
                    + "&per_page=100&sort=desc&page=" + page);
            if (!events.isArray() || events.isEmpty()) {
                break;
            }
            boolean inWindow = false;
            for (JsonNode e : events) {
                OffsetDateTime t = parseTime(e.path("created_at").asText());
                if (t == null) {
                    continue;
                }
                String date = t.toLocalDate().toString();
                if (date.compareTo(after) < 0) {
                    continue;
                }
                inWindow = true;
                counts.merge(date, 1, Integer::sum);
            }
            if (events.size() < 100 || !inWindow) {
                break;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((d, c) -> out.add(Map.of("date", d, "count", c)));
        return out;
    }

    /* ---------- 时间窗解析 ---------- */

    /** 报告/查询时间窗：起止日期（含端点）与中文范围描述 */
    public record Window(LocalDate since, LocalDate until, String scopeZh) {
    }

    /** 时间窗最大跨度（半年），防止误输入超大区间 */
    private static final int MAX_WINDOW_DAYS = 186;

    private static final DateTimeFormatter MD_ZH = DateTimeFormatter.ofPattern("M月d日");
    private static final Pattern P_FULL = Pattern.compile("(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})");
    private static final Pattern P_MD = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
    private static final Pattern P_RANGE = Pattern.compile(
            "(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}|\\d{1,2}月\\d{1,2}[日号]?)\\s*[至到~～—–]\\s*"
                    + "(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}|\\d{1,2}月\\d{1,2}[日号]?)");

    /**
     * 从自然语言解析时间窗，支持：今天/昨天/本周/上周/近N天/近N周/
     * 8月1日、2026-08-01、8月1日至8月15日 等具体日期或区间；
     * 识别不到时间词返回 null。
     */
    public static Window parseWindow(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String c = command.trim();
        LocalDate today = LocalDate.now();

        Matcher range = P_RANGE.matcher(c);
        if (range.find()) {
            LocalDate a = parseDate(range.group(1), today);
            LocalDate b = parseDate(range.group(2), today);
            if (a != null && b != null && !a.isAfter(b)) {
                return window(a, b, null);
            }
        }
        if (c.contains("上周")) {
            LocalDate mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            return window(mon, mon.plusDays(6), "上周");
        }
        Matcher nWeek = Pattern.compile("[近过去]\\s*(\\d+)\\s*周").matcher(c);
        if (nWeek.find()) {
            int n = Math.min(Integer.parseInt(nWeek.group(1)), 26);
            return window(today.minusDays(n * 7L - 1), today, "近" + n + "周");
        }
        Matcher nDay = Pattern.compile("[近过去]\\s*(\\d+)\\s*天").matcher(c);
        if (nDay.find()) {
            int n = Math.min(Integer.parseInt(nDay.group(1)), MAX_WINDOW_DAYS);
            return window(today.minusDays(n - 1L), today, "近" + n + "天");
        }
        if (c.contains("本周") || c.contains("这周") || c.contains("这一周")
                || c.contains("最近一周") || c.contains("近一周")) {
            return window(today.minusDays(6), today, "本周");
        }
        if (c.contains("昨天") || c.contains("昨日")) {
            return window(today.minusDays(1), today.minusDays(1), "昨天");
        }
        if (c.contains("今天") || c.contains("今日")) {
            return window(today, today, "今天");
        }
        // 单个日期：该日全天
        LocalDate single = parseDate(c, today);
        if (single != null) {
            return window(single, single, null);
        }
        return null;
    }

    /** 构造时间窗（含端点），跨度封顶半年；label 为已知时间词（今天/本周/上周…），用于结果描述 */
    public static Window window(LocalDate a, LocalDate b, String label) {
        if (b.isBefore(a)) {
            LocalDate t = a;
            a = b;
            b = t;
        }
        if (b.isAfter(a.plusDays(MAX_WINDOW_DAYS))) {
            b = a.plusDays(MAX_WINDOW_DAYS);
            label = null;
        }
        String aMd = a.format(MD_ZH);
        String bMd = b.format(MD_ZH);
        String scopeZh = a.equals(b)
                ? (label == null ? aMd : label + "（" + aMd + "）")
                : (label == null ? aMd + "–" + bMd : label + "（" + aMd + "–" + bMd + "）");
        return new Window(a, b, scopeZh);
    }

    /** 解析单个日期：yyyy-MM-dd / yyyy.M.d / yyyy年M月D日 / M月D日（缺年补当前年，超前一周视为去年） */
    private static LocalDate parseDate(String s, LocalDate today) {
        if (s == null || s.isBlank()) {
            return null;
        }
        Matcher f = P_FULL.matcher(s);
        if (f.find()) {
            try {
                return LocalDate.of(Integer.parseInt(f.group(1)), Integer.parseInt(f.group(2)), Integer.parseInt(f.group(3)));
            } catch (Exception ignored) {
                return null;
            }
        }
        Matcher md = P_MD.matcher(s);
        if (md.find()) {
            try {
                LocalDate d = LocalDate.of(today.getYear(), Integer.parseInt(md.group(1)), Integer.parseInt(md.group(2)));
                // 年初说 12 月这类未来日期，多半指去年
                return d.isAfter(today.plusDays(7)) ? d.minusYears(1) : d;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** 工具参数/指令 → 时间窗：显式 since/until 优先，其次 day 参数，最后从指令解析 */
    private static Window windowFrom(Map<String, Object> args, String command) {
        LocalDate s = parseIso(str(args.get("since")));
        LocalDate u = parseIso(str(args.get("until")));
        if (s != null && u != null) {
            LocalDate a = s.isBefore(u) ? s : u;
            LocalDate b = s.isBefore(u) ? u : s;
            LocalDate today = LocalDate.now();
            if (a.isAfter(today)) {
                a = today;
            }
            if (b.isAfter(today)) {
                b = today;
            }
            return window(a, b, null);
        }
        String day = str(args.get("day"));
        if (day != null) {
            String d = day.toLowerCase();
            LocalDate today = LocalDate.now();
            if (d.contains("lastweek") || d.contains("last_week")) {
                LocalDate mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
                return window(mon, mon.plusDays(6), "上周");
            }
            if (d.contains("week") || d.contains("7")) {
                return window(today.minusDays(6), today, "本周");
            }
            if (d.contains("yesterday")) {
                return window(today.minusDays(1), today.minusDays(1), "昨天");
            }
            if (d.contains("today") || d.contains("day")) {
                return window(today, today, "今天");
            }
        }
        return parseWindow(command);
    }

    private static LocalDate parseIso(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String inferType(String type, String command) {
        if (type != null) {
            return switch (type.toLowerCase()) {
                case "mine", "my", "me" -> "mine";
                case "issue", "issues" -> "issues";
                case "mr", "mrs", "merge_request", "merge_requests" -> "mrs";
                case "pipeline", "pipelines", "ci" -> "pipelines";
                default -> "projects";
            };
        }
        String c = command == null ? "" : command.toLowerCase();
        boolean aboutMe = command != null && (command.contains("我的") || command.contains("我最近") || command.contains("我提交") || command.contains("我推送"));
        if (aboutMe && !(c.contains("issue") || command.contains("合并请求") || command.contains("流水线"))) {
            return "mine";
        }
        if (c.contains("issue") || command.contains("缺陷") || command.contains("任务单")) return "issues";
        if (c.contains("mr") || command.contains("合并请求") || command.contains("代码评审")) return "mrs";
        if (c.contains("流水线") || c.contains("pipeline") || command.contains("构建")) return "pipelines";
        return "projects";
    }

    /** 我的提交记录：token 用户的推送事件，一条推送一行；支持"今天"筛选 */
    private ToolResult listMine(Map<String, Object> args, String userCommand) throws Exception {
        JsonNode me = getJson("/api/v4/user");
        String myName = me.path("name").asText("我");
        boolean todayOnly = "today".equalsIgnoreCase(String.valueOf(args.getOrDefault("day", "")))
                || (userCommand != null && (userCommand.contains("今天") || userCommand.contains("今日")));

        JsonNode events = getJson("/api/v4/users/" + me.path("id").asLong() + "/events?action=pushed&per_page=100");

        java.time.LocalDate today = java.time.LocalDate.now();
        Map<Long, String> projNames = new LinkedHashMap<>();
        List<String> list = new ArrayList<>();
        JsonNode firstNotToday = null;
        int todayCount = 0;

        for (JsonNode e : events) {
            java.time.OffsetDateTime t = parseTime(e.path("created_at").asText());
            if (t == null) continue;
            boolean isToday = t.toLocalDate().equals(today);
            if (isToday) todayCount++;
            if (todayOnly && !isToday) {
                if (firstNotToday == null) firstNotToday = e;
                continue;
            }

            long pid = e.path("project_id").asLong();
            if (pid <= 0) continue;
            String projName = projNames.computeIfAbsent(pid, id -> {
                try {
                    JsonNode p = getJson("/api/v4/projects/" + id + "?simple=true");
                    return p.path("name_with_namespace").asText(p.path("name").asText());
                } catch (Exception ignored) {
                    return "项目#" + id;
                }
            });

            String commit = e.path("push_data").path("commit_title").asText("");
            int commitCount = e.path("push_data").path("commit_count").asInt(1);
            list.add(t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    + " · " + projName
                    + (commit.isBlank() ? "" : " · " + commit + (commitCount > 1 ? "（含 " + commitCount + " 个提交）" : "")));
            if (list.size() >= 12) break;
        }

        if (list.isEmpty()) {
            if (todayOnly && firstNotToday != null) {
                java.time.OffsetDateTime t = parseTime(firstNotToday.path("created_at").asText());
                return ToolResult.note("「" + myName + "」今天还没有提交记录；最近一次提交在 "
                        + (t == null ? "" : t.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                        + "：" + firstNotToday.path("push_data").path("commit_title").asText(""));
            }
            return ToolResult.note("未查询到「" + myName + "」的提交记录（检查 token 对应用户是否有代码推送）");
        }
        String scope = todayOnly ? "今天" : "最近";
        return new ToolResult("list", null, list,
                myName + " " + scope + "推送 " + (todayOnly ? todayCount : list.size()) + " 次，涉及记录如下");
    }

    /**
     * 日报/周报素材：时间窗内我在各项目的逐条提交（按项目分组）。
     * 项目候选来自推送事件（after 过滤 + 分页），提交明细来自仓库 commits 接口并按作者过滤；
     * 明细过滤为空时回退到推送事件的提交概要。
     */
    private ToolResult listMineCommits(Window w) throws Exception {
        JsonNode me = getJson("/api/v4/user");
        String myName = me.path("name").asText("我");
        String myEmail = me.path("email").asText("");
        String commitEmail = me.path("commit_email").asText("");

        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        OffsetDateTime since = w.since().atStartOfDay(zone).toOffsetDateTime();
        // 区间右端含当天全天：截止到末日的次日 0 点
        OffsetDateTime until = w.until().plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        // 候选项目：时间窗内有推送事件的仓库
        // 注意：这台 GitLab 的 after 是「严格晚于该日」，会把起始日整天排除，因此多退一天，窗口边界由下方精确过滤
        Map<Long, List<JsonNode>> pushesByProject = new LinkedHashMap<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode events = getJson("/api/v4/users/" + me.path("id").asLong()
                    + "/events?action=pushed&after=" + w.since().minusDays(1) + "&per_page=100&sort=desc&page=" + page);
            if (!events.isArray() || events.isEmpty()) {
                break;
            }
            for (JsonNode e : events) {
                OffsetDateTime t = parseTime(e.path("created_at").asText());
                long pid = e.path("project_id").asLong();
                if (t == null || pid <= 0 || t.isBefore(since) || !t.isBefore(until)) {
                    continue;
                }
                pushesByProject.computeIfAbsent(pid, k -> new ArrayList<>()).add(e);
            }
            if (events.size() < 100) {
                break;
            }
        }

        List<String> list = new ArrayList<>();
        int totalCommits = 0;
        int projectCount = 0;
        for (Map.Entry<Long, List<JsonNode>> en : pushesByProject.entrySet()) {
            long pid = en.getKey();
            String projName;
            try {
                JsonNode p = getJson("/api/v4/projects/" + pid + "?simple=true");
                projName = p.path("name").asText(p.path("name_with_namespace").asText("项目#" + pid));
            } catch (Exception ignored) {
                projName = "项目#" + pid;
            }

            List<String> titles = new ArrayList<>();
            try {
                JsonNode commits = getJson("/api/v4/projects/" + pid + "/repository/commits"
                        + "?since=" + enc(since.toString()) + "&until=" + enc(until.toString()) + "&per_page=100");
                for (JsonNode c : commits) {
                    if (isMine(c, myName, myEmail, commitEmail)) {
                        titles.add(fmtTime(c.path("created_at").asText("")) + " · " + c.path("title").asText(""));
                    }
                }
            } catch (Exception ignored) {
                // 仓库不可访问时走推送事件兜底
            }
            if (titles.isEmpty()) {
                for (JsonNode e : en.getValue()) {
                    String title = e.path("push_data").path("commit_title").asText("");
                    int cnt = e.path("push_data").path("commit_count").asInt(1);
                    if (!title.isBlank()) {
                        titles.add(fmtTime(e.path("created_at").asText()) + " · " + title
                                + (cnt > 1 ? "（含 " + cnt + " 个提交）" : ""));
                    }
                }
            }
            if (!titles.isEmpty()) {
                projectCount++;
                totalCommits += titles.size();
                list.add("【" + shortProjectName(projName) + "】" + titles.size() + " 个提交");
                list.addAll(titles);
            }
        }

        String scope = w.scopeZh();
        if (list.isEmpty()) {
            return ToolResult.note("「" + myName + "」" + scope + "还没有提交记录，无日报/周报素材");
        }
        return new ToolResult("list", null, list,
                myName + " " + scope + "共提交 " + totalCommits + " 次，涉及 " + projectCount + " 个项目");
    }

    /** 提交是否出自 token 用户：作者名或提交邮箱任一匹配 */
    private static boolean isMine(JsonNode commit, String myName, String myEmail, String commitEmail) {
        String author = commit.path("author_name").asText("");
        if (!myName.isBlank() && myName.equals(author)) {
            return true;
        }
        String email = commit.path("author_email").asText("");
        return !email.isBlank() && (email.equals(myEmail) || email.equals(commitEmail));
    }

    /** "组名 / 项目名" 只保留项目名，报告列表更紧凑 */
    private static String shortProjectName(String nameWithNamespace) {
        if (nameWithNamespace == null) return "";
        int idx = nameWithNamespace.lastIndexOf('/');
        return idx >= 0 ? nameWithNamespace.substring(idx + 1).trim() : nameWithNamespace;
    }

    private static java.time.OffsetDateTime parseTime(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- API 查询 ---------- */

    private ToolResult listProjects(String command) throws Exception {
        JsonNode arr = getJson("/api/v4/projects?per_page=6&order_by=last_activity_at&simple=true");
        List<String> list = new ArrayList<>();
        for (JsonNode p : arr) {
            list.add(p.path("name_with_namespace").asText(p.path("name").asText())
                    + " · 最近活跃 " + fmtTime(p.path("last_activity_at").asText()));
        }
        if (list.isEmpty()) {
            return ToolResult.note("GitLab 上未查询到可见项目（检查 token 权限）");
        }
        return new ToolResult("list", null, list, "最近活跃的 " + list.size() + " 个 GitLab 项目");
    }

    /** 项目搜索原始结果（最多 5 个），供服务映射推断、澄清卡与「服务映射」面板复用 */
    public JsonNode searchProjects(String keyword) throws Exception {
        return getJson("/api/v4/projects?search=" + enc(keyword) + "&per_page=5&simple=true");
    }

    /** 按完整路径（group/project）解析出唯一项目，找不到返回 null */
    public JsonNode projectByPath(String fullPath) throws Exception {
        String p = fullPath.replaceAll("^/+|/+$", "");
        String last = p.substring(p.lastIndexOf('/') + 1);
        JsonNode arr = searchProjects(last);
        for (JsonNode n : arr) {
            if (n.path("path_with_namespace").asText("").equalsIgnoreCase(p)) {
                return n;
            }
        }
        return null;
    }

    /** 时间窗内项目默认分支的提交（最多 100 条），变更关联用；时间边界由 GitLab 按 since/until 过滤 */
    List<JsonNode> fetchProjectCommits(long projectId, OffsetDateTime since, OffsetDateTime until) throws Exception {
        JsonNode commits = getJson("/api/v4/projects/" + projectId + "/repository/commits"
                + "?since=" + enc(since.toString()) + "&until=" + enc(until.toString()) + "&per_page=100");
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode c : commits) {
            out.add(c);
        }
        return out;
    }

    /** 项目最近流水线（最多 N 条），变更关联判断窗口内是否有失败构建用 */
    JsonNode fetchPipelines(long projectId, int limit) throws Exception {
        return getJson("/api/v4/projects/" + projectId + "/pipelines?per_page=" + limit);
    }

    JsonNode findProject(String keyword) throws Exception {
        JsonNode arr = getJson("/api/v4/projects?search=" + enc(keyword) + "&per_page=5&simple=true");
        JsonNode best = null;
        for (JsonNode p : arr) {
            if (best == null) best = p;
            String path = p.path("path_with_namespace").asText("");
            if (path.endsWith("/" + keyword) || path.equals(keyword)) {
                return p;
            }
        }
        return best;
    }

    private ToolResult listIssues(long projectId, String name) throws Exception {
        JsonNode arr = getJson("/api/v4/projects/" + projectId + "/issues?state=opened&per_page=5");
        List<String> list = new ArrayList<>();
        for (JsonNode i : arr) {
            list.add("#" + i.path("iid").asInt() + " " + i.path("title").asText()
                    + " · " + i.path("author").path("name").asText()
                    + (i.path("assignee").has("name") ? " → " + i.path("assignee").path("name").asText() : ""));
        }
        int count = list.size();
        if (count == 0) list.add(name + " 当前没有打开的 Issue");
        return new ToolResult("list", null, list, name + " 打开中的 Issue " + count + " 条");
    }

    private ToolResult listMergeRequests(long projectId, String name) throws Exception {
        JsonNode arr = getJson("/api/v4/projects/" + projectId + "/merge_requests?state=opened&per_page=5");
        List<String> list = new ArrayList<>();
        for (JsonNode m : arr) {
            list.add("!" + m.path("iid").asInt() + " " + m.path("title").asText()
                    + " · " + m.path("author").path("name").asText()
                    + " · " + m.path("source_branch").asText() + " → " + m.path("target_branch").asText());
        }
        int count = list.size();
        if (count == 0) list.add(name + " 当前没有打开的合并请求");
        return new ToolResult("list", null, list, name + " 打开中的 MR " + count + " 个");
    }

    private ToolResult listPipelines(long projectId, String name) throws Exception {
        JsonNode arr = getJson("/api/v4/projects/" + projectId + "/pipelines?per_page=5");
        List<String> list = new ArrayList<>();
        for (JsonNode p : arr) {
            list.add("#" + p.path("id").asInt() + " · " + p.path("ref").asText()
                    + " · " + p.path("status").asText() + " · " + fmtTime(p.path("updated_at").asText()));
        }
        int count = list.size();
        if (count == 0) list.add(name + " 最近没有流水线记录");
        return new ToolResult("list", null, list, name + " 最近流水线 " + count + " 条");
    }

    /* ---------- 基础设施 ---------- */

    JsonNode getJson(String path) throws Exception {
        String body = restClient.get()
                .uri(baseUrl + path)
                .header("PRIVATE-TOKEN", currentToken())
                .retrieve()
                .body(String.class);
        return mapper.readTree(body == null ? "[]" : body);
    }

    private static String fmtTime(String iso) {
        try {
            return OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        } catch (Exception e) {
            return iso == null ? "" : iso;
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
