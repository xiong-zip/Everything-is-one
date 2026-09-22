package com.agentflow.alarm;

/**
 * 一次告警事件的归一化结果：把各家监控平台千奇百怪的 webhook 载荷压成同一组字段，
 * 后续的排查指令生成、去重、落库、推送都只依赖它，不再关心原始形态。
 *
 * @param source    来源标识（载荷里的 source 字段，缺省 webhook）
 * @param alertName 告警名称
 * @param severity  级别（critical / warning / info …）
 * @param status    状态：firing 触发中 / resolved 已恢复
 * @param service   涉及服务名，用于无 trace ID 时按服务排查
 * @param traceId   32 位十六进制链路 ID，有它就能直接跑链路分析
 * @param message   告警摘要文本
 * @param dedupKey  去重键：同一告警在窗口内反复触发只排查一次
 * @param raw       原始载荷（截断后落库，便于事后核对解析是否正确）
 */
public record AlarmEvent(String source, String alertName, String severity, String status,
                         String service, String traceId, String message, String dedupKey, String raw) {

    /** 已恢复的告警不需要再排查根因，只留痕 */
    public boolean resolved() {
        return "resolved".equalsIgnoreCase(status);
    }

    public boolean hasTraceId() {
        return traceId != null && !traceId.isBlank();
    }

    public boolean hasService() {
        return service != null && !service.isBlank();
    }

    /** 展示用标题：优先告警名，其次服务名，最后兜底 */
    public String title() {
        if (alertName != null && !alertName.isBlank()) {
            return alertName;
        }
        if (hasService()) {
            return "服务 " + service + " 告警";
        }
        return "监控告警";
    }

    /** 落库/展示用的级别，缺省 info（不猜成 critical，避免无端 @ 值班人） */
    public String severityOrInfo() {
        return severity == null || severity.isBlank() ? "info" : severity;
    }
}
