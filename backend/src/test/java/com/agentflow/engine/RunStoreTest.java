package com.agentflow.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 任务历史持久化：runs/events 基础 CRUD 与 afterSeq 增量读取（临时 SQLite 库） */
class RunStoreTest {

    @TempDir
    Path tempDir;

    private RunStore newStore() {
        return new RunStore(tempDir.resolve("test-" + System.nanoTime() + ".db").toString());
    }

    @Test
    void runLifecycle() {
        RunStore store = newStore();
        store.init();

        long runId = store.createRun("task-1", "查天气");
        assertTrue(runId > 0);

        store.saveEvent(runId, 0, "status", Map.of("text", "start"));
        store.saveEvent(runId, 1, "intent", Map.of("summary", "天气"));
        store.saveEvent(runId, 2, "done", Map.of("output", "晴"));

        store.finishRun(runId, "done", "已查天气", "晴");

        Map<String, Object> run = store.getRun(runId);
        assertEquals("done", run.get("status"));
        assertEquals("查天气", run.get("command"));
        assertEquals(3, ((List<?>) run.get("events")).size());
        assertEquals("查天气", String.valueOf(store.listRuns(10).get(0).get("command")));
    }

    @Test
    void listEventsAfterSeq() {
        RunStore store = newStore();
        store.init();
        long runId = store.createRun("task-2", "写文案");
        for (int i = 0; i < 5; i++) {
            store.saveEvent(runId, i, "status", Map.of("seq", i));
        }
        // 断线重连：只要 seq>3 的
        List<Map<String, Object>> tail = store.listEvents(runId, 3);
        assertEquals(1, tail.size());
        assertEquals("status", tail.get(0).get("event"));
    }

    @Test
    void findByTaskIdAndMissing() {
        RunStore store = newStore();
        store.init();
        store.createRun("task-3", "查股价");
        assertNotNull(store.findRunIdByTaskId("task-3"));
        assertNull(store.findRunIdByTaskId("no-such"));
    }

    private static void assertTrue(boolean v) {
        org.junit.jupiter.api.Assertions.assertTrue(v);
    }
}
