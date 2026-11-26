package org.jeecg.modules.demo.video.util;

import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.service.impl.SubscriptionHeartbeatService;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** No camera, model, Redis or database required. */
public class SubscriptionHeartbeatCheck {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static TabAiSubscriptionNew camera() {
        return new TabAiSubscriptionNew().setId("camera-1").setName("东门摄像头").setHeartbeatEnabled(1)
                .setHeartbeatServerCode("east_gate").setHeartbeatServerName("东门服务").setHeartbeatUrl("http://127.0.0.1/heartbeat");
    }
    public static void main(String[] args) throws Exception {
        TabAiSubscriptionNew tab = camera(); SubscriptionHeartbeatOptions.validate(tab);
        check(SubscriptionHeartbeatOptions.interval(tab) == 30, "default interval");
        SubscriptionHeartbeatOptions.validate(new TabAiSubscriptionNew());
        boolean failed = false; try { SubscriptionHeartbeatOptions.validate(camera().setHeartbeatUrl("file:///tmp/x")); } catch (IllegalArgumentException ex) { failed = true; }
        check(failed, "reject non HTTP URL");
        failed = false; try { SubscriptionHeartbeatOptions.validate(camera().setHeartbeatServerCode("中文")); } catch (IllegalArgumentException ex) { failed = true; }
        check(failed, "English code only");
        TabAiSubscriptionNew source = camera().setRunState(0).setEventUrl("DO_NOT_COPY_CALLBACK").setBeginEventTypes("DO_NOT_COPY_VIDEO").setModelJmType(100);
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<TabAiSubscriptionNew> copy = SubscriptionHeartbeatConfigSync.toOthers(source);
        Set<String> copiedColumns = new HashSet<>();
        for (String assignment : copy.getSqlSet().split(",")) copiedColumns.add(assignment.substring(0, assignment.indexOf('=')));
        check(copiedColumns.equals(new HashSet<>(Arrays.asList("heartbeat_enabled", "heartbeat_server_code", "heartbeat_server_name", "heartbeat_url", "heartbeat_interval_seconds"))), "only five heartbeat fields synchronized");
        check(copy.getSqlSegment().contains("id <>") && copy.getParamNameValuePairs().containsValue(source.getId()), "source camera excluded");
        check(!copy.getParamNameValuePairs().containsValue("DO_NOT_COPY_CALLBACK") && !copy.getParamNameValuePairs().containsValue("DO_NOT_COPY_VIDEO"), "camera and inference addresses not copied");
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<TabAiSubscriptionNew> disabledCopy = SubscriptionHeartbeatConfigSync.toOthers(new TabAiSubscriptionNew().setId("disabled").setHeartbeatEnabled(0));
        disabledCopy.getSqlSet();
        check(disabledCopy.getParamNameValuePairs().containsValue(null) && disabledCopy.getParamNameValuePairs().containsValue(0), "disabled and empty heartbeat values explicitly synchronized");

        JSONObject body = SubscriptionHeartbeatService.payload(tab, 1);
        check(body.size() == 4 && body.getIntValue("onlineStatus") == 1, "exact payload root");
        JSONObject item = body.getJSONObject("cameras");
        check(item.size() == 3 && "camera-1".equals(item.getString("cameraCode")) && "东门摄像头".equals(item.getString("cameraName")), "camera-scoped object");
        TabAiSubscriptionNew initiallyDisabled = camera().setId("initially-disabled").setHeartbeatEnabled(0).setRunState(1);
        SubscriptionHeartbeatState.started(initiallyDisabled); SubscriptionHeartbeatState.completed(initiallyDisabled);
        check(SubscriptionHeartbeatState.progressing(initiallyDisabled.getId(), 60), "live inference progress retained for later bulk enable");
        check(SubscriptionHeartbeatService.automaticEnabled(camera().setId(initiallyDisabled.getId()).setRunState(1)), "bulk-enabled running camera works without inference restart");
        Object oldConfig = SubscriptionHeartbeatState.runningToken(initiallyDisabled.getId());
        SubscriptionHeartbeatState.configurationChanged();
        check(!SubscriptionHeartbeatState.isCurrentRun(initiallyDisabled.getId(), oldConfig) && SubscriptionHeartbeatState.progressing(initiallyDisabled.getId(), 60), "config refresh drops old queued messages while preserving inference progress");
        SubscriptionHeartbeatState.stopped(initiallyDisabled.getId());
        SubscriptionHeartbeatState.started(tab);
        check(!SubscriptionHeartbeatState.progressing(tab.getId(), 60), "started is not proof of inference");
        SubscriptionHeartbeatState.completed(tab);
        check(SubscriptionHeartbeatState.progressing(tab.getId(), 60), "completion is fresh");
        TabAiSubscriptionNew replacement = camera(); SubscriptionHeartbeatState.started(replacement); SubscriptionHeartbeatState.completed(tab);
        check(!SubscriptionHeartbeatState.progressing(tab.getId(), 60), "old worker cannot mark restarted camera alive");
        SubscriptionHeartbeatState.completed(replacement); SubscriptionHeartbeatState.stopped(tab.getId());
        check(!SubscriptionHeartbeatState.progressing(tab.getId(), 60), "stop clears progress");

        SubscriptionHeartbeatSchedule schedule = new SubscriptionHeartbeatSchedule();
        check(schedule.allow("camera-1", "v1", 30, 0), "first heartbeat is due");
        for (int model = 0; model < 10; model++) check(!schedule.allow("camera-1", "v1", 30, TimeUnit.SECONDS.toNanos(29)), "model count cannot trigger extra heartbeat");
        check(schedule.allow("camera-1", "v1", 30, TimeUnit.SECONDS.toNanos(30)), "30 second interval");
        check(!schedule.allow("camera-1", "v1", 30, TimeUnit.SECONDS.toNanos(59)), "no early send");
        check(schedule.allow("camera-1", "v2", 60, TimeUnit.SECONDS.toNanos(60)), "changed configuration refreshes schedule");
        check(!schedule.allow("camera-1", "v2", 60, TimeUnit.SECONDS.toNanos(119)), "configured 60 second interval enforced");
        check(schedule.allow("camera-1", "v2", 60, TimeUnit.SECONDS.toNanos(120)), "configured interval reached");

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        try (SubscriptionHeartbeatDispatcher dispatcher = new SubscriptionHeartbeatDispatcher(1, 1, (url, json) -> {
            check(Thread.currentThread().getName().startsWith("subscription-heartbeat-http"), "no caller-runs HTTP");
            calls.incrementAndGet(); entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        })) {
            check(dispatcher.offer("camera-1", "unused", "{}"), "first camera accepted");
            check(entered.await(2, TimeUnit.SECONDS), "sender started");
            check(!dispatcher.offer("camera-1", "unused", "{}"), "same camera in-flight is coalesced");
            check(dispatcher.offer("camera-2", "unused", "{}"), "queue accepts second camera");
            long start = System.nanoTime();
            for (int i = 0; i < 10000; i++) dispatcher.offer("overload-" + i, "unused", "{}");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(elapsed < 2000, "saturated sender must not block producers: " + elapsed);
            check(dispatcher.queued() <= 1 && dispatcher.rejected() > 0 && calls.get() == 1, "queue bounded, no inline fallback");
            release.countDown();
        } finally { release.countDown(); }

        AtomicReference<String> receivedToken = new AtomicReference<>(); AtomicReference<String> received = new AtomicReference<>(); AtomicReference<String> method = new AtomicReference<>(); AtomicInteger uploads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/heartbeat", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Auth-Token")); uploads.incrementAndGet(); method.set(exchange.getRequestMethod()); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) { byte[] buffer = new byte[1024]; int n; while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n); }
            received.set(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/failure", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            SubscriptionHeartbeatDispatcher.post("http://127.0.0.1:" + server.getAddress().getPort() + "/heartbeat", body.toJSONString(), 1000, 1000);
            check("POST".equals(method.get()) && body.equals(JSONObject.parseObject(received.get())), "UTF-8 POST matches payload");
            SubscriptionHeartbeatService service = new SubscriptionHeartbeatService();
            java.lang.reflect.Field tokenField = SubscriptionHeartbeatService.class.getDeclaredField("authToken");
            tokenField.setAccessible(true); tokenField.set(service, "local-test-token=");
            SubscriptionHeartbeatDispatcher testDispatcher = new SubscriptionHeartbeatDispatcher(1, 2, (url, json) -> {});
            java.lang.reflect.Field dispatcherField = SubscriptionHeartbeatService.class.getDeclaredField("dispatcher");
            dispatcherField.setAccessible(true); dispatcherField.set(service, testDispatcher);
            try {
                String base = "http://127.0.0.1:" + server.getAddress().getPort();
                TabAiSubscriptionNew automatic = camera().setId("automatic-camera").setHeartbeatUrl(base + "/heartbeat").setRunState(0);
                check(!service.offerScheduled(automatic), "stopped camera never auto uploads");
                automatic.setRunState(1);
                check(!service.offerScheduled(automatic), "stale database running flag without live task never uploads");
                SubscriptionHeartbeatState.started(automatic);
                automatic.setRunState(0);
                check(!service.offerScheduled(automatic), "database stopped flag blocks even with live registration");
                automatic.setRunState(1);
                int autoBefore = uploads.get(); CountDownLatch autoDrained = new CountDownLatch(1);
                check(service.offerScheduled(automatic), "running enabled camera accepted");
                testDispatcher.submit("auto-barrier", autoDrained::countDown);
                check(autoDrained.await(3, TimeUnit.SECONDS) && uploads.get() == autoBefore + 1, "running camera uploads once");
                check("local-test-token=".equals(receivedToken.get()), "scheduled upload sends auth token header");
                CountDownLatch hold = new CountDownLatch(1), holding = new CountDownLatch(1), stoppedDrained = new CountDownLatch(1);
                testDispatcher.submit("hold-for-stop", () -> { holding.countDown(); try { hold.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
                check(holding.await(2, TimeUnit.SECONDS), "sender held for stop race");
                int stopBefore = uploads.get();
                check(service.offerScheduled(automatic), "automatic message queued before stop");
                SubscriptionHeartbeatState.stopped(automatic.getId());
                SubscriptionHeartbeatState.started(camera().setId(automatic.getId()).setRunState(1));
                hold.countDown(); testDispatcher.submit("stop-barrier", stoppedDrained::countDown);
                check(stoppedDrained.await(3, TimeUnit.SECONDS) && uploads.get() == stopBefore, "queued old-run message discarded after stop/restart");
                SubscriptionHeartbeatState.stopped(automatic.getId());

                JSONObject result = service.testUpload(camera().setHeartbeatUrl(base + "/heartbeat")).get(3, TimeUnit.SECONDS);
                check(result.getIntValue("httpStatus") == 204 && result.getLongValue("elapsedMs") >= 0 && result.getString("requestId") != null, "manual upload returns HTTP result, duration and request id");
                check("local-test-token=".equals(receivedToken.get()), "manual upload sends auth token header");
                boolean rejectedHttp = false;
                try { service.testUpload(camera().setId("camera-failure").setHeartbeatUrl(base + "/failure")).get(3, TimeUnit.SECONDS); }
                catch (ExecutionException expected) { rejectedHttp = expected.getCause().getMessage().contains("HTTP 503"); }
                check(rejectedHttp, "manual upload reports non-2xx failure");
                CountDownLatch busy = new CountDownLatch(1), unblock = new CountDownLatch(1), drained = new CountDownLatch(1);
                testDispatcher.submit("other-camera", () -> { busy.countDown(); try { unblock.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
                check(busy.await(2, TimeUnit.SECONDS), "test sender blocked");
                int before = uploads.get();
                CompletableFuture<JSONObject> cancelled = service.testUpload(camera().setId("camera-cancel").setHeartbeatUrl(base + "/heartbeat"));
                cancelled.cancel(false); unblock.countDown();
                testDispatcher.submit("barrier", drained::countDown);
                check(drained.await(2, TimeUnit.SECONDS), "queued test drained");
                check(uploads.get() == before, "cancelled queued test is not uploaded later");
            } finally { service.close(); }

        } finally { server.stop(0); }
        System.out.println("Heartbeat configuration, status, bounded-queue isolation and local HTTP checks passed");
    }
}