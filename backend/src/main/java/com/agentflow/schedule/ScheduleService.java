package com.agentflow.schedule;

import com.agentflow.engine.AgentEngine;
import com.agentflow.notify.NotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自动晨报机器人：定时（cron 配置）headless 执行晨报指令，
 * 生成后经 Webhook 推送（企微/钉钉），全程无需前端在线。
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
    private final boolean enabled;
    private final String cron;
    private final String command;

    public ScheduleService(AgentEngine engine, ScheduleStore store, NotifyService notify,
                           @Value("${agentflow.schedule.morning-report.enabled:false}") boolean enabled,
                           @Value("${agentflow.schedule.morning-report.cron:0 0 9 * * MON-FRI}") String cron,
                           @Value("${agentflow.schedule.morning-report.command:根据我的 GitLab 提交记录生成昨天的工作日报}") String command) {
        this.engine = engine;
        this.store = store;
        this.notify = notify;
        this.enabled = enabled;
        this.cron = cron;
        this.command = command;
    }

    /** 由 Spring 按 cron 触发；总开关关闭时直接返回 */
    @Scheduled(cron = "${agentflow.schedule.morning-report.cron:0 0 9 * * MON-FRI}")
    public void morningReportTick() {
        if (!enabled) {
            return;
        }
        runMorningReport(TRIGGER_CRON);
    }

    /** 立即试跑（管理入口）：同步执行并返回执行结果 */
    public Map<String, Object> runNow() {
        Map<String, Object> result = runMorningReport(TRIGGER_MANUAL);
        result.put("webhookConfigured", notify.isConfigured());
        return result;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("cron", cron);
        m.put("command", command);
        m.put("webhookConfigured", notify.isConfigured());
        m.put("recentRuns", store.recent(10));
        return m;
    }

    private Map<String, Object> runMorningReport(String trigger) {
        log.info("晨报任务触发（{}）：{}", trigger, command);
        Map<String, String> run = engine.executeHeadless(command, RUN_TIMEOUT_MS);
        String status = run.getOrDefault("status", "error");
        String summary = run.getOrDefault("summary", "");
        String output = run.getOrDefault("output", "");
        boolean pushed = "done".equals(status) && notify.send("【AgentFlow 晨报】" + summary, output);
        store.record(trigger, run.get("taskId"), status, summary, output, pushed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trigger", trigger);
        result.put("taskId", run.get("taskId"));
        result.put("status", status);
        result.put("summary", summary);
        result.put("output", output);
        result.put("pushed", pushed);
        return result;
    }
}
