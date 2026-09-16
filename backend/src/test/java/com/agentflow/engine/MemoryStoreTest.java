package com.agentflow.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 长期记忆：持久化往返、去重、子串查找删除与自动提取开关 */
class MemoryStoreTest {

    @TempDir
    Path tempDir;

    private MemoryStore newStore() {
        MemoryStore s = new MemoryStore(tempDir.resolve("mem-" + System.nanoTime() + ".db").toString());
        s.init();
        return s;
    }

    @Test
    void addListDeleteRoundtrip() {
        MemoryStore store = newStore();
        assertTrue(store.add("日报署名用小熊"));
        assertTrue(store.add("排查默认用 dev 集群"));

        // 完全重复的内容不写入
        assertFalse(store.add("日报署名用小熊"));
        // 空白/超长拒绝
        assertFalse(store.add("   "));
        assertFalse(store.add("x".repeat(201)));

        List<MemoryStore.MemoryItem> all = store.list();
        assertEquals(2, all.size());
        assertEquals("日报署名用小熊", all.get(0).content());

        assertTrue(store.delete(all.get(0).id()));
        assertEquals(1, store.list().size());
        assertFalse(store.delete(all.get(0).id()));
    }

    @Test
    void findBySubstringIgnoresCase() {
        MemoryStore store = newStore();
        store.add("排查默认用 dev 集群");
        store.add("dev 环境的数据库连 DM_TEST");

        List<MemoryStore.MemoryItem> hits = store.findBySubstring("dev");
        assertEquals(2, hits.size());
        assertTrue(store.findBySubstring("不存在").isEmpty());
        assertTrue(store.findBySubstring(null).isEmpty());
    }

    @Test
    void deleteByContentAndClear() {
        MemoryStore store = newStore();
        store.add("偏好A");
        store.add("偏好B");
        assertTrue(store.deleteByContent("偏好A"));
        assertFalse(store.deleteByContent("偏好A"));
        assertEquals(1, store.clear());
        assertEquals(0, store.list().size());
    }

    @Test
    void autoExtractFlagPersists() {
        MemoryStore store = newStore();
        assertTrue(store.isAutoExtract(), "默认开启");
        store.setAutoExtract(false);
        assertFalse(store.isAutoExtract());
        store.setAutoExtract(true);
        assertTrue(store.isAutoExtract());
    }
}
