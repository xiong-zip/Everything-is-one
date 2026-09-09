package com.agentflow.controller;

import com.agentflow.engine.AgentEngine;
import com.agentflow.engine.RunStore;
import com.agentflow.llm.LlmClient;
import com.agentflow.model.PlanConfirmRequest;
import com.agentflow.model.RunRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    /** 示例建议：仅作输入灵感提示，不限制可执行的指令 */
    private static final List<Map<String, String>> EXAMPLES = List.of(
            Map.of("command", "根据我的 GitLab 提交记录生成今天的工作日报", "short", "GitLab 日报"),
            Map.of("command", "根据我的 GitLab 提交记录生成本周的工作周报", "short", "GitLab 周报"),
            Map.of("command", "帮我查厦门今天天气，然后生成一段朋友圈文案", "short", "天气 + 文案"),
            Map.of("command", "查一下贵州茅台今天的股价，写一段给领导的汇报", "short", "股价 + 汇报"),
            Map.of("command", "帮我写一封调休假的请假邮件", "short", "写邮件"));

    private final AgentEngine engine;
    private final LlmClient llmClient;
    private final RunStore runStore;

    public AgentController(AgentEngine engine, LlmClient llmClient, RunStore runStore) {
        this.engine = engine;
        this.llmClient = llmClient;
        this.runStore = runStore;
    }

    @PostMapping("/run")
    public Map<String, String> run(@RequestBody RunRequest request) {
        if (request == null || request.command() == null || request.command().isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        String taskId = engine.start(request.command().trim(), request.history(), request.mode());
        return Map.of("taskId", taskId);
    }

    /** confirm 模式：前端确认/编辑后的计划回传，引擎继续执行 */
    @PostMapping("/run/{taskId}/confirm")
    public Map<String, String> confirm(@PathVariable("taskId") String taskId,
                                       @RequestBody PlanConfirmRequest request) {
        if (!engine.confirm(taskId, request == null ? null : request.steps())) {
            throw new IllegalArgumentException("任务不存在或已结束");
        }
        return Map.of("ok", "confirmed");
    }

    /** afterSeq：调用方已收到的最大事件序号，断线重连时只补发缺失部分；-1（默认）从头补发 */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("taskId") String taskId,
                             @RequestParam(name = "afterSeq", defaultValue = "-1") int afterSeq) {
        SseEmitter emitter = engine.stream(taskId, afterSeq);
        if (emitter == null) {
            throw new IllegalArgumentException("任务不存在或已过期，请重新提交");
        }
        return emitter;
    }

    /** 手动停止：引擎在最近的检查点收尾，已产出的事件仍保留在历史中 */
    @PostMapping("/cancel/{taskId}")
    public Map<String, String> cancel(@PathVariable("taskId") String taskId) {
        if (!engine.cancel(taskId)) {
            throw new IllegalArgumentException("任务不存在或已结束");
        }
        return Map.of("ok", "cancelled");
    }

    /* ---------- 任务历史与回放 ---------- */

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(name = "keyword", required = false) String keyword) {
        return runStore.listRuns(limit, keyword);
    }

    /** 单次运行的完整事件流：前端按序重发即可原样回放 */
    @GetMapping("/history/{id}")
    public Map<String, Object> historyRun(@PathVariable("id") long id) {
        Map<String, Object> run = runStore.getRun(id);
        if (run == null) {
            throw new IllegalArgumentException("历史记录不存在");
        }
        return run;
    }

    @DeleteMapping("/history/{id}")
    public Map<String, String> deleteHistoryRun(@PathVariable("id") long id) {
        runStore.deleteRun(id);
        return Map.of("ok", "deleted");
    }

    @DeleteMapping("/history")
    public Map<String, String> clearHistory() {
        runStore.clearAll();
        return Map.of("ok", "cleared");
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
}
