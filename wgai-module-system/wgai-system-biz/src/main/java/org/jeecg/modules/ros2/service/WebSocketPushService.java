package org.jeecg.modules.ros2.service;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.message.websocket.WebSocket;
import org.jeecg.modules.ros2.model.VelocityCommand;
import org.jeecg.modules.ros2.model.VelocityVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * WebSocket 推送服务
 *
 * 关键修复：ROS2 数据全部绕过 Redis，直接调用 WebSocket.pushMessage() 系列
 *
 * 旧版问题：所有方法都调 webSocket.sendMessage() → Redis pub/sub →
 *           SocketHandler → webSocket.pushMessage()
 *   - robot_pose 以 50Hz 推送，每秒 50 条消息进入 Redis 队列
 *   - Redis 消费速度跟不上生产速度，消息在队列中堆积
 *   - SocketHandler 高频回调 webSocket.pushMessage()（旧版用 getAsyncRemote()）
 *   - 触发 TEXT_FULL_WRITING 异常
 *
 * 新版策略：
 *   高频实时数据（robot_pose / cmd_vel）
 *     → webSocket.pushMessage()         [tryLock 丢帧，不堵调用线程]
 *
 *   低频关键数据（path_update / nav_status / localization_status）
 *     → webSocket.pushMessageReliable() [tryLock(1s) 等待送达，不丢失]
 *
 *   普通业务通知（非 ROS2 场景）
 *     → webSocket.sendMessage()         [走 Redis，保持原有集群广播能力]
 */
@Slf4j
@Service
public class WebSocketPushService {

    @Autowired
    private WebSocket webSocket;

    // 有界的当前状态缓存：供新页面/重连补回推送；不缓存点云、控制指令或个人通知。
    private final String runtimeId = java.util.UUID.randomUUID().toString();
    private long runtimeRevision;
    private final java.util.Map<String, String> navigationEvents = new java.util.LinkedHashMap<>();

    private synchronized void remember(JSONObject message) {
        String type = message.getString("type");
        if (!java.util.Arrays.asList("path_update", "maneuver_status", "localization_status", "nav_status").contains(type)) return;
        if ("path_update".equals(type) && navigationEvents.containsKey("maneuver_status")) {
            JSONObject task = JSONObject.parseObject(navigationEvents.get("maneuver_status")).getJSONObject("data");
            if (task != null && java.util.Arrays.asList("done", "failed", "canceled").contains(task.getString("phase"))) return;
        }
        message.put("runtimeId", runtimeId);
        message.put("revision", ++runtimeRevision);
        JSONObject cached = JSONObject.parseObject(message.toJSONString());
        if ("maneuver_status".equals(type)) {
            JSONObject current = cached.getJSONObject("data");
            if (current != null && java.util.Arrays.asList("done", "failed", "canceled").contains(current.getString("phase"))) {
                navigationEvents.remove("path_update");
            }
            String previousJson = navigationEvents.get(type);
            JSONObject previous = previousJson == null ? null : JSONObject.parseObject(previousJson).getJSONObject("data");
            // 页面原本保留最近压线快照。后续侧移/PLC失败无extra时，刷新也应保留同一任务的诊断。
            if (current != null && previous != null && current.getLongValue("missionId") == previous.getLongValue("missionId")
                    && !current.getBooleanValue("clearDiagnostic") && !current.containsKey("hits")
                    && !"done".equals(current.getString("phase")) && !"canceled".equals(current.getString("phase"))
                    && previous.containsKey("hits")) {
                for (String key : previous.keySet()) {
                    if (java.util.Arrays.asList("missionId", "startedAtMs", "goal", "phase", "message", "ok", "clearDiagnostic").contains(key)) continue;
                    if (!current.containsKey(key)) current.put(key, previous.get(key));
                }
            }
        }
        navigationEvents.put(type, cached.toJSONString());
    }

    public synchronized java.util.Map<String, Object> navigationSnapshot() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        java.util.List<JSONObject> events = new java.util.ArrayList<>();
        for (String event : navigationEvents.values()) events.add(JSONObject.parseObject(event));
        events.sort(java.util.Comparator.comparingLong(e -> e.getLongValue("revision")));
        result.put("runtimeId", runtimeId); result.put("revision", runtimeRevision);
        result.put("events", events);
        return result;
    }

    public synchronized void clearNavigationSnapshot() {
        navigationEvents.clear(); ++runtimeRevision;
    }

    // ─────────────────── 点云按需推送 ───────────────────
    //
    // ⚠ 点云每帧 ~3000 点、按 JSON 对象编码 ~100KB，5Hz ≈ 4Mbps，而且以前不管谁在看都广播。
    //   只有建图页用它；浏览器在另一台电脑上，这 4Mbps 全压在主机那块 WiFi 上，
    //   和 PLC 的 S7 通信抢同一个无线网卡 → ping 从几毫秒涨到 1~4 秒、页面控制记录丢、地图图片加载不出来，
    //   控制指令和急停也跟着延迟(2026-09-16 现场)。
    //   改成跟建图状态走：点「开始建图」/「清空重扫」才推，「取消建图」/「保存地图」就停。导航页不需要点云。
    //   ⚠ 只管"推给浏览器"。Java 从 rosbridge 收点云照常(本机回环不占 WiFi)，避障/旋转判定/建图栅格都靠它。

    private volatile boolean cloudPushEnabled = false;

    /** MappingController 在开始/清空重扫时开，取消/保存时关 */
    public void setCloudPushEnabled(boolean enabled) {
        if (cloudPushEnabled != enabled) {
            log.info("[点云推送] {}", enabled ? "开始往页面推送点云(建图中)" : "停止往页面推送点云");
        }
        cloudPushEnabled = enabled;
    }

    /** 当前是否往页面推点云 */
    public boolean isCloudPushWanted() {
        return cloudPushEnabled;
    }

    // ─────────────────── 高频实时数据（直连 WebSocket，tryLock 丢帧） ───────────────────

    /**
     * 推送速度数据（cmd_vel，~20Hz）
     * 直接调用 pushMessage，Session 忙则丢帧，绝不堵塞 ROS2 消息处理线程。
     */
    public void pushVelocity(VelocityCommand velocity) {
        try {
            VelocityVO vo = VelocityVO.fromEntity(velocity);
            JSONObject message = new JSONObject();
            message.put("type",      "cmd_vel_update");
            message.put("data",      vo);
            message.put("timestamp", System.currentTimeMillis());
            remember(message);
            webSocket.pushMessage(message.toJSONString());   // ① 高频广播
        } catch (Exception e) {
            log.error("推送速度数据失败", e);
        }
    }

    /**
     * 推送机器人位姿（robot_pose，~50Hz）
     * 直接调用 pushMessage，Session 忙则丢帧。
     */
    public void pushRobotPose(Object poseData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "robot_pose");
            message.put("data",      poseData);
            message.put("timestamp", System.currentTimeMillis());
            remember(message);
            webSocket.pushMessage(message.toJSONString());   // ① 高频广播
        } catch (Exception e) {
            log.error("推送机器人位姿失败", e);
        }
    }

    /**
     * 推送位置数据（通用，高频）
     */
    public void pushPosition(Object position) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "position");
            message.put("data",      position);
            message.put("timestamp", System.currentTimeMillis());
            remember(message);
            webSocket.pushMessage(message.toJSONString());   // ① 高频广播
        } catch (Exception e) {
            log.error("推送位置数据失败", e);
        }
    }

    // ─────────────────── 低频关键数据（直连 WebSocket，tryLock(1s) 确保送达） ───────────────────

    /**
     * 推送导航路径（path_update）
     * 路径规划一次触发一次，必须送达，使用可靠广播。
     */
    public void pushPath(Object pathData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "path_update");
            message.put("data",      pathData);
            message.put("timestamp", System.currentTimeMillis());
            remember(message);
            webSocket.pushMessageReliable(message.toJSONString());  // ② 可靠广播
        } catch (Exception e) {
            log.error("推送路径数据失败", e);
        }
    }

    /**
     * 推送导航状态（nav_status：成功/失败/取消）
     * 状态变更事件，必须送达。
     */
    public void pushNavStatus(Object statusData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "nav_status");
            message.put("data",      statusData);
            message.put("timestamp", System.currentTimeMillis());
            remember(message);
            webSocket.pushMessageReliable(message.toJSONString());  // ② 可靠广播
        } catch (Exception e) {
            log.error("推送导航状态失败", e);
        }
    }

    /**
     * 推送地图数据（map，低频）
     */
    public void pushMap(Object mapData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "map");
            message.put("data",      mapData);
            message.put("timestamp", System.currentTimeMillis());
            remember(message);
            webSocket.pushMessageReliable(message.toJSONString());  // ② 可靠广播
        } catch (Exception e) {
            log.error("推送地图数据失败", e);
        }
    }

    /**
     * 推送定位状态（localization_status：globalizing / converged）
     * 全局重定位触发/收敛通知，必须送达。
     *
     * @param status "globalizing" | "converged" | "failed"
     */
    public void pushLocalizationStatus(String status) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("type",      "localization_status");
            payload.put("status",    status);
            payload.put("waitMs",    15000);               // 前端倒计时参考值
            payload.put("timestamp", System.currentTimeMillis());
            remember(payload);
            webSocket.pushMessageReliable(payload.toJSONString());  // ② 可靠广播
            log.info("📡 推送 localization_status: {}", status);
        } catch (Exception e) {
            log.warn("推送 localization_status 失败: {}", e.getMessage());
        }
    }

    // ─────────────────── 通用推送接口 ───────────────────

    /**
     * 推送给指定用户（单点，走 Redis 保留集群广播能力）
     */
    public void pushToUser(String userId, String type, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      type);
            message.put("data",      data);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.sendMessage(userId, message.toJSONString());
        } catch (Exception e) {
            log.error("推送消息到用户失败: userId={}", userId, e);
        }
    }

    /**
     * 高频广播（外部调用入口，对应 pushMessage tryLock 丢帧）
     * 适合 ROS2 实时传感器数据。
     */
    public void pushToAll(String type, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      type);
            message.put("data",      data);
            message.put("timestamp", System.currentTimeMillis());
            remember(message);
            webSocket.pushMessage(message.toJSONString());          // ① 高频广播
        } catch (Exception e) {
            log.error("高频广播失败: type={}", type, e);
        }
    }

    /**
     * 可靠广播（外部调用入口，对应 pushMessageReliable tryLock(1s)）
     * 适合 path_update / nav_status 等关键消息。
     */
    public void pushToAllReliable(String type, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      type);
            message.put("data",      data);
            message.put("timestamp", System.currentTimeMillis());
            remember(message);
            webSocket.pushMessageReliable(message.toJSONString());  // ② 可靠广播
        } catch (Exception e) {
            log.error("可靠广播失败: type={}", type, e);
        }
    }
}