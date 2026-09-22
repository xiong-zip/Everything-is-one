package com.agentflow.controller;

import com.agentflow.mcp.McpServer;
import com.agentflow.mcp.McpToolService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务管理：登记服务端、刷新工具清单、查看接进来的工具、开关与删除。
 *
 * <p>新增与刷新刻意<b>分成两步</b>：保存只落配置（不连网，立即返回），
 * 「刷新工具」才去连服务端拉 tools/list。因为 MCP 服务端可能在内网、可能慢、可能暂时不可达，
 * 把连网动作塞进保存会让「先填好配置、回头再连通」这件事做不了。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpToolService service;

    public McpController(McpToolService service) {
        this.service = service;
    }

    /** 服务端列表（含已接进来的工具、最近刷新时间与失败原因） */
    @GetMapping("/servers")
    public Map<String, Object> servers() {
        List<Map<String, Object>> list = service.overview();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("servers", list);
        out.put("total", list.size());
        return out;
    }

    @PostMapping("/servers")
    public Map<String, Object> save(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        // headers 字段「没传」与「传空对象」语义不同：前者保留原有请求头，后者清空。
        // 界面上出于安全不回显令牌值，所以只改地址时不能把请求头一起抹掉。
        Map<String, String> headers = body.containsKey("headers") ? stringMap(body.get("headers")) : null;
        McpServer saved = service.save(
                String.valueOf(body.getOrDefault("name", "")),
                String.valueOf(body.getOrDefault("url", "")),
                headers,
                bool(body.get("confirmAll")),
                bool(body.get("enabled")),
                body.get("originalName") == null ? null : String.valueOf(body.get("originalName")));
        return Map.of("ok", true, "name", saved.name(), "enabled", saved.enabled(),
                "confirmAll", saved.confirmAll());
    }

    @PutMapping("/servers/{name}/enabled")
    public Map<String, Object> setEnabled(@PathVariable("name") String name, @RequestBody Map<String, Object> body) {
        boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "false")));
        service.setEnabled(name, enabled);
        return Map.of("ok", true, "name", name, "enabled", enabled);
    }

    /** 连服务端拉最新工具清单并注册；失败会把原因记在服务端上（面板可见），不影响已有工具 */
    @PostMapping("/servers/{name}/refresh")
    public Map<String, Object> refresh(@PathVariable("name") String name) {
        return service.refresh(name);
    }

    @DeleteMapping("/servers/{name}")
    public Map<String, Object> delete(@PathVariable("name") String name) {
        service.delete(name);
        return Map.of("ok", true, "name", name);
    }

    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null && !String.valueOf(v).isBlank()) {
                out.put(String.valueOf(k).trim(), String.valueOf(v).trim());
            }
        });
        return out;
    }

    /** 三态布尔：字段缺省返回 null（由服务层套默认值），避免把「没传」当成 false */
    private static Boolean bool(Object o) {
        return o == null ? null : Boolean.parseBoolean(String.valueOf(o));
    }
}
