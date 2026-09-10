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

/** 效能统计：GitLab 提交热力图（结果缓存 10 分钟，避免每次打开都打 GitLab） */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private static final long CACHE_MS = 10 * 60_000L;

    private final GitLabTool gitLabTool;

    private volatile Map<String, Object> cache;
    private volatile long cacheAt;
    private volatile int cacheDays = -1;

    public StatsController(GitLabTool gitLabTool) {
        this.gitLabTool = gitLabTool;
    }

    @GetMapping("/heatmap")
    public Map<String, Object> heatmap(@RequestParam(defaultValue = "182") int days) {
        days = Math.max(30, Math.min(days, 400));
        Map<String, Object> cached = cache;
        if (cached != null && cacheDays == days && System.currentTimeMillis() - cacheAt < CACHE_MS) {
            return cached;
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
        }
        cache(out, days);
        return out;
    }

    private void cache(Map<String, Object> data, int days) {
        this.cache = data;
        this.cacheDays = days;
        this.cacheAt = System.currentTimeMillis();
    }
}
