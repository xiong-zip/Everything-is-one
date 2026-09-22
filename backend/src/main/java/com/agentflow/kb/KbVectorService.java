package com.agentflow.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库向量化编排：嵌入模型可用时负责「补齐向量 + 查询嵌入」，
 * 不可用时一切按关键词模式走（本服务只做无侵入增强）。
 *
 * 模型一致性：kb_meta 里记录生成向量的嵌入模型名；换模型后旧向量空间不同、
 * 余弦值失去意义，重建索引时先整体清空再重算——半新半旧的向量库比没有更误导。
 */
@Component
public class KbVectorService {

    private static final Logger log = LoggerFactory.getLogger(KbVectorService.class);
    private static final String META_MODEL = "kb_embedding_model";

    private final EmbeddingClient client;
    private final KbStore store;
    /** 重建/回填互斥：上传索引与手动重建并发跑会重复计费 */
    private final Object backfillLock = new Object();

    public KbVectorService(EmbeddingClient client, KbStore store) {
        this.client = client;
        this.store = store;
    }

    public boolean active() {
        return client.isEnabled();
    }

    public String model() {
        return client.model();
    }

    /** 面板展示用状态 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", client.isEnabled());
        out.put("model", client.model());
        int total = store.countChunks();
        int embedded = store.countEmbedded();
        out.put("totalChunks", total);
        out.put("embeddedChunks", embedded);
        out.put("indexedModel", store.metaGet(META_MODEL));
        out.put("upToDate", client.isEnabled() && total > 0 && embedded == total
                && client.model().equals(store.metaGet(META_MODEL)));
        return out;
    }

    /**
     * 重建全部向量：模型变更时先清空旧向量；返回本次新嵌入的分块数。
     * 分批推进、逐块落库——中途失败已算的部分保留，重跑只补缺口。
     */
    public Map<String, Object> backfill() {
        if (!client.isEnabled()) {
            throw new IllegalArgumentException("未配置嵌入模型：请在 .env 设置 AGENTFLOW_EMBED_URL 与 AGENTFLOW_EMBED_MODEL 后重启");
        }
        synchronized (backfillLock) {
            String indexed = store.metaGet(META_MODEL);
            if (indexed != null && !indexed.equals(client.model()) && store.countEmbedded() > 0) {
                log.info("嵌入模型已变更（{} → {}），清空旧向量后重建", indexed, client.model());
                store.clearEmbeddings();
                store.metaSet(META_MODEL, client.model());
            }
            List<KbStore.ChunkRow> pending = new ArrayList<>();
            for (KbStore.ChunkRow r : store.allChunkRows()) {
                if (r.embedding() == null) {
                    pending.add(r);
                }
            }
            int done = 0;
            for (int i = 0; i < pending.size(); i += 16) {
                List<KbStore.ChunkRow> batch = pending.subList(i, Math.min(i + 16, pending.size()));
                List<float[]> vecs = client.embed(batch.stream().map(KbStore.ChunkRow::content).toList());
                for (int j = 0; j < batch.size(); j++) {
                    store.updateEmbedding(batch.get(j).id(), KbVectors.encode(vecs.get(j)));
                }
                done += batch.size();
            }
            if (done > 0) {
                store.metaSet(META_MODEL, client.model());
            }
            log.info("知识库向量索引完成：新嵌入 {} 块（总 {} 块）", done, store.countChunks());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("embedded", done);
            out.put("totalChunks", store.countChunks());
            out.put("embeddedChunks", store.countEmbedded());
            out.put("model", client.model());
            return out;
        }
    }

    /** 新上传文件的分块异步向量化（上传主流程不等它）；失败留待「重建索引」补齐 */
    public void indexFile(long fileId) {
        if (!client.isEnabled()) {
            return;
        }
        try {
            synchronized (backfillLock) {
                List<KbStore.ChunkRow> pending = new ArrayList<>();
                for (KbStore.ChunkRow r : store.allChunkRows()) {
                    if (r.fileId() == fileId && r.embedding() == null) {
                        pending.add(r);
                    }
                }
                if (pending.isEmpty()) {
                    return;
                }
                List<float[]> vecs = client.embed(pending.stream().map(KbStore.ChunkRow::content).toList());
                for (int j = 0; j < pending.size(); j++) {
                    store.updateEmbedding(pending.get(j).id(), KbVectors.encode(vecs.get(j)));
                }
                store.metaSet(META_MODEL, client.model());
            }
            log.info("知识库文件 {} 向量化完成（{} 块）", fileId, store.countEmbedded());
        } catch (Exception ex) {
            log.warn("知识库文件 {} 向量化失败（可在面板点「重建索引」补齐）：{}", fileId, ex.getMessage());
        }
    }

    /** 查询向量：尚未建索引/模型不一致/嵌入失败返回 null，检索自动退回关键词打分 */
    public float[] embedQuery(String query) {
        if (!vectorsReady() || query == null || query.isBlank()) {
            return null;
        }
        try {
            return client.embedOne(query);
        } catch (Exception ex) {
            log.warn("查询向量化失败，本次检索退回关键词模式：{}", ex.getMessage());
            return null;
        }
    }

    /** 当前向量是否与查询向量同空间（模型一致且已有任何向量） */
    public boolean vectorsReady() {
        return client.isEnabled() && store.countEmbedded() > 0
                && client.model().equals(store.metaGet(META_MODEL));
    }
}
