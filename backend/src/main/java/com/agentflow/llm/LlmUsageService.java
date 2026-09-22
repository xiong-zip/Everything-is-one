package com.agentflow.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 埋点的口径层：把 {@link LlmUsageStore} 的原始统计整理成前端能直接展示的视图。
 *
 * <p><b>关于金额</b>：单价由配置给出（{@code agentflow.llm.price.input/output}，元/百万 token），
 * 默认为 0 表示不显示金额，只显示 token。不内置任何模型价格表是有意的——
 * 各家价格会变、网关还常有自己的折扣，写死一个数只会给出一个看起来精确、实际错误的结论。
 * 配了单价才算钱，且面板上同时标明「按配置单价折算」。
 */
@Service
public class LlmUsageService {

    /** 用途 → 中文标签。这套 key 由 {@link LlmClient} 的 PURPOSE_* 常量定义，两边必须一致 */
    private static final Map<String, String> PURPOSE_LABELS = Map.of(
            LlmClient.PURPOSE_PLAN, "任务规划",
            LlmClient.PURPOSE_REACT, "自主决策",
            LlmClient.PURPOSE_REASON, "推理思考",
            LlmClient.PURPOSE_GENERATE, "内容生成",
            LlmClient.PURPOSE_SUMMARIZE, "结果汇总",
            LlmClient.PURPOSE_MEMORY, "记忆提取",
            LlmClient.PURPOSE_TEST, "连通测试",
            LlmClient.PURPOSE_CHAT, "对话",
            LlmClient.PURPOSE_POSTMORTEM, "故障报告",
            "embedding", "向量嵌入");

    private final LlmUsageStore store;
    private final double priceInput;
    private final double priceOutput;

    public LlmUsageService(LlmUsageStore store,
                           @Value("${agentflow.llm.price.input:0}") double priceInput,
                           @Value("${agentflow.llm.price.output:0}") double priceOutput) {
        this.store = store;
        this.priceInput = Math.max(0, priceInput);
        this.priceOutput = Math.max(0, priceOutput);
    }

    public boolean priceConfigured() {
        return priceInput > 0 || priceOutput > 0;
    }

    public Map<String, Object> overview(int hours) {
        Map<String, Object> totals = store.totals(hours);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", hours);
        out.put("totals", totals);

        long prompt = num(totals.get("promptTokens"));
        long completion = num(totals.get("completionTokens"));
        if (priceConfigured()) {
            double cost = prompt / 1_000_000.0 * priceInput + completion / 1_000_000.0 * priceOutput;
            out.put("cost", Math.round(cost * 10000) / 10000.0);
            out.put("priceConfigured", true);
            out.put("priceInput", priceInput);
            out.put("priceOutput", priceOutput);
        } else {
            out.put("cost", null);
            out.put("priceConfigured", false);
        }

        out.put("byPurpose", withLabels(store.byPurpose(hours), "key"));
        out.put("byModel", store.byModel(hours));
        out.put("hourly", store.hourly(Math.min(hours, 24 * 7)));
        out.put("topTasks", store.topTasks(hours, 8));
        out.put("recent", store.recent(20));
        out.put("records", store.count());
        // 把词表也给前端：流水里的用途是原始 key，前端不该再维护一份同样的映射
        out.put("purposeLabels", PURPOSE_LABELS);
        return out;
    }

    /** 单次任务的分阶段明细（前端展开某个任务时用） */
    public List<Map<String, Object>> taskBreakdown(String taskId) {
        return withLabels(store.taskBreakdown(taskId), "purpose");
    }

    public int clear() {
        return store.clear();
    }

    /** 给分组结果补上中文标签，前端不必再维护一份词表 */
    private static List<Map<String, Object>> withLabels(List<Map<String, Object>> rows, String keyField) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            String key = String.valueOf(row.getOrDefault(keyField, ""));
            m.put("label", PURPOSE_LABELS.getOrDefault(key, key.isEmpty() ? "未标注" : key));
            out.add(m);
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
