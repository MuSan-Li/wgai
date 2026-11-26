package org.jeecg.modules.demo.video.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.mapper.TabAiSubscriptionNewMapper;
import org.jeecg.modules.demo.video.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** One heartbeat per configured camera/subscription per interval, independent of model count. */
@Slf4j
@Component
public class SubscriptionHeartbeatService {
    @Autowired private TabAiSubscriptionNewMapper mapper;
    @Value("${ai.subscription.heartbeat.workers:2}") private int workers = 2;
    @Value("${ai.subscription.heartbeat.queue-capacity:64}") private int queueCapacity = 64;
    @Value("${ai.subscription.heartbeat.connect-timeout-ms:1000}") private int connectTimeout = 1000;
    @Value("${ai.subscription.heartbeat.read-timeout-ms:1500}") private int readTimeout = 1500;
    @Value("${ai.subscription.heartbeat.auth-token:BLmaXOZu5X7jE551JGslkhXdNdwYIwgteKjnAPASuec=}") private String authToken = "BLmaXOZu5X7jE551JGslkhXdNdwYIwgteKjnAPASuec=";
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "subscription-heartbeat-scheduler"); t.setDaemon(true); return t;
    });
    private final SubscriptionHeartbeatSchedule schedule = new SubscriptionHeartbeatSchedule();
    private SubscriptionHeartbeatDispatcher dispatcher;
    private long lastErrorLog;
    @Value("${ai.subscription.heartbeat.inference-stale-seconds:60}") private long inferenceStaleSeconds = 60;
    @PostConstruct public void init() {
        dispatcher = new SubscriptionHeartbeatDispatcher(Math.max(1, Math.min(8, workers)), Math.max(1, Math.min(1024, queueCapacity)), (url, body) -> {
            try { upload(url, body, "scheduled"); }
            catch (Exception ignored) { /* upload already records the failure; no retries. */ }
        });
        scheduler.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
    }
    private void tick() {
        try {
            List<TabAiSubscriptionNew> cameras = mapper.selectList(new QueryWrapper<TabAiSubscriptionNew>()
                    .select("id", "name", "run_state", "heartbeat_enabled", "heartbeat_server_code", "heartbeat_server_name", "heartbeat_url", "heartbeat_interval_seconds")
                    .eq("heartbeat_enabled", 1).eq("run_state", 1));
            Set<String> ids = new HashSet<>(); long now = System.nanoTime();
            for (TabAiSubscriptionNew camera : cameras) {
                String id = camera.getId();
                if (!automaticEnabled(camera)) continue;
                ids.add(id);
                try {
                    SubscriptionHeartbeatOptions.validate(camera);
                    String fingerprint = camera.getHeartbeatUrl() + "\n" + camera.getHeartbeatServerCode() + "\n" + camera.getHeartbeatServerName() + "\n" + camera.getHeartbeatIntervalSeconds() + "\n" + camera.getRunState();
                    int interval = SubscriptionHeartbeatOptions.interval(camera);
                    if (!schedule.allow(id, fingerprint, interval, now)) continue;
                    // The queued action also verifies the exact running instance before sending.
                    if (!offerScheduled(camera)) {
                        log.warn("[保活上传跳过] cameraId={} reason=订阅已停止、在途任务或队列已满 queued={} rejected={}，不补发", id, dispatcher.queued(), dispatcher.rejected());
                    }
                } catch (Exception ex) { throttledError("保活配置无效，跳过", ex); }
            }
            schedule.retain(ids);
        } catch (Exception ex) { throttledError("保活配置读取失败", ex); }
    }
    public static boolean automaticEnabled(TabAiSubscriptionNew camera) {
        return SubscriptionHeartbeatOptions.enabled(camera) && Integer.valueOf(1).equals(camera.getRunState())
                && SubscriptionHeartbeatState.runningToken(camera.getId()) != null;
    }
    public boolean offerScheduled(TabAiSubscriptionNew camera) {
        if (!automaticEnabled(camera)) return false;
        Object run = SubscriptionHeartbeatState.runningToken(camera.getId());
        if (run == null) return false;
        return dispatcher.submit(camera.getId(), () -> {
            if (!SubscriptionHeartbeatState.isCurrentRun(camera.getId(), run)) {
                log.info("[保活上传跳过] cameraId={} reason=订阅已停止或重新启动，丢弃旧任务消息", camera.getId());
                return;
            }
            int status = SubscriptionHeartbeatState.progressing(camera.getId(), Math.max(5, inferenceStaleSeconds)) ? 1 : 0;
            try { upload(camera.getHeartbeatUrl(), payload(camera, status).toJSONString(), "scheduled"); }
            catch (Exception ignored) { /* Failure already logged; never retry on inference threads. */ }
        });
    }
    public CompletableFuture<JSONObject> testUpload(TabAiSubscriptionNew submitted) {
        SubscriptionHeartbeatOptions.validate(submitted);
        if (!SubscriptionHeartbeatOptions.enabled(submitted)) throw new IllegalArgumentException("请先开启保活消息推送");
        // Snapshot only heartbeat fields. Testing never saves the form or starts inference.
        TabAiSubscriptionNew camera = new TabAiSubscriptionNew().setHeartbeatEnabled(1)
                .setId(submitted.getId() == null || submitted.getId().trim().isEmpty() ? "heartbeat-test" : submitted.getId())
                .setName(submitted.getName() == null || submitted.getName().trim().isEmpty() ? "保活测试摄像头" : submitted.getName())
                .setHeartbeatServerCode(submitted.getHeartbeatServerCode()).setHeartbeatServerName(submitted.getHeartbeatServerName())
                .setHeartbeatUrl(submitted.getHeartbeatUrl());
        if (!camera.getId().matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("摄像头ID格式无效");
        String body = payload(camera, SubscriptionHeartbeatState.progressing(camera.getId(), Math.max(5, inferenceStaleSeconds)) ? 1 : 0).toJSONString();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        // Same camera key as scheduled uploads: one in-flight upload per camera, including manual tests.
        boolean accepted = dispatcher.submit(camera.getId(), () -> {
            if (future.isDone()) return; // Timed-out queued tests must not be sent later.
            try { future.complete(upload(camera.getHeartbeatUrl(), body, "test")); }
            catch (Exception ex) { future.completeExceptionally(ex); }
        });
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) log.warn("[保活测试上传取消] cameraId={}，尚未开始的任务不再发送；在途请求以HTTP日志为准", camera.getId());
        });
        if (accepted) log.info("[保活测试上传已提交] cameraId={} queued={}", camera.getId(), dispatcher.queued());
        if (!accepted) {
            log.warn("[保活测试上传拒绝] cameraId={} queued={} rejected={}", camera.getId(), dispatcher.queued(), dispatcher.rejected());
            future.completeExceptionally(new IllegalStateException("该摄像头正在上传或发送队列已满，请稍后重试"));
        }
        return future;
    }
    private JSONObject upload(String url, String body, String mode) throws Exception {
        String requestId = UUID.randomUUID().toString(); long start = System.nanoTime();
        JSONObject request = JSONObject.parseObject(body);
        String cameraId = request.getJSONObject("cameras").getString("cameraCode");
        java.net.URI uri = new java.net.URI(url);
        String endpoint = new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        log.info("[保活上传开始] requestId={} mode={} cameraId={} serverCode={} endpoint={} inferenceStatus={}",
                requestId, mode, cameraId, request.getString("serverCode"), endpoint, request.getJSONObject("cameras").getInteger("inferenceStatus"));
        log.info("[保活上传内容] requestId={} payload={}", requestId, body);
        try {
            int status = SubscriptionHeartbeatDispatcher.post(url, body, Math.max(100, connectTimeout), Math.max(100, readTimeout), authToken);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.info("[保活上传成功] requestId={} mode={} cameraId={} httpStatus={} elapsedMs={}", requestId, mode, cameraId, status, elapsed);
            JSONObject result = new JSONObject(true); result.put("requestId", requestId); result.put("httpStatus", status);
            result.put("elapsedMs", elapsed); result.put("payload", request); return result;
        } catch (Exception ex) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // Do not log exception URLs, which could contain query credentials.
            String reason = ex instanceof java.net.SocketTimeoutException ? "连接或响应超时" : ex instanceof java.net.ConnectException ? "连接失败" : ex instanceof java.io.IOException && ex.getMessage() != null && ex.getMessage().matches("HTTP [0-9]{3}") ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("[保活上传失败] requestId={} mode={} cameraId={} endpoint={} elapsedMs={} reason={}，不重试", requestId, mode, cameraId, endpoint, elapsed, reason);
            throw new java.io.IOException("保活上传失败: " + reason + "，requestId=" + requestId);
        }
    }
    public static JSONObject payload(TabAiSubscriptionNew camera, int inferenceStatus) {
        JSONObject body = new JSONObject(true); body.put("serverCode", camera.getHeartbeatServerCode());
        body.put("serverName", camera.getHeartbeatServerName()); body.put("onlineStatus", 1);
        JSONObject item = new JSONObject(true); item.put("cameraCode", camera.getId()); item.put("cameraName", camera.getName()); item.put("inferenceStatus", inferenceStatus);
        body.put("cameras", item); return body;
    }
    private void throttledError(String message, Exception ex) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog >= 30000) { lastErrorLog = now; log.warn("[{}] {}，推理继续运行", message, ex.toString()); }
    }
    @PreDestroy public void close() {
        scheduler.shutdownNow(); if (dispatcher != null) dispatcher.close();
    }
}