package org.jeecg.modules.demo.video.util;

import java.util.concurrent.atomic.AtomicBoolean;

/** Makes eviction/shutdown release a cloned native frame exactly once. */
public final class FrameTask implements Runnable {
    private final Runnable action;
    private final Runnable discard;
    private final AtomicBoolean claimed = new AtomicBoolean();
    public FrameTask(Runnable action, Runnable discard) { this.action = action; this.discard = discard; }
    public void run() { if (claimed.compareAndSet(false, true)) action.run(); }
    public void discard() { if (claimed.compareAndSet(false, true)) discard.run(); }
    public static void discard(Runnable task) { if (task instanceof FrameTask) ((FrameTask) task).discard(); }
}
