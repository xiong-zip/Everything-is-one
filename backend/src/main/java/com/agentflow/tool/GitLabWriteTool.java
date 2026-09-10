package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 公司内部 GitLab 写操作工具：创建 Issue / Issue 评论 / MR 评论。
 * 只提供三类低风险动作（无删除、无合并、无推送），requiresConfirm=true，
 * 引擎会强制走人工确认流程后才执行。
 */
@Component
public class GitLabWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GitLabWriteTool.class);

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String envToken;
    private final GitLabAccountStore accountStore;

    public GitLabWriteTool(ToolHttpClient toolHttpClient,
                           GitLabAccountStore accountStore,
                           @Value("${agentflow.gitlab.base-url:http://gitlab.zoesoft.com.cn}") String baseUrl,
                           @Value("${agentflow.gitlab.token:${GITLAB_TOKEN:}}") String token) {
        this.restClient = toolHttpClient.restClient();
        this.accountStore = accountStore;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.envToken = token == null ? "" : token.trim();
    }

    /** 当前生效 token：「GitLab 账户」配置的账户优先，未配置时回退 .env 的 GITLAB_TOKEN */
    private String currentToken() {
        String t = accountStore.activeToken();
        return t == null || t.isBlank() ? envToken : t.trim();
    }

    @Override
    public String name() {
        return "gitlab.action";
    }

    @Override
    public String description() {
        return "公司 GitLab 写操作（执行前必须经用户确认）：创建 Issue（op=issue.create，需 project+title，可选 description）、"
                + "给 Issue 添加评论（op=issue.comment，需 project+iid+body）、给合并请求添加评论（op=mr.comment，需 project+iid+body）";
    }

    @Override
    public String argsHint() {
        return "{\"op\": \"issue.create|issue.comment|mr.comment\", \"project\": \"项目名\", \"title\": \"Issue 标题（issue.create 必填）\", "
                + "\"description\": \"Issue 描述（可选）\", \"iid\": \"Issue/MR 编号（评论必填）\", \"body\": \"评论内容（评论必填）\"}";
    }

    @Override
    public boolean requiresConfirm() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (currentToken().isEmpty()) {
            return ToolResult.note("未配置 GitLab Access Token（右上角「工作台 → GitLab 账户」添加）");
        }
        String op = str(args.get("op"));
        String project = str(args.get("project"));
        if (op == null || project == null) {
            return ToolResult.note("gitlab.action 需要 op 与 project 参数");
        }
        try {
            JsonNode proj = findProject(project);
            if (proj == null) {
                return ToolResult.note("GitLab 上未找到项目「" + project + "」，可写完整路径或换个关键词");
            }
            long pid = proj.path("id").asLong();
            String name = proj.path("name_with_namespace").asText(proj.path("name").asText());
            return switch (op.toLowerCase()) {
                case "issue.create" -> createIssue(pid, name, args);
                case "issue.comment" -> addNote(pid, name, args, false);
                case "mr.comment" -> addNote(pid, name, args, true);
                default -> ToolResult.note("不支持的写操作：" + op + "（仅 issue.create / issue.comment / mr.comment）");
            };
        } catch (IllegalArgumentException ex) {
            return ToolResult.note(ex.getMessage());
        } catch (Exception ex) {
            log.warn("GitLab 写操作失败: {}", ex.getMessage());
            return ToolResult.note("GitLab 写操作失败：" + ex.getMessage());
        }
    }

    private ToolResult createIssue(long pid, String project, Map<String, Object> args) throws Exception {
        String title = str(args.get("title"));
        if (title == null) {
            throw new IllegalArgumentException("issue.create 需要 title 参数");
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", title);
        String desc = str(args.get("description"));
        if (desc != null) {
            body.put("description", desc);
        }
        JsonNode created = postJson("/api/v4/projects/" + pid + "/issues", body);
        return ToolResult.note("已在 " + project + " 创建 Issue #" + created.path("iid").asInt()
                + "「" + created.path("title").asText("") + "」：" + created.path("web_url").asText(""));
    }

    private ToolResult addNote(long pid, String project, Map<String, Object> args, boolean mr) throws Exception {
        String iid = str(args.get("iid"));
        String comment = str(args.get("body"));
        if (iid == null || comment == null) {
            throw new IllegalArgumentException((mr ? "mr.comment" : "issue.comment") + " 需要 iid 与 body 参数");
        }
        String path = mr
                ? "/api/v4/projects/" + pid + "/merge_requests/" + iid + "/notes"
                : "/api/v4/projects/" + pid + "/issues/" + iid + "/notes";
        postJson(path, Map.of("body", comment));
        String kind = mr ? "合并请求 !" : "Issue #";
        return ToolResult.note("已在 " + project + " 的 " + kind + iid + " 添加评论：" + comment);
    }

    private JsonNode findProject(String keyword) throws Exception {
        JsonNode arr = getJson("/api/v4/projects?search=" + enc(keyword) + "&per_page=5&simple=true");
        JsonNode best = null;
        for (JsonNode p : arr) {
            if (best == null) {
                best = p;
            }
            String path = p.path("path_with_namespace").asText("");
            if (path.endsWith("/" + keyword) || path.equals(keyword)) {
                return p;
            }
        }
        return best;
    }

    private JsonNode getJson(String path) throws Exception {
        String body = restClient.get()
                .uri(baseUrl + path)
                .header("PRIVATE-TOKEN", currentToken())
                .retrieve()
                .body(String.class);
        return mapper.readTree(body == null ? "[]" : body);
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        String resp = restClient.post()
                .uri(baseUrl + path)
                .header("PRIVATE-TOKEN", currentToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
        return mapper.readTree(resp == null ? "{}" : resp);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
