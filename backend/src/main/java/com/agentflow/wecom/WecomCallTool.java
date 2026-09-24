package com.agentflow.wecom;

import com.agentflow.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 企微通用兜底工具：wecom.call({"service", "method", "args"}) 可以触达 wecom-cli 的全部服务
 * （邮件、待办、日程、会议、微盘、通讯录、智能表格……），方法名支持 resource.method 两段式（如 records.add）。
 *
 * <p>参数经 stdin（{@code --json -}）原样送达，数组/嵌套对象都不受命令行转义影响。
 * 调用失败若是参数问题（必填缺失/类型不匹配），会自动拉取该方法的 schema 附在返回里，
 * 让 ReAct/混合模式的 Agent 能就地修正重试（自愈，同数据库查询列名自愈的思路）。
 *
 * <p>读写判定是静态的（Tool.requiresConfirm 不接收运行期参数），所以这里<b>一律强制人工确认</b>：
 * 通用通道里方法行为未知，宁可多按一次确认，也不做盲写。高频只读场景应走专用只读工具（wecom.doc.query）。
 */
@Component
public class WecomCallTool extends WecomToolSupport {

    /** service / method 只允许小写字母数字与连字符，且不得以 - 开头：防止把参数变成 CLI 旗标 */
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** 企微文档 ID 形态：字母+数字前缀_较长 base64ish 串（如 s3_ASYA…、w3_AFsA…、a1_AFsA…） */
    private static final Pattern DOCID_IN_TEXT = Pattern.compile("[a-z]\\d_[A-Za-z0-9_-]{18,}");

    private final WecomStore store;

    public WecomCallTool(WecomCliRunner cli, WecomStore store) {
        super(cli);
        this.store = store;
    }

    @Override
    public String name() {
        return "wecom.call";
    }

    @Override
    public String description() {
        return "企业微信通用调用（执行前必须经用户确认）：可调用 wecom-cli 的任意服务与方法——"
                + "doc 文档、sheet 表格、smartsheet 智能表格（get 查表结构；records.add/update 增改记录，行结构用 [{\"values\":{\"字段名\":\"内容\"}}]，必填 docid/sheet_id/records）、"
                + "mail 邮件、todo 待办、calendar 日程、meeting 会议、disk 微盘、contact 通讯录、media 媒体。"
                + "method 支持两段式（如 records.add）。args 为该方法的参数对象（参数名用 snake_case，如 docid/sheet_id/records）；"
                + "参数不对时工具会返回该方法的 schema 说明，按说明修正后重试即可。需部署机 wecom-cli 已扫码授权";
    }

    @Override
    public String argsHint() {
        return "{\"service\": \"服务名（如 smartsheet）\", \"method\": \"方法名（如 records.add）\", "
                + "\"args\": {\"docid\": \"文档 ID\", \"sheet_id\": \"子表 ID\", \"records\": \"行数据数组\"}}";
    }

    @Override
    public boolean requiresConfirm() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        ToolResult g = guard();
        if (g != null) {
            return g;
        }
        String service = str(args.get("service"));
        String method = str(args.get("method"));
        if (service == null || method == null) {
            return ToolResult.note("wecom.call 需要 service 与 method 参数");
        }
        List<String> cmd = buildCommand(service, method);
        if (cmd == null) {
            return ToolResult.note("非法的 service/method（只允许小写字母、数字与连字符，且不能以 - 开头）："
                    + service + " " + method);
        }
        Map<String, Object> body = parseArgs(args.get("args"));
        // 规划器实测会把 args 压成空串丢掉 docid：用户指令里几乎总带着文档 ID（格式特征极强），
        // 从指令全文兜底提取，避免整次调用空转
        if (!body.containsKey("docid")) {
            String extracted = extractDocid(userCommand);
            if (extracted != null) {
                body.put("docid", extracted);
            }
        }
        ToolResult result = runJson(cmd, body);
        return result.result() != null && result.result().containsKey("note")
                ? selfHeal(cmd, service, method, result)
                : result;
    }

    /** 从指令文本提取第一个疑似 docid 的 token（纯函数，单测覆盖） */
    static String extractDocid(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = DOCID_IN_TEXT.matcher(text);
        return m.find() ? m.group() : null;
    }

    /**
     * 参数自愈：失败信息若指向参数问题，拉取该方法的 schema（{@code --schema} 旗标不走请求体，
     * 无转义风险）附在返回里；命令不存在则回该方法所在服务的可用方法清单。
     */
    private ToolResult selfHeal(List<String> cmd, String service, String method, ToolResult failed) {
        String note = String.valueOf(failed.result().get("note"));
        boolean paramError = note.contains("必填") || note.contains("参数错误") || note.contains("类型不匹配")
                || note.contains("unrecognized subcommand") || note.contains("unexpected argument");
        if (!paramError) {
            return failed;
        }
        if (note.contains("unrecognized subcommand") || note.contains("unexpected argument")) {
            List<String> methods = new ArrayList<>();
            for (WecomStore.Capability cap : store.capabilities()) {
                if (cap.service().equals(service)) {
                    methods.add(cap.method());
                }
            }
            if (!methods.isEmpty()) {
                return ToolResult.note(note + "\n服务 " + service + " 可用的方法：" + String.join("、", methods)
                        + "\n请换用正确的方法名重试");
            }
            return failed;
        }
        List<String> schemaCmd = new ArrayList<>(cmd);
        schemaCmd.add("--schema");
        WecomCliRunner.CliResult r = cli.exec(schemaCmd);
        if (!r.ok() || r.output().isBlank()) {
            return failed;
        }
        String schema = r.output().strip();
        if (schema.length() > 2500) {
            schema = schema.substring(0, 2500) + "\n…（schema 过长已截断）";
        }
        return ToolResult.note(note + "\n该方法的参数 schema 如下，请按结构修正 args 后重试：\n" + schema);
    }

    /** 拼出 [service, resource?, method]；非法 token 返回 null（纯函数，单测覆盖） */
    static List<String> buildCommand(String service, String method) {
        if (!SAFE_TOKEN.matcher(service).matches()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        out.add(service);
        int dot = method.indexOf('.');
        if (dot > 0 && dot < method.length() - 1) {
            String resource = method.substring(0, dot);
            String op = method.substring(dot + 1);
            if (!SAFE_TOKEN.matcher(resource).matches() || !SAFE_TOKEN.matcher(op).matches()) {
                return null;
            }
            out.add(resource);
            out.add(op);
        } else if (SAFE_TOKEN.matcher(method).matches()) {
            out.add(method);
        } else {
            return null;
        }
        return out;
    }

    /**
     * 解析 args：接受对象、JSON 字符串（规划器两种都传过）、空串/null。
     * JSON 字符串解析失败时按空对象处理并如实告知（纯函数，单测覆盖）。
     */
    static Map<String, Object> parseArgs(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (k != null) {
                    out.put(String.valueOf(k), v);
                }
            });
            return out;
        }
        if (raw instanceof String s && !s.isBlank() && s.strip().startsWith("{")) {
            try {
                Object parsed = MAPPER.readValue(s.strip(), Object.class);
                if (parsed instanceof Map<?, ?> m) {
                    Map<String, Object> out = new java.util.LinkedHashMap<>();
                    m.forEach((k, v) -> {
                        if (k != null) {
                            out.put(String.valueOf(k), v);
                        }
                    });
                    return out;
                }
            } catch (Exception ignored) {
                // 非法 JSON 当空对象，CLI 会报缺参数并触发 schema 自愈
            }
        }
        return new java.util.LinkedHashMap<>();
    }
}
