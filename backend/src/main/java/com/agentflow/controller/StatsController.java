package com.agentflow.controller;

import com.agentflow.tool.GitLabTool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 效能统计：GitLab 提交热力图（按账户+天数分槽缓存 10 分钟，避免每次打开都打 GitLab） */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private static final long CACHE_MS = 10 * 60_000L;
    /** 最多保留几个账户×天数的缓存槽：来回切换账户时秒回，不用每次全量抓 6~16 秒 */
    private static final int MAX_SLOTS = 4;

    private final GitLabTool gitLabTool;

    private record Slot(Map<String, Object> data, long at) {
    }

    /** key = token指纹|days；单槽缓存会让切换账户必然全量重抓，放大前端过期响应竞态 */
    private final Map<String, Slot> slots = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Slot> eldest) {
            return size() > MAX_SLOTS;
        }
    };

    public StatsController(GitLabTool gitLabTool) {
        this.gitLabTool = gitLabTool;
    }

    @GetMapping("/heatmap")
    public Map<String, Object> heatmap(@RequestParam(defaultValue = "182") int days) {
        days = Math.max(30, Math.min(days, 400));
        String key = gitLabTool.tokenFingerprint() + "|" + days;
        Slot hit;
        synchronized (slots) {
            hit = slots.get(key);
        }
        if (hit != null && System.currentTimeMillis() - hit.at() < CACHE_MS) {
            return hit.data();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!gitLabTool.isConfigured()) {
            // 未配置不缓存：刚保存账户后立即就能看到热力图
            out.put("enabled", false);
            out.put("days", days);
            return out;
        }
        try {
            List<Map<String, Object>> counts = gitLabTool.dailyCommitCounts(days);
            long total = 0;
            int maxCount = 0;
            int activeDays = counts.size();
            int streak = 0;
            int best = 0;
            LocalDate prev = null;
            List<String> dates = new ArrayList<>();
            for (Map<String, Object> c : counts) {
                int n = (Integer) c.get("count");
                total += n;
                maxCount = Math.max(maxCount, n);
                LocalDate d = LocalDate.parse((String) c.get("date"));
                streak = prev != null && prev.plusDays(1).equals(d) ? streak + 1 : 1;
                best = Math.max(best, streak);
                prev = d;
                dates.add((String) c.get("date"));
            }
            out.put("enabled", true);
            out.put("days", days);
            out.put("startDate", LocalDate.now().minusDays(days - 1).toString());
            out.put("endDate", LocalDate.now().toString());
            out.put("counts", counts);
            out.put("total", total);
            out.put("activeDays", activeDays);
            out.put("maxCount", maxCount);
            out.put("bestStreak", best);
            out.put("lastCommitDate", dates.isEmpty() ? null : dates.get(dates.size() - 1));
        } catch (Exception ex) {
            out.put("enabled", true);
            out.put("error", "GitLab 查询失败：" + ex.getMessage());
            out.put("days", days);
            // 失败不进缓存：下一次打开能立刻重试，而不是把错误结果钉 10 分钟
            return out;
        }
        synchronized (slots) {
            slots.put(key, new Slot(out, System.currentTimeMillis()));
        }
        return out;
    }
}
