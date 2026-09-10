package com.agentflow.signoz;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 故障案例归档工具（写操作，强制人工放行）：把一次链路分析的结论沉淀进 docs/incidents/。
 *
 * 为什么由程序推导指纹：指纹必须跨次数稳定，检索才成立。
 * 因此 error_class / signature / span / status / services / 时间 都由链路数据算出，
 * 调用方只需要给"人才能判断"的部分：标题、现象、根因、处置、验证、证据强度。
 */
@Component
public class SigNozCaseTool implements Tool {

    private final SigNozMcpClient client;
    private final TraceFetcher fetcher;
    private final IncidentKb kb;

    public SigNozCaseTool(SigNozMcpClient client, TraceFetcher fetcher, IncidentKb kb) {
        this.client = client;
        this.fetcher = fetcher;
        this.kb = kb;
    }

    @Override
    public String name() {
        return "signoz.case";
    }

    @Override
    public String description() {
        return "把一次链路分析的结论归档为故障案例（写入 docs/incidents/，同一故障模式只累加次数、不重复建档）。"
                + "仅在用户要求归档/沉淀/记录到知识库时使用；归档前需人工确认。"
                + "指纹与涉及服务由程序从链路数据推导，调用方只需提供标题、现象、根因、处置步骤、验证方式。";
    }

    @Override
    public String argsHint() {
        return "{\"traceId\": \"32 位十六进制链路 ID\", "
                + "\"title\": \"一句话故障模式标题（如：达梦驱动不支持参数类型导致押金明细查询失败）\", "
                + "\"symptom\": \"现象：业务/用户视角看到什么\", "
                + "\"rootCause\": \"根因一句话（必须来自链路或日志里真实出现的信息，不能编造）\", "
                + "\"fix\": \"处置步骤，多条用换行或分号分隔，P0 在前\", "
                + "\"verification\": \"怎么确认修好了\", "
                + "\"confidence\": \"日志证实|span推断|证据不足\"}";
    }

    @Override
    public boolean requiresConfirm() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (!client.isConfigured()) {
            return ToolResult.note("未配置 SigNoz MCP 地址：请在 .env 设置 SIGNOZ_MCP_URL 后重启服务");
        }
        String traceId = SigNozTraceTool.resolveTraceId(args, userCommand);
        if (traceId == null) {
            return ToolResult.note("归档失败：没有识别到 trace ID。请提供 32 位十六进制链路 ID");
        }

        TraceFetcher.Fetched fetched;
        try {
            fetched = fetcher.fetch(traceId, str(args.get("timeRange")));
        } catch (Exception ex) {
            return ToolResult.note("归档失败：查询 SigNoz 出错 — " + ex.getMessage());
        }
        if (!fetched.found()) {
            return ToolResult.note("归档失败：未查到 trace " + traceId
                    + "，无法推导故障指纹。确认 trace ID、发生时间与查询环境后重试");
        }

        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(fetched.spans());
        String rootCause = str(args.get("rootCause"));
        if (rootCause == null) {
            rootCause = str(args.get("root_cause"));
        }
        List<String> fix = toList(args.get("fix"));

        IncidentKb.ArchiveResult r;
        try {
            r = kb.archive(traceId, fp, fetched.spans(),
                    str(args.get("title")), str(args.get("symptom")), rootCause,
                    str(args.get("confidence")), fix, str(args.get("verification")));
        } catch (Exception ex) {
            return ToolResult.note("归档失败：" + ex.getMessage() + "（案例未写入，请检查 " + kb.root() + " 是否可写）");
        }

        StringBuilder sb = new StringBuilder();
        if (r.alreadyRecorded()) {
            sb.append("该 trace 已归档过，未重复计数。");
        } else if (r.created()) {
            sb.append("已新建故障案例。");
        } else {
            sb.append("命中已有故障案例，已累加发生次数（同一故障模式不重复建档）。");
        }
        sb.append("\n案例：").append(r.id())
                .append("（累计第 ").append(r.occurrences()).append(" 次）")
                .append("\n文件：").append(kb.root().resolve(r.caseFile()))
                .append("\n指纹：").append(TraceDigest.renderFingerprint(fp));
        if (r.created()) {
            sb.append("\n提示：案例中的现象/根因/处置/验证来自本次分析，"
                    + "如未提供会留占位文本，可在该 Markdown 文件里直接补充。");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", r.id());
        result.put("caseFile", r.caseFile());
        result.put("occurrences", r.occurrences());
        result.put("created", r.created());
        result.put("alreadyRecorded", r.alreadyRecorded());

        return new ToolResult("list", result, List.of(sb.toString().split("\n")),
                "故障案例归档完成 · " + r.id() + " · 累计第 " + r.occurrences() + " 次");
    }

    /** fix 可能是 List（规划器结构化输出）或用换行/分号分隔的字符串 */
    static List<String> toList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        }
        for (String part : String.valueOf(raw).split("[\\n;；]+")) {
            String t = part.replaceAll("^[\\s\\-\\d.、]+", "").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
