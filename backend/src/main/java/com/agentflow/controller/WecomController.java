package com.agentflow.controller;

import com.agentflow.wecom.WecomCliRunner;
import com.agentflow.wecom.WecomStore;
import com.agentflow.wecom.WecomToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企微接入面板：CLI 安装/授权状态、能力清单刷新与查看。
 * 注意：本控制器不接收任何 Secret——扫码授权只能在部署机终端由本人完成。
 */
@RestController
@RequestMapping("/api/wecom")
public class WecomController {

    private final WecomCliRunner cli;
    private final WecomToolService service;
    private final WecomStore store;

    public WecomController(WecomCliRunner cli, WecomToolService service, WecomStore store) {
        this.cli = cli;
        this.service = service;
        this.store = store;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cli.enabled());
        out.put("cliCmd", cli.cliCmd());
        out.put("authStatus", cli.authStatus());
        String version = store.state("cliVersion");
        out.put("cliVersion", version.isEmpty() ? cli.version() : version);
        Map<String, List<Map<String, String>>> caps = store.capabilitiesByService();
        out.put("capabilities", caps);
        out.put("capabilityTotal", caps.values().stream().mapToInt(List::size).sum());
        out.put("lastRefreshAt", store.state("lastRefreshAt"));
        out.put("lastError", store.state("lastError"));
        return out;
    }

    /** 重新解析 wecom-cli 帮助输出，刷新能力清单（要跑几十个子进程，手动触发） */
    @PostMapping("/capabilities/refresh")
    public Map<String, Object> refreshCapabilities() {
        return service.refresh();
    }

    /** 立即重查授权（终端扫码完成后点这个验证） */
    @PostMapping("/auth/check")
    public Map<String, Object> authCheck() {
        return Map.of("authStatus", cli.authStatus());
    }
}
