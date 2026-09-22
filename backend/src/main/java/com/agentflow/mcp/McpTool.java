package com.agentflow.mcp;

import com.agentflow.tool.ResponseDigest;
import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 MCP 服务端的一个远端工具适配成 AgentFlow 工具。
 *
 * <p>本地名是 {@code 服务端名.远端工具名}（如 {@code grafana.query_loki}），与内置工具的
 * {@code gitlab.query} / {@code k8s.query} 命名保持一致——规划器看到的是一份同构的工具清单，
 * 不需要知道哪些是内置的、哪些是远端接进来的。这也是「接新系统的边际成本从写一个 Tool 类
 * 降到填一个地址」的落点。
 */
public class McpTool implements Tool {

    private final McpServer server;
    private final McpServerStore.StoredTool tool;
    private final McpClientFactory clients;

    public McpTool(McpServer server, McpServerStore.StoredTool tool, McpClientFactory clients) {
        this.server = server;
        this.tool = tool;
        this.clients = clients;
    }

    /**
     * 本地工具名。远端工具名里可能出现点号等不适合作为注册名的字符，统一替换掉；
     * 调用远端时仍用原始名（{@link #remoteName()}），两者不能混。
     */
    public static String localName(String serverName, String remoteName) {
        return serverName + "." + (remoteName == null ? "" : remoteName.replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    public String remoteName() {
        return tool.name();
    }

    @Override
    public String name() {
        return localName(server.name(), tool.name());
    }

    /** 远端工具描述的长度上限：远端常常附带整篇用法文档，而它会注入每次规划 prompt */
    private static final int MAX_DESCRIPTION_CHARS = 200;

    @Override
    public String description() {
        String desc = tool.description() == null || tool.description().isBlank()
                ? "（远端未提供说明）" : tool.description();
        if (desc.length() > MAX_DESCRIPTION_CHARS) {
            desc = desc.substring(0, MAX_DESCRIPTION_CHARS) + "…";
        }
        return desc + "（来自 MCP 服务 " + server.name() + "）";
    }

    @Override
    public String argsHint() {
        return McpToolInfo.renderArgsHintFromSchema(tool.inputSchema(), tool.requiredArgs());
    }

    @Override
    public boolean requiresConfirm() {
        return server.requiresConfirm(tool.readOnlyHint());
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        Map<String, Object> arguments = args == null ? Map.of() : args;
        List<String> missing = new ArrayList<>();
        for (String r : tool.requiredArgs()) {
            Object v = arguments.get(r);
            if (v == null || String.valueOf(v).isBlank()) {
                missing.add(r);
            }
        }
        if (!missing.isEmpty()) {
            // 参数不全就发出去只会拿到一个远端报错，不如直接在本地说清楚缺什么
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("missing", missing);
            result.put("argsHint", argsHint());
            return ToolResult.note("调用 " + name() + " 缺少必填参数：" + String.join("、", missing)
                    + "。参数要求：" + argsHint());
        }
        try {
            String text = clients.forServer(server).callTool(remoteName(), arguments);
            return ResponseDigest.summarize(name(), text);
        } catch (Exception ex) {
            return ToolResult.note("调用 MCP 工具 " + name() + " 失败：" + ex.getMessage());
        }
    }
}
