package com.agentflow.model;

import java.util.List;

/**
 * 执行计划中的一个子任务：kind = tool | think | write。
 * 只描述"做什么"，不携带任何展示数据——执行结果一律由运行期产生。
 * group：并行分组，同 group 的连续 tool 步并行执行（0 = 串行）；
 * skip：确认模式下用户勾选跳过的步骤标记。
 */
public record PlanStep(String kind, String tag, String title, ToolCall tool, List<String> lines,
                       int group, boolean skip) {

    public static PlanStep tool(String title, ToolCall tool) {
        return new PlanStep("tool", "工具调用", title, tool, null, 0, false);
    }

    public static PlanStep think(String title) {
        return new PlanStep("think", "智能分析", title, null, List.of(), 0, false);
    }

    public static PlanStep write(String title) {
        return new PlanStep("write", "内容生成", title, null, null, 0, false);
    }

    public PlanStep withFlags(int group, boolean skip) {
        return new PlanStep(kind, tag, title, tool, lines, group, skip);
    }
}
