package org.jeecg.modules.ros2.service;

import com.alibaba.fastjson.JSONObject;
import org.jeecg.modules.message.websocket.WebSocket;
import org.jeecg.modules.ros2.controller.MapController;
import org.jeecg.modules.ros2.controller.NavigationController;
import javax.websocket.Session;
import javax.websocket.RemoteEndpoint;
import java.lang.reflect.*;
import java.util.*;

/** 无硬件回归：多页面同用户连接、原连接心跳回复、只读刷新和有界不可变快照。 */
public class NavigationRecoveryCheck {
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
    static void field(Object o, String n, Object v) throws Exception {
        Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o,v);
    }
    static Session session(String id, List<String> output) {
        RemoteEndpoint.Basic remote=(RemoteEndpoint.Basic)Proxy.newProxyInstance(NavigationRecoveryCheck.class.getClassLoader(),
            new Class[]{RemoteEndpoint.Basic.class}, (p,m,a)-> { if (m.getName().equals("sendText")) output.add((String)a[0]); return null; });
        return (Session)Proxy.newProxyInstance(NavigationRecoveryCheck.class.getClassLoader(),new Class[]{Session.class},(p,m,a)-> {
            switch(m.getName()) { case "getId": return id; case "isOpen": return true; case "getBasicRemote": return remote;
                case "equals": return p==a[0]; case "hashCode": return System.identityHashCode(p); case "toString": return id; default: return null; }
        });
    }
    static class MapStub extends MapController { @Override public String navigationMapName(){return "map-running";} }
    static class BridgeStub extends ROS2BridgeService {
        boolean hasPose; int controls;
        @Override public boolean isConnected(){return true;}
        @Override public AmclSample getAmclSample(){return new AmclSample(1,System.currentTimeMillis()-90000,hasPose?new double[]{1,2,.3}:null,null);}
        @Override public double[] getEstimatedMapPose(){return null;}
        @Override public void resubscribeNav2Topics(){controls++; throw new AssertionError("Snapshot cannot resubscribe/control ROS");}
    }
    public static void main(String[] args) throws Exception {
        WebSocket socket=new WebSocket();
        List<String> a=new ArrayList<>(), b=new ArrayList<>(), c=new ArrayList<>();
        Session first=session("connection-a",a), second=session("connection-b",b), other=session("connection-c",c);
        socket.onOpen(first,"operator1"); socket.onOpen(second,"operator1"); socket.onOpen(other,"operator2");
        socket.pushMessage("broadcast"); check(a.size()==1&&b.size()==1&&c.size()==1,"Both pages receive broadcast");
        socket.onMessage("ping",first,"operator1"); check(a.size()==2&&b.size()==1,"Heartbeat returns only to originating session");
        socket.onClose(first,"operator1"); socket.pushMessage("operator1","private"); check(b.size()==2&&c.size()==1,"Old session close keeps new connection and user targeting");
        socket.onClose(second,"operator1"); socket.onClose(other,"operator2");

        WebSocketPushService push=new WebSocketPushService();field(push,"webSocket",socket);
        Map<String,Object> data=new LinkedHashMap<>();data.put("phase","navigating");data.put("goal",new double[]{8,9,0});
        push.pushToAll("maneuver_status",data); data.put("phase","mutated");
        Map<String,Object> snap=push.navigationSnapshot(); List<JSONObject> events=(List<JSONObject>)snap.get("events");
        check(events.size()==1&&events.get(0).getJSONObject("data").getString("phase").equals("navigating"),"Snapshot copies envelope when recorded");
        events.get(0).put("revision",-1);check(((List<JSONObject>)push.navigationSnapshot().get("events")).get(0).getLongValue("revision")==1,"Readers cannot mutate cache");
        for(int i=0;i<100;i++) push.pushToAll("cloud_update",data);
        check(((List<?>)push.navigationSnapshot().get("events")).size()==1,"Cloud telemetry never accumulates in snapshot");
        push.pushPath(Collections.singletonMap("poses",Arrays.asList(1,2)));push.pushToAll("maneuver_status",Collections.singletonMap("phase","failed"));
        events=(List<JSONObject>)push.navigationSnapshot().get("events");check(events.size()==1&&events.get(0).getJSONObject("data").getString("phase").equals("failed"),"Terminal removes cached path");
        JSONObject diag=new JSONObject();diag.put("missionId",10);diag.put("phase","diagnostic");diag.put("hits",Arrays.asList(new double[]{1,2}));diag.put("paddingCount",42);
        push.pushToAll("maneuver_status",diag);
        JSONObject failed=new JSONObject();failed.put("missionId",10);failed.put("phase","failed");failed.put("message","PLC unavailable");
        push.pushToAll("maneuver_status",failed);
        events=(List<JSONObject>)push.navigationSnapshot().get("events");JSONObject latest=events.get(events.size()-1).getJSONObject("data");
        check(latest.containsKey("hits")&&latest.getIntValue("paddingCount")==42&&latest.getString("phase").equals("failed"),"Terminal without extra retains same-task collision snapshot");
        check(!failed.containsKey("hits"),"Snapshot merge cannot mutate caller payload");
        failed.put("clearDiagnostic",true);push.pushToAll("maneuver_status",failed);events=(List<JSONObject>)push.navigationSnapshot().get("events");
        check(!events.get(events.size()-1).getJSONObject("data").containsKey("hits"),"Localization jump explicitly clears collision snapshot");
        push.pushToAll("maneuver_status",diag);failed.remove("clearDiagnostic");failed.put("missionId",11);push.pushToAll("maneuver_status",failed);
        events=(List<JSONObject>)push.navigationSnapshot().get("events");check(!events.get(events.size()-1).getJSONObject("data").containsKey("hits"),"New mission cannot inherit previous diagnostic");
        ManeuverService maneuver=new ManeuverService();field(maneuver,"push",push);
        Class<?> missionClass=Class.forName("org.jeecg.modules.ros2.service.ManeuverService$Mission");
        Constructor<?> ctor=missionClass.getDeclaredConstructor(long.class,double.class,double.class,double.class,double.class,boolean.class);ctor.setAccessible(true);
        Object mission=ctor.newInstance(20L,8d,9d,0d,0d,false);field(maneuver,"mission",mission);
        Method status=ManeuverService.class.getDeclaredMethod("status",missionClass,String.class,String.class,boolean.class);status.setAccessible(true);
        status.invoke(maneuver,mission,"navigating","active",true);push.pushPath(Collections.singletonMap("poses",Arrays.asList(1,2)));
        check(maneuver.isRunning(),"Mission active before cancellation");maneuver.abort("手动取消");
        events=(List<JSONObject>)push.navigationSnapshot().get("events");latest=events.get(events.size()-1).getJSONObject("data");
        check(!maneuver.isRunning()&&latest.getString("phase").equals("canceled")&&latest.getLongValue("missionId")==20,"Abort records canceled terminal used after refresh");
        long cancelRevision=((Number)push.navigationSnapshot().get("revision")).longValue();
        status.invoke(maneuver,mission,"navigating","late progress",true);push.pushPath(Collections.singletonMap("poses",Arrays.asList(3,4)));maneuver.abort("再次取消");
        check(((Number)push.navigationSnapshot().get("revision")).longValue()==cancelRevision,"Late progress/path and repeated abort cannot revive canceled mission");
        check(((List<?>)push.navigationSnapshot().get("events")).size()==1,"Canceled snapshot has no old path");maneuver.destroy();
        BridgeStub bridge=new BridgeStub(); NavigationController controller=new NavigationController();
        field(controller,"bridgeService",bridge);field(controller,"pushService",push);field(controller,"mapController",new MapStub());
        Map<String,Object> state=controller.runtimeState().getResult();check(state.get("mapName").equals("map-running")&&!state.containsKey("pose"),"No AMCL still restores map and state without NPE");
        bridge.hasPose=true;state=controller.runtimeState().getResult();Map<String,Object> pose=(Map<String,Object>)state.get("pose");
        check(((Number)pose.get("x")).doubleValue()==1&&((Number)pose.get("ageMs")).longValue()>=90000,"Stationary old pose available with explicit age");
        check(bridge.controls==0,"No ROS controls on refresh");
        push.clearNavigationSnapshot();check(((List<?>)push.navigationSnapshot().get("events")).isEmpty(),"Map change clears former task snapshots");
        System.out.println("PASS recovery: multiple same-user sessions, exact-session heartbeat, old-close isolation, user targeting, bounded immutable cache, no-AMCL snapshot, stationary pose age and no ROS controls");
    }
}
