package com.agentflow.controller;

import com.agentflow.alarm.AlarmParser;
import com.agentflow.alarm.AlarmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警值守入口：给监控平台配一个 webhook 地址即可接入。
 *
 * <p>接收端刻意做成<b>裸字符串</b>（{@code @RequestBody String}）而不是反序列化成 DTO：
 * 各家平台的载荷结构不同且会随版本变，先原样收下再交给 {@link AlarmParser} 按字段名归一化查找，
 * 避免因为多了/少了一个字段就整条告警 400 丢掉。任何能读出字符串的内容类型都能收。
 *
 * <p>响应语义：{@code accepted=true} 表示已受理（{@code action} 说明是开始排查、去重跳过
 * 还是已恢复跳过）；{@code accepted=false} 表示未执行排查（值守关闭、载荷为空、队列满）。
 * 排查本身异步进行，结论稍后推送并可在工作台查看。
 */
@RestController
@RequestMapping("/api/hooks/alarm")
public class AlarmController {

    private static final Logger log = LoggerFactory.getLogger(AlarmController.class);

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    /**
     * 接收告警。令牌可用请求头 {@code X-AgentFlow-Token} 或查询参数 {@code ?token=} 传，
     * 两者都支持是因为部分监控平台的自定义 webhook 只能配置 URL，不能加请求头。
     */
    @PostMapping
    public Map<String, Object> receive(@RequestBody(required = false) String body,
                                       @RequestHeader(value = "X-AgentFlow-Token", required = false) String headerToken,
                                       @RequestParam(value = "token", required = false) String queryToken) {
        String provided = headerToken != null && !headerToken.isBlank() ? headerToken : queryToken;
        if (!alarmService.authorized(provided)) {
            log.warn("告警 webhook 令牌校验失败，已拒绝");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "令牌无效");
        }
        return alarmService.intake(AlarmParser.parse(body));
    }

    /** 值守配置与记录汇总（工作台「告警值守」面板） */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return alarmService.status();
    }

    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        return alarmService.records(limit, offset);
    }

    @DeleteMapping("/records/{id}")
    public Map<String, Object> deleteRecord(@PathVariable("id") long id) {
        if (!alarmService.deleteRecord(id)) {
            throw new IllegalArgumentException("记录不存在：" + id);
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/records")
    public Map<String, Object> clearRecords() {
        return Map.of("ok", true, "cleared", alarmService.clearRecords());
    }

    /**
     * 模拟一条告警走完整链路（受理 → 排查 → 推送）。
     * 真实告警不好造，上线前用它验证通道、指令模板与推送格式是否都通。
     */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> in = body == null ? Map.of() : body;
        Map<String, Object> result = new LinkedHashMap<>(alarmService.simulate(
                str(in.get("alertName")), str(in.get("service")), str(in.get("traceId")), str(in.get("severity"))));
        result.put("simulated", true);
        return result;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
