package com.agentflow.controller;

import com.agentflow.llm.LlmClient;
import com.agentflow.llm.LlmConfigStore;
import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 模型接入配置：Anthropic 兼容 / OpenAI 兼容（DeepSeek 等）双协议，保存即生效 */
@RestController
@RequestMapping("/api/llm")
public class LlmConfigController {

    private final LlmClient llmClient;
    private final LlmConfigStore store;

    public LlmConfigController(LlmClient llmClient, LlmConfigStore store) {
        this.llmClient = llmClient;
        this.store = store;
    }

    /** 启动时应用 DB 里的运行时配置（没有则维持 .env 默认） */
    @PostConstruct
    void applyStored() {
        Map<String, String> cfg = store.load();
        if (cfg != null) {
            try {
                llmClient.applyConfig(cfg.get("provider"), cfg.get("baseUrl"), cfg.get("apiKey"), cfg.get("model"));
            } catch (Exception ex) {
                // 存量配置非法（如地址失效）：回退默认，避免整个 LLM 不可用
                llmClient.resetToEnv();
            }
        }
    }

    /** 当前生效配置（key 掩码显示；source=db 表示界面配置覆盖中） */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> db = store.load();
        out.put("provider", llmClient.getProvider());
        out.put("baseUrl", llmClient.getBaseUrl());
        out.put("model", llmClient.getModel());
        out.put("apiKeyMasked", mask(llmClient.getApiKey()));
        out.put("configured", llmClient.isEnabled());
        out.put("source", db != null && db.get("baseUrl") != null
                && db.get("baseUrl").equals(llmClient.getBaseUrl()) ? "db" : "env");
        return out;
    }

    @PutMapping("/config")
    public Map<String, Object> save(@RequestBody Map<String, String> body) {
        String provider = body.getOrDefault("provider", "openai").trim();
        String baseUrl = body.getOrDefault("baseUrl", "").trim();
        String apiKey = body.getOrDefault("apiKey", "").trim();
        String model = body.getOrDefault("model", "").trim();
        // key 留空 = 保持已配置的 key 不变
        if (apiKey.isEmpty()) {
            apiKey = llmClient.getApiKey();
        }
        llmClient.applyConfig(provider, baseUrl, apiKey, model); // 非法直接 400
        store.save(provider, baseUrl.replaceAll("/+$", ""), apiKey, model);
        return Map.of("ok", true);
    }

    /** 恢复 .env 默认配置 */
    @DeleteMapping("/config")
    public Map<String, Object> reset() {
        store.clear();
        llmClient.resetToEnv();
        return Map.of("ok", true);
    }

    /**
     * 连通性测试：带表单参数时直接测该参数（不落库不切换）；
     * 不带参数时测当前生效配置。
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) Map<String, String> body) {
        long start = System.currentTimeMillis();
        try {
            String reply;
            String provider;
            String model;
            if (body != null && !body.getOrDefault("baseUrl", "").isBlank()
                    && !body.getOrDefault("model", "").isBlank()) {
                provider = body.getOrDefault("provider", "openai");
                String apiKey = body.getOrDefault("apiKey", "").trim();
                if (apiKey.isEmpty()) {
                    apiKey = llmClient.getApiKey();
                }
                model = body.get("model");
                reply = llmClient.testCall(provider, body.get("baseUrl"), apiKey, model);
            } else {
                provider = llmClient.getProvider();
                model = llmClient.getModel();
                reply = llmClient.testCall(provider, llmClient.getBaseUrl(), llmClient.getApiKey(), model);
            }
            return Map.of("ok", true, "reply", reply,
                    "ms", System.currentTimeMillis() - start,
                    "provider", provider, "model", model);
        } catch (Exception ex) {
            return Map.of("ok", false, "error", String.valueOf(ex.getMessage()),
                    "ms", System.currentTimeMillis() - start);
        }
    }

    private static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return key.length() > 10 ? key.substring(0, 4) + "****" + key.substring(key.length() - 4) : "****";
    }
}
