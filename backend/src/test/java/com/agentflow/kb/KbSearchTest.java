package com.agentflow.kb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 检索切词与词频打分（kb.query 的核心纯函数） */
class KbSearchTest {

    /* ---------- tokenize ---------- */

    @Test
    void chineseQueryProducesBigrams() {
        List<String> tokens = KbSearchTool.tokenize("怎么部署达梦数据库");
        // 「怎么」是停用词被丢弃；「达梦」「数据」「据库」…等 2-gram 保留
        assertFalse(tokens.contains("怎么"));
        assertTrue(tokens.contains("达梦"));
        assertTrue(tokens.contains("数据"));
    }

    @Test
    void latinWordsLowercased() {
        List<String> tokens = KbSearchTool.tokenize("K8s 集群如何扩容 kubectl");
        assertTrue(tokens.contains("k8s"));
        assertTrue(tokens.contains("kubectl"));
        assertFalse(tokens.contains("how")); // 停用词
    }

    @Test
    void pureStopwordsGiveEmptyTokens() {
        assertTrue(KbSearchTool.tokenize("知识库有什么资料").stream().noneMatch(t -> t.equals("知识")));
        // 「有什么」「资料」全在停用词表：结果应为空或不含停用词
        List<String> tokens = KbSearchTool.tokenize("the of and");
        assertTrue(tokens.isEmpty());
    }

    @Test
    void blankQueryGivesEmpty() {
        assertTrue(KbSearchTool.tokenize(null).isEmpty());
        assertTrue(KbSearchTool.tokenize("  ").isEmpty());
    }

    /* ---------- termFreq ---------- */

    @Test
    void termFreqCountsOccurrences() {
        Map<String, Integer> tf = KbSearchTool.termFreq(
                "达梦数据库驱动达梦连接串", List.of("达梦", "连接", "缺失"));
        assertEquals(2, tf.get("达梦"));
        assertEquals(1, tf.get("连接"));
        assertFalse(tf.containsKey("缺失"));
    }

    @Test
    void termFreqCaseInsensitive() {
        Map<String, Integer> tf = KbSearchTool.termFreq("Use kubectl apply; KUBECTL get pods", List.of("kubectl"));
        assertEquals(2, tf.get("kubectl"));
    }

    @Test
    void termFreqEmptyContent() {
        assertTrue(KbSearchTool.termFreq("", List.of("a")).isEmpty());
        assertTrue(KbSearchTool.termFreq(null, List.of("a")).isEmpty());
    }

    /* ---------- 工具元信息 ---------- */

    @Test
    void toolIsReadOnlyWithModes() {
        KbStore store = new KbStore("./target/test-kb-meta.db");
        store.init();
        KbSearchTool tool = new KbSearchTool(store, 600, 80, 5);
        assertEquals("kb.query", tool.name());
        assertFalse(tool.requiresConfirm());
        assertTrue(tool.argsHint().contains("search"));
        // 空库直接检索：返回 note 提示而非报错
        var tr = tool.execute(Map.of("mode", "search", "query", "任意问题"), null);
        assertEquals("json", tr.resultType());
        assertTrue(String.valueOf(tr.result()).contains("知识库为空"));
    }
}
