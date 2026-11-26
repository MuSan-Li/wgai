package org.jeecg.modules.demo.video.util;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** Dedicated bounded sender: never run HTTP on inference, decoding, or scheduler threads. */
public final class SubscriptionHeartbeatDispatcher implements AutoCloseable {
    private final ThreadPoolExecutor pool;
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();
    private final BiConsumer<String, String> sender;
    public SubscriptionHeartbeatDispatcher(int workers, int capacity, BiConsumer<String, String> sender) {
        this.sender = sender;
        this.pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), r -> { Thread t = new Thread(r, "subscription-heartbeat-http"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
    }
    public boolean offer(String cameraKey, String url, String json) {
        return submit(cameraKey, () -> sender.accept(url, json));
    }
    public boolean submit(String cameraKey, Runnable action) {
        if (inFlight.putIfAbsent(cameraKey, Boolean.TRUE) != null) return false;
        try {
            pool.execute(() -> {
                try { action.run(); }
                finally { inFlight.remove(cameraKey); }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            inFlight.remove(cameraKey); rejected.incrementAndGet(); return false;
        }
    }
    public int queued() { return pool.getQueue().size(); }
    public long rejected() { return rejected.get(); }
    public static int post(String url, String json, int connectMs, int readMs) throws IOException {
        return post(url, json, connectMs, readMs, null);
    }
    public static int post(String url, String json, int connectMs, int readMs, String authToken) throws IOException {
        if (authToken != null && (authToken.contains("\r") || authToken.contains("\n"))) throw new IOException("Invalid auth header");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectMs); conn.setReadTimeout(readMs); conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST"); conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (authToken != null && !authToken.trim().isEmpty()) conn.setRequestProperty("X-Auth-Token", authToken.trim());
        byte[] body = json.getBytes(StandardCharsets.UTF_8); conn.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            return status;
        } finally { conn.disconnect(); }
    }
    @Override public void close() { pool.shutdownNow(); inFlight.clear(); }
}