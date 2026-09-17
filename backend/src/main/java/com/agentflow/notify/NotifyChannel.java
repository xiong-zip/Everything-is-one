package com.agentflow.notify;

/**
 * 一个推送通道（企微/钉钉群机器人）。
 *
 * @param name      通道名（主键，界面上用来区分，如「研发告警群」）
 * @param type      通道类型：wecom（企微）/ dingtalk（钉钉）——决定 @ 人怎么组包
 * @param url       群机器人 Webhook 地址（含 key，属敏感信息，列表接口脱敏）
 * @param createdAt 创建时间
 */
public record NotifyChannel(String name, String type, String url, String createdAt) {

    public static final String WECOM = "wecom";
    public static final String DINGTALK = "dingtalk";

    public boolean dingtalk() {
        return DINGTALK.equalsIgnoreCase(type);
    }

    public String typeName() {
        return dingtalk() ? "钉钉" : "企微";
    }

    /**
     * 能否发文件附件：企微群机器人支持（先 upload_media 拿 media_id 再发），
     * 钉钉自定义机器人 webhook 只支持 text/markdown/link/actionCard/feedCard，发不了文件。
     */
    public boolean supportsFile() {
        return !dingtalk();
    }

    /** 按 URL 猜类型（新增时给默认值用；企微 qyapi，钉钉 oapi） */
    public static String detectType(String url) {
        String u = url == null ? "" : url.toLowerCase();
        return u.contains("dingtalk") || u.contains("oapi.dingtalk") ? DINGTALK : WECOM;
    }

    /**
     * 脱敏后的 URL：只留 key 参数末 4 位。
     * 与项目里 token 脱敏口径一致——列表页不该把可用凭据回传给前端。
     */
    public String maskedUrl() {
        if (url == null || url.isBlank()) {
            return "";
        }
        int keyAt = url.indexOf("key=");
        if (keyAt < 0) {
            return url.length() <= 12 ? "••••" : url.substring(0, 8) + "••••";
        }
        String prefix = url.substring(0, keyAt + 4);
        String key = url.substring(keyAt + 4);
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return prefix + "••••••" + tail;
    }
}
