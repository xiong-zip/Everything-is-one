package com.agentflow.tool.dynamic;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolHttpClient;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行期注册的工具：按配置拼 URL 调用 HTTP 接口（仅 GET），
 * 响应摘要化后交给 LLM。响应体截断，防止大响应撑爆上下文。
 */
public class DynamicTool implements Tool {

    private static final int MAX_LIST_ITEMS = 12;
    private static final int MAX_ITEM_CHARS = 150;
    private static final int MAX_RAW_CHARS = 800;

    private final ToolHttpClient httpClient;
    private final DynamicToolConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public DynamicTool(ToolHttpClient httpClient, DynamicToolConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    public DynamicToolConfig config() {
        return config;
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public String description() {
        return config.description();
    }

    @Override
    public String argsHint() {
        return config.argsHint();
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        String url = buildUrl(args == null ? Map.of() : args);
        if (url == null) {
            return ToolResult.note("调用 " + config.name() + " 缺少必填路径参数，请补全后重试");
        }
        try {
            RestClient restClient = httpClient.restClient();
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return summarize(body == null ? "" : body, url);
        } catch (Exception ex) {
            return ToolResult.note("调用 " + config.name() + " 失败：" + ex.getMessage());
        }
    }

    /** 拼接请求 URL：路径参数替换、查询参数追加；缺路径参数返回 null */
    String buildUrl(Map<String, Object> args) {
        String path = config.pathTemplate();
        for (String p : config.pathParams()) {
            Object v = args.get(p);
            if (v == null || String.valueOf(v).isBlank()) {
                return null;
            }
            path = path.replace("{" + p + "}", enc(String.valueOf(v)));
        }
        StringBuilder url = new StringBuilder(config.baseUrl()).append(path);
        List<String> query = new ArrayList<>();
        for (String p : config.queryParams()) {
            Object v = args.get(p);
            if (v != null && !String.valueOf(v).isBlank()) {
                query.add(p + "=" + enc(String.valueOf(v)));
            }
        }
        if (!query.isEmpty()) {
            url.append("?").append(String.join("&", query));
        }
        return url.toString();
    }

    /** 响应摘要：数组逐条、对象拍平顶层键值，超长截断；非 JSON 原样截断 */
    private ToolResult summarize(String body, String url) {
        if (body.isBlank()) {
            return ToolResult.note(config.name() + " 返回空响应");
        }
        try {
            JsonNode root = mapper.readTree(body);
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
                return new ToolResult("list", null, list, config.name() + " 返回 " + root.size() + " 条数据");
            }
            if (root.isObject()) {
                root.fields().forEachRemaining(e -> {
                    if (list.size() < MAX_LIST_ITEMS) {
                        JsonNode v = e.getValue();
                        list.add(e.getKey() + ": " + truncate(v.isValueNode() ? v.asText() : v.toString(), MAX_ITEM_CHARS));
                    }
                });
                return new ToolResult("list", null, list, config.name() + " 返回结果");
            }
            return ToolResult.note(truncate(body, MAX_RAW_CHARS));
        } catch (Exception ex) {
            return ToolResult.note(truncate(body, MAX_RAW_CHARS));
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
