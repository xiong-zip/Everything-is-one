package com.agentflow.mcp;

import java.util.Map;

/**
 * 一个注册在案的 MCP 服务端。
 *
 * @param name          本地标识，同时用作工具名前缀（如 grafana → grafana.query_loki）
 * @param url           Streamable HTTP 端点
 * @param headers       附加请求头（多数 MCP 服务端靠 Authorization 鉴权）
 * @param confirmAll    该服务端的所有工具是否强制走人工确认。默认 true —— 远端工具的行为未知，
 *                      可能存在发消息、删数据这类副作用，宁可多点一次确认也不要静默执行
 * @param enabled       是否注册其工具
 * @param lastRefreshAt 最近一次成功拉取工具清单的时间
 * @param lastError     最近一次刷新/调用的失败原因（空串表示正常），面板上直接展示便于排查
 */
public record McpServer(String name, String url, Map<String, String> headers, boolean confirmAll,
                        boolean enabled, String createdAt, String lastRefreshAt, String lastError) {

    public McpServer withRefresh(String refreshedAt, String error) {
        return new McpServer(name, url, headers, confirmAll, enabled, createdAt, refreshedAt, error);
    }

    public McpServer withEnabled(boolean on) {
        return new McpServer(name, url, headers, confirmAll, on, createdAt, lastRefreshAt, lastError);
    }

    /**
     * 工具是否必须人工确认。优先看服务端自报的 readOnlyHint（MCP 规范字段）；
     * 服务端没说（null）时按服务端级配置决定：默认确认，用户明确关掉才放行。
     */
    public boolean requiresConfirm(Boolean readOnlyHint) {
        if (confirmAll) {
            return true;
        }
        return readOnlyHint != null && !readOnlyHint;
    }
}
