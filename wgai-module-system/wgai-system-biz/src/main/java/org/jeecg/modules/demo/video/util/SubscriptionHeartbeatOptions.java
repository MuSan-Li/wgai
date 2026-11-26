package org.jeecg.modules.demo.video.util;

import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import java.net.URI;

public final class SubscriptionHeartbeatOptions {
    private SubscriptionHeartbeatOptions() { }
    public static boolean enabled(TabAiSubscriptionNew tab) { return Integer.valueOf(1).equals(tab.getHeartbeatEnabled()); }
    public static int interval(TabAiSubscriptionNew tab) {
        int seconds = tab.getHeartbeatIntervalSeconds() == null ? 30 : tab.getHeartbeatIntervalSeconds();
        if (seconds < 5 || seconds > 3600) throw new IllegalArgumentException("保活推送间隔必须为5至3600秒");
        return seconds;
    }
    public static void validate(TabAiSubscriptionNew tab) {
        if (tab.getHeartbeatEnabled() != null && tab.getHeartbeatEnabled() != 0 && tab.getHeartbeatEnabled() != 1) throw new IllegalArgumentException("保活开关只能为0或1");
        if (!enabled(tab)) return;
        interval(tab);
        String code = tab.getHeartbeatServerCode(), name = tab.getHeartbeatServerName();
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9_-]{0,99}")) throw new IllegalArgumentException("保活英文名称须以英文字母开头，仅允许英文、数字、下划线或短横线，最多100字符");
        if (name == null || name.trim().isEmpty() || name.length() > 100) throw new IllegalArgumentException("请填写100字符以内的保活名称");
        try {
            String value = tab.getHeartbeatUrl();
            if (value == null || value.length() > 2000) throw new IllegalArgumentException();
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (Exception ex) { throw new IllegalArgumentException("保活推送地址必须是有效HTTP(S)地址，不允许内嵌账号或片段"); }
    }
}