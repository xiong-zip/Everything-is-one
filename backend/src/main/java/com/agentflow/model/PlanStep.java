package com.agentflow.model;

import java.util.List;

/**
 * 执行计划中的一个子任务：kind = tool | think | write。
 * 只描述"做什么"，不携带任何展示数据——执行结果一律由运行期产生。
 */
public record PlanStep(String kind, String tag, String title, ToolCall tool, List<String> lines) {

    public static PlanStep tool(String title, ToolCall tool) {
        return new PlanStep("tool", "工具调用", title, tool, null);
    }

    public static PlanStep think(String title) {
        return new PlanStep("think", "智能分析", title, null, List.of());
    }

    public static PlanStep write(String title) {
        return new PlanStep("write", "内容生成", title, null, null);
    }
}
