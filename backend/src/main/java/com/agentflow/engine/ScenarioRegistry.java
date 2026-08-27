package com.agentflow.engine;

import com.agentflow.model.AgentStep;
import com.agentflow.model.FinalData;
import com.agentflow.model.Scenario;
import com.agentflow.model.ToolCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScenarioRegistry {

    private final List<Scenario> scenarios = new ArrayList<>();
    private final List<String[]> matchers = new ArrayList<>();

    public ScenarioRegistry() {
        scenarios.add(weatherScenario());
        scenarios.add(stockScenario());
        scenarios.add(tripScenario());

        matchers.add(new String[]{"weather", "天气", "朋友圈"});
        matchers.add(new String[]{"stock", "股价", "股票", "周报"});
        matchers.add(new String[]{"trip", "行程", "杭州", "美食", "旅游"});
    }

    public List<Scenario> all() {
        return scenarios;
    }

    public Scenario find(String command) {
        String c = command.trim();
        for (Scenario s : scenarios) {
            if (s.command().equals(c)) {
                return s;
            }
        }
        for (String[] m : matchers) {
            for (int i = 1; i < m.length; i++) {
                if (c.contains(m[i])) {
                    return scenarios.stream()
                            .filter(s -> s.id().equals(m[0]))
                            .findFirst()
                            .orElse(scenarios.get(0));
                }
            }
        }
        return scenarios.get(0);
    }

    private static Scenario weatherScenario() {
        List<AgentStep> steps = List.of(
                new AgentStep(
                        "tool", "工具调用", "查询厦门今日天气",
                        new ToolCall("weather.query", "city: \"厦门\", date: \"今天\""),
                        null,
                        "weather",
                        map("city", "厦门", "date", "今天", "condition", "晴",
                                "tempLo", 27, "tempHi", 33, "wind", "东南风 3 级",
                                "humidity", "72%", "uv", "强", "feels", 36),
                        null, 900),
                new AgentStep(
                        "think", "智能分析", "提炼天气要点 · 确定文案基调",
                        null,
                        List.of(
                                "关键信息：晴天、27~33℃、紫外线强、湿度较高",
                                "用户场景：发朋友圈，需要轻松、生活化的口吻",
                                "决定基调：慵懒夏日感 + 实用防晒提醒",
                                "结构：一句氛围开场 + 天气细节 + 暖心提醒"),
                        null, null, null, 1300),
                new AgentStep(
                        "write", "内容生成", "生成朋友圈文案（3 版备选）",
                        new ToolCall("llm.generate", "prompt: \"厦门天气文案\", style: \"生活化\", count: 3"),
                        null,
                        "copy",
                        map("versions", List.of(
                                map("tag", "V1 · 清爽防晒", "text",
                                        "厦门的夏天把太阳开到最大档了 ☀️\n今天 27~33℃，晴到发亮，出门记得涂防晒！\n不过天这么蓝，晒一点也值得~"),
                                map("tag", "V2 · 慵懒氛围", "text",
                                        "夏日的厦门，连风都是热的。\n27~33℃ 的晴天，适合躲进空调房，也适合大胆出门。\n反正夏天嘛，开心最重要。"),
                                map("tag", "V3 · 极简", "text",
                                        "厦门 · 晴 33℃\n阳光正好，适合出发。\n记得防晒，今天的紫外线有点强 🌤"))),
                        null, 1700));

        FinalData finalData = new FinalData(
                "已完成 3 个子任务：天气查询 → 要点分析 → 文案生成。今日厦门晴热、紫外线强，推荐使用「清爽防晒」版文案。",
                "厦门的夏天把太阳开到最大档了 ☀️\n今天 27~33℃，晴到发亮，出门记得涂防晒！\n不过天这么蓝，晒一点也值得~",
                List.of("调用工具 2 个", "推理 1 轮", "全程无需人工介入"));

        return new Scenario("weather", "帮我查厦门今天天气，然后生成一段朋友圈文案", "天气 + 朋友圈文案", steps, finalData);
    }

    private static Scenario stockScenario() {
        List<AgentStep> steps = List.of(
                new AgentStep(
                        "tool", "工具调用", "查询宁德时代今日行情",
                        new ToolCall("stock.query", "symbol: \"300750\", date: \"今天\""),
                        null,
                        "stock",
                        map("name", "宁德时代", "symbol", "300750", "price", 189.62, "change", "+1.23%",
                                "trend", "up", "open", 187.2, "amount", "42.8 亿", "turnover", "1.15%"),
                        null, 900),
                new AgentStep(
                        "think", "智能分析", "解读行情 · 提炼汇报要点",
                        null,
                        List.of(
                                "今日上涨 1.23%，成交额 42.8 亿，量价配合健康",
                                "短期受动力电池订单预期提振",
                                "周报侧重：结论 → 数据佐证 → 后续关注"),
                        null, null, null, 1300),
                new AgentStep(
                        "write", "内容生成", "生成周报汇报文案",
                        new ToolCall("llm.generate", "prompt: \"宁德时代周报\", audience: \"领导\", tone: \"严谨专业\""),
                        null,
                        "copy",
                        map("versions", List.of(
                                map("tag", "周报 · 汇报版", "text",
                                        "本周重点关注新能源板块龙头宁德时代：今日收盘 189.62 元，上涨 1.23%，成交额 42.8 亿，走势稳健。短期受动力电池订单预期提振，中期逻辑不变，建议保持跟踪、逢低关注。"))),
                        null, 1700));

        FinalData finalData = new FinalData(
                "已完成 3 个子任务：行情查询 → 要点分析 → 周报生成。今日宁德时代收涨 1.23%，文案可直接用于周报汇报。",
                "本周重点关注新能源板块龙头宁德时代：今日收盘 189.62 元，上涨 1.23%，成交额 42.8 亿，走势稳健。短期受动力电池订单预期提振，中期逻辑不变，建议保持跟踪、逢低关注。",
                List.of("调用工具 2 个", "推理 1 轮", "风格：严谨专业"));

        return new Scenario("stock", "查一下宁德时代今天的股价，然后写一段给领导的周报总结", "股价 + 周报总结", steps, finalData);
    }

    private static Scenario tripScenario() {
        List<AgentStep> steps = List.of(
                new AgentStep(
                        "tool", "工具调用", "查询上海 → 杭州交通",
                        new ToolCall("transit.query", "from: \"上海\", to: \"杭州\", date: \"周末\""),
                        null,
                        "transit",
                        map("mode", "高铁", "duration", "约 1 小时 05 分", "price", "二等座 ¥73 起",
                                "freq", "首班 06:30 · 末班 21:40"),
                        null, 900),
                new AgentStep(
                        "tool", "工具调用", "推荐杭州本地美食 Top 5",
                        new ToolCall("poi.recommend", "city: \"杭州\", type: \"美食\", top: 5"),
                        null,
                        "list", null,
                        List.of("西湖醋鱼 · 楼外楼", "龙井虾仁 · 绿茶餐厅", "片儿川 · 菊英面店", "葱包烩 · 河坊街", "定胜糕 · 江南春"),
                        1100),
                new AgentStep(
                        "think", "智能分析", "规划两天行程动线",
                        null,
                        List.of(
                                "Day1 环湖漫游：住湖滨 → 骑行 → 夜市",
                                "Day2 灵隐祈福：灵隐寺 → 龙井茶园",
                                "美食穿插进动线，减少折返"),
                        null, null, null, 1300),
                new AgentStep(
                        "write", "内容生成", "生成完整行程单",
                        new ToolCall("llm.generate", "prompt: \"杭州两日行程\", style: \"行程单\""),
                        null,
                        "copy",
                        map("versions", List.of(
                                map("tag", "行程单", "text",
                                        "Day1 西湖漫游线\n09:00 高铁 上海 → 杭州东\n10:30 入住湖滨酒店 · 放行李\n12:00 午餐「楼外楼」西湖醋鱼\n14:00 环湖骑行（苏堤—白堤）\n17:00 断桥残雪看落日\n19:30 河坊街夜逛 · 葱包烩 / 定胜糕\n\nDay2 灵隐祈福线\n08:00 早餐「菊英面店」片儿川\n09:00 灵隐寺 + 飞来峰\n12:30 龙井村 · 龙井虾仁午餐\n14:00 茶园步道漫步\n16:00 返程高铁 回上海"))),
                        null, 1900));

        FinalData finalData = new FinalData(
                "已完成 4 个子任务：交通查询 → 美食推荐 → 动线规划 → 行程生成。两天行程兼顾景点、美食与返程。",
                "Day1 西湖漫游线\n09:00 高铁 上海 → 杭州东\n10:30 入住湖滨酒店 · 放行李\n12:00 午餐「楼外楼」西湖醋鱼\n14:00 环湖骑行（苏堤—白堤）\n17:00 断桥残雪看落日\n19:30 河坊街夜逛 · 葱包烩 / 定胜糕\n\nDay2 灵隐祈福线\n08:00 早餐「菊英面店」片儿川\n09:00 灵隐寺 + 飞来峰\n12:30 龙井村 · 龙井虾仁午餐\n14:00 茶园步道漫步\n16:00 返程高铁 回上海",
                List.of("调用工具 3 个", "推理 1 轮", "覆盖 5 种杭州美食"));

        return new Scenario("trip", "帮我规划周末两天从上海去杭州的行程，顺便推荐当地美食", "周末行程 + 美食", steps, finalData);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
