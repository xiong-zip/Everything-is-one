package com.agentflow.controller;

import com.agentflow.mcpserver.McpCallStore;
import com.agentflow.mcpserver.McpExposeHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对外 MCP 服务端点（给企微智能机器人等外部 MCP 客户端接入）。
 *
 * <p>鉴权：配置了 Bearer 令牌则必须携带；未配置时只放行本机回环——
 * 这个端点能触发真实工具执行，默认不该对整个内网敞开。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpExposeController {

    public static final String ENDPOINT = "/api/mcp/server";

    private final McpExposeHandler handler;
    private final McpCallStore calls;
    private final boolean enabled;
    private final String token;

    public McpExposeController(McpExposeHandler handler,
                               McpCallStore calls,
                               @Value("${agentflow.mcpserver.enabled:true}") boolean enabled,
                               @Value("${agentflow.mcpserver.token:}") String token) {
        this.handler = handler;
        this.calls = calls;
        this.enabled = enabled;
        this.token = token == null ? "" : token.trim();
    }

    /** MCP Streamable HTTP 的唯一入口：POST 承载全部 JSON-RPC 消息 */
    @PostMapping("/server")
    public ResponseEntity<String> server(HttpServletRequest request, @RequestBody(required = false) String body) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("{\"error\":\"对外 MCP 服务未启用\"}");
        }
        String deny = authDeny(request);
        if (deny != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(deny);
        }
        if (body == null || body.isBlank()) {
            return ResponseEntity.badRequest().body("{\"error\":\"请求体不能为空\"}");
        }
        String client = request.getRemoteAddr();
        String resp = handler.handle(body, client);
        // 通知类消息（initialize 之后的 notifications/* 等）按规范不产生响应体
        return resp == null
                ? ResponseEntity.accepted().build()
                : ResponseEntity.ok().header("Content-Type", "application/json").body(resp);
    }

    /** 面板状态：接入地址、令牌配置、暴露的工具数与最近调用 */
    @GetMapping("/expose/status")
    public Map<String, Object> status(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("endpoint", ENDPOINT);
        out.put("tokenConfigured", !token.isEmpty());
        out.put("loopbackOnly", token.isEmpty());
        out.put("toolCount", handler.exposedTools().size());
        out.put("blacklisted", 0);
        out.put("recentCalls", calls.recent(20));
        return out;
    }

    @DeleteMapping("/expose/calls")
    public Map<String, Object> clearCalls() {
        calls.clear();
        return Map.of("ok", true);
    }

    /** 未授权原因；null 表示放行 */
    private String authDeny(HttpServletRequest request) {
        if (!token.isEmpty()) {
            String auth = request.getHeader("Authorization");
            if (auth == null || !auth.equals("Bearer " + token)) {
                return "{\"error\":\"未授权：需要 Authorization: Bearer <令牌>\"}";
            }
            return null;
        }
        String addr = request.getRemoteAddr();
        if (!isLoopback(addr)) {
            return "{\"error\":\"未配置令牌时仅允许本机访问；内网/公网使用请配置 AGENTFLOW_MCP_SERVER_TOKEN\"}";
        }
        return null;
    }

    static boolean isLoopback(String addr) {
        return addr != null && (addr.equals("127.0.0.1") || addr.equals("0:0:0:0:0:0:0:1")
                || addr.equals("::1") || addr.equals("localhost"));
    }
}
