package com.agentflow.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 工具侧出站 HTTP 客户端。
 *
 * <p>分两条超时档位：普通工具（GitLab / Kuboard / 推送 / 动态工具）走短超时，
 * 避免一个卡住的外部接口把引擎线程占满；远程 MCP 服务端走长超时，
 * 因为链路查询这类调用本身就可能跑几十秒，用 10 秒会把正常请求误杀。
 */
@Component
public class ToolHttpClient {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10000;
    private static final int DEFAULT_REMOTE_READ_TIMEOUT_MS = 60000;

    private final RestClient restClient;
    private final RestClient remoteRestClient;

    /** 默认配置（测试与无 Spring 场景用） */
    public ToolHttpClient() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_REMOTE_READ_TIMEOUT_MS);
    }

    @Autowired
    public ToolHttpClient(@Value("${agentflow.tool.connect-timeout-ms:5000}") int connectTimeoutMs,
                          @Value("${agentflow.tool.read-timeout-ms:10000}") int readTimeoutMs,
                          @Value("${agentflow.tool.remote-read-timeout-ms:60000}") int remoteReadTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)))
                .proxy(proxySelector())
                .build();
        this.restClient = build(httpClient, readTimeoutMs);
        this.remoteRestClient = build(httpClient, remoteReadTimeoutMs);
    }

    public RestClient restClient() {
        return restClient;
    }

    /** 远程 MCP 服务端专用：长读超时，避免正常的慢查询被误判为失败 */
    public RestClient remoteRestClient() {
        return remoteRestClient;
    }

    private static RestClient build(HttpClient httpClient, int readTimeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(Math.max(1000, readTimeoutMs)));
        return RestClient.builder().requestFactory(factory).build();
    }

    private static ProxySelector proxySelector() {
        String proxy = firstNonBlank(
                System.getenv("HTTPS_PROXY"),
                System.getenv("https_proxy"),
                System.getenv("HTTP_PROXY"),
                System.getenv("http_proxy"));
        if (proxy == null) {
            return ProxySelector.getDefault();
        }
        try {
            URI uri = URI.create(proxy);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port < 0) {
                return ProxySelector.getDefault();
            }
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (Exception e) {
            return ProxySelector.getDefault();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
