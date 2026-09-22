package com.agentflow.signoz;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键故障报告工具（signoz.report，只读）：给一个 trace ID，
 * 自动聚合链路分析、代码变更关联、K8s 实例状态与告警值守时间线，
 * 生成结构化故障报告（影响面 / 时间线 / 根因分析 / 处置建议 / 待办事项）。
 *
 * 与 signoz.trace 的分工：trace 回答「这条链路发生了什么」，report 回答
 * 「这次故障的完整故事与后续动作」——把散在各工具里的证据一次串起来。
 * 归档到案例库仍是 signoz.case 的职责（写操作需人工放行）。
 */
@Component
public class PostmortemTool implements Tool {

    /** 报告正文较长，时间线/结果卡最多渲染这么多行，全文以 result.report 为准 */
    private static final int MAX_LINES = 100;

    private final PostmortemService service;
    private final SigNozMcpClient client;

    public PostmortemTool(PostmortemService service, SigNozMcpClient client) {
        this.service = service;
        this.client = client;
    }

    @Override
    public String name() {
        return "signoz.report";
    }

    @Override
    public String description() {
        if (!client.isConfigured()) {
            return "一键故障报告（当前未配置 SigNoz MCP 地址：请在 .env 设置 SIGNOZ_MCP_URL 后重启）";
        }
        return "一键故障报告（postmortem，只读）：给 trace ID 自动聚合四路证据——链路分析（根因/传播链）、"
                + "变更关联（谁改坏的）、K8s 实例状态、告警值守时间线——生成结构化故障报告："
                + "影响面 / 时间线 / 根因分析（含置信度）/ 处置建议 / 待办事项。"
                + "用户要求「出故障报告」「复盘」「postmortem」「汇总这次故障」时用本工具（也可在链路分析面板一键生成）。"
                + "报告只聚合证据不落盘；要把结论沉淀为案例用 signoz.case 归档。";
    }

    @Override
    public String argsHint() {
        return "{\"traceId\": \"32 位十六进制链路 ID（如 c4ea16342cf1a0526d22fa20d57c9e2a）\", "
                + "\"timeRange\": \"可选：30m|1h|6h|24h|7d，默认 24h，查不到自动扩到 7d\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (!client.isConfigured()) {
            return ToolResult.note("未配置 SigNoz MCP 地址：请在 .env 设置 SIGNOZ_MCP_URL 后重启服务");
        }
        String traceId = SigNozTraceTool.resolveTraceId(args, userCommand);
        if (traceId == null || !traceId.matches("[0-9a-fA-F]{16,}")) {
            return ToolResult.note("没有识别到 trace ID：请提供 32 位十六进制链路 ID，例如「给 c4ea16342cf1a0526d22fa20d57c9e2a 生成故障报告」");
        }
        PostmortemService.Report report;
        try {
            report = service.generate(traceId, str(args.get("timeRange")));
        } catch (IllegalArgumentException ex) {
            return ToolResult.note(ex.getMessage());
        } catch (Exception ex) {
            return ToolResult.note("生成故障报告失败：" + ex.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>(report.meta());
        result.put("engine", report.engine());
        result.put("report", report.markdown());

        List<String> lines = new ArrayList<>();
        for (String l : report.markdown().split("\n")) {
            if (lines.size() < MAX_LINES) {
                lines.add(l);
            }
        }
        if (lines.size() == MAX_LINES) {
            lines.add("…（报告较长已截断展示，全文见结果 report 字段）");
        }
        String summary = "故障报告已生成（" + ("llm".equals(report.engine()) ? "模型归纳" : "模板拼装")
                + "）· " + report.meta().getOrDefault("services", List.of())
                + (Boolean.TRUE.equals(report.meta().get("failed")) ? " · 失败链路" : "");
        return new ToolResult("list", result, lines, summary);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
