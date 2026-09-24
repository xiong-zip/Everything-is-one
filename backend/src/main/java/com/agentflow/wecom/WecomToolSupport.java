package com.agentflow.wecom;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * wecom.* 工具的公共骨架：执行前的可用性闸门（总开关 / CLI 是否安装 / 是否已授权）
 * 与「跑 CLI → 解析 JSON 输出 → 转 ToolResult」的公共路径。
 *
 * <p>description() 刻意保持静态文案（不查授权状态）：它会在每次工具清单变化时被调用来
 * 重建规划 prompt，里面跑子进程会把提示词构建变成秒级操作；可用性检查放到 execute() 里做。
 */
abstract class WecomToolSupport implements Tool {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final WecomCliRunner cli;

    protected WecomToolSupport(WecomCliRunner cli) {
        this.cli = cli;
    }

    /** 执行前闸门：未启用 / 未安装 / 未授权时给出可行动的引导语 */
    protected ToolResult guard() {
        if (!cli.enabled()) {
            return ToolResult.note("企业微信工具未启用（.env 设 AGENTFLOW_WECOM_ENABLED=true 后重启）");
        }
        String auth = cli.authStatus();
        if ("cli-missing".equals(auth)) {
            return ToolResult.note("部署机未安装 wecom-cli（npm install -g @wecom/cli，需 Node 18+），安装后在工作台「企业微信」面板确认授权");
        }
        if (!"authorized".equals(auth)) {
            return ToolResult.note("wecom-cli 尚未授权：请在部署机终端执行 wecom-cli auth init 扫码登录，完成后到工作台「企业微信」面板点「检查授权」");
        }
        return null;
    }

    /** 跑一次子命令并把输出转成 ToolResult：stdout 是 JSON 就解析成 Map，否则原样带回（截断） */
    protected ToolResult runJson(List<String> args) {
        return runJson(args, null);
    }

    /**
     * 带 JSON 请求体执行：请求体作为 {@code --json <JSON>} 参数传入。
     * Windows 上的引号安全由 {@link WecomCliRunner#escapeWinArg} 保证（内部双引号预转义），
     * 数组、嵌套对象都能原样到达——实测经 Java→CreateProcess→clap 整链路可还原。
     */
    protected ToolResult runJson(List<String> args, Map<String, Object> body) {
        List<String> full = new java.util.ArrayList<>(args);
        if (body != null) {
            full.add("--json");
            full.add(toJson(body));
        }
        WecomCliRunner.CliResult r = cli.exec(full);
        if (r.cliMissing()) {
            return ToolResult.note("部署机未安装 wecom-cli（npm install -g @wecom/cli）");
        }
        if (r.timeout()) {
            return ToolResult.note("wecom-cli 执行超时已终止，可稍后重试或调大 AGENTFLOW_WECOM_TIMEOUT_MS");
        }
        String out = r.output().strip();
        if (r.exitCode() != 0) {
            String detail = out.isEmpty() ? ("退出码 " + r.exitCode()) : out;
            return ToolResult.note("wecom-cli 调用失败：" + firstLines(detail, 15));
        }
        if (out.isEmpty()) {
            return ToolResult.note("wecom-cli 执行成功（无输出）");
        }
        if (out.startsWith("{") || out.startsWith("[")) {
            try {
                Object parsed = MAPPER.readValue(out, Object.class);
                if (parsed instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) m;
                    return new ToolResult("json", map, null, summarize(map));
                }
                return new ToolResult("json", Map.of("output", parsed), null, String.valueOf(parsed));
            } catch (Exception ignored) {
                // 输出宣称是 JSON 但解析失败：原样带回比抛错更有用
            }
        }
        return new ToolResult("json", Map.of("output", out), null, firstLines(out, 10));
    }

    protected static String toJson(Map<String, Object> args) {
        try {
            return MAPPER.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception ex) {
            return "{}";
        }
    }

    protected static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 识别规划器把 argsHint 占位符当真实值传下来的情况（实测出现过
     * docid="&lt;上一步定位到的文档ID或URL&gt;"）。这类值直接传给 CLI 只会换来
     * 一条无用的参数报错，应当拦下来引导先搜索。
     */
    static boolean isPlaceholderValue(String v) {
        if (v == null) {
            return true; // 未提供也按占位处理：读路径借此转「按名搜索」，写路径据此要求补真实 docid
        }
        String t = v.trim();
        return t.isEmpty() || t.contains("<") || t.contains(">") || t.contains("上一步")
                || t.equalsIgnoreCase("docid") || t.equalsIgnoreCase("url")
                || t.startsWith("如") && t.length() < 30;
    }

    private static String firstLines(String s, int maxLines) {
        String[] lines = s.split("\n");
        if (lines.length <= maxLines) {
            return s;
        }
        return String.join("\n", java.util.Arrays.copyOf(lines, maxLines)) + "\n…（输出过长已截断）";
    }

    private static String summarize(Map<String, Object> map) {
        Object docs = map.get("docs") != null ? map.get("docs") : map.get("data");
        if (docs instanceof List<?> l && !l.isEmpty()) {
            return "返回 " + l.size() + " 条结果（字段见 JSON）";
        }
        Object errcode = map.get("errcode");
        if (errcode != null && !"0".equals(String.valueOf(errcode))) {
            return "调用返回错误码 " + errcode + "：" + map.get("errmsg");
        }
        return "调用成功（结果见 JSON）";
    }
}
