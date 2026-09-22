package com.agentflow.controller;

import com.agentflow.llm.LlmUsageService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * LLM 成本与耗时看板的数据接口（工作台「效能与成本」面板）。
 *
 * <p>回答三类问题：过去 N 小时总共花了多少 token、耗时多少（totals）；
 * 时间与额度花在哪个阶段/哪个模型（byPurpose / byModel / hourly）；
 * 哪个任务最贵、某次任务各阶段怎么分布（topTasks / tasks/{id}）。
 */
@RestController
@RequestMapping("/api/llm/usage")
public class LlmUsageController {

    private final LlmUsageService service;

    public LlmUsageController(LlmUsageService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> overview(@RequestParam(defaultValue = "24") int hours) {
        return service.overview(Math.max(1, Math.min(hours, 24 * 30)));
    }

    /** 某次任务的分阶段明细 */
    @GetMapping("/tasks/{taskId}")
    public List<Map<String, Object>> taskBreakdown(@PathVariable("taskId") String taskId) {
        return service.taskBreakdown(taskId);
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        return Map.of("ok", true, "cleared", service.clear());
    }
}
