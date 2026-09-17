package com.agentflow.schedule;

import com.agentflow.engine.AgentEngine;
import com.agentflow.notify.NotifyMode;
import com.agentflow.notify.NotifyService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定时任务机器人：按 cron（配置在 .env，全局统一）依次执行所有<b>已启用</b>的定时任务，
 * 每个任务是一条指令，生成后经推送通道发出，全程无需前端在线。
 *
 * <p>任务与开关都在工作台界面管理（存 SQLite，立即生效、无需重启）。生效条件是
 * <b>总开关 && 该任务开关</b>：总开关沿用老的单任务时代语义，作为全局闸门；
 * 任务开关让你能单独停掉某一条而不删掉它。
 *
 * <p>注意 cron 目前是<b>全局一个</b>，所有启用的任务在同一时刻依次执行——多任务各自
 * 独立时间需要把 {@code @Scheduled} 换成运行时可重新调度的 TaskScheduler，尚未做。
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final long RUN_TIMEOUT_MS = 5 * 60_000L;
    private static final String TRIGGER_CRON = "cron";
    private static final String TRIGGER_MANUAL = "manual";

    private final AgentEngine engine;
    private final ScheduleStore store;
    private final NotifyService notify;
    private final boolean enabledByEnv;
    private final String cron;
    private final String commandByEnv;

    public ScheduleService(AgentEngine engine, ScheduleStore store, NotifyService notify,
                           @Value("${agentflow.schedule.morning-report.enabled:false}") boolean enabled,
                           @Value("${agentflow.schedule.morning-report.cron:0 0 9 * * MON-FRI}") String cron,
                           @Value("${agentflow.schedule.morning-report.command:根据我的 GitLab 提交记录生成昨天的工作日报}") String command) {
        this.engine = engine;
        this.store = store;
        this.notify = notify;
        this.enabledByEnv = enabled;
        this.cron = cron;
        this.commandByEnv = command;
    }

    /**
     * 老库升级：升级前只有一条全局指令，没有任务概念。首次启动时把它变成一个任务，
     * 并把历史执行记录归到这条指令下——不回填的话那些记录会变成没人认领的孤儿。
     */
    @PostConstruct
    void migrateLegacyCommand() {
        if (!store.tasks().isEmpty()) {
            return;
        }
        String legacy = store.readCommand();
        if (legacy == null || legacy.isBlank()) {
            legacy = commandByEnv;
        }
        int filled = store.backfillRunsCommand(legacy);
        store.createTask(legacy, true);
        log.info("已把原有晨报指令初始化为首个定时任务（回填历史记录 {} 条）：{}", filled, legacy);
    }

    /** 总开关：界面设置过以界面为准，否则用 .env 默认值（每次触发都重读，改完立即生效） */
    public boolean isEnabled() {
        Boolean stored = store.readEnabled();
        return stored != null ? stored : enabledByEnv;
    }

    public void setEnabled(boolean enabled) {
        store.writeEnabled(enabled);
        log.info("定时任务总开关已更新：{}", enabled ? "开启" : "关闭");
    }

    /** 生效的推送形态：界面设置过以界面为准，否则纯文本（保证老部署行为不变） */
    public NotifyMode notifyMode() {
        return NotifyMode.parse(store.readNotifyMode());
    }

    public NotifyMode setNotifyMode(String mode) {
        // 认不出来的值一律落回纯文本，避免把非法配置持久化下来
        NotifyMode parsed = NotifyMode.parse(mode);
        store.writeNotifyMode(parsed.key());
        log.info("推送形态已更新：{}", parsed.label());
        return parsed;
    }

    /* ---------- 任务管理 ---------- */

    public ScheduleStore.Task createTask(String command, Boolean enabled) {
        String v = normalize(command);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("指令不能为空");
        }
        if (v.length() > MAX_COMMAND_CHARS) {
            throw new IllegalArgumentException("指令过长，上限 " + MAX_COMMAND_CHARS + " 字（当前 " + v.length() + " 字）");
        }
        if (store.findTaskByCommand(v) != null) {
            throw new IllegalArgumentException("已存在相同指令的定时任务，无需重复添加");
        }
        ScheduleStore.Task task = store.createTask(v, enabled == null || enabled);
        if (task == null) {
            throw new IllegalArgumentException("新增失败，请重试");
        }
        return task;
    }

    public ScheduleStore.Task updateTask(long id, String command, Boolean enabled) {
        ScheduleStore.Task self = store.findTask(id);
        if (self == null) {
            throw new IllegalArgumentException("任务不存在：" + id);
        }
        String v = command == null ? self.command() : normalize(command);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("指令不能为空");
        }
        if (v.length() > MAX_COMMAND_CHARS) {
            throw new IllegalArgumentException("指令过长，上限 " + MAX_COMMAND_CHARS + " 字（当前 " + v.length() + " 字）");
        }
        boolean on = enabled == null ? self.enabled() : enabled;
        if (!store.updateTask(id, v, on)) {
            throw new IllegalArgumentException("已存在相同指令的定时任务：" + v);
        }
        return store.findTask(id);
    }

    public void setTaskEnabled(long id, boolean enabled) {
        if (store.findTask(id) == null) {
            throw new IllegalArgumentException("任务不存在：" + id);
        }
        store.setTaskEnabled(id, enabled);
        log.info("定时任务 {} 自动执行已{}", id, enabled ? "开启" : "关闭");
    }

    /** 删除任务，连它的执行记录一起删（界面上「删除分类」的语义） */
    public void deleteTask(long id, boolean withRuns) {
        if (!store.deleteTask(id, withRuns)) {
            throw new IllegalArgumentException("任务不存在：" + id);
        }
        log.info("定时任务 {} 已删除（{}执行记录）", id, withRuns ? "含" : "不含");
    }

    public void deleteRun(String taskId, String createdAt) {
        if (!store.deleteRun(taskId, createdAt)) {
            throw new IllegalArgumentException("执行记录不存在或已删除");
        }
    }

    private static final int MAX_COMMAND_CHARS = 500;

    /** 指令做空白规整：换行折成空格，避免同一条指令因排版差异被当成两个任务 */
    private static String normalize(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ");
    }

    /* ---------- 调度与执行 ---------- */

    /** 由 Spring 按 cron 触发：总开关关闭时整体不跑，否则依次执行每个已启用的任务 */
    @Scheduled(cron = "${agentflow.schedule.morning-report.cron:0 0 9 * * MON-FRI}")
    public void scheduledTick() {
        if (!isEnabled()) {
            return;
        }
        for (ScheduleStore.Task task : store.tasks()) {
            if (!task.enabled()) {
                continue;
            }
            runTask(task, TRIGGER_CRON);
        }
    }

    /** 立即试跑：指定任务，不传则跑第一个（兼容老的「一键试跑」调用） */
    public Map<String, Object> runNow(Long taskId) {
        ScheduleStore.Task task = taskId == null
                ? store.tasks().stream().findFirst().orElse(null)
                : store.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException(taskId == null
                    ? "还没有任何定时任务，请先新增一条指令"
                    : "任务不存在：" + taskId);
        }
        Map<String, Object> result = runTask(task, TRIGGER_MANUAL);
        result.put("webhookConfigured", notify.isConfigured());
        return result;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", isEnabled());
        // 界面未改过时，当前值来自 .env；供界面提示用
        m.put("envDefault", enabledByEnv);
        m.put("cron", cron);
        NotifyMode mode = notifyMode();
        m.put("notifyMode", mode.key());
        m.put("notifyModeLabel", mode.label());
        m.put("notifyModes", java.util.Arrays.stream(NotifyMode.values())
                .map(x -> Map.of("key", x.key(), "label", x.label())).toList());
        m.put("webhookConfigured", notify.isConfigured());
        m.put("tasks", store.taskSummaries());
        return m;
    }

    private Map<String, Object> runTask(ScheduleStore.Task task, String trigger) {
        NotifyMode mode = notifyMode();
        log.info("定时任务触发（{}，任务 {}，推送形态 {}）：{}", trigger, task.id(), mode.key(), task.command());
        Map<String, String> run = engine.executeHeadless(task.command(), RUN_TIMEOUT_MS);
        String status = run.getOrDefault("status", "error");
        String summary = run.getOrDefault("summary", "");
        String output = run.getOrDefault("output", "");
        boolean pushed = "done".equals(status)
                && notify.push(mode, "【AgentFlow 定时任务】" + summary, output, false, null);
        store.record(trigger, run.get("taskId"), status, summary, output, pushed, task.command());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trigger", trigger);
        result.put("taskId", run.get("taskId"));
        result.put("scheduleTaskId", task.id());
        result.put("command", task.command());
        result.put("status", status);
        result.put("summary", summary);
        result.put("output", output);
        result.put("pushed", pushed);
        result.put("notifyMode", mode.key());
        return result;
    }
}
