package com.agentflow.tool;

import java.util.List;
import java.util.Map;

public record ToolResult(String resultType, Map<String, Object> result, List<String> list, String summary) {

    /** 通用提示型结果：工具无法给出有效数据时的如实降级 */
    public static ToolResult note(String message) {
        return new ToolResult("json", Map.of("note", message), null, message);
    }
}
