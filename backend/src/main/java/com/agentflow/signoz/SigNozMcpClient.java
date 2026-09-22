package com.agentflow.signoz;

import com.agentflow.mcp.McpClient;
import com.agentflow.tool.ToolHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SigNoz 专用的 MCP 调用入口：把 SigNoz 的领域错误话术加上，协议细节全部交给
 * {@link McpClient}（通用 MCP 客户端，工作台里登记的其它 MCP 服务走同一套实现）。
 *
 * <p>SigNoz 的服务端 tools/call 可无状态直接调用，因此通用客户端那条「先直接调、
 * 失败了才 initialize 重试」的快路径对它零开销；这里也不再自己维护 JSON-RPC 序号与会话。
 */
@Component
public class SigNozMcpClient {

    private final String url;
    private final McpClient client;

    public SigNozMcpClient(ToolHttpClient toolHttpClient,
                           @Value("${agentflow.signoz.mcp-url:}") String url) {
        this.url = url == null ? "" : url.trim();
        this.client = this.url.isEmpty() ? null : new McpClient(toolHttpClient.remoteRestClient(), this.url, Map.of());
    }

    public boolean isConfigured() {
        return client != null;
    }

    /** 当前配置的 MCP 地址，供工具描述与错误提示展示 */
    public String url() {
        return url;
    }

    /**
     * 调用一个 SigNoz MCP 工具，返回其文本载荷（SigNoz 返回的是 JSON 字符串）。
     * 服务不可达、JSON-RPC 报错、工具自身报错都抛异常，由调用方转成对用户友好的提示。
     */
    public String callTool(String name, Map<String, Object> arguments) {
        if (client == null) {
            throw new IllegalStateException("未配置 SigNoz MCP 地址（agentflow.signoz.mcp-url / SIGNOZ_MCP_URL）");
        }
        try {
            return client.callTool(name, arguments);
        } catch (McpClient.McpException ex) {
            // 加上来源前缀：告警与工具提示里同时可能出现多个 MCP 服务，得让用户看清是哪一个
            throw new IllegalStateException("SigNoz " + ex.getMessage(), ex);
        }
    }
}
