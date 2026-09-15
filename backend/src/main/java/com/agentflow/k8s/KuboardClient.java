package com.agentflow.k8s;

import com.agentflow.tool.ToolHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kuboard 客户端：程序化完成 Kuboard 登录（Dex SSO 三步走），
 * 之后用 KuboardToken Cookie 访问其 K8s API 代理通道 /k8s-api/{cluster}/...。
 *
 * 登录流程（与浏览器行为一致）：
 *  1. GET  /sso/auth?client_id=kuboard-sso&...  → 303 到 /sso/auth/default?req=xxx
 *  2. POST /login/password (JSON)              → 校验用户名密码（可能触发 MFA）
 *  3. POST /sso/auth/default?req=xxx (表单，password 字段为 JSON 字符串) → 303
 *  4. 逐跳跟随 /sso/approval → /callback?code=...（此处 Set-Cookie: KuboardToken）
 *
 * Token 失效时代码会自动重登一次，无需调用方感知。
 */
@Component
public class KuboardClient {

    private static final Logger log = LoggerFactory.getLogger(KuboardClient.class);
    private static final String SSO_ENTRY = "/sso/auth?access_type=offline&client_id=kuboard-sso"
            + "&redirect_uri=%2Fcallback&response_type=code&scope=openid+profile+email+groups"
            + "&state=%2F&connector_id=default";

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String username;
    private final String password;

    /** 登录态缓存：KuboardToken 等 cookie；失效时置空重新登录 */
    private volatile String cookieHeader;

    public KuboardClient(ToolHttpClient toolHttpClient,
                         @Value("${agentflow.kuboard.url:}") String url,
                         @Value("${agentflow.kuboard.username:}") String username,
                         @Value("${agentflow.kuboard.password:}") String password) {
        this.restClient = toolHttpClient.restClient();
        this.baseUrl = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.trim();
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !username.isEmpty() && !password.isEmpty();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 调用 K8s API 代理：path 为原生 K8s API 路径，如 /api/v1/namespaces/dev/pods。
     * 返回响应文本；未配置/不可达/鉴权失败抛异常，由调用方转成对用户友好的提示。
     */
    public String get(String cluster, String path) {
        if (!isConfigured()) {
            throw new IllegalStateException("未配置 Kuboard（agentflow.kuboard.url/username/password 或 KUBOARD_URL/KUBOARD_USERNAME/KUBOARD_PASSWORD）");
        }
        try {
            return doGet(cluster, path);
        } catch (AuthNeededException e) {
            log.info("Kuboard 会话失效，重新登录后重试");
            cookieHeader = null;
            return doGet(cluster, path);
        }
    }

    /** 拉取 Kuboard 上已导入的集群列表（name + 描述 + 版本），供工具提示与校验 */
    public JsonNode listClusters() {
        String body = getRaw("/kuboard-api/kind/KubernetesCluster");
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("解析 Kuboard 集群列表失败：" + ex.getMessage(), ex);
        }
    }

    private String doGet(String cluster, String path) {
        if (cookieHeader == null) {
            login();
        }
        String url = baseUrl + "/k8s-api/" + cluster + path;
        return restClient.get()
                .uri(url)
                .header("Cookie", cookieHeader)
                .header("Accept", "application/json")
                .exchange((req, resp) -> {
                    String text = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    int code = resp.getStatusCode().value();
                    if (code == 401 || code == 403) {
                        throw new AuthNeededException("k8s-api 鉴权失败 HTTP " + code);
                    }
                    // Kuboard 代理在会话失效时返回 HTTP 400 + code 407（未找到当前用户的 token）
                    if (code >= 400) {
                        String hint = text.length() > 300 ? text.substring(0, 300) + "…" : text;
                        throw new IllegalStateException("K8s API 返回 HTTP " + code + "：" + hint);
                    }
                    return text;
                }, false);
    }

    /** 直接访问 Kuboard 自身的 API（非集群代理），同样带登录态 */
    private String getRaw(String path) {
        if (cookieHeader == null) {
            login();
        }
        return restClient.get()
                .uri(baseUrl + path)
                .header("Cookie", cookieHeader)
                .header("Accept", "application/json")
                .exchange((req, resp) -> {
                    String text = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    int code = resp.getStatusCode().value();
                    if (code == 401 || code == 403) {
                        throw new AuthNeededException("kuboard-api 鉴权失败 HTTP " + code);
                    }
                    if (code >= 400) {
                        throw new IllegalStateException("Kuboard API 返回 HTTP " + code);
                    }
                    return text;
                }, false);
    }

    /** 执行完整登录流程，成功后缓存 Cookie 头 */
    private synchronized void login() {
        if (cookieHeader != null) {
            return;
        }
        try {
            // 1. SSO 入口 → 登录表单页（req 会话）
            Map<String, String> cookies = new LinkedHashMap<>();
            String reqPath = redirectLocation(restClient.get().uri(baseUrl + SSO_ENTRY)
                    .header("Accept", "text/html"), cookies);
            if (reqPath == null || !reqPath.contains("req=")) {
                throw new IllegalStateException("Kuboard 登录入口异常（未返回 req 会话）");
            }

            // 2. 校验用户名密码
            Map<String, Object> cred = new LinkedHashMap<>();
            cred.put("username", username);
            cred.put("password", password);
            String checkBody = restClient.post()
                    .uri(baseUrl + "/login/password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cookie", joinCookies(cookies))
                    .body(cred)
                    .retrieve()
                    .body(String.class);
            JsonNode check = mapper.readTree(checkBody == null ? "{}" : checkBody);
            if (!"success".equals(check.path("status").asText(""))) {
                throw new IllegalStateException("Kuboard 用户名或密码错误"
                        + (check.hasNonNull("message") ? "：" + check.path("message").asText() : ""));
            }

            // 3. 以隐藏表单方式提交凭据到 Dex（password 字段是 JSON 字符串，与登录页行为一致）
            String form = "login=" + enc(username)
                    + "&password=" + enc(mapper.writeValueAsString(Map.of("password", password, "passcode", "")));
            String loc = redirectLocation(restClient.post()
                    .uri(baseUrl + reqPath)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Cookie", joinCookies(cookies))
                    .body(form), cookies);

            // 4. 逐跳跟随，直到拿到 KuboardToken（/callback 下发）并落到最终页面
            int hops = 0;
            while (loc != null && hops++ < 8) {
                loc = redirectLocation(restClient.get()
                        .uri(baseUrl + loc)
                        .header("Cookie", joinCookies(cookies))
                        .header("Accept", "text/html"), cookies);
            }
            if (!cookies.containsKey("KuboardToken")) {
                throw new IllegalStateException("Kuboard 登录完成但未获得 KuboardToken");
            }
            cookieHeader = joinCookies(cookies);
            log.info("Kuboard 登录成功（{}）", baseUrl);
        } catch (AuthNeededException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Kuboard 登录失败（" + baseUrl + "）：" + e.getMessage(), e);
        }
    }

    /** 发送请求并读取响应：返回 Location 值（无跳转则 null），过程中收集 Set-Cookie */
    private String redirectLocation(RestClient.RequestHeadersSpec<?> spec, Map<String, String> cookies) {
        return spec.exchange((req, resp) -> {
            for (String sc : resp.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
                int eq = sc.indexOf('=');
                int semi = sc.indexOf(';');
                if (eq > 0) {
                    cookies.put(sc.substring(0, eq).trim(), sc.substring(eq + 1, semi > 0 ? semi : sc.length()).trim());
                }
            }
            try (var is = resp.getBody()) {
                is.readAllBytes();
            }
            return resp.getHeaders().getFirst(HttpHeaders.LOCATION);
        }, false);
    }

    private static String joinCookies(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 内部信号：会话失效需要重登 */
    private static class AuthNeededException extends RuntimeException {
        AuthNeededException(String message) {
            super(message);
        }
    }
}
