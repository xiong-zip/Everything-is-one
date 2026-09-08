package com.agentflow.tool.dynamic;

import com.agentflow.tool.ToolHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 Swagger / OpenAPI 文档，把 GET 操作生成为动态工具配置。
 * 仅导入 GET（只读、低风险）；operationId 缺失时用路径派生命名。
 */
@Component
public class OpenApiImporter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiImporter.class);
    private static final int MAX_TOOLS = 50;

    private final ToolHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenApiImporter(ToolHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** 抓取并解析 OpenAPI 文档，返回可注册的工具配置列表 */
    public List<DynamicToolConfig> importFrom(String specUrl) throws Exception {
        RestClient restClient = httpClient.restClient();
        String body = restClient.get().uri(URI.create(specUrl)).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("OpenAPI 文档为空或不可访问");
        }
        JsonNode root = mapper.readTree(body);
        String baseUrl = resolveBaseUrl(root, specUrl);
        final List<DynamicToolConfig> collected = new ArrayList<>();
        JsonNode paths = root.path("paths");
        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode get = pathEntry.getValue().path("get");
            if (!get.isObject() || get.isEmpty()) {
                return;
            }
            DynamicToolConfig cfg = toConfig(baseUrl, path, get, pathEntry.getValue().path("parameters"));
            if (cfg != null) {
                collected.add(cfg);
            }
        });
        List<DynamicToolConfig> configs = collected.size() > MAX_TOOLS
                ? new ArrayList<>(collected.subList(0, MAX_TOOLS))
                : collected;
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("文档中没有可导入的 GET 操作");
        }
        return configs;
    }

    /** baseUrl 优先取 servers[0].url，缺失时回退到文档地址的 origin */
    private String resolveBaseUrl(JsonNode root, String specUrl) {
        JsonNode servers = root.path("servers");
        if (servers.isArray() && servers.size() > 0) {
            String s = servers.get(0).path("url").asText("").replaceAll("/+$", "");
            if (!s.isBlank()) {
                return s;
            }
        }
        try {
            URI uri = URI.create(specUrl);
            int port = uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法确定服务地址，且文档未提供 servers 配置");
        }
    }

    private DynamicToolConfig toConfig(String baseUrl, String path, JsonNode get, JsonNode pathLevelParams) {
        String opId = get.path("operationId").asText("");
        String name = "openapi." + sanitize(opId.isBlank() ? path : opId);
        if (name.equals("openapi.")) {
            return null;
        }
        String summary = firstNonBlank(get.path("summary").asText(""), get.path("description").asText(""), path);
        Map<String, String> params = new LinkedHashMap<>();
        List<String> pathParams = new ArrayList<>();
        List<String> queryParams = new ArrayList<>();
        collectParams(pathLevelParams, params, pathParams, queryParams);
        collectParams(get.path("parameters"), params, pathParams, queryParams);

        StringBuilder hint = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                hint.append(", ");
            }
            hint.append("\"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
            first = false;
        }
        hint.append("}");
        return new DynamicToolConfig(name, summary + "（OpenAPI 导入）", hint.toString(),
                baseUrl, "GET", path, pathParams, queryParams);
    }

    private void collectParams(JsonNode parameters, Map<String, String> params,
                               List<String> pathParams, List<String> queryParams) {
        if (!parameters.isArray()) {
            return;
        }
        for (JsonNode p : parameters) {
            String name = p.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String desc = firstNonBlank(p.path("description").asText(""), name);
            boolean required = p.path("required").asBoolean(false);
            params.put(name, desc + (required ? "（必填）" : ""));
            if ("path".equals(p.path("in").asText())) {
                pathParams.add(name);
            } else if ("query".equals(p.path("in").asText())) {
                queryParams.add(name);
            }
        }
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        String out = sb.toString().replaceAll("-+$", "");
        return out.length() > 60 ? out.substring(0, 60) : out;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
