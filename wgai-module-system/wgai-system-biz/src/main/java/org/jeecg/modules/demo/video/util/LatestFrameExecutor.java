package org.jeecg.modules.demo.video.util;

import java.util.concurrent.*;

/** Single-frame inference retains its existing processing, with configurable drop-oldest waiting. */
public final class LatestFrameExecutor {
    private LatestFrameExecutor() { }
    public static RejectedExecutionHandler dropOldest() {
        return (task, executor) -> {
            synchronized (executor) {
                while (!executor.isShutdown()) {
                    if (executor.getQueue().offer(task)) {
                        if (executor.isShutdown() && executor.remove(task)) FrameTask.discard(task);
                        return;
                    }
                    FrameTask.discard(executor.getQueue().poll());
                }
                FrameTask.discard(task);
            }
        };
    }
    public static void shutdownNow(ExecutorService executor) {
        executor.shutdownNow().forEach(FrameTask::discard);
    }
}
