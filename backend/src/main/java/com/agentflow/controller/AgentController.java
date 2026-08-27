package com.agentflow.controller;

import com.agentflow.engine.AgentEngine;
import com.agentflow.engine.ScenarioRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentEngine engine;
    private final ScenarioRegistry registry;

    public AgentController(AgentEngine engine, ScenarioRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    @GetMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestParam("command") String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        return engine.run(command.trim());
    }

    @GetMapping("/scenarios")
    public List<Map<String, String>> scenarios() {
        return registry.all().stream()
                .map(s -> Map.of("command", s.command(), "short", s.shortName()))
                .toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
