package com.agentflow.tool;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class ToolHttpClient {

    private final RestClient restClient;

    public ToolHttpClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(proxySelector())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public RestClient restClient() {
        return restClient;
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
