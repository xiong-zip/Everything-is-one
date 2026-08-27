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
    public ToolResult execute(String userCommand) {
        String symbol = resolveSymbol(userCommand);
        Map<String, Object> data = fetchQuote(symbol);
        data.put("symbol", symbol.substring(2));
        data.put("trend", String.valueOf(data.get("change")).startsWith("-") ? "down" : "up");
        String summary = data.get("name") + " " + data.get("price") + " 元 " + data.get("change")
                + " 今开 " + data.get("open") + " 成交额 " + data.get("amount") + " 换手 " + data.get("turnover");
        return new ToolResult("stock", data, null, summary);
    }

    private String resolveSymbol(String command) {
        Matcher m = Pattern.compile("(\\d{6})").matcher(command);
        if (m.find()) {
            String code = m.group(1);
            if (code.startsWith("6")) return "sh" + code;
            if (code.startsWith("4") || code.startsWith("8")) return "bj" + code;
            return "sz" + code;
        }
        for (Map.Entry<String, String> e : NAME_MAP.entrySet()) {
            if (command.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return "sz300750";
    }

    private Map<String, Object> fetchQuote(String symbol) {
        try {
            byte[] bytes = restClient.get()
                    .uri(QUOTE_URL + symbol)
                    .header("Referer", "https://gu.qq.com/")
                    .retrieve()
                    .body(byte[].class);
            String text = new String(bytes, GBK);
            String payload = text.substring(text.indexOf('"') + 1, text.lastIndexOf('"'));
            String[] f = payload.split("~");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", f[1]);
            r.put("price", f[3]);
            r.put("open", f[5]);
            r.put("change", f[32] + "%");
            r.put("amount", formatAmount(f[37]));
            r.put("turnover", f[38] + "%");
            return r;
        } catch (Exception ex) {
            throw new IllegalStateException("行情查询失败: " + ex.getMessage(), ex);
        }
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
}
