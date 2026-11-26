package org.jeecg.modules.demo.video.util;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Used only by the dedicated scheduler; inference never calls it. */
public final class SubscriptionHeartbeatSchedule {
    private final Map<String, Long> nextDue = new HashMap<>();
    private final Map<String, String> fingerprints = new HashMap<>();
    public boolean allow(String cameraId, String fingerprint, int intervalSeconds, long nowNanos) {
        if (!fingerprint.equals(fingerprints.put(cameraId, fingerprint))) nextDue.remove(cameraId);
        Long due = nextDue.get(cameraId);
        if (due != null && nowNanos - due < 0) return false;
        nextDue.put(cameraId, nowNanos + TimeUnit.SECONDS.toNanos(intervalSeconds));
        return true;
    }
    public void retain(Set<String> ids) { nextDue.keySet().retainAll(ids); fingerprints.keySet().retainAll(ids); }
}