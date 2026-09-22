package com.agentflow.mcp;

import com.agentflow.tool.ToolHttpClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端缓存：同一个服务端复用同一个连接对象。
 *
 * <p>缓存键包含 url 与 headers 的指纹，所以用户在界面上改了地址或令牌后会自然拿到新客户端，
 * 不需要显式失效——旧的留在 map 里无害（一个服务端就几条）。但断线重连的状态（会话 ID）
 * 也随之重置，这正是改配置后想要的效果。
 */
@Component
public class McpClientFactory {

    private final ToolHttpClient httpClient;
    private final Map<String, McpClient> cache = new ConcurrentHashMap<>();

    public McpClientFactory(ToolHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public McpClient forServer(McpServer server) {
        return cache.computeIfAbsent(key(server),
                k -> new McpClient(httpClient.remoteRestClient(), server.url(), server.headers()));
    }

    /** 配置变更后调用：丢弃该服务端的缓存客户端，下次按新配置重建 */
    public void invalidate(String serverName) {
        cache.keySet().removeIf(k -> k.startsWith(serverName + "|"));
    }

    private static String key(McpServer s) {
        Map<String, String> headers = s.headers() == null ? Map.of() : s.headers();
        return s.name() + "|" + s.url() + "|" + headers.hashCode();
    }
}
