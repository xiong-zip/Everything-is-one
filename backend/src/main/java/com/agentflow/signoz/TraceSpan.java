package com.agentflow.signoz;

/**
 * 一条 trace span 的精简模型：只保留根因分析需要的字段。
 * 字段取值来自 SigNoz MCP 返回的 span 数据（缺失的字段留空，不补默认值，避免编造）。
 */
public record TraceSpan(String service,
                        String name,
                        String spanId,
                        String parentSpanId,
                        long durationNano,
                        boolean hasError,
                        String statusCode,
                        String statusMessage,
                        String spanKind,
                        String timestamp,
                        String httpStatus,
                        String env) {

    /** 耗时毫秒，保留两位 */
    public double durationMs() {
        return durationNano / 1_000_000.0;
    }

    /** 形如 pay-service / [HTTP] POST 获取账户余额 */
    public String label() {
        return service + " / " + name;
    }
}
