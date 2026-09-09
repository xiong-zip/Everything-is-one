package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 公司内部 GitLab 只读查询工具：项目 / Issue / 合并请求 / 流水线。
 * 凭证走环境变量 GITLAB_URL / GITLAB_TOKEN（见 .env），只做 GET 查询。
 */
@Component
public class GitLabTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GitLabTool.class);

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String token;

    public GitLabTool(ToolHttpClient toolHttpClient,
                      @Value("${agentflow.gitlab.base-url:http://gitlab.zoesoft.com.cn}") String baseUrl,
                      @Value("${agentflow.gitlab.token:${GITLAB_TOKEN:}}") String token) {
        this.restClient = toolHttpClient.restClient();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
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
        return "{\"project\": \"项目名（issues/mrs/pipelines 时必填）\", \"type\": \"mine|projects|issues|mrs|pipelines\", \"day\": \"today|yesterday|week（mine 时可选：今天/昨天/本周的逐条提交记录）\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (token.isEmpty()) {
            return ToolResult.note("未配置 GITLAB_TOKEN，无法访问公司 GitLab（在 .env 中配置后重启即可）");
        }
        try {
            String type = inferType(str(args.get("type")), userCommand);
            String project = str(args.get("project"));

            if ("mine".equals(type)) {
                String day = inferDay(str(args.get("day")), userCommand);
                if (day != null) {
                    return listMineCommits(day);
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
        return !token.isEmpty();
    }

    /**
     * 近 days 天的每日提交数（推送事件 commit_count 聚合），供效能热力图。
     * 分页拉取时间窗内推送事件，返回 [{date: "yyyy-MM-dd", count: n}]（仅非零日）。
     */
    public List<Map<String, Object>> dailyCommitCounts(int days) throws Exception {
        if (token.isEmpty()) {
            return List.of();
        }
        JsonNode me = getJson("/api/v4/user");
        long uid = me.path("id").asLong();
        String after = java.time.LocalDate.now().minusDays(days - 1).toString();
        Map<String, Integer> counts = new TreeMap<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode events = getJson("/api/v4/users/" + uid + "/events?action=pushed&after=" + after
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
                int n = e.path("push_data").path("commit_count").asInt(1);
                counts.merge(date, Math.max(1, n), Integer::sum);
            }
            if (events.size() < 100 || !inWindow) {
                break;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((d, c) -> out.add(Map.of("date", d, "count", c)));
        return out;
    }

    /* ---------- 类型与时间窗推断 ---------- */

    /** day 参数解析：显式参数优先，其次从指令关键词推断；返回 today/yesterday/week/null */
    private String inferDay(String day, String command) {
        if (day != null) {
            String d = day.toLowerCase();
            if (d.contains("week") || d.contains("7")) return "week";
            if (d.contains("yesterday")) return "yesterday";
            if (d.contains("today") || d.contains("day")) return "today";
        }
        if (command == null) return null;
        if (command.contains("周报") || command.contains("本周") || command.contains("这周")
                || command.contains("一周") || command.contains("7天") || command.contains("近7")) {
            return "week";
        }
        if (command.contains("昨天") || command.contains("昨日")) {
            return "yesterday";
        }
        if (command.contains("今天") || command.contains("今日")) {
            return "today";
        }
        return null;
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
     * 项目候选来自推送事件，提交明细来自仓库 commits 接口并按作者过滤；
     * 明细过滤为空时回退到推送事件的提交概要。
     */
    private ToolResult listMineCommits(String day) throws Exception {
        JsonNode me = getJson("/api/v4/user");
        String myName = me.path("name").asText("我");
        String myEmail = me.path("email").asText("");
        String commitEmail = me.path("commit_email").asText("");

        boolean weekly = "week".equals(day);
        boolean yesterday = "yesterday".equals(day);
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate startDate = weekly ? today.minusDays(6) : yesterday ? today.minusDays(1) : today;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        OffsetDateTime since = startDate.atStartOfDay(zone).toOffsetDateTime();
        // yesterday 窗口截止今天 0 点（= 昨天结束）；today/week 截止现在
        OffsetDateTime until = yesterday
                ? today.atStartOfDay(zone).toOffsetDateTime()
                : OffsetDateTime.now(zone);

        // 候选项目：时间窗内有推送事件的仓库
        JsonNode events = getJson("/api/v4/users/" + me.path("id").asLong()
                + "/events?action=pushed&per_page=100");
        Map<Long, List<JsonNode>> pushesByProject = new LinkedHashMap<>();
        for (JsonNode e : events) {
            OffsetDateTime t = parseTime(e.path("created_at").asText());
            long pid = e.path("project_id").asLong();
            if (t == null || pid <= 0 || t.isBefore(since) || t.isAfter(until)) continue;
            pushesByProject.computeIfAbsent(pid, k -> new ArrayList<>()).add(e);
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

        String scope = weekly
                ? "本周（" + startDate.format(java.time.format.DateTimeFormatter.ofPattern("MM.dd"))
                  + "–" + today.format(java.time.format.DateTimeFormatter.ofPattern("MM.dd")) + "）"
                : yesterday ? "昨天" : "今天";
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

    private JsonNode findProject(String keyword) throws Exception {
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

    private JsonNode getJson(String path) throws Exception {
        String body = restClient.get()
                .uri(baseUrl + path)
                .header("PRIVATE-TOKEN", token)
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
