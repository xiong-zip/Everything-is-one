package com.agentflow.kb;

import com.agentflow.llm.LlmClient;
import com.agentflow.llm.LlmContext;
import com.agentflow.llm.LlmUsageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 嵌入（Embedding）客户端：OpenAI 兼容协议（POST /v1/embeddings），
 * 供知识库向量检索使用。DeepSeek 仅对话接口不提供 embedding，
 * 因此这里单独配置（任意 OpenAI 兼容网关 / 本地 Ollama 均可）：
 * .env 配 AGENTFLOW_EMBED_URL / AGENTFLOW_EMBED_MODEL / AGENTFLOW_EMBED_KEY。
 *
 * <p>不配置就不启用——检索自动保持关键词模式，这是能力增强而不是依赖。
 * 每次调用照常落 LLM 埋点（用途 embedding），成本看板里能看到向量化的开销。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    /** 单请求文本条数：各家 embeddings 接口普遍限制单批输入数，16 是稳妥值 */
    private static final int BATCH_SIZE = 16;

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmUsageStore usageStore;
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public EmbeddingClient(com.agentflow.tool.ToolHttpClient toolHttpClient,
                           LlmUsageStore usageStore,
                           @Value("${agentflow.kb.embed-url:}") String url,
                           @Value("${agentflow.kb.embed-model:}") String model,
                           @Value("${agentflow.kb.embed-key:}") String apiKey) {
        // toolHttpClient 允许为 null：单测里子类重写 embed() 后不会走到真实 HTTP
        this.restClient = toolHttpClient == null ? null : toolHttpClient.remoteRestClient();
        this.usageStore = usageStore;
        this.baseUrl = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** 是否已配置（地址与模型名都齐） */
    public boolean isEnabled() {
        return !baseUrl.isEmpty() && !model.isEmpty();
    }

    public String model() {
        return model;
    }

    /** 嵌入单条文本；失败抛异常（调用方决定降级） */
    public float[] embedOne(String text) {
        List<float[]> out = embed(List.of(text));
        return out.isEmpty() ? null : out.get(0);
    }

    /**
     * 批量嵌入（按 BATCH_SIZE 分批请求）。返回顺序与输入一致；
     * 任一批失败即抛异常——索引要么完整要么没有，半截向量库比没有更误导。
     */
    public List<float[]> embed(List<String> texts) {
        if (!isEnabled()) {
            throw new IllegalStateException("未配置嵌入模型（.env 设置 AGENTFLOW_EMBED_URL / AGENTFLOW_EMBED_MODEL）");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            out.addAll(call(texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()))));
        }
        return out;
    }

    private List<float[]> call(List<String> batch) {
        long start = System.nanoTime();
        String error = null;
        try {
            String json = restClient.post()
                    .uri(embeddingsUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of("model", model, "input", batch)))
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(json == null ? "{}" : json);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != batch.size()) {
                throw new IllegalStateException("embedding 响应条数（" + data.size() + "）与请求（" + batch.size() + "）不一致");
            }
            // 响应按 index 字段对位，不依赖数组顺序
            float[][] vecs = new float[batch.size()][];
            int dim = 0;
            for (JsonNode item : data) {
                int idx = item.path("index").asInt(-1);
                JsonNode arr = item.path("embedding");
                if (idx < 0 || idx >= vecs.length || !arr.isArray()) {
                    throw new IllegalStateException("embedding 响应格式异常（index=" + idx + "）");
                }
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                vecs[idx] = v;
                dim = Math.max(dim, v.length);
            }
            recordUsage(batch, dim, (System.nanoTime() - start) / 1_000_000L, null);
            return List.of(vecs);
        } catch (Exception ex) {
            error = ex.getMessage();
            recordUsage(batch, 0, (System.nanoTime() - start) / 1_000_000L, error);
            log.warn("embedding 调用失败：{}", error);
            throw new IllegalStateException("embedding 调用失败：" + error, ex);
        }
    }

    /** URL 归一化：给了 /v1 结尾就直接拼 /embeddings，否则补 /v1/embeddings（与 LlmClient 同口径） */
    private String embeddingsUrl() {
        return baseUrl.endsWith("/v1") ? baseUrl + "/embeddings" : baseUrl + "/v1/embeddings";
    }

    /** 埋点：token 按文本长度估算（embeddings 接口普遍按输入 token 计费，无输出 token） */
    private void recordUsage(List<String> batch, int dim, long elapsedMs, String error) {
        try {
            int tokens = 0;
            for (String t : batch) {
                tokens += LlmClient.estimateTokens(t);
            }
            usageStore.record(new LlmUsageStore.LlmCall("embedding", "openai", model,
                    tokens, 0, tokens, true, elapsedMs, error == null, error, false, LlmContext.taskId()));
        } catch (Throwable t) {
            // 埋点是旁路，失败不影响嵌入本身
            log.debug("embedding 埋点写入异常（已忽略）：{}", t.toString());
        }
    }
}
