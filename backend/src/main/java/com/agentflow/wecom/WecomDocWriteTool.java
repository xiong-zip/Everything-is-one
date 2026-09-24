package com.agentflow.wecom;

import com.agentflow.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企微文档写工具：新建文档 / 追加内容 / 覆盖写。全部是远端副作用操作，
 * requiresConfirm=true，引擎强制走人工确认流程后才执行。
 */
@Component
public class WecomDocWriteTool extends WecomToolSupport {

    public WecomDocWriteTool(WecomCliRunner cli) {
        super(cli);
    }

    @Override
    public String name() {
        return "wecom.doc.write";
    }

    @Override
    public String description() {
        return "企业微信文档写操作（执行前必须经用户确认）：新建文档（op=create，需 docName 与 content，可选 docType=doc|sheet|smartsheet|smartpage，"
                + "smartpage 为智能文档、按 markdown 导入创建）、在文档末尾追加内容（op=append，需 docid 与 content，仅普通文档）、"
                + "覆盖文档全部内容（op=overwrite，需 docid 与 content，仅普通文档）、重命名（op=rename，需 docid 与 docName）。"
                + "需部署机 wecom-cli 已扫码授权（工作台 → 企业微信）";
    }

    @Override
    public String argsHint() {
        return "{\"op\": \"create|append|overwrite|rename\", \"docName\": \"文档标题（create 必填；rename 时为新标题）\", "
                + "\"content\": \"Markdown/纯文本内容\", \"docType\": \"doc|sheet|smartsheet|smartpage（可选，默认 doc）\", "
                + "\"docid\": \"文档 ID 或完整 URL（append/overwrite/rename 必填）\"}";
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
        String op = str(args.get("op"));
        if (op == null) {
            return ToolResult.note("wecom.doc.write 需要 op 参数");
        }
        boolean needsContent = !"rename".equalsIgnoreCase(op);
        String content = str(args.get("content"));
        if (needsContent && content == null) {
            return ToolResult.note("wecom.doc.write 需要 op 与 content 参数");
        }
        return switch (op.toLowerCase()) {
            case "create" -> create(args, content);
            case "append" -> write("append", args, content);
            case "overwrite" -> write("overwrite", args, content);
            case "rename" -> rename(args);
            default -> ToolResult.note("不支持的写操作：" + op + "（仅 create / append / overwrite / rename）");
        };
    }

    private ToolResult create(Map<String, Object> args, String content) {
        String docName = str(args.get("docName"));
        if (docName == null) {
            docName = str(args.get("doc_name"));
        }
        if (docName == null) {
            return ToolResult.note("op=create 需要 docName 参数（文档标题）");
        }
        String docType = str(args.get("docType"));
        // 智能文档：内容按 markdown 文件导入创建（CLI 的 smartpage create 不接受正文，import 才带内容）
        if ("smartpage".equals(docType)) {
            return createSmartpage(docName, content);
        }
        // 请求体经 stdin（--json -）传入：命令行传 JSON 的双引号会被 Java→Windows 转义拆碎（实测）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("doc_name", docName);
        body.put("doc_type", docType == null ? "doc" : docType);
        body.put("content", content);
        return runJson(List.of("doc", "create"), body);
    }

    private ToolResult createSmartpage(String docName, String content) {
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempFile("af-smartpage", ".md");
            java.nio.file.Files.writeString(tmp, content, java.nio.charset.StandardCharsets.UTF_8);
            return runJson(List.of("smartpage", "import",
                    "--name", docName,
                    "--file-path", tmp.toString()));
        } catch (Exception ex) {
            return ToolResult.note("智能文档创建失败（写临时 markdown 失败）：" + ex.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private ToolResult rename(Map<String, Object> args) {
        String docid = resolveDocid(args);
        String newName = str(args.get("docName"));
        if (newName == null) {
            newName = str(args.get("new_name"));
        }
        if (docid == null || newName == null) {
            return ToolResult.note("op=rename 需要真实 docid 与 docName（新标题）参数（可用 wecom.doc.query 先搜索定位）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docid", docid);
        body.put("new_name", newName);
        return runJson(List.of("doc", "names", "update"), body);
    }

    private ToolResult write(String op, Map<String, Object> args, String content) {
        String docid = resolveDocid(args);
        if (docid == null) {
            return ToolResult.note("op=" + op + " 需要真实 docid 参数（文档 ID 或完整 URL，可用 wecom.doc.query 先搜索定位；写操作不自动猜测目标文档）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docid", docid);
        body.put("content", content);
        return runJson(List.of("doc", "contents", op), body);
    }

    /** 写操作解析 docid：占位符一律视为未提供，绝不带着占位符或猜测值去改文档 */
    private static String resolveDocid(Map<String, Object> args) {
        String docid = str(args.get("docid"));
        if (docid == null) {
            docid = str(args.get("url"));
        }
        return isPlaceholderValue(docid) ? null : docid;
    }
}
