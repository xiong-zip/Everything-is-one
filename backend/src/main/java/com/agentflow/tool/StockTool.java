package com.agentflow.tool;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StockTool implements Tool {

    private static final String QUOTE_URL = "https://qt.gtimg.cn/q=";
    private static final Charset GBK = Charset.forName("GBK");

    private static final Map<String, String> NAME_MAP = new LinkedHashMap<>();

    static {
        NAME_MAP.put("宁德时代", "sz300750");
        NAME_MAP.put("贵州茅台", "sh600519");
        NAME_MAP.put("招商银行", "sh600036");
        NAME_MAP.put("中国平安", "sh601318");
        NAME_MAP.put("比亚迪", "sz002594");
        NAME_MAP.put("五粮液", "sz000858");
        NAME_MAP.put("平安银行", "sz000001");
        NAME_MAP.put("万科", "sz000002");
        NAME_MAP.put("工商银行", "sh601398");
        NAME_MAP.put("中国银行", "sh601988");
        NAME_MAP.put("中信证券", "sh600030");
        NAME_MAP.put("海康威视", "sz002415");
        NAME_MAP.put("立讯精密", "sz002475");
        NAME_MAP.put("隆基绿能", "sh601012");
        NAME_MAP.put("中芯国际", "sh688981");
    }

    private final RestClient restClient;

    public StockTool(ToolHttpClient toolHttpClient) {
        this.restClient = toolHttpClient.restClient();
    }

    @Override
    public String name() {
        return "stock.query";
    }

    @Override
    public String description() {
        return "查询 A 股个股实时行情（真实行情数据）";
    }

    @Override
    public String argsHint() {
        return "{\"stock\": \"股票名称或 6 位代码\"}";
    }

    /** 从文本中识别股票名称（支持 6 位代码），找不到返回 null */
    public static String findStock(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d{6})").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        for (String name : NAME_MAP.keySet()) {
            if (text.contains(name)) {
                return name;
            }
        }
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        String stock = str(args.get("stock"));
        if (stock == null) {
            stock = str(args.get("name"));
        }
        if (stock == null) {
            stock = findStock(userCommand);
        }
        if (stock == null) {
            return ToolResult.note("未识别到股票，可在指令中写明名称（如「贵州茅台」）或 6 位代码");
        }
        String symbol = resolveSymbol(stock);
        Map<String, Object> data;
        try {
            data = fetchQuote(symbol);
        } catch (Exception ex) {
            return ToolResult.note("行情服务暂不可用：" + ex.getMessage());
        }
        data.put("symbol", symbol.substring(2));
        data.put("trend", String.valueOf(data.get("change")).startsWith("-") ? "down" : "up");
        String summary = data.get("name") + " " + data.get("price") + " 元 " + data.get("change")
                + " 今开 " + data.get("open") + " 成交额 " + data.get("amount") + " 换手 " + data.get("turnover");
        return new ToolResult("stock", data, null, summary);
    }

    private String resolveSymbol(String stock) {
        if (stock.matches("\\d{6}")) {
            if (stock.startsWith("6")) return "sh" + stock;
            if (stock.startsWith("4") || stock.startsWith("8")) return "bj" + stock;
            return "sz" + stock;
        }
        String symbol = NAME_MAP.get(stock);
        if (symbol != null) {
            return symbol;
        }
        // 名称不在预置表：按常见前缀尝试（沪 6 开头 / 深 0、3 开头无法从名称判断，默认沪）
        throw new IllegalStateException("暂不支持股票「" + stock + "」，请使用 6 位代码");
    }

    private Map<String, Object> fetchQuote(String symbol) {
        byte[] bytes = restClient.get()
                .uri(QUOTE_URL + symbol)
                .header("Referer", "https://gu.qq.com/")
                .retrieve()
                .body(byte[].class);
        String text = new String(bytes, GBK);
        String payload = text.substring(text.indexOf('"') + 1, text.lastIndexOf('"'));
        String[] f = payload.split("~");
        if (f.length < 39) {
            throw new IllegalStateException("行情返回格式异常");
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", f[1]);
        r.put("price", f[3]);
        r.put("open", f[5]);
        r.put("change", f[32] + "%");
        r.put("amount", formatAmount(f[37]));
        r.put("turnover", f[38] + "%");
        return r;
    }

    private static String formatAmount(String wan) {
        try {
            double v = Double.parseDouble(wan);
            if (v >= 10000) {
                return String.format("%.1f 亿", v / 10000.0);
            }
            return String.format("%.0f 万", v);
        } catch (NumberFormatException e) {
            return wan + " 万";
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
