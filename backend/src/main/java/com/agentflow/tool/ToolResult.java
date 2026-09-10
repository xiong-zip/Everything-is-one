package com.agentflow.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果。clarify 非空表示"未直接命中，给出候选让用户选择"：
 * options 每项 {label: 展示文本, action: 选中后要执行的指令}。
 */
public record ToolResult(String resultType, Map<String, Object> result, List<String> list, String summary,
                         Clarify clarify) {

    public record Clarify(String question, List<Map<String, String>> options) {
    }

    public ToolResult(String resultType, Map<String, Object> result, List<String> list, String summary) {
        this(resultType, result, list, summary, null);
    }

    /** 通用提示型结果：工具无法给出有效数据时的如实降级 */
    public static ToolResult note(String message) {
        return new ToolResult("json", Map.of("note", message), null, message);
    }

    /** 未命中但给出候选：正常返回提示 + clarify 选项（前端渲染可选卡） */
    public static ToolResult withClarify(String message, Clarify clarify) {
        return new ToolResult("json", Map.of("note", message), null, message, clarify);
    }
}
