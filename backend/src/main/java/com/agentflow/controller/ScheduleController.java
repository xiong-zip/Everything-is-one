package com.agentflow.controller;

import com.agentflow.notify.NotifyMode;
import com.agentflow.schedule.ScheduleService;
import com.agentflow.schedule.ScheduleStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定时任务管理：查看状态与任务列表、总开关、推送形态、任务增删改与逐个开关、立即试跑。
 * 执行记录按任务（即指令）归类，随任务一起返回，界面上就是「每个分类下的最近执行」。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return scheduleService.status();
    }

    /**
     * 总开关：写 SQLite 持久化，下次触发即按新值判断（无需重启、无需改 .env）。
     * 关闭后所有任务都不自动执行，但单个任务仍可「立即试跑」。
     */
    @PostMapping("/enabled")
    public Map<String, Object> setEnabled(@RequestBody Map<String, Object> body) {
        boolean enabled = body != null
                && Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "false")));
        scheduleService.setEnabled(enabled);
        return Map.of("ok", true, "enabled", enabled);
    }

    /**
     * 推送形态：纯文本 / markdown 长文 / 摘要+文件附件。写 SQLite，下次推送即生效。
     * 认不出来的值落回纯文本。
     */
    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestBody Map<String, Object> body) {
        String mode = body == null ? "" : String.valueOf(body.getOrDefault("mode", ""));
        NotifyMode parsed = scheduleService.setNotifyMode(mode);
        return Map.of("ok", true, "notifyMode", parsed.key(), "notifyModeLabel", parsed.label());
    }

    /** 新增定时任务（一条指令 = 一个任务/分类） */
    @PostMapping("/tasks")
    public Map<String, Object> createTask(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String command = String.valueOf(body.getOrDefault("command", ""));
        Boolean enabled = body.get("enabled") == null
                ? null : Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        ScheduleStore.Task task = scheduleService.createTask(command, enabled);
        return Map.of("ok", true, "id", task.id(), "command", task.command(), "enabled", task.enabled());
    }

    /** 修改任务指令或开关（字段缺省表示不改） */
    @PutMapping("/tasks/{id}")
    public Map<String, Object> updateTask(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        String command = body == null || body.get("command") == null
                ? null : String.valueOf(body.get("command"));
        Boolean enabled = body == null || body.get("enabled") == null
                ? null : Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        ScheduleStore.Task task = scheduleService.updateTask(id, command, enabled);
        return Map.of("ok", true, "id", task.id(), "command", task.command(), "enabled", task.enabled());
    }

    /** 单个任务的自动执行开关 */
    @PutMapping("/tasks/{id}/enabled")
    public Map<String, Object> setTaskEnabled(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        boolean enabled = body != null
                && Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "false")));
        scheduleService.setTaskEnabled(id, enabled);
        return Map.of("ok", true, "enabled", enabled);
    }

    /** 删除任务；withRuns 默认 true（连它的执行记录一起删） */
    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> deleteTask(@PathVariable("id") long id,
                                          @RequestParam(value = "withRuns", defaultValue = "true") boolean withRuns) {
        scheduleService.deleteTask(id, withRuns);
        return Map.of("ok", true, "withRuns", withRuns);
    }

    /** 删除单条执行记录（taskId 为那条记录的任务 ID，createdAt 为它的时间戳） */
    @DeleteMapping("/runs")
    public Map<String, Object> deleteRun(@RequestParam("taskId") String taskId,
                                         @RequestParam("createdAt") String createdAt) {
        scheduleService.deleteRun(taskId, createdAt);
        return Map.of("ok", true);
    }

    /** 立即试跑：body 可带 taskId 指定任务，不带则跑第一个（同步返回，报告生成约需数十秒） */
    @PostMapping("/run-now")
    public Map<String, Object> runNow(@RequestBody(required = false) Map<String, Object> body) {
        Long taskId = body == null || body.get("taskId") == null
                ? null : Long.valueOf(String.valueOf(body.get("taskId")));
        return scheduleService.runNow(taskId);
    }
}
