package org.jeecg.modules.demo.video.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Hardware-independent regression checks; runnable with plain javac/java. */
public final class InferenceQueueCheck {
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        check(TensorBatch.effectiveSize(false, 4, 8) == 1, "batch disabled defaults to one real image");
        check(TensorBatch.effectiveSize(true, 1, 8) == 1, "fixed batch one never waits for batching");
        check(TensorBatch.effectiveSize(true, 4, 8) == 4, "fixed batch read from model");
        check(TensorBatch.effectiveSize(true, -1, 8) == 8, "dynamic batch uses configured maximum");
        List<Integer> discarded = new ArrayList<>();
        LatestFrameQueue<Integer> queue = new LatestFrameQueue<>(2, discarded::add);
        queue.offer(1); queue.offer(2); queue.offer(3);
        check(discarded.equals(Arrays.asList(1)), "evict oldest");
        check(queue.takeBatch(2, 0).equals(Arrays.asList(2, 3)), "retain latest FIFO");
        queue.offer(4); queue.close(); queue.offer(5);
        check(discarded.equals(Arrays.asList(1, 4, 5)), "close and late submission release");
        check(queue.takeBatch(2, 0).isEmpty(), "closed worker wakes");

        LatestFrameQueue<Integer> partial = new LatestFrameQueue<>(4, x -> {});
        partial.offer(1);
        long started = System.nanoTime();
        check(partial.takeBatch(4, 20).size() == 1, "partial batch after timeout");
        check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "batch wait bounded");
        partial.close();

        float[] padded = TensorBatch.slice(new float[]{1, 2, 3, 4, 5, 6}, 2, 1, 2, 4);
        check(Arrays.equals(padded, new float[]{3, 4, 5, 6, 5, 6, 5, 6}), "tail padding retains image order");

        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Integer> executed = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger released = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), LatestFrameExecutor.dropOldest());
        pool.execute(new FrameTask(() -> {
            running.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, released::incrementAndGet));
        check(running.await(2, TimeUnit.SECONDS), "worker started");
        pool.execute(new FrameTask(() -> executed.add(1), released::incrementAndGet));
        pool.execute(new FrameTask(() -> executed.add(2), released::incrementAndGet));
        check(released.get() == 1, "old executor task released exactly once");
        release.countDown(); pool.shutdown();
        check(pool.awaitTermination(2, TimeUnit.SECONDS), "worker finished");
        check(executed.equals(Arrays.asList(2)), "newest task executed");
        pool.execute(new FrameTask(() -> executed.add(3), released::incrementAndGet));
        check(released.get() == 2, "shutdown submission released");
        System.out.println("PASS: drop-oldest, FIFO, partial batch, shutdown, resource release, batch padding");
    }
}
