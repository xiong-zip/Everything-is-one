package com.agentflow.tool.dynamic;

import com.agentflow.tool.ResponseDigest;
import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolHttpClient;
import com.agentflow.tool.ToolResult;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行期注册的工具：按配置拼 URL 调用 HTTP 接口（仅 GET），
 * 响应经 {@link ResponseDigest} 摘要化后交给 LLM（截断防大响应撑爆上下文）。
 */
public class DynamicTool implements Tool {

    private final ToolHttpClient httpClient;
    private final DynamicToolConfig config;

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
            return ResponseDigest.summarize(config.name(), body == null ? "" : body);
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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
