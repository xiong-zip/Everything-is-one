package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WeatherTool implements Tool {

    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";

    private static final Map<String, double[]> CITY_COORDS = new LinkedHashMap<>();

    static {
        CITY_COORDS.put("北京", new double[]{39.9042, 116.4074});
        CITY_COORDS.put("上海", new double[]{31.2304, 121.4737});
        CITY_COORDS.put("广州", new double[]{23.1291, 113.2644});
        CITY_COORDS.put("深圳", new double[]{22.5431, 114.0579});
        CITY_COORDS.put("杭州", new double[]{30.2741, 120.1551});
        CITY_COORDS.put("厦门", new double[]{24.4798, 118.0819});
        CITY_COORDS.put("成都", new double[]{30.5728, 104.0668});
        CITY_COORDS.put("重庆", new double[]{29.5630, 106.5516});
        CITY_COORDS.put("武汉", new double[]{30.5928, 114.3055});
        CITY_COORDS.put("西安", new double[]{34.3416, 108.9398});
        CITY_COORDS.put("南京", new double[]{32.0603, 118.7969});
        CITY_COORDS.put("天津", new double[]{39.3434, 117.3616});
        CITY_COORDS.put("苏州", new double[]{31.2989, 120.5853});
        CITY_COORDS.put("长沙", new double[]{28.2282, 112.9388});
        CITY_COORDS.put("青岛", new double[]{36.0671, 120.3826});
        CITY_COORDS.put("大连", new double[]{38.9140, 121.6147});
        CITY_COORDS.put("郑州", new double[]{34.7466, 113.6254});
        CITY_COORDS.put("济南", new double[]{36.6512, 117.1201});
        CITY_COORDS.put("合肥", new double[]{31.8206, 117.2272});
        CITY_COORDS.put("福州", new double[]{26.0745, 119.2965});
        CITY_COORDS.put("昆明", new double[]{24.8801, 102.8329});
        CITY_COORDS.put("贵阳", new double[]{26.6470, 106.6302});
        CITY_COORDS.put("南宁", new double[]{22.8170, 108.3665});
        CITY_COORDS.put("哈尔滨", new double[]{45.8038, 126.5349});
        CITY_COORDS.put("长春", new double[]{43.8171, 125.3235});
        CITY_COORDS.put("沈阳", new double[]{41.8057, 123.4315});
        CITY_COORDS.put("石家庄", new double[]{38.0428, 114.5149});
        CITY_COORDS.put("太原", new double[]{37.8706, 112.5489});
        CITY_COORDS.put("南昌", new double[]{28.6820, 115.8579});
        CITY_COORDS.put("兰州", new double[]{36.0611, 103.8343});
        CITY_COORDS.put("西宁", new double[]{36.6171, 101.7782});
        CITY_COORDS.put("银川", new double[]{38.4872, 106.2309});
        CITY_COORDS.put("乌鲁木齐", new double[]{43.8256, 87.6168});
        CITY_COORDS.put("拉萨", new double[]{29.6520, 91.1721});
        CITY_COORDS.put("呼和浩特", new double[]{40.8414, 111.7519});
        CITY_COORDS.put("海口", new double[]{20.0440, 110.1999});
        CITY_COORDS.put("三亚", new double[]{18.2528, 109.5119});
    }

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeatherTool(ToolHttpClient toolHttpClient) {
        this.restClient = toolHttpClient.restClient();
    }

    @Override
    public ToolResult execute(String userCommand) {
        String city = extractCity(userCommand);
        double[] coord = CITY_COORDS.getOrDefault(city, CITY_COORDS.get("厦门"));
        Map<String, Object> data = fetchWeather(coord[0], coord[1]);
        data.put("city", city);
        data.put("date", "今天");
        String summary = city + " " + data.get("condition") + " " + data.get("tempLo") + "~" + data.get("tempHi") + "℃"
                + " 湿度 " + data.get("humidity") + " 风力 " + data.get("wind") + " 紫外线 " + data.get("uv")
                + " 体感 " + data.get("feels") + "℃";
        return new ToolResult("weather", data, null, summary);
    }

    private String extractCity(String command) {
        for (String c : CITY_COORDS.keySet()) {
            if (command.contains(c)) {
                return c;
            }
        }
        return "厦门";
    }

    private Map<String, Object> fetchWeather(double lat, double lon) {
        try {
            String json = restClient.get()
                    .uri(FORECAST_URL + "?latitude={lat}&longitude={lon}"
                            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
                            + "&daily=temperature_2m_max,temperature_2m_min,uv_index_max"
                            + "&timezone={tz}&forecast_days=1",
                            lat, lon, "Asia/Shanghai")
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(json);
            JsonNode current = root.path("current");
            JsonNode daily = root.path("daily");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("condition", wmoToText(current.path("weather_code").asInt()));
            r.put("tempLo", Math.round(daily.path("temperature_2m_min").path(0).asDouble()));
            r.put("tempHi", Math.round(daily.path("temperature_2m_max").path(0).asDouble()));
            r.put("feels", Math.round(current.path("apparent_temperature").asDouble()));
            r.put("humidity", current.path("relative_humidity_2m").asInt() + "%");
            r.put("wind", windToText(current.path("wind_speed_10m").asDouble()));
            r.put("uv", uvToText(daily.path("uv_index_max").path(0).asDouble()));
            return r;
        } catch (Exception ex) {
            throw new IllegalStateException("天气查询失败: " + ex.getMessage(), ex);
        }
    }

    private static String wmoToText(int code) {
        if (code == 0) return "晴";
        if (code == 1) return "基本晴朗";
        if (code == 2) return "多云";
        if (code == 3) return "阴";
        if (code >= 45 && code <= 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code >= 85 && code <= 86) return "阵雪";
        if (code >= 95 && code <= 99) return "雷暴";
        return "未知";
    }

    private static String windToText(double kmh) {
        if (kmh < 6) return "1 级";
        if (kmh < 12) return "2 级";
        if (kmh < 20) return "3 级";
        if (kmh < 29) return "4 级";
        if (kmh < 39) return "5 级";
        if (kmh < 50) return "6 级";
        if (kmh < 62) return "7 级";
        return "8 级及以上";
    }

    private static String uvToText(double uv) {
        if (uv < 3) return "弱";
        if (uv < 6) return "中等";
        if (uv < 8) return "强";
        if (uv < 11) return "很强";
        return "极强";
    }
}
