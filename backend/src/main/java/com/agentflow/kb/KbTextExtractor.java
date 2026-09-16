package com.agentflow.kb;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 知识库文件文本抽取：按扩展名分发。
 * 纯文本类直接读；html 剥标签；PDF 走 PDFBox；docx/xlsx 走 POI（xlsx 表头行重复拼进每行，便于检索定位列含义）。
 * 不支持的格式（旧版 .doc/.xls 等）抛 IllegalArgumentException，经全局异常处理器转 400 带可读提示。
 */
@Component
public class KbTextExtractor {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");

    /** 支持的扩展名白名单（上传入口与前端 accept 共用的口径） */
    private static final Set<String> SUPPORTED_EXTS =
            Set.of("md", "txt", "csv", "json", "log", "html", "htm", "pdf", "docx", "xlsx");

    public static boolean supported(String filename) {
        String e = ext(filename);
        return e != null && SUPPORTED_EXTS.contains(e);
    }

    private static String ext(String filename) {
        if (filename == null) {
            return null;
        }
        int i = filename.lastIndexOf('.');
        if (i < 0 || i == filename.length() - 1) {
            return null;
        }
        return filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** 抽取纯文本；不支持的格式抛 IllegalArgumentException */
    public String extract(String filename, byte[] bytes) {
        String e = ext(filename);
        if (e == null) {
            throw new IllegalArgumentException("无法识别文件类型（没有扩展名）：" + filename
                    + "。支持：md/txt/csv/json/log/html/pdf/docx/xlsx");
        }
        try {
            return switch (e) {
                case "md", "txt", "csv", "json", "log" -> new String(bytes, StandardCharsets.UTF_8);
                case "html", "htm" -> stripHtml(new String(bytes, StandardCharsets.UTF_8));
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                case "xlsx" -> extractXlsx(bytes);
                case "doc" -> throw unsupported(filename, "请在 Word 里另存为 .docx 后重新上传");
                case "xls" -> throw unsupported(filename, "请在 Excel 里另存为 .xlsx 后重新上传");
                default -> throw unsupported(filename, "支持：md/txt/csv/json/log/html/pdf/docx/xlsx");
            };
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("解析 " + filename + " 失败：" + ex.getMessage()
                    + "。文件可能已损坏或加密，请确认后重试");
        }
    }

    private static IllegalArgumentException unsupported(String filename, String hint) {
        return new IllegalArgumentException("暂不支持 " + filename + "。" + hint);
    }

    /** 实体 → 真实字符（常见五种足够覆盖知识库文档场景） */
    private static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    }

    static String stripHtml(String html) {
        String s = SCRIPT_BLOCK.matcher(html).replaceAll("");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        // 压缩标签替换留下的连续空白，保留换行
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replace('\u00A0', ' ').strip();
    }

    private static String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return ex.getText();
        }
    }

    private static String extractXlsx(byte[] bytes) throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes));
             XSSFExcelExtractor ex = new XSSFExcelExtractor(wb)) {
            ex.setIncludeSheetNames(true);
            return ex.getText();
        }
    }
}
