package com.agentflow.controller;

import com.agentflow.engine.AgentEngine;
import com.agentflow.llm.LlmClient;
import com.agentflow.model.RunRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    /** 示例建议：仅作输入灵感提示，不限制可执行的指令 */
    private static final List<Map<String, String>> EXAMPLES = List.of(
            Map.of("command", "帮我查厦门今天天气，然后生成一段朋友圈文案", "short", "天气 + 朋友圈文案"),
            Map.of("command", "查一下贵州茅台今天的股价，写一段给领导的周报总结", "short", "股价 + 周报总结"),
            Map.of("command", "帮我规划周末两天从上海去杭州的行程，顺便推荐当地美食", "short", "行程 + 美食"),
            Map.of("command", "帮我写一封调休假的请假邮件", "short", "写邮件"));

    private final AgentEngine engine;
    private final LlmClient llmClient;

    public AgentController(AgentEngine engine, LlmClient llmClient) {
        this.engine = engine;
        this.llmClient = llmClient;
    }

    @PostMapping("/run")
    public Map<String, String> run(@RequestBody RunRequest request) {
        if (request == null || request.command() == null || request.command().isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        String taskId = engine.start(request.command().trim());
        return Map.of("taskId", taskId);
    }

    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("taskId") String taskId) {
        SseEmitter emitter = engine.stream(taskId);
        if (emitter == null) {
            throw new IllegalArgumentException("任务不存在或已过期，请重新提交");
        }
        return emitter;
    }

    /** 示例建议（引擎不限于这些指令，任意自然语言任务均可） */
    @GetMapping("/scenarios")
    public List<Map<String, String>> scenarios() {
        return EXAMPLES;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "llmEnabled", llmClient.isEnabled(),
                "model", llmClient.getModel(),
                "baseUrl", llmClient.getBaseUrl());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
