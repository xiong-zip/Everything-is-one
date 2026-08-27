package com.agentflow.controller;

import com.agentflow.engine.AgentEngine;
import com.agentflow.engine.ScenarioRegistry;
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

    private final AgentEngine engine;
    private final ScenarioRegistry registry;
    private final LlmClient llmClient;

    public AgentController(AgentEngine engine, ScenarioRegistry registry, LlmClient llmClient) {
        this.engine = engine;
        this.registry = registry;
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

    @GetMapping("/scenarios")
    public List<Map<String, String>> scenarios() {
        return registry.all().stream()
                .map(s -> Map.of("command", s.command(), "short", s.shortName()))
                .toList();
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
