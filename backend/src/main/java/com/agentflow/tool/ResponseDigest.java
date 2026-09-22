package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部接口响应摘要化：把任意 JSON / 文本压成条数可控的列表交给 LLM。
 *
 * <p>被两类工具共用——OpenAPI 导入的动态工具与 MCP 远端工具，它们都是「不知道对面返回什么」
 * 的场景。规则统一在这里，是因为两边面对的问题完全一样：响应可能很大（撑爆上下文）、
 * 可能是数组、可能是对象、也可能根本不是 JSON，而规划器只看得懂「几行字」。
 */
public final class ResponseDigest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LIST_ITEMS = 12;
    private static final int MAX_ITEM_CHARS = 150;
    private static final int MAX_RAW_CHARS = 800;

    private ResponseDigest() {
    }

    /**
     * @param label 工具名，用于结果说明与降级提示
     * @param body  原始响应体
     */
    public static ToolResult summarize(String label, String body) {
        if (body == null || body.isBlank()) {
            return ToolResult.note(label + " 返回空响应");
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            List<String> list = new ArrayList<>();
            if (root.isArray()) {
                int i = 0;
                for (JsonNode item : root) {
                    if (i++ >= MAX_LIST_ITEMS) {
                        list.add("…（共 " + root.size() + " 条，仅展示前 " + MAX_LIST_ITEMS + " 条）");
                        break;
                    }
                    list.add(truncate(item.isValueNode() ? item.asText() : item.toString(), MAX_ITEM_CHARS));
                }
                return new ToolResult("list", null, list, label + " 返回 " + root.size() + " 条数据");
            }
            if (root.isObject()) {
                root.fields().forEachRemaining(e -> {
                    if (list.size() < MAX_LIST_ITEMS) {
                        JsonNode v = e.getValue();
                        list.add(e.getKey() + ": " + truncate(v.isValueNode() ? v.asText() : v.toString(), MAX_ITEM_CHARS));
                    }
                });
                return new ToolResult("list", null, list, label + " 返回结果");
            }
            return ToolResult.note(truncate(body, MAX_RAW_CHARS));
        } catch (Exception ex) {
            // 不是 JSON（纯文本/HTML/表格）：原样截断，至少让 LLM 看到开头
            return ToolResult.note(truncate(body, MAX_RAW_CHARS));
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
