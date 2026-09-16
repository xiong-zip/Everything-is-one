package com.agentflow.signoz;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SigNoz 链路分析工具（只读）：查 span 树与错误日志，输出根因优先的摘要，
 * 并在分析前比对故障案例知识库——命中已知模式就把历史根因与处置方案一并给出。
 *
 * 归档写操作在 {@link SigNozCaseTool}，两者分开是为了让"分析"无需人工放行，
 * 只有真正落盘的写操作才走确认流程。
 */
@Component
public class SigNozTraceTool implements Tool {

    /** 32 位十六进制即 trace ID：规划器曾把它当"无意义哈希串"，这里显式纠正 */
    private static final Pattern TRACE_ID = Pattern.compile("\\b([0-9a-fA-F]{32})\\b");

    private final SigNozMcpClient client;
    private final TraceFetcher fetcher;
    private final IncidentKb kb;
    private final TraceAnalysisStore store;

    public SigNozTraceTool(SigNozMcpClient client, TraceFetcher fetcher, IncidentKb kb,
                          TraceAnalysisStore store) {
        this.client = client;
        this.fetcher = fetcher;
        this.kb = kb;
        this.store = store;
    }

    @Override
    public String name() {
        return "signoz.trace";
    }

    @Override
    public String description() {
        if (!client.isConfigured()) {
            return "SigNoz 链路分析（当前未配置 MCP 地址：请在 .env 设置 SIGNOZ_MCP_URL 后重启）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("分析 SigNoz 分布式链路：给出 trace ID 即可查出 span 树与 ERROR/WARN 日志，")
                .append("输出根因、失败传播链、耗时与状态矛盾（如 HTTP 200 但业务失败）。")
                .append("用户发来的一串 32 位十六进制字符（如 c4ea16342cf1a0526d22fa20d57c9e2a）就是 trace ID，")
                .append("应当直接用本工具分析，不要当成无意义哈希串、也不要要求用户补充说明。")
                .append("分析前会自动比对故障案例知识库，命中已知模式会给出历史根因与处置方案。")
                .append("分析完成后（尤其失败链路）应紧接着用 gitlab.changes 传同一个 traceId 关联故障前的代码变更，")
                .append("回答「谁改坏的」。只读，不修改任何数据。");
        int cases = kb.entries().size();
        sb.append(cases == 0 ? "（知识库当前为空，属正常冷启动）" : "（知识库已有 " + cases + " 条案例）");
        return sb.toString();
    }

    @Override
    public String argsHint() {
        return "{\"traceId\": \"32 位十六进制链路 ID（如 c4ea16342cf1a0526d22fa20d57c9e2a）\", "
                + "\"timeRange\": \"可选：30m|1h|6h|24h|7d，默认 24h，查不到自动扩到 7d\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (!client.isConfigured()) {
            return ToolResult.note("未配置 SigNoz MCP 地址：请在 .env 设置 SIGNOZ_MCP_URL（如 http://192.168.2.111:18000/mcp）后重启服务");
        }
        String traceId = resolveTraceId(args, userCommand);
        if (traceId == null) {
            return ToolResult.note("没有识别到 trace ID：请提供 32 位十六进制链路 ID，例如「分析链路 c4ea16342cf1a0526d22fa20d57c9e2a」");
        }

        TraceFetcher.Fetched fetched;
        try {
            fetched = fetcher.fetch(traceId, str(args.get("timeRange")));
        } catch (Exception ex) {
            return ToolResult.note("查询 SigNoz 失败：" + ex.getMessage());
        }
        if (!fetched.found()) {
            return notFound(traceId, fetched);
        }

        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(fetched.spans());
        String kbSection = null;
        IncidentKb.Match match = kb.match(fp, IncidentKb.servicesOf(fetched.spans()));
        if (match.actionable()) {
            kbSection = kb.renderMatchSection(match);
        }
        String digest = TraceDigest.render(traceId, fetched.rangeUsed(), fetched.spans(),
                fetched.logs(), kbSection, fetched.notice());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("timeRange", fetched.rangeUsed());
        result.put("spanCount", fetched.spans().size());
        result.put("failed", fetched.failed());
        result.put("logCount", fetched.logs().size());
        result.put("fingerprint", TraceDigest.renderFingerprint(fp));
        result.put("kbMatch", match.strength().name() + (match.entry() == null ? "" : ":" + match.entry().id()));
        // services / traceTime 供后续步骤（如 gitlab.changes 变更关联）直接取用，不必再查一次
        result.put("services", IncidentKb.servicesOf(fetched.spans()));
        String firstSeen = IncidentKb.timeInfo(fetched.spans()).firstSeen();
        result.put("traceTime", firstSeen.length() >= 16 ? firstSeen.substring(0, 16).replace('T', ' ') : firstSeen);
        TraceSpan root = TraceDigest.mainRoot(fetched.spans());
        if (root != null) {
            result.put("totalMs", TraceDigest.fmt(root.durationMs()));
        }
        TraceSpan origin = TraceDigest.errorOrigin(fetched.spans());
        if (origin != null) {
            result.put("failurePoint", origin.label());
        }

        List<String> lines = new ArrayList<>();
        for (String l : digest.split("\n")) {
            lines.add(l);
        }
        String summary = fetched.failed()
                ? "SigNoz 链路分析完成 · 失败链路 · " + (origin == null ? "" : origin.label())
                + (match.actionable() ? " · 命中案例 " + match.entry().id() : " · 未命中知识库")
                : "SigNoz 链路分析完成 · 成功链路 · 总耗时 " + (root == null ? "?" : TraceDigest.fmt(root.durationMs())) + "ms";

        // 落一条分析记录供工作台「链路分析」面板查看；失败只记日志，不影响本次分析结果
        store.record(new TraceAnalysisStore.AnalysisRecord(0, traceId, "", fetched.rangeUsed(),
                fetched.spans().size(), IncidentKb.servicesOf(fetched.spans()), true, fetched.failed(),
                origin == null ? "" : origin.label(), fp.errorClass(), fp.signature(),
                root == null ? 0 : root.durationMs(), firstEnv(fetched.spans()),
                match.actionable() && match.entry() != null ? match.entry().id() : "",
                match.strength().name(), 1, digest));

        return new ToolResult("list", result, lines, summary);
    }

    private static String firstEnv(List<TraceSpan> spans) {
        for (TraceSpan s : spans) {
            if (s.env() != null && !s.env().isBlank()) {
                return s.env();
            }
        }
        return "";
    }

    /** 未找到时的降级提示：把服务端"该 trace 存在于某时间段"的提示原样带出，别让用户白猜 */
    private ToolResult notFound(String traceId, TraceFetcher.Fetched fetched) {
        StringBuilder sb = new StringBuilder();
        sb.append("未在 SigNoz 中查到 trace：").append(traceId)
                .append("（已尝试 24h 与 7d 两个时间窗）");
        if (fetched.notice() != null && !fetched.notice().isBlank()) {
            sb.append("\n服务端提示：").append(TraceDigest.oneLine(fetched.notice(), 300));
        }
        sb.append("\n可能原因：trace ID 输入有误；超出数据保留期；采样未保留；")
                .append("trace 未写入 SigNoz；查询环境与 trace 所在环境不一致。")
                .append("\n建议补充：大概发生时间、来源服务或接口、环境。");
        // 未找到也留一条记录：便于回看"我查过哪些 trace 但没查到"
        store.record(new TraceAnalysisStore.AnalysisRecord(0, traceId, "", fetched.rangeUsed(),
                0, List.of(), false, false, "", "", "", 0, "", "", "NONE", 1, sb.toString()));
        return ToolResult.note(sb.toString());
    }

    /** trace ID 解析：显式参数优先，其次从用户指令里提取 32 位十六进制串 */
    static String resolveTraceId(Map<String, Object> args, String userCommand) {
        String explicit = str(args.get("traceId"));
        if (explicit != null) {
            Matcher m = TRACE_ID.matcher(explicit);
            if (m.find()) {
                return m.group(1).toLowerCase();
            }
            if (explicit.matches("[0-9a-fA-F\\-]{16,}")) {
                return explicit.toLowerCase();
            }
        }
        if (userCommand != null) {
            Matcher m = TRACE_ID.matcher(userCommand);
            if (m.find()) {
                return m.group(1).toLowerCase();
            }
        }
        return explicit;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
