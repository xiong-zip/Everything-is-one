package com.agentflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量事件读取：会话分页回放原本逐条 getRun，一次请求放大成 limit+1 次查询，
 * 这里锁住「一次 IN 查询取回整页事件」的行为与分组顺序。
 */
class RunStoreBatchEventsTest {

    @TempDir
    Path tempDir;

    private RunStore newStore() {
        RunStore store = new RunStore(tempDir.resolve("batch-" + System.nanoTime() + ".db").toString());
        store.init();
        return store;
    }

    @Test
    void groupsEventsByRunIdInSeqOrder() {
        RunStore store = newStore();
        long a = store.createRun("t-a", "指令 A", "s-1");
        long b = store.createRun("t-b", "指令 B", "s-1");
        for (int i = 0; i < 3; i++) {
            store.saveEvent(a, i, "event-a" + i, Map.of("i", i));
        }
        store.saveEvent(b, 0, "event-b0", Map.of("i", 0));
        store.saveEvent(b, 1, "event-b1", Map.of("i", 1));

        Map<Long, List<Map<String, Object>>> grouped = store.listEventsByRunIds(List.of(a, b));

        assertEquals(2, grouped.size());
        assertEquals(List.of("event-a0", "event-a1", "event-a2"), names(grouped.get(a)));
        assertEquals(List.of("event-b0", "event-b1"), names(grouped.get(b)));
        // data 反序列化成 JsonNode，回放管线需要它保持结构；组内按 seq 升序，索引 1 即 seq=1 的事件
        JsonNode data = (JsonNode) grouped.get(b).get(1).get("data");
        assertEquals(1, data.get("i").asInt());
    }

    @Test
    void missingRunYieldsEmptyListNotMissingKey() {
        RunStore store = newStore();
        long a = store.createRun("t-a", "指令 A", "s-1");
        long orphan = a + 999;   // 没有任何事件的 run id

        Map<Long, List<Map<String, Object>>> grouped = store.listEventsByRunIds(List.of(a, orphan));

        assertEquals(2, grouped.size());
        assertTrue(grouped.get(a).isEmpty());
        assertTrue(grouped.get(orphan).isEmpty());
    }

    @Test
    void emptyInputReturnsEmptyMap() {
        RunStore store = newStore();
        assertTrue(store.listEventsByRunIds(List.of()).isEmpty());
        assertTrue(store.listEventsByRunIds(null).isEmpty());
    }

    /** 会话分页带 output，因此调用方不必再逐条 getRun 补字段 */
    @Test
    void sessionPageCarriesOutputAndEventsBatchMatches() {
        RunStore store = newStore();
        long a = store.createRun("t-a", "指令 A", "s-page");
        store.saveEvent(a, 0, "done", Map.of("output", "成品"));
        store.finishRun(a, "done", "摘要 A", "成品 A");

        RunStore.SessionPage page = store.listRunsBySessionPage("s-page", 20, null);
        assertEquals(1, page.runs().size());
        assertFalse(page.hasMore());
        assertEquals("成品 A", page.runs().get(0).get("output"));

        long runId = ((Number) page.runs().get(0).get("id")).longValue();
        Map<Long, List<Map<String, Object>>> events = store.listEventsByRunIds(List.of(runId));
        assertEquals(1, events.get(runId).size());
        assertEquals("done", events.get(runId).get(0).get("event"));
    }

    private static List<String> names(List<Map<String, Object>> events) {
        return events.stream().map(e -> String.valueOf(e.get("event"))).toList();
    }
}
