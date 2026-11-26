package org.jeecg.modules.demo.video.util;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.support.spring.FastJsonHttpMessageConverter;
import org.jeecg.modules.demo.video.controller.TabAiSubscriptionNewController;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.service.impl.SubscriptionHeartbeatService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.concurrent.*;

/** MVC regression: manual heartbeat responses must not start Servlet ASYNC dispatch. */
public class HeartbeatMvcCheck {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static class UploadStub extends SubscriptionHeartbeatService {
        volatile CompletableFuture<JSONObject> next;
        @Override public CompletableFuture<JSONObject> testUpload(TabAiSubscriptionNew config) { return next; }
    }
    private static JSONObject call(MockMvc mvc) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/video/tabAiSubscriptionNew/testHeartbeat")
                .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content("{\"heartbeatEnabled\":1}")).andReturn();
        check(!result.getRequest().isAsyncStarted(), "Shiro compatibility: never start Servlet ASYNC");
        check(result.getResponse().getStatus() == 200, "normal API response");
        return JSONObject.parseObject(result.getResponse().getContentAsString());
    }
    public static void main(String[] args) throws Exception {
        TabAiSubscriptionNewController controller = new TabAiSubscriptionNewController();
        UploadStub service = new UploadStub();
        java.lang.reflect.Field field = TabAiSubscriptionNewController.class.getDeclaredField("heartbeatService");
        field.setAccessible(true); field.set(controller, service);
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        try {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setMessageConverters(new FastJsonHttpMessageConverter())
                    .setValidator(new org.springframework.validation.Validator() {
                        public boolean supports(Class<?> type) { return true; }
                        public void validate(Object target, org.springframework.validation.Errors errors) { }
                    }).build();
            CompletableFuture<JSONObject> success = new CompletableFuture<>(); service.next = success;
            worker.schedule(() -> { JSONObject result = new JSONObject(); result.put("httpStatus", 204); success.complete(result); }, 50, TimeUnit.MILLISECONDS);
            check(call(mvc).getBooleanValue("success"), "delayed worker success returned without redispatch");
            CompletableFuture<JSONObject> failure = new CompletableFuture<>(); service.next = failure;
            failure.completeExceptionally(new java.io.IOException("HTTP 503"));
            JSONObject failed = call(mvc);
            check(!failed.getBooleanValue("success") && failed.getString("message").contains("503"), "failure cause preserved");
            CompletableFuture<JSONObject> timeout = new CompletableFuture<>(); service.next = timeout;
            long start = System.nanoTime(); JSONObject timedOut = call(mvc);
            check(!timedOut.getBooleanValue("success") && timeout.isCancelled(), "timeout cancels pending task");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 6500, "manual request bounded to five seconds");
            System.out.println("PASS: MVC heartbeat success/error/timeout return without Servlet ASYNC dispatch");
        } finally { worker.shutdownNow(); service.close(); }
    }
}