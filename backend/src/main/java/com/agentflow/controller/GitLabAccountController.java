package com.agentflow.controller;

import com.agentflow.tool.GitLabAccount;
import com.agentflow.tool.GitLabAccountStore;
import com.agentflow.tool.ToolHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitLab 账户管理：保存 / 切换 / 删除 Access Token（SQLite），
 * 前端「GitLab 账户」抽屉维护；保存后立即生效（无需重启）。
 */
@RestController
@RequestMapping("/api/gitlab/accounts")
public class GitLabAccountController {

    private final GitLabAccountStore store;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public GitLabAccountController(GitLabAccountStore store, ToolHttpClient toolHttpClient,
                                   @Value("${agentflow.gitlab.base-url:http://gitlab.zoesoft.com.cn}") String baseUrl) {
        this.store = store;
        this.restClient = toolHttpClient.restClient();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accounts", store.listSafe());
        String active = store.effectiveActive();
        out.put("active", active == null ? "" : active);
        return out;
    }

    /** 保存（按 name 覆盖）；token 留空表示编辑时沿用原 token */
    @PostMapping
    public Map<String, String> save(@RequestBody AccountRequest req) {
        if (req == null || isBlank(req.name)) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String name = req.name().trim();
        if (!name.matches("[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1,32}")) {
            throw new IllegalArgumentException("账户名仅限中文/字母/数字/下划线/中划线，最长 32 位");
        }
        GitLabAccount existing = store.find(name);
        String token = isBlank(req.token) ? (existing == null ? "" : existing.token()) : req.token().trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Access Token 不能为空");
        }
        // 保存前就没有可用账户时（如第一个账户），新账户自动设为默认；已有默认则不打扰
        boolean hadActive = store.effectiveActive() != null;
        if (!store.save(new GitLabAccount(name, token, existing == null ? null : existing.createdAt()))) {
            throw new IllegalArgumentException("保存失败，请检查后重试");
        }
        if (!hadActive) {
            store.setActive(name);
        }
        return Map.of("ok", "saved");
    }

    /** 设为当前使用的账户；name 传空表示取消默认（只剩一个账户时它自动生效） */
    @PutMapping("/active")
    public Map<String, String> setActive(@RequestBody Map<String, String> body) {
        String name = body == null ? "" : body.getOrDefault("name", "");
        if (!name.isBlank() && store.find(name) == null) {
            throw new IllegalArgumentException("账户不存在：" + name);
        }
        store.setActive(name.isBlank() ? null : name);
        return Map.of("ok", name.isBlank() ? "cleared" : name);
    }

    @DeleteMapping("/{name}")
    public Map<String, String> delete(@PathVariable("name") String name) {
        if (!store.delete(name)) {
            throw new IllegalArgumentException("账户不存在：" + name);
        }
        return Map.of("ok", "deleted");
    }

    /** 测试连接：用该账户 token 调 /api/v4/user，验证有效性并显示对应身份 */
    @PostMapping("/{name}/test")
    public Map<String, Object> test(@PathVariable("name") String name) {
        GitLabAccount acc = store.find(name);
        if (acc == null) {
            throw new IllegalArgumentException("账户不存在：" + name);
        }
        try {
            String body = restClient.get()
                    .uri(baseUrl + "/api/v4/user")
                    .header("PRIVATE-TOKEN", acc.token())
                    .retrieve()
                    .body(String.class);
            JsonNode user = mapper.readTree(body == null ? "{}" : body);
            String username = user.path("username").asText("");
            String display = user.path("name").asText(username);
            return Map.of("ok", true, "message",
                    "连接成功，身份：" + display + (username.isBlank() ? "" : "（@" + username + "）"));
        } catch (HttpStatusCodeException ex) {
            boolean auth = ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403;
            return Map.of("ok", false, "message",
                    "连接失败：" + (auth ? "Token 无效、已过期或权限不足" : "HTTP " + ex.getStatusCode().value()));
        } catch (Exception ex) {
            return Map.of("ok", false, "message",
                    "连接失败：" + (ex.getMessage() == null ? "无法访问 " + baseUrl : ex.getMessage()));
        }
    }

    public record AccountRequest(String name, String token) {
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
