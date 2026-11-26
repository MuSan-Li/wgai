package org.jeecg.modules.demo.video.util;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;

/** Explicit allowlist: synchronization must never copy camera identity or inference settings. */
public final class SubscriptionHeartbeatConfigSync {
    private SubscriptionHeartbeatConfigSync() { }
    public static UpdateWrapper<TabAiSubscriptionNew> toOthers(TabAiSubscriptionNew source) {
        if (source.getId() == null || source.getId().trim().isEmpty()) throw new IllegalArgumentException("请先保存当前订阅");
        SubscriptionHeartbeatOptions.validate(source);
        return new UpdateWrapper<TabAiSubscriptionNew>().ne("id", source.getId())
                .set("heartbeat_enabled", SubscriptionHeartbeatOptions.enabled(source) ? 1 : 0)
                .set("heartbeat_server_code", source.getHeartbeatServerCode())
                .set("heartbeat_server_name", source.getHeartbeatServerName())
                .set("heartbeat_url", source.getHeartbeatUrl())
                .set("heartbeat_interval_seconds", SubscriptionHeartbeatOptions.interval(source));
    }
}