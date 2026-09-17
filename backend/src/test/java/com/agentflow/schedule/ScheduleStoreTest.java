package com.agentflow.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 定时任务（按指令归类）、执行记录归属、总开关与推送形态的持久化 */
class ScheduleStoreTest {

    private static final String CMD_A = "根据我的 GitLab 提交记录生成昨天的工作日报";
    private static final String CMD_B = "巡检 dev 集群有没有异常 Pod";

    @TempDir
    Path tempDir;

    private ScheduleStore newStore() {
        // 每次用独立文件，避免实例间串数据
        ScheduleStore s = new ScheduleStore(tempDir.resolve("sch-" + System.nanoTime() + ".db").toString());
        s.init();
        return s;
    }

    private static long record(ScheduleStore store, String command, String status, boolean pushed, String summary) {
        return store.record("manual", "agent-" + System.nanoTime(), status, summary, "正文", pushed, command);
    }

    /* ---------- 总开关（未设置过必须是 null，调用方才能回退 .env） ---------- */

    @Test
    void masterSwitchIsNullBeforeAnyToggle() {
        assertNull(newStore().readEnabled());
        assertNull(newStore().readNotifyMode());
    }

    @Test
    void masterSwitchPersistsAcrossReopen() {
        String db = tempDir.resolve("sw.db").toString();
        ScheduleStore store = new ScheduleStore(db);
        store.init();
        store.writeEnabled(true);
        assertEquals(Boolean.TRUE, store.readEnabled());

        ScheduleStore reopened = new ScheduleStore(db);
        reopened.init();
        assertEquals(Boolean.TRUE, reopened.readEnabled());
    }

    @Test
    void notifyModePersists() {
        ScheduleStore store = newStore();
        store.writeNotifyMode("file");
        assertEquals("file", store.readNotifyMode());
        store.writeNotifyMode("markdown");
        assertEquals("markdown", store.readNotifyMode());
    }

    /* ---------- 任务：指令唯一，执行记录按指令归类 ---------- */

    @Test
    void createTaskIsIdempotentByCommand() {
        ScheduleStore store = newStore();
        ScheduleStore.Task a = store.createTask(CMD_A, true);
        assertNotNull(a);
        assertTrue(a.enabled());
        assertNotNull(a.createdAt());

        // 同一条指令不重复建卡，返回已有的那个
        ScheduleStore.Task again = store.createTask(CMD_A, false);
        assertEquals(a.id(), again.id());
        assertTrue(again.enabled(), "已存在时应原样返回，不被新参数改掉开关");
        assertEquals(1, store.tasks().size());
    }

    @Test
    void findTaskByCommandAndId() {
        ScheduleStore store = newStore();
        ScheduleStore.Task a = store.createTask(CMD_A, true);
        store.createTask(CMD_B, false);

        assertEquals(a.id(), store.findTaskByCommand(CMD_A).id());
        assertNull(store.findTaskByCommand("不存在的指令"));
        assertNull(store.findTaskByCommand(null));
        assertNull(store.findTask(9999));
        assertEquals(2, store.tasks().size());
    }

    @Test
    void runsAreGroupedUnderTheirCommand() {
        ScheduleStore store = newStore();
        store.createTask(CMD_A, true);
        store.createTask(CMD_B, true);

        record(store, CMD_A, "done", true, "日报一");
        record(store, CMD_A, "error", false, "日报二");
        record(store, CMD_B, "done", true, "巡检一");

        List<Map<String, Object>> summaries = store.taskSummaries();
        assertEquals(2, summaries.size());

        Map<String, Object> first = summaries.get(0);
        assertEquals(CMD_A, first.get("command"));
        assertEquals(2, first.get("runCount"), "同一指令的两次执行应归到同一个任务下");
        assertEquals("error", first.get("lastStatus"), "最近一次失败应反映在任务上");
        assertEquals(false, first.get("lastPushed"));
        assertNotNull(first.get("lastRunAt"));

        Map<String, Object> second = summaries.get(1);
        assertEquals(1, second.get("runCount"));
        assertEquals("done", second.get("lastStatus"));
    }

    @Test
    void taskWithNoRunsStillListed() {
        ScheduleStore store = newStore();
        store.createTask(CMD_A, false);

        Map<String, Object> task = store.taskSummaries().get(0);
        assertEquals(0, task.get("runCount"));
        assertEquals("", task.get("lastRunAt"));
        assertEquals(false, task.get("enabled"));
    }

    @Test
    void updateTaskMovesItsRunsToNewCommand() {
        ScheduleStore store = newStore();
        ScheduleStore.Task a = store.createTask(CMD_A, true);
        record(store, CMD_A, "done", true, "日报");

        String renamed = CMD_A + "（含风险项）";
        assertTrue(store.updateTask(a.id(), renamed, true));

        // 指令是归类键，改名后老记录要跟着换分类，不能变成孤儿
        Map<String, Object> task = store.taskSummaries().get(0);
        assertEquals(renamed, task.get("command"));
        assertEquals(1, task.get("runCount"));
    }

    @Test
    void updateTaskRejectsDuplicateCommand() {
        ScheduleStore store = newStore();
        ScheduleStore.Task a = store.createTask(CMD_A, true);
        store.createTask(CMD_B, true);

        // 改成另一条已存在的指令会撞唯一键，应当被拒
        assertFalse(store.updateTask(a.id(), CMD_B, true));
        assertEquals(CMD_A, store.findTask(a.id()).command());
    }

    @Test
    void setTaskEnabledOnlyTogglesSwitch() {
        ScheduleStore store = newStore();
        ScheduleStore.Task a = store.createTask(CMD_A, true);

        store.setTaskEnabled(a.id(), false);
        assertFalse(store.findTask(a.id()).enabled());
        assertEquals(CMD_A, store.findTask(a.id()).command(), "切开关不应动到指令");

        store.setTaskEnabled(a.id(), true);
        assertTrue(store.findTask(a.id()).enabled());
    }

    @Test
    void deleteTaskWithRunsRemovesItsHistory() {
        ScheduleStore store = newStore();
        ScheduleStore.Task a = store.createTask(CMD_A, true);
        store.createTask(CMD_B, true);
        record(store, CMD_A, "done", true, "日报");
        record(store, CMD_B, "done", true, "巡检");

        assertTrue(store.deleteTask(a.id(), true));
        assertNull(store.findTask(a.id()));
        assertEquals(1, store.taskSummaries().size());
        // 只删自己的记录，别的任务不受影响
        assertEquals(1, store.taskSummaries().get(0).get("runCount"));
        assertTrue(store.recent(10).stream()
                .noneMatch(r -> CMD_A.equals(r.get("command"))));
    }

    @Test
    void deleteTaskKeepingRunsLeavesHistoryOrphan() {
        ScheduleStore store = newStore();
        ScheduleStore.Task a = store.createTask(CMD_A, true);
        record(store, CMD_A, "done", true, "日报");

        assertTrue(store.deleteTask(a.id(), false));
        assertTrue(store.taskSummaries().isEmpty());
        assertEquals(1, store.recent(10).size(), "保留记录时历史仍在");
    }

    @Test
    void deleteTaskOnMissingIdIsFalse() {
        assertFalse(newStore().deleteTask(123L, true));
    }

    /* ---------- 老库升级 ---------- */

    @Test
    void backfillFillsLegacyRunsWithNoCommand() {
        ScheduleStore store = newStore();
        // 老库写出来的记录 command 为空（升级前没有这一列）
        store.record("cron", "agent-1", "done", "旧日报", "正文", true, "");
        store.record("manual", "agent-2", "done", "旧日报二", "正文", false, "");

        assertEquals(2, store.backfillRunsCommand(CMD_A), "应回填两条");
        assertEquals(0, store.backfillRunsCommand(CMD_A), "已回填过就不该重复统计");

        store.createTask(CMD_A, true);
        assertEquals(2, store.taskSummaries().get(0).get("runCount"));
    }

    @Test
    void backfillIgnoresBlankCommand() {
        ScheduleStore store = newStore();
        store.record("cron", "a", "done", "s", "o", false, "");
        assertEquals(0, store.backfillRunsCommand("  "));
        assertEquals(0, store.backfillRunsCommand(null));
    }

    /* ---------- 执行记录 ---------- */

    @Test
    void recentIsNewestFirstAndTruncatesLongOutput() {
        ScheduleStore store = newStore();
        store.record("cron", "agent-1", "done", "早", "长".repeat(1000), true, CMD_A);
        store.record("manual", "agent-2", "error", "晚", "短", false, CMD_A);

        List<Map<String, Object>> recent = store.recent(10);
        assertEquals(2, recent.size());
        assertEquals("agent-2", recent.get(0).get("taskId"), "应按时间倒序");
        assertEquals(CMD_A, recent.get(0).get("command"));
        assertEquals(true, recent.get(1).get("pushed"));

        String output = (String) recent.get(1).get("output");
        assertTrue(output.length() < 500, "超长正文应被截断");
        assertTrue(output.endsWith("…"));
    }

    @Test
    void deleteRunRemovesSingleRecord() {
        ScheduleStore store = newStore();
        store.record("manual", "agent-1", "done", "日报", "正文", true, CMD_A);

        Map<String, Object> run = store.recent(10).get(0);
        assertTrue(store.deleteRun((String) run.get("taskId"), (String) run.get("createdAt")));
        assertFalse(store.deleteRun((String) run.get("taskId"), (String) run.get("createdAt")));
        assertTrue(store.recent(10).isEmpty());
    }
}
