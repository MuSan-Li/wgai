package org.jeecg.modules.demo.video.util;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.support.spring.FastJsonHttpMessageConverter;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jeecg.modules.demo.video.controller.TabAiSubscriptionNewController;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.service.ITabAiSubscriptionNewService;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class SubscriptionRuntimeApiCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static int listCalls;
    public static void main(String[] args) throws Exception {
        TabAiSubscriptionNew task = new TabAiSubscriptionNew().setId("1968552071558119426").setRunState(1);
        TabAiSubscriptionNew stopped = new TabAiSubscriptionNew().setId("stopped").setRunState(0);
        SubscriptionHeartbeatState.started(task);
        SubscriptionRuntimeStatus.begin(task);
        SubscriptionRuntimeStatus.inferred();
        SubscriptionHeartbeatState.completed(task);
        SubscriptionRuntimeStatus.clear();
        ITabAiSubscriptionNewService service = (ITabAiSubscriptionNewService) Proxy.newProxyInstance(
            ITabAiSubscriptionNewService.class.getClassLoader(), new Class<?>[]{ITabAiSubscriptionNewService.class},
            (proxy, method, values) -> {
                if (method.getName().equals("list")) {
                    listCalls++;
                    QueryWrapper<?> query = (QueryWrapper<?>) values[0];
                    check("id,run_state,dify_start_end,dify_start_time".equals(query.getSqlSelect()), "minimal database fields");
                    check(query.getSqlSegment().contains("id IN"), "query only requested subscriptions");
                    check(query.getParamNameValuePairs().containsValue(task.getId()), "full string ID remains intact");
                    return Arrays.asList(task, stopped);
                }
                if (method.getName().equals("page")) {
                    Page<TabAiSubscriptionNew> page = new Page<>(1, 10, 1);
                    page.setRecords(Arrays.asList(task));
                    return page;
                }
                throw new AssertionError("Unexpected service call: " + method.getName());
            });
        TabAiSubscriptionNewController controller = new TabAiSubscriptionNewController();
        Field field = TabAiSubscriptionNewController.class.getDeclaredField("tabAiSubscriptionNewService");
        field.setAccessible(true); field.set(controller, service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new FastJsonHttpMessageConverter())
            .setValidator(new org.springframework.validation.Validator() {
                public boolean supports(Class<?> type) { return true; }
                public void validate(Object target, org.springframework.validation.Errors errors) { }
            }).build();
        try {
            MvcResult response = mvc.perform(MockMvcRequestBuilders.get("/video/tabAiSubscriptionNew/runtimeStatus")
                .param("ids", " " + task.getId() + "," + task.getId() + ",stopped, ")).andReturn();
            check(response.getResponse().getStatus() == 200, "runtime HTTP 200");
            check("no-store".equals(response.getResponse().getHeader("Cache-Control")), "live response is not cacheable");
            JSONObject json = JSONObject.parseObject(response.getResponse().getContentAsString());
            check(json.getBooleanValue("success"), "runtime response success");
            JSONObject running = json.getJSONObject("result").getJSONObject(task.getId());
            check("RUNNING".equals(running.getString("code")), "real inference visible through HTTP");
            check(running.getLongValue("lastInferenceAt") > 0 && running.getLongValue("inferenceCalls") == 1, "model progress preserved");
            check(running.getIntValue("runState") == 1, "start marker returned separately");
            check("STOPPED".equals(json.getJSONObject("result").getJSONObject("stopped").getString("code")), "stopped remains stopped");
            MvcResult list = mvc.perform(MockMvcRequestBuilders.get("/video/tabAiSubscriptionNew/list")).andReturn();
            JSONObject row = JSONObject.parseObject(list.getResponse().getContentAsString())
                .getJSONObject("result").getJSONArray("records").getJSONObject(0);
            check(row.getJSONObject("runtimeInfo").getLongValue("inferenceCalls") == 1, "list JSON contains runtimeInfo");
            check(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(task)).has("runtimeInfo"), "Jackson preserves non-database runtime field");
            int before = listCalls;
            controller.runtimeStatus(" , ", new org.springframework.mock.web.MockHttpServletResponse());
            check(listCalls == before, "empty request skips DB");
            String tooMany = IntStream.range(0,201).mapToObj(String::valueOf).collect(Collectors.joining(","));
            check(!controller.runtimeStatus(tooMany, new org.springframework.mock.web.MockHttpServletResponse()).isSuccess(), "limit enforced");
            check(listCalls == before, "oversized request skips DB");
            System.out.println("PASS: list/runtime HTTP JSON, real model timestamps/calls, stopped state, no-store, IDs and limits");
        } finally {
            SubscriptionHeartbeatState.stopped(task.getId());
            SubscriptionRuntimeStatus.clear();
        }
    }
}
