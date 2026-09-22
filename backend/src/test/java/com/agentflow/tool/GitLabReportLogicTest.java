package com.agentflow.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周报/日报口径的三块纯逻辑：时间窗解析（本周=日历周而非滚动 7 天）、
 * 提交作者身份匹配（档案 + 别名）、Merge/Revert 噪音判定。
 */
class GitLabReportLogicTest {

    private static final ObjectMapper M = new ObjectMapper();

    /* ---------- 时间窗 ---------- */

    // 2026-09-17 是周四；本周的日历周起点是 09-14（周一）
    private static final LocalDate THU = LocalDate.of(2026, 9, 17);

    @Test
    void thisWeekIsCalendarWeekFromMonday() {
        GitLabTool.Window w = GitLabTool.parseWindow("根据我的 GitLab 提交记录生成本周的工作周报", THU);
        assertEquals(LocalDate.of(2026, 9, 14), w.since());
        assertEquals(THU, w.until());
        assertTrue(w.scopeZh().startsWith("本周"));
    }

    @Test
    void thisWeekOnMondayStartsToday() {
        LocalDate mon = LocalDate.of(2026, 9, 14);
        GitLabTool.Window w = GitLabTool.parseWindow("本周周报", mon);
        assertEquals(mon, w.since());
        assertEquals(mon, w.until());
    }

    @Test
    void lastWeekStaysMondayBased() {
        GitLabTool.Window w = GitLabTool.parseWindow("上周的工作周报", THU);
        assertEquals(LocalDate.of(2026, 9, 7), w.since());
        assertEquals(LocalDate.of(2026, 9, 13), w.until());
    }

    /** 最近一周/近一周是滚动 7 天，不能因为含「一周」就走日历周 */
    @Test
    void recentWeekStaysRolling() {
        GitLabTool.Window w = GitLabTool.parseWindow("最近一周的提交", THU);
        assertEquals(LocalDate.of(2026, 9, 11), w.since());
        assertEquals(THU, w.until());
    }

    @Test
    void explicitRangeWins() {
        GitLabTool.Window w = GitLabTool.parseWindow("9月1日至9月15日的周报", THU);
        assertEquals(LocalDate.of(2026, 9, 1), w.since());
        assertEquals(LocalDate.of(2026, 9, 15), w.until());
    }

    @Test
    void noTimeWordReturnsNull() {
        assertNull(GitLabTool.parseWindow("随便写点什么", THU));
    }

    /* ---------- 作者身份匹配 ---------- */

    private static com.fasterxml.jackson.databind.JsonNode commit(String name, String email) throws Exception {
        return M.readTree("{\"author_name\":\"" + name + "\",\"author_email\":\"" + email + "\"}");
    }

    /** 实测翻车场景：档案=姓名+公司邮箱，git=账号名+个人邮箱，档案身份一条都匹配不上 */
    @Test
    void aliasRescuesMismatchedGitIdentity() throws Exception {
        GitLabTool.AuthorIdentity id = GitLabTool.AuthorIdentity.of("肖雄",
                Set.of("xiaoxiong@zoesoft.com.cn"), List.of("xiaoxiong", "2665684431@qq.com"));
        assertTrue(id.matches(commit("肖雄", "xiaoxiong@zoesoft.com.cn")));
        assertTrue(id.matches(commit("xiaoxiong", "2665684431@qq.com")), "别名（作者名或邮箱）必须能认回真实提交");
        assertFalse(id.matches(commit("同事", "mate@zoesoft.com.cn")));
    }

    @Test
    void aliasMatchesByNameOrEmailCaseInsensitively() throws Exception {
        GitLabTool.AuthorIdentity id = GitLabTool.AuthorIdentity.of("张三", Set.of(), List.of("XiaoXiong"));
        assertTrue(id.matches(commit("xiaoxiong", "any@mail.com")), "别名按作者名匹配，不分大小写");
        assertFalse(id.matches(commit("别人", "xiongxiong@example.com")));
    }

    @Test
    void profileEmailStillMatches() throws Exception {
        GitLabTool.AuthorIdentity id = GitLabTool.AuthorIdentity.of("张三", Set.of("z@corp.com"), List.of());
        assertTrue(id.matches(commit("别的名字", "z@corp.com")));
        assertFalse(id.matches(commit("别的名字", "other@corp.com")));
    }

    /* ---------- 提交正文详情 ---------- */

    /** 实测格式：message = 标题 + 空行 + "- " 开头的要点罗列 */
    @Test
    void detailOfStripsTitleAndBullets() {
        String title = "fix(basicImage): 清理表格死代码";
        String message = title + "\n\n- 移除未使用的 rowSelection 绑定\n- changeFlag 增加失败回滚\n\n- deleteFn 回调省略参数命名\n";
        assertEquals("移除未使用的 rowSelection 绑定；changeFlag 增加失败回滚；deleteFn 回调省略参数命名",
                GitLabTool.detailOf(title, message));
    }

    @Test
    void detailOfTruncatesToCap() {
        String title = "t";
        String message = title + "\n\n" + "很长的细节描述".repeat(60);
        String out = GitLabTool.detailOf(title, message);
        assertTrue(out.length() <= 151, "应截断到 150 字加省略号，实际 " + out.length());
        assertTrue(out.endsWith("…"));
    }

    @Test
    void detailOfEmptyWhenTitleOnly() {
        assertEquals("", GitLabTool.detailOf("feat: 只有标题", "feat: 只有标题"));
        assertEquals("", GitLabTool.detailOf("feat: 只有标题", null));
        assertEquals("", GitLabTool.detailOf("feat: 只有标题", "  "));
    }

    /** message 首行与 title 不一致时的兜底：取首行之后的正文 */
    @Test
    void detailOfFallsBackWhenPrefixDiffers() {
        assertEquals("要点一；要点二",
                GitLabTool.detailOf("标题A", "标题B（修正）\n- 要点一\n- 要点二\n"));
    }

    /* ---------- 同步噪音 ---------- */

    @Test
    void mergeAndRevertTitlesAreNoise() {
        assertTrue(GitLabTool.isSyncNoise("Merge branch 'xiaoxiong0915' into 'master'"));
        assertTrue(GitLabTool.isSyncNoise("merge remote-tracking branch 'origin/master'"));
        assertTrue(GitLabTool.isSyncNoise("Revert \"feat: 某功能\""));
        assertFalse(GitLabTool.isSyncNoise("feat(basicImage): 基础镜像页面对齐发布平台多架构交互"));
        assertFalse(GitLabTool.isSyncNoise("mergewidget 优化"));   // 前缀带空格，不误伤
        assertFalse(GitLabTool.isSyncNoise(null));
        assertFalse(GitLabTool.isSyncNoise(""));
    }
}
