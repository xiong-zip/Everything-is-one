package com.agentflow.signoz;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 链路分析记录：工作台「链路分析」面板的数据来源 */
@RestController
@RequestMapping("/api/signoz")
public class SignozController {

    private final TraceAnalysisStore store;
    private final PostmortemService postmortem;

    public SignozController(TraceAnalysisStore store, PostmortemService postmortem) {
        this.store = store;
        this.postmortem = postmortem;
    }

    /** 分页列表，keyword 可匹配 trace_id / 失败点 / 指纹 / 服务 / 命中案例 */
    @GetMapping("/analyses")
    public Map<String, Object> list(@RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        limit = Math.max(1, Math.min(limit, 500));
        offset = Math.max(0, offset);
        List<TraceAnalysisStore.AnalysisRecord> items = store.list(keyword, limit, offset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", store.count(keyword));
        out.put("limit", limit);
        out.put("offset", offset);
        return out;
    }

    @GetMapping("/analyses/{id}")
    public ResponseEntity<TraceAnalysisStore.AnalysisRecord> detail(@PathVariable long id) {
        TraceAnalysisStore.AnalysisRecord r = store.get(id);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    @DeleteMapping("/analyses/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        return Map.of("deleted", store.delete(id));
    }

    @DeleteMapping("/analyses")
    public Map<String, Object> clear() {
        return Map.of("cleared", store.clear());
    }

    @GetMapping("/stats")
    public TraceAnalysisStore.Stats stats() {
        return store.stats();
    }

    /**
     * 一键故障报告：聚合链路/变更/K8s/告警时间线生成 postmortem（链路面板按钮入口）。
     * 同步执行（含 LLM 归纳时可能要几十秒），前端按需展示进度态。
     */
    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody Map<String, String> body) {
        String traceId = body == null ? null : body.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId 不能为空");
        }
        String timeRange = body.get("timeRange");
        PostmortemService.Report r = postmortem.generate(traceId.trim(), timeRange);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", r.traceId());
        out.put("engine", r.engine());
        out.put("markdown", r.markdown());
        out.put("meta", r.meta());
        return out;
    }
}
