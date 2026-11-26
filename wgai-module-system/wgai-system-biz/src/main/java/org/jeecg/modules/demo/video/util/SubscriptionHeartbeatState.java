package org.jeecg.modules.demo.video.util;

import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Inference hot path: one map read and one volatile write, no IO, allocation, queue or lock. */
public final class SubscriptionHeartbeatState {
    private SubscriptionHeartbeatState() { }
    private static final ConcurrentHashMap<String, Entry> ACTIVE = new ConcurrentHashMap<>();
    private static final class Entry {
        final TabAiSubscriptionNew owner;
        volatile long completedNanos;
        Entry(TabAiSubscriptionNew owner) { this.owner = owner; }
    }
    public static void started(TabAiSubscriptionNew task) {
        ACTIVE.put(task.getId(), new Entry(task));
        SubscriptionRuntimeStatus.started(task);
    }
    public static void completed(TabAiSubscriptionNew task) {
        SubscriptionRuntimeStatus.completed(task);
        Entry entry = ACTIVE.get(task.getId());
        if (entry != null && entry.owner == task) entry.completedNanos = System.nanoTime();
    }
    /** Refresh only heartbeat run tokens; camera processing and model instances keep running. */
    public static void configurationChanged() {
        ACTIVE.replaceAll((id, old) -> {
            Entry fresh = new Entry(old.owner); fresh.completedNanos = old.completedNanos; return fresh;
        });
    }
    public static void stopped(String id) { ACTIVE.remove(id); SubscriptionRuntimeStatus.stopped(id); }
    /** Opaque run identity prevents a queued message from surviving stop/restart. */
    public static Object runningToken(String id) { return ACTIVE.get(id); }
    public static boolean isCurrentRun(String id, Object token) { return token != null && ACTIVE.get(id) == token; }
    public static boolean progressing(String id, long maxAgeSeconds) {
        Entry entry = ACTIVE.get(id);
        if (entry == null) return false;
        long last = entry.completedNanos;
        return last != 0 && System.nanoTime() - last <= TimeUnit.SECONDS.toNanos(maxAgeSeconds);
    }
}