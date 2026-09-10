package com.agentflow.signoz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 知识库读写：建档、去重累加、幂等、指纹匹配 */
class IncidentKbTest {

    private static final String MSG = "### Error querying database.  Cause: dm.jdbc.driver.DMException: "
            + "Not support this type\n### The error may involve com.zoe.pay.business.dao."
            + "SecurityDepositDetailDao.selectListByQuery";

    private static List<TraceSpan> failingSpans(String traceTs) {
        return List.of(
                new TraceSpan("gateway-service", "gateway.server.requests", "s1", "", 58_000_000L, false,
                        "Unset", "", "Server", traceTs, "", "dev"),
                new TraceSpan("pay-service", "[HTTP] POST 获取账户余额", "s2", "s1", 35_000_000L, true,
                        "Error", MSG, "Server", traceTs, "200", "dev"));
    }

    private static IncidentKb kb(Path dir) {
        return new IncidentKb(dir.toString());
    }

    @Test
    void archivesNewCaseAndIndex(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        List<TraceSpan> spans = failingSpans("2026-09-08T01:48:45.880000000Z");
        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(spans);

        IncidentKb.ArchiveResult r = kb.archive("trace0000000000000000000000000001", fp, spans,
                "达梦驱动不支持参数类型", "账户信息获取失败", "参数 JdbcType 为 null", "日志证实",
                List.of("显式指定 JdbcType", "修正异常状态映射"), "重跑接口确认无 Error");

        assertTrue(r.created());
        assertFalse(r.alreadyRecorded());
        assertEquals(1, r.occurrences());
        assertTrue(r.id().startsWith("INC-20260908-"), r.id());

        Path caseFile = kb.root().resolve(r.caseFile());
        assertTrue(Files.isRegularFile(caseFile), "案例文件应已创建");
        String text = Files.readString(caseFile, StandardCharsets.UTF_8);
        assertTrue(text.contains("dm.jdbc.driver.DMException"), text);
        assertTrue(text.contains("显式指定 JdbcType"), "处置步骤应写入");
        assertTrue(text.contains("## 关联"), text);

        assertEquals(1, kb.entries().size());
        assertEquals(r.id(), kb.entries().get(0).id());
        assertEquals(1, kb.entries().get(0).occurrences());
    }

    @Test
    void secondArchiveOfSameTraceIsIdempotent(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        List<TraceSpan> spans = failingSpans("2026-09-08T01:48:45.880000000Z");
        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(spans);
        String traceId = "trace0000000000000000000000000001";

        kb.archive(traceId, fp, spans, "t", "s", "rc", "日志证实", List.of("f"), "v");
        IncidentKb.ArchiveResult again = kb.archive(traceId, fp, spans, "t", "s", "rc", "日志证实",
                List.of("f"), "v");

        assertTrue(again.alreadyRecorded(), "同一 trace 重复归档不应重复计数");
        assertEquals(1, again.occurrences());
        assertEquals(1, kb.entries().size());
    }

    @Test
    void sameFingerprintAccumulatesInsteadOfNewCase(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        List<TraceSpan> first = failingSpans("2026-09-08T01:48:45.880000000Z");
        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(first);
        kb.archive("trace0000000000000000000000000001", fp, first, "t", "s", "rc", "日志证实",
                List.of("f"), "v");

        // 另一个时间、另一个 trace，但指纹相同 → 应累加到同一条案例
        List<TraceSpan> second = failingSpans("2026-09-09T02:00:00.000000000Z");
        TraceDigest.Fingerprint fp2 = TraceDigest.fingerprint(second);
        IncidentKb.ArchiveResult r = kb.archive("trace0000000000000000000000000002", fp2, second,
                "t2", "s2", "rc2", "日志证实", List.of("f2"), "v2");

        assertFalse(r.created(), "同指纹应累加而非新建");
        assertEquals(2, r.occurrences());
        assertEquals(1, kb.entries().size());
        assertEquals(2, kb.entries().get(0).occurrences());
        // first_seen 保持不变，last_seen 更新
        assertEquals(kb.entries().get(0).firstSeen(), kb.entries().get(0).firstSeen());
        assertTrue(kb.entries().get(0).lastSeen().startsWith("2026-09-09"), kb.entries().get(0).lastSeen());
    }

    @Test
    void matchesStrongHitBySignature(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        List<TraceSpan> spans = failingSpans("2026-09-08T01:48:45.880000000Z");
        kb.archive("trace0000000000000000000000000001", TraceDigest.fingerprint(spans), spans,
                "达梦 JdbcType", "现象", "根因", "日志证实", List.of("处置一"), "验证");

        // 同一故障模式在新链路上重现（不同 trace、不同时间）
        List<TraceSpan> replay = failingSpans("2026-09-10T03:00:00.000000000Z");
        IncidentKb.Match m = kb.match(TraceDigest.fingerprint(replay), IncidentKb.servicesOf(replay));

        assertEquals(IncidentKb.Strength.STRONG, m.strength());
        assertTrue(m.actionable());
        assertNotNull(m.entry());

        String section = kb.renderMatchSection(m);
        assertNotNull(section);
        assertTrue(section.contains("知识库命中[强]"), section);
        assertTrue(section.contains("处置一"), "命中时应带出历史处置：" + section);
        assertTrue(section.contains("以当前链路证据为准"), section);
    }

    /** 通用异常（NPE 等）即使对上也只能弱命中：全系统都可能抛，不能证明同一故障 */
    @Test
    void genericExceptionNeverStrongAlone(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        List<TraceSpan> npe = List.of(
                new TraceSpan("gateway-service", "gateway.server.requests", "s1", "", 58_000_000L, false,
                        "Unset", "", "Server", "2026-09-10T01:00:00Z", "", "dev"),
                new TraceSpan("order-service", "[HTTP] POST 下单", "s2", "s1", 30_000_000L, true,
                        "Error", "java.lang.NullPointerException: Cannot invoke ...", "Server",
                        "2026-09-10T01:00:00.500Z", "500", "dev"));
        kb.archive("npe000000000000000000000000000001", TraceDigest.fingerprint(npe), npe,
                "下单空指针", "下单失败", "某处空指针", "span推断", List.of("修复"), "验证");

        // 另一处完全不同位置的 NPE：异常类相同但与案例无服务交集 → 最多弱
        List<TraceSpan> elsewhere = List.of(
                new TraceSpan("gateway-service", "gateway.server.requests", "s1", "", 58_000_000L, false,
                        "Unset", "", "Server", "2026-09-11T01:00:00Z", "", "dev"),
                new TraceSpan("report-service", "[HTTP] GET 报表导出", "s2", "s1", 30_000_000L, true,
                        "Error", "java.lang.NullPointerException: Cannot invoke ...", "Server",
                        "2026-09-11T01:00:00.500Z", "500", "dev"));
        IncidentKb.Match m = kb.match(TraceDigest.fingerprint(elsewhere), IncidentKb.servicesOf(elsewhere));
        assertTrue(m.strength().ordinal() <= IncidentKb.Strength.WEAK.ordinal(),
                "跨服务 + 通用异常只能弱命中，实际=" + m.strength());

        // 特定异常（DMException）单独对上：分数 2 + 服务交集 0.5 → 中命中（可复用但要求复核）
        List<TraceSpan> dmElsewhere = List.of(
                new TraceSpan("pay-service", "[HTTP] POST 充值", "s2", "s1", 30_000_000L, true,
                        "Error", "dm.jdbc.driver.DMException: bad column", "Server",
                        "2026-09-11T01:00:00.500Z", "500", "dev"));
        kb.archive("dmdmdmdmdmdmdmdmdmdmdmdmdmdmdmd001", TraceDigest.fingerprint(dmElsewhere),
                dmElsewhere, "达梦异常A", "现象", "根因", "span推断", List.of("f"), "v");
        List<TraceSpan> dmAgain = List.of(
                new TraceSpan("pay-service", "[HTTP] POST 退款", "s2", "s1", 30_000_000L, true,
                        "Error", "dm.jdbc.driver.DMException: another", "Server",
                        "2026-09-11T02:00:00.500Z", "500", "dev"));
        IncidentKb.Match dm = kb.match(TraceDigest.fingerprint(dmAgain), IncidentKb.servicesOf(dmAgain));
        assertEquals(IncidentKb.Strength.MEDIUM, dm.strength(),
                "特定异常+同服务但无其他信号应为中命中，实际=" + dm.strength());
    }

    /** 服务零交集的案例无论指纹多像都只作参考，不进报告（跨业务域不算同一故障） */
    @Test
    void servicePrefilterCapsAtWeak(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        // 案例落在 pay-service
        List<TraceSpan> pay = List.of(
                new TraceSpan("pay-service", "[HTTP] POST 获取账户余额", "s2", "s1", 30_000_000L, true,
                        "Error", "Caused by: dm.jdbc.driver.DMException: Not support this type\n"
                        + "### The error may involve com.zoe.pay.business.dao.SecurityDepositDetailDao.selectListByQuery",
                        "Server", "2026-09-08T01:00:00Z", "200", "dev"));
        kb.archive("pay000000000000000000000000000001", TraceDigest.fingerprint(pay), pay,
                "押金查询失败", "现象", "根因", "日志证实", List.of("f"), "v");

        // signature 完全一致的报错，但发生在另一个服务的另一条链路里（服务集合不含 pay-service）
        List<TraceSpan> otherDomain = List.of(
                new TraceSpan("his-service", "[HTTP] POST 病历归档", "s2", "s1", 30_000_000L, true,
                        "Error", "Caused by: dm.jdbc.driver.DMException: Not support this type\n"
                        + "### The error may involve com.zoe.pay.business.dao.SecurityDepositDetailDao.selectListByQuery",
                        "Server", "2026-09-09T01:00:00Z", "200", "dev"));
        IncidentKb.Match m = kb.match(TraceDigest.fingerprint(otherDomain), IncidentKb.servicesOf(otherDomain));
        assertEquals(IncidentKb.Strength.WEAK, m.strength(),
                "服务零交集只能弱命中（signature 相同也不该跨域强命中），实际=" + m.strength());
        assertFalse(m.actionable());
    }

    @Test
    void ignoresWeakNoiseAndEmptyKb(@TempDir Path dir) {
        IncidentKb kb = kb(dir);
        assertFalse(kb.exists());
        assertEquals(0, kb.entries().size());
        List<TraceSpan> spans = failingSpans("2026-09-08T01:48:45.880000000Z");
        assertEquals(IncidentKb.Strength.NONE,
                kb.match(TraceDigest.fingerprint(spans), IncidentKb.servicesOf(spans)).strength());
        assertNull(kb.renderMatchSection(new IncidentKb.Match(IncidentKb.Strength.NONE, null)));
    }

    @Test
    void nextIdIncrementsWithinSameDay() {
        IncidentKb.CaseEntry a = new IncidentKb.CaseEntry("INC-20260908-001", "t", "f",
                new TraceDigest.Fingerprint("", "", "", "", List.of()), "日志证实", 1, "", "", List.of(), null);
        IncidentKb.CaseEntry b = new IncidentKb.CaseEntry("INC-20260908-003", "t", "f",
                new TraceDigest.Fingerprint("", "", "", "", List.of()), "日志证实", 1, "", "", List.of(), null);
        assertEquals("INC-20260908-004", IncidentKb.nextId(List.of(a, b), "20260908"));
        assertEquals("INC-20260909-001", IncidentKb.nextId(List.of(a, b), "20260909"));
    }

    @Test
    void insertsTraceRefAfterRelatedHeading() {
        String body = "\n# 标题\n\n## 关联\n\n- trace 报告：无\n";
        String out = IncidentKb.insertTraceRef(body, "trace `abc`（2026-09-08T09:48:45+08:00，dev）");
        assertTrue(out.contains("## 关联"), out);
        int at = out.indexOf("## 关联");
        int ref = out.indexOf("- trace `abc`");
        assertTrue(ref > at, "应在「关联」标题之后插入：" + out);
        assertTrue(out.contains("- trace 报告：无"), "原有内容应保留");
    }

    @Test
    void frontmatterAndBodySplit() {
        String text = "---\nid: X\n---\n\n# 标题\n正文\n";
        assertEquals("id: X\n", IncidentKb.frontmatter(text));
        assertEquals("\n# 标题\n正文\n", IncidentKb.bodyOf(text));
    }

    /** 生成的案例文件是给人看的：结构不能有重复标题、缺空行这类排版缺陷 */
    @Test
    void generatedCaseFileIsWellFormed(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        List<TraceSpan> spans = failingSpans("2026-09-08T01:48:45.880000000Z");
        IncidentKb.ArchiveResult r = kb.archive("trace0000000000000000000000000001",
                TraceDigest.fingerprint(spans), spans, "标题", "现象", "根因", "日志证实",
                List.of("处置一", "处置二"), "验证");
        String text = Files.readString(kb.root().resolve(r.caseFile()), StandardCharsets.UTF_8);

        assertEquals(1, text.split("## 关联", -1).length - 1, "「## 关联」标题只能出现一次：" + text);
        assertFalse(text.contains("owner: ''"), "空的 owner 字段是噪音，不该生成");
        // 每个二级标题前都应有且仅有一个空行（原来的 bug 是处置步骤后直接接标题）
        for (String h : List.of("## 现象", "## 根因", "## 关键证据", "## 处置步骤", "## 验证方式", "## 修订记录", "## 关联")) {
            assertTrue(text.contains("\n\n" + h), h + " 前应有空行：" + text);
        }
        assertTrue(text.contains("1. 处置一\n2. 处置二\n\n## 验证方式"), "处置步骤与下个标题间应有空行：" + text);
        assertTrue(text.contains("## 关联\n\n- trace `trace0000000000000000000000000001`"), text);
    }

    /**
     * 累加时不能改写指纹：索引里存的是原指纹（match 只读索引），
     * 若案例文件被写成新指纹，两者会静默分叉、案例文件的指纹成为死数据。
     */
    @Test
    void updateKeepsFingerprintConsistentBetweenFileAndIndex(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        // 建档：pay-service 的充值接口，达梦异常
        List<TraceSpan> original = List.of(
                new TraceSpan("pay-service", "[HTTP] POST 充值", "s2", "s1", 30_000_000L, true,
                        "Error", "dm.jdbc.driver.DMException: bad column", "Server",
                        "2026-09-08T01:00:00Z", "500", "dev"));
        IncidentKb.ArchiveResult first = kb.archive("trace0000000000000000000000000001",
                TraceDigest.fingerprint(original), original, "充值失败", "现象", "根因", "span推断",
                List.of("f"), "v");

        // 累加：同服务、同异常类，但操作不同 → 中命中，非强命中
        List<TraceSpan> recurrence = List.of(
                new TraceSpan("pay-service", "[HTTP] POST 退款", "s2", "s1", 30_000_000L, true,
                        "Error", "dm.jdbc.driver.DMException: another", "Server",
                        "2026-09-09T01:00:00Z", "500", "dev"));
        IncidentKb.ArchiveResult second = kb.archive("trace0000000000000000000000000002",
                TraceDigest.fingerprint(recurrence), recurrence, "退款失败", "现象2", "根因2", "span推断",
                List.of("f2"), "v2");

        assertFalse(second.created(), "应累加到已有案例");
        assertEquals(2, second.occurrences());

        // 案例文件与索引的指纹必须一致，且保持建档时的原值
        String fileText = Files.readString(kb.root().resolve(first.caseFile()), StandardCharsets.UTF_8);
        Map<String, Object> fm = loadFrontmatter(fileText);
        IncidentKb.CaseEntry idx = kb.entries().get(0);
        assertEquals(idx.fingerprint().span(), spanOf(fm), "案例文件与索引的 span 指纹不应分叉");
        assertEquals("pay-service / [HTTP] POST 充值", idx.fingerprint().span());
    }

    /** 大链路的 span 清单必须截断，否则前端列表会长到失控 */
    @Test
    void spanInventoryIsCappedForLargeTraces() {
        List<TraceSpan> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new TraceSpan("svc-" + i, "op-" + i, "s" + i, i == 0 ? "" : "s" + (i - 1),
                    1_000_000L + i, false, "Unset", "", "Internal", "2026-09-08T01:00:00Z", "", "dev"));
        }
        long t0 = System.currentTimeMillis();
        String text = TraceDigest.render("t", "24h", many, List.of(), null, null);
        assertTrue(text.contains("最慢 40 个 / 共 200 个"), "应显示截断提示：" + text);
        assertEquals(40, text.split("\\[svc-", -1).length - 1, "清单里只应有 40 个 span");
        assertTrue(System.currentTimeMillis() - t0 < 2000, "渲染不应因大链路变慢");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFrontmatter(String text) {
        String raw = IncidentKb.frontmatter(text);
        assertNotNull(raw);
        try {
            org.yaml.snakeyaml.LoaderOptions lo = new org.yaml.snakeyaml.LoaderOptions();
            Object o = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(lo)).load(raw);
            return (Map<String, Object>) o;
        } catch (Exception e) {
            throw new AssertionError("frontmatter 解析失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String spanOf(Map<String, Object> fm) {
        Object fp = fm.get("fingerprint");
        return fp instanceof Map<?, ?> m ? String.valueOf(m.get("span")) : null;
    }

    @Test
    void slugIsAsciiSafe() {
        assertEquals("securitydepositdetaildao-selectlistbyquery",
                IncidentKb.slugify("SecurityDepositDetailDao.selectListByQuery"));
        assertEquals("", IncidentKb.slugify("押金明细查询失败"));
    }

    /**
     * 手工编辑的索引里时间戳往往没加引号，snakeyaml 会解析成 Date；
     * 必须还原成 ISO 本地时间，不能渲染成 "Tue Sep 08 09:48:45 CST 2026"。
     */
    @Test
    void toleratesUnquotedTimestampFromHandEditing(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("cases"));
        Files.writeString(dir.resolve("index.yaml"), """
                version: 1
                updated: 2026-09-08
                cases:
                  - id: INC-20260908-001
                    title: 手工维护的案例
                    file: cases/INC-20260908-001-x.md
                    fingerprint:
                      error_class: dm.jdbc.driver.DMException
                      signature: SecurityDepositDetailDao.selectListByQuery
                      span: pay-service / [HTTP] POST 获取账户余额
                      status: HTTP 200 + OTel Error
                      keywords: [DMException]
                    confidence: 日志证实
                    occurrences: 2
                    first_seen: 2026-09-08T09:48:45+08:00
                    last_seen: 2026-09-08T09:48:45+08:00
                    services: [pay-service]
                """, StandardCharsets.UTF_8);

        IncidentKb kb = kb(dir);
        IncidentKb.CaseEntry e = kb.entries().get(0);
        assertEquals("2026-09-08T09:48:45+08:00", e.lastSeen());
        assertEquals("2026-09-08T09:48:45+08:00", e.firstSeen());
    }

    /** 生成的案例/索引必须能原样读回：含 " #" 与 ": " 的值不能被 YAML 当注释/映射截断 */
    @Test
    void generatedFilesRoundTripRiskyValues(@TempDir Path dir) throws Exception {
        IncidentKb kb = kb(dir);
        List<TraceSpan> spans = failingSpans("2026-09-08T01:48:45.880000000Z");
        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(spans);
        String rootCause = "绑定参数 #1 时 JdbcType 为 null（原因: dm.jdbc.driver.DMException: Not support this type）";

        kb.archive("trace0000000000000000000000000001", fp, spans, "标题含 # 号", "现象",
                rootCause, "日志证实", List.of("指定 JdbcType # 必做"), "重跑验证");

        // 索引与案例文件都应能无歧义读回
        IncidentKb.CaseEntry e = kb.entries().get(0);
        assertTrue(e.lastSeen().startsWith("2026-09-08"), "索引时间戳应为 ISO：" + e.lastSeen());
        IncidentKb.CaseDetail d = kb.detail(e);
        assertEquals(rootCause, d.rootCause(), "含 ' #' 的根因不能被截断");
        assertEquals(List.of("指定 JdbcType # 必做"), d.fix());
        assertEquals("标题含 # 号", e.title());

        String text = Files.readString(kb.root().resolve(e.file()), StandardCharsets.UTF_8);
        assertTrue(text.contains(rootCause), "案例文件正文/字段应完整保留根因");
    }
}
