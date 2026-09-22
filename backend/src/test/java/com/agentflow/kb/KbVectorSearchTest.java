package com.agentflow.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 向量检索：编解码 / 余弦 / 混合打分 / 端到端（假嵌入） */
class KbVectorSearchTest {

    /* ---------- KbVectors 纯函数 ---------- */

    @Test
    void encodeDecodeRoundtrip() {
        float[] vec = {0.5f, -1.25f, 3.75f, 0f};
        float[] back = KbVectors.decode(KbVectors.encode(vec));
        assertEquals(vec.length, back.length);
        for (int i = 0; i < vec.length; i++) {
            assertEquals(vec[i], back[i], 1e-6f);
        }
    }

    @Test
    void decodeRejectsBadInput() {
        assertNull(KbVectors.decode(null));
        assertNull(KbVectors.decode(new byte[0]));
        assertNull(KbVectors.decode(new byte[3])); // 非 4 字节对齐
    }

    @Test
    void cosineBasics() {
        assertEquals(1.0, KbVectors.cosine(new float[]{1, 0}, new float[]{2, 0}), 1e-9);
        assertEquals(0.0, KbVectors.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
        // 零向量/长度不一致 → 0，不抛异常
        assertEquals(0.0, KbVectors.cosine(new float[]{0, 0}, new float[]{1, 1}), 1e-9);
        assertEquals(0.0, KbVectors.cosine(new float[]{1}, new float[]{1, 2}), 1e-9);
    }

    /* ---------- 混合打分 ---------- */

    @Test
    void hybridScoreWeightsAndClamps() {
        // 纯语义命中：关键词 0 分
        assertEquals(0.65, KbSearchTool.hybridScore(1.0, 0, 10), 1e-9);
        // 纯关键词命中：余弦 0，关键词满归一
        assertEquals(0.35, KbSearchTool.hybridScore(0, 10, 10), 1e-9);
        // maxKw=0 时关键词项为 0（全库无字面命中）
        assertEquals(0.325, KbSearchTool.hybridScore(0.5, 0, 0), 1e-9);
        // 余弦越界被收敛到 [-1,1]
        assertEquals(0.65, KbSearchTool.hybridScore(5.0, 0, 0), 1e-9);
    }

    /* ---------- 端到端：假嵌入 + 混合排序 ---------- */

    @TempDir
    Path tempDir;

    /** 固定向量的假嵌入客户端：内容含「部署」→ e1，含「团建」→ e2，查询「发布上线」→ e1 */
    private static EmbeddingClient fakeClient() {
        return new EmbeddingClient(null, null, "http://fake", "fake-embed", "k") {
            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream()
                        .map(t -> t.contains("部署") || t.contains("发布") || t.contains("上线")
                                ? new float[]{1f, 0f} : new float[]{0f, 1f})
                        .toList();
            }
        };
    }

    @Test
    void semanticOnlyQueryHitsViaVector() {
        KbStore store = new KbStore(tempDir.resolve("vec-" + System.nanoTime() + ".db").toString());
        store.init();
        store.saveFile("deploy.md", "s1", 100, 60, List.of("应用部署手册：先打包再执行 kubectl apply"));
        store.saveFile("minutes.md", "s2", 100, 40, List.of("会议纪要：下周团建"));

        KbVectorService svc = new KbVectorService(fakeClient(), store);
        svc.backfill();
        assertEquals(2, store.countEmbedded());

        KbSearchTool tool = new KbSearchTool(store, svc, 600, 80, 5);
        // 「发布上线」与「部署」无字面重叠：纯关键词模式不会命中，混合模式靠语义向量命中
        com.agentflow.tool.ToolResult r = tool.execute(
                Map.of("mode", "search", "query", "怎么发布上线"), "怎么发布上线");
        assertEquals("vector-hybrid", r.result().get("ranker"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) r.result().get("hits");
        assertEquals(1, hits.size());
        assertEquals("deploy.md", hits.get(0).get("file"));
    }

    @Test
    void keywordFallbackWhenEmbeddingNotConfigured() {
        KbStore store = new KbStore(tempDir.resolve("kw-" + System.nanoTime() + ".db").toString());
        store.init();
        store.saveFile("deploy.md", "s1", 100, 60, List.of("应用部署手册：先打包再执行 kubectl apply"));

        // 未配置嵌入（enabled=false）→ embedQuery 返回 null，纯关键词模式
        EmbeddingClient unconfigured = new EmbeddingClient(null, null, "", "", "");
        KbVectorService svc = new KbVectorService(unconfigured, store);
        KbSearchTool tool = new KbSearchTool(store, svc, 600, 80, 5);
        com.agentflow.tool.ToolResult r = tool.execute(Map.of("mode", "search", "query", "部署手册"), "部署手册");
        assertEquals("keyword", r.result().get("ranker"));
        assertTrue(!((List<?>) r.result().get("hits")).isEmpty());
    }

    /* ---------- KbStore 向量列 ---------- */

    @Test
    void embeddingColumnAndMetaRoundtrip() {
        KbStore store = new KbStore(tempDir.resolve("meta-" + System.nanoTime() + ".db").toString());
        store.init();
        long id = store.saveFile("a.md", "s", 10, 20, List.of("甲", "乙"));
        assertEquals(2, store.countChunks());
        assertEquals(0, store.countEmbedded());

        List<KbStore.ChunkRow> rows = store.allChunkRows();
        assertEquals(2, rows.size());
        assertNull(rows.get(0).embedding());

        store.updateEmbedding(rows.get(0).id(), KbVectors.encode(new float[]{1f, 2f}));
        assertEquals(1, store.countEmbedded());
        float[] back = KbVectors.decode(store.allChunkRows().get(0).embedding());
        assertEquals(2, back.length);

        store.metaSet("kb_embedding_model", "bge-m3");
        assertEquals("bge-m3", store.metaGet("kb_embedding_model"));
        store.metaSet("kb_embedding_model", "bge-m4");
        assertEquals("bge-m4", store.metaGet("kb_embedding_model"));

        store.clearEmbeddings();
        assertEquals(0, store.countEmbedded());
    }
}
