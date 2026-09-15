package com.agentflow.controller;

import com.agentflow.llm.LlmClient;
import com.agentflow.llm.LlmConfigStore;
import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型接入：多档案配置（Anthropic 兼容 / OpenAI 兼容），可增删改、一键切换激活，立即生效 */
@RestController
@RequestMapping("/api/llm")
public class LlmConfigController {

    private final LlmClient llmClient;
    private final LlmConfigStore store;

    public LlmConfigController(LlmClient llmClient, LlmConfigStore store) {
        this.llmClient = llmClient;
        this.store = store;
    }

    /** 启动时应用激活档案（没有则维持 .env 默认） */
    @PostConstruct
    void applyStored() {
        Map<String, String> active = store.loadActive();
        if (active != null) {
            try {
                llmClient.applyConfig(active.get("provider"), active.get("baseUrl"), active.get("apiKey"), active.get("model"));
            } catch (Exception ex) {
                // 档案配置非法（如地址失效）：回退默认，避免整个 LLM 不可用
                llmClient.resetToEnv();
            }
        }
    }

    /** 当前生效配置（key 掩码显示；source=profile 表示档案激活中，env 表示 .env 默认） */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> active = store.loadActive();
        out.put("provider", llmClient.getProvider());
        out.put("baseUrl", llmClient.getBaseUrl());
        out.put("model", llmClient.getModel());
        out.put("apiKeyMasked", mask(llmClient.getApiKey()));
        out.put("configured", llmClient.isEnabled());
        out.put("envModel", llmClient.getEnvModel());
        out.put("source", active != null ? "profile" : "env");
        if (active != null) {
            out.put("activeName", active.get("name"));
        }
        return out;
    }

    /** 档案列表 + 激活状态（key 掩码，不回传明文） */
    @GetMapping("/profiles")
    public Map<String, Object> profiles() {
        List<Map<String, Object>> list = new ArrayList<>();
        String activeId = store.activeProfileId();
        for (Map<String, String> p : store.listProfiles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.get("id"));
            m.put("name", p.get("name"));
            m.put("provider", p.get("provider"));
            m.put("baseUrl", p.get("baseUrl"));
            m.put("model", p.get("model"));
            m.put("apiKeyMasked", mask(p.get("apiKey")));
            m.put("active", p.get("id").equals(activeId));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profiles", list);
        out.put("activeId", activeId);
        out.put("current", config());
        return out;
    }

    /** 新建档案（activate=true 时同时激活）；key 必填（新建没有可保持的旧值） */
    @PostMapping("/profiles")
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        String provider = body.getOrDefault("provider", "openai").trim();
        String baseUrl = body.getOrDefault("baseUrl", "").trim().replaceAll("/+$", "");
        String apiKey = body.getOrDefault("apiKey", "").trim();
        String model = body.getOrDefault("model", "").trim();
        if (name.isEmpty() || baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            throw new IllegalArgumentException("名称、请求地址、API Key、模型名都不能为空");
        }
        llmClient.applyConfig(provider, baseUrl, apiKey, model); // 非法直接 400
        String id = store.createProfile(name, provider, baseUrl, apiKey, model);
        if ("true".equals(body.get("activate"))) {
            store.activate(id);
        } else {
            llmClient.resetToEnv(); // 试参数时被临时应用，新建未激活则回退
            applyStored();
        }
        return Map.of("ok", true, "id", id);
    }

    /** 更新档案；key 留空 = 保持不变；更新激活中的档案立即生效 */
    @PutMapping("/profiles/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        String provider = body.getOrDefault("provider", "openai").trim();
        String baseUrl = body.getOrDefault("baseUrl", "").trim().replaceAll("/+$", "");
        String apiKey = body.getOrDefault("apiKey", "").trim();
        String model = body.getOrDefault("model", "").trim();
        if (name.isEmpty() || baseUrl.isEmpty() || model.isEmpty()) {
            throw new IllegalArgumentException("名称、请求地址、模型名都不能为空");
        }
        boolean isActive = id.equals(store.activeProfileId());
        // 激活中的档案更新后立即应用（key 留空则沿用当前生效 key）
        if (isActive) {
            llmClient.applyConfig(provider, baseUrl, apiKey.isEmpty() ? llmClient.getApiKey() : apiKey, model);
        }
        if (!store.updateProfile(id, name, provider, baseUrl, apiKey.isEmpty() ? null : apiKey, model)) {
            throw new IllegalArgumentException("档案不存在");
        }
        return Map.of("ok", true);
    }

    /** 删除档案；删除激活中的档案则回退 .env 默认 */
    @DeleteMapping("/profiles/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        store.deleteProfile(id);
        if (store.activeProfileId() == null) {
            llmClient.resetToEnv();
        }
        return Map.of("ok", true);
    }

    /** 一键切换：激活指定档案并立即生效 */
    @PostMapping("/profiles/{id}/activate")
    public Map<String, Object> activate(@PathVariable String id) {
        Map<String, String> target = null;
        for (Map<String, String> p : store.listProfiles()) {
            if (id.equals(p.get("id"))) {
                target = p;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("档案不存在");
        }
        llmClient.applyConfig(target.get("provider"), target.get("baseUrl"), target.get("apiKey"), target.get("model"));
        store.activate(id);
        return Map.of("ok", true, "model", llmClient.getModel());
    }

    /** 取消激活，回退 .env 默认配置 */
    @DeleteMapping("/active")
    public Map<String, Object> deactivate() {
        store.clearActive();
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
