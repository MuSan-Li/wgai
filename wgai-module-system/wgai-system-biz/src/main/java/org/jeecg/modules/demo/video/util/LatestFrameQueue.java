package org.jeecg.modules.demo.video.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Bounded FIFO of waiting frames. Replaces the oldest waiting item atomically. */
public final class LatestFrameQueue<T> implements AutoCloseable {
    private final ArrayDeque<T> items = new ArrayDeque<>();
    private final int capacity;
    private final Consumer<T> discard;
    private boolean closed;

    public LatestFrameQueue(int capacity, Consumer<T> discard) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.discard = discard;
    }
    public void offer(T item) {
        T old = null;
        synchronized (this) {
            if (closed) old = item;
            else {
                if (items.size() == capacity) old = items.removeFirst();
                items.addLast(item);
                notifyAll();
            }
        }
        if (old != null) discard.accept(old);
    }
    public synchronized List<T> takeBatch(int maxBatch, long waitMs) throws InterruptedException {
        if (maxBatch < 1 || waitMs < 0) throw new IllegalArgumentException("invalid batch options");
        while (!closed && items.isEmpty()) wait();
        // Keep frames in the bounded queue while collecting, so fresh arrivals can replace stale frames.
        int target = Math.min(maxBatch, capacity);
        long deadline = System.nanoTime() + waitMs * 1000000L;
        while (!closed && items.size() < target && waitMs > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            wait(Math.max(1, remaining / 1000000L));
        }
        List<T> batch = new ArrayList<>();
        if (!closed) while (!items.isEmpty() && batch.size() < maxBatch) batch.add(items.removeFirst());
        return batch;
    }
    public synchronized int size() { return items.size(); }
    public synchronized boolean isClosed() { return closed; }
    @Override public void close() {
        List<T> pending;
        synchronized (this) {
            closed = true;
            pending = new ArrayList<>(items);
            items.clear();
            notifyAll();
        }
        pending.forEach(discard);
    }
}
