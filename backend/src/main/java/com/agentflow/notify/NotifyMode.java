package com.agentflow.notify;

/**
 * 推送形态。三档对应群机器人三种能力，长内容按需要升级：
 *
 * <ul>
 *   <li>{@link #TEXT} —— 纯文本，企微上限 2048 字节（超长是投递时静默截断，不报错），可 @ 到人；</li>
 *   <li>{@link #MARKDOWN} —— markdown，企微上限 4096 字节，可读性好，但企微下带不上手机号 @；</li>
 *   <li>{@link #FILE} —— 摘要文本 + 完整内容作为附件，绕开字节上限（实测企微文件上限 20MB）。</li>
 * </ul>
 *
 * <p>告警一律走 {@link #TEXT}：@ 值班人只有文本消息支持得可靠，而告警卡片本来就该短。
 */
public enum NotifyMode {

    TEXT("text", "纯文本"),
    MARKDOWN("markdown", "Markdown 长文"),
    FILE("file", "摘要 + 文件附件");

    private final String key;
    private final String label;

    NotifyMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** 解析配置值；认不出来（含 null/空）统一回退纯文本，保证老配置行为不变 */
    public static NotifyMode parse(String raw) {
        if (raw != null) {
            for (NotifyMode m : values()) {
                if (m.key.equalsIgnoreCase(raw.trim()) || m.name().equalsIgnoreCase(raw.trim())) {
                    return m;
                }
            }
        }
        return TEXT;
    }
}
