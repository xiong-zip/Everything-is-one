package com.agentflow.kb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文本抽取：纯文本直读、html 剥标签、不支持格式的可读报错 */
class KbTextExtractorTest {

    private final KbTextExtractor extractor = new KbTextExtractor();

    @Test
    void plainTextAndMarkdownPassThrough() {
        String md = "# 标题\n正文内容";
        assertEquals(md, extractor.extract("readme.md", md.getBytes(StandardCharsets.UTF_8)));
        assertEquals("a,b,c", extractor.extract("data.csv", "a,b,c".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void htmlStripsTagsScriptsAndEntities() {
        String html = """
                <html><head><script>var x=1;</script><style>.a{}</style></head>
                <body><h1>部署手册</h1><p>先安装&nbsp;JDK &amp; Maven</p></body></html>
                """;
        String text = extractor.extract("manual.html", html.getBytes(StandardCharsets.UTF_8));
        assertTrue(text.contains("部署手册"));
        assertTrue(text.contains("先安装 JDK & Maven"));
        assertFalse(text.contains("<"));
        assertFalse(text.contains("script"));
        assertFalse(text.contains(".a{}"));
    }

    @Test
    void unsupportedExtensionsGiveReadableErrors() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract("legacy.doc", new byte[10]));
        assertTrue(ex.getMessage().contains("docx"));

        ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract("old.xls", new byte[10]));
        assertTrue(ex.getMessage().contains("xlsx"));

        ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract("photo.png", new byte[10]));
        assertTrue(ex.getMessage().contains("支持"));

        ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract("noext", new byte[10]));
        assertTrue(ex.getMessage().contains("扩展名"));
    }

    @Test
    void supportedExtensionCheck() {
        assertTrue(KbTextExtractor.supported("a.md"));
        assertTrue(KbTextExtractor.supported("b.PDF"));
        assertTrue(KbTextExtractor.supported("c.xlsx"));
        assertFalse(KbTextExtractor.supported("d.doc"));
        assertFalse(KbTextExtractor.supported("e.png"));
        assertFalse(KbTextExtractor.supported(null));
        assertFalse(KbTextExtractor.supported("noext"));
    }

    @Test
    void brokenPdfReportsParseFailure() {
        // 非 PDF 魔数的字节喂给 pdfbox：应转成可读的解析失败提示
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract("fake.pdf", "这不是PDF".repeat(100).getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("解析") || ex.getMessage().contains("失败"));
    }
}
