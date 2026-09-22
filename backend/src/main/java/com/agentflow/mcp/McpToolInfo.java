package com.agentflow.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 MCP 服务端 tools/list 拿到的一个工具定义。
 *
 * <p>{@code argsHint} 在解析时就渲染好并随记录一起存库，不放在 getter 里现算：
 * 它会被注入每一次规划 prompt，而 inputSchema 是任意深度的 JSON，每次现解析纯属浪费。
 *
 * @param name         远端工具名（原样保留，调用时要用它）
 * @param description  远端描述，直接作为 AgentFlow 工具描述注入规划 prompt
 * @param inputSchema  JSON Schema 原文，落库留档
 * @param requiredArgs 必填参数名
 * @param readOnlyHint MCP 规范里的 {@code annotations.readOnlyHint}；服务端没给就是 null
 *                     （null 与 false 语义不同：null 表示未知，是否要人工确认由服务端级配置决定）
 * @param argsHint     渲染好的参数提示，如 {@code {"query": "string 查询语句，必填"}}
 */
public record McpToolInfo(String name, String description, String inputSchema,
                          List<String> requiredArgs, Boolean readOnlyHint, String argsHint) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * 参数提示的长度上限。这些文本会随每次规划注入 prompt，而远端工具的 schema 描述可能极其冗长——
     * 实测 SigNoz MCP 的单个工具光参数说明就接近 4KB，38 个工具加起来会把 prompt 撑到几十 KB，
     * 既烧额度又淹没其它工具。所以这里对「参数个数、单个描述、整体长度」三层都设上限：
     * 保留能帮模型填对参数的信息，丢掉大段的用法说明（模型看工具描述已足够判断要不要调用）。
     */
    private static final int MAX_PROPERTIES = 12;
    private static final int MAX_ARG_DESC_CHARS = 60;
    private static final int MAX_HINT_CHARS = 600;
    /** 尾部「…共 N 个参数」的预留长度，避免这句关键信息被长度上限挤掉 */
    private static final int TAIL_BUDGET = 24;

    /** 从 tools/list 的单个工具节点解析；缺 name 的节点无法调用，直接丢弃 */
    public static McpToolInfo fromJson(JsonNode node) {
        if (node == null || node.path("name").asText("").isBlank()) {
            return null;
        }
        JsonNode schema = node.path("inputSchema");
        Map<String, String> properties = new LinkedHashMap<>();
        JsonNode props = schema.path("properties");
        if (props.isObject()) {
            props.fields().forEachRemaining(e -> properties.put(e.getKey(), describe(e.getValue())));
        }
        List<String> required = new ArrayList<>();
        JsonNode req = schema.path("required");
        if (req.isArray()) {
            for (JsonNode r : req) {
                String v = r.asText("");
                if (!v.isBlank()) {
                    required.add(v);
                }
            }
        }
        JsonNode hint = node.path("annotations").path("readOnlyHint");
        Boolean readOnly = hint.isBoolean() ? hint.asBoolean() : null;
        return new McpToolInfo(node.path("name").asText(),
                node.path("description").asText(""),
                schema.isMissingNode() ? "{}" : schema.toString(),
                List.copyOf(required), readOnly,
                renderArgsHint(properties, required));
    }

    /**
     * 参数说明：{@code {"query": "string 查询语句，必填"}}，注入规划 prompt 的可读形态。
     *
     * <p>截断时<b>先给尾部留位置</b>：一旦中途收手，会补上「共 N 个参数」。
     * 「还有多少参数没列出来」是模型判断要不要调用的关键信息，若被长度上限挤掉，
     * 模型会以为工具只接受列出来的那几个参数。
     */
    static String renderArgsHint(Map<String, String> properties, List<String> required) {
        if (properties.isEmpty()) {
            return "{}";
        }
        StringBuilder body = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (shown >= MAX_PROPERTIES) {
                break;
            }
            String piece = (shown > 0 ? ", " : "") + "\"" + e.getKey() + "\": \""
                    + clip(e.getValue(), MAX_ARG_DESC_CHARS)
                    + (required.contains(e.getKey()) ? "，必填" : "，可选") + "\"";
            // 首个参数无条件保留（每项本身已受 MAX_ARG_DESC_CHARS 约束），其余按剩余空间收
            if (shown > 0 && body.length() + piece.length() > MAX_HINT_CHARS - TAIL_BUDGET) {
                break;
            }
            body.append(piece);
            shown++;
        }
        String tail = shown < properties.size() ? ", …共 " + properties.size() + " 个参数" : "";
        return clip("{" + body + tail + "}", MAX_HINT_CHARS + TAIL_BUDGET);
    }

    /** 截断到指定字符数并加省略号；null 安全 */
    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 从落库的 inputSchema 还原参数提示（重启后重新注册工具时用，不必再连服务端） */
    public static String renderArgsHintFromSchema(String inputSchema, List<String> required) {
        Map<String, String> properties = new LinkedHashMap<>();
        try {
            JsonNode props = MAPPER.readTree(inputSchema == null ? "{}" : inputSchema).path("properties");
            if (props.isObject()) {
                props.fields().forEachRemaining(e -> properties.put(e.getKey(), describe(e.getValue())));
            }
        } catch (Exception ignored) {
            // schema 解析不了就退化成空提示：参数最终由 LLM 按工具描述给，不影响调用
        }
        return renderArgsHint(properties, required == null ? List.of() : required);
    }

    private static String describe(JsonNode property) {
        if (property == null || property.isMissingNode()) {
            return "任意值";
        }
        String type = property.path("type").asText("");
        if (type.isBlank() && property.path("properties").isObject()) {
            type = "object";
        }
        String desc = property.path("description").asText("");
        if (type.isBlank()) {
            return desc.isBlank() ? "任意值" : desc;
        }
        return desc.isBlank() ? type : type + " " + desc;
    }
}
