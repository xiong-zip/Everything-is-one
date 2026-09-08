package com.agentflow.controller;

import com.agentflow.schedule.ScheduleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 晨报机器人管理：查看配置与最近执行、立即试跑 */
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

    /** 立即试跑一次晨报并按配置推送（同步返回，报告生成约需数十秒） */
    @PostMapping("/run-now")
    public Map<String, Object> runNow() {
        return scheduleService.runNow();
    }
}
