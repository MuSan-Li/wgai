package org.jeecg.modules.ros2.service;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import org.jeecg.modules.ros2.controller.MapController;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Hardware-free regression checks: no ROS connection and no PLC writes. Run main after targeted compilation. */
public class NavigationRuntimeCheck {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void set(Object target, String name, Object value) throws Exception {
        Field f = ManeuverService.class.getDeclaredField(name); f.setAccessible(true); f.set(target,value);
    }
    static Object get(Object target, String name) throws Exception {
        Field f = ManeuverService.class.getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    static class Bridge extends ROS2BridgeService {
        double[][] poses; int index; int cancelled; boolean odom = true;
        Bridge(double[]... poses) { this.poses=poses; }
        @Override public synchronized AmclSample getAmclSample() {
            int i=Math.min(index++,poses.length-1);
            return new AmclSample(i+1,System.currentTimeMillis(),poses[i],odom ? new double[]{0,0,0}:null);
        }
        @Override public double[] getOdomPose(long age) { return odom ? new double[]{0,0,0}:null; }
        @Override public JsonObject callService(String s,String t,JsonObject a,long timeout) { return new JsonObject(); }
        @Override public boolean cancelAllNavGoals() { cancelled++; return true; }
    }
    static class PhaseBridge extends ROS2BridgeService {
        final OdomPoseHistory history=new OdomPoseHistory();int tick,cancelled;double jump;
        PhaseBridge(){history.add(20_000_000_000L,new double[]{0,0,0});updateAmclPose(observation(0,0));}
        JsonObject observation(int i,double x){JsonObject m=amcl(20,x);m.getAsJsonObject("header").getAsJsonObject("stamp").addProperty("nanosec",i*100_000_000);return m;}
        @Override public double[] getOdomPoseAt(long stamp){return history.at(stamp);}
        @Override public double[] getOdomPose(long age){return new double[]{Math.max(0,tick-1)*.1,0,0};}
        @Override public synchronized List<AmclSample> getAmclSamplesAfter(long seq){
            tick++;
            // Latest AMCL is always ahead of the latest delivered odometry; previous pending one can now match.
            updateAmclPose(observation(tick,tick*.1+jump));
            history.add(20_000_000_000L+(tick-1)*100_000_000L,new double[]{(tick-1)*.1,0,0});
            return super.getAmclSamplesAfter(seq);
        }
        @Override public JsonObject callService(String s,String t,JsonObject a,long timeout){return new JsonObject();}
        @Override public boolean cancelAllNavGoals(){cancelled++;return true;}
    }
    static class Map extends MapController {
        int scans; List<double[]> obstacles = new ArrayList<>();
        @Override public double getStaticMapResolution() { return .05; }
        @Override public double getGlobalFootprintPadding() { return .15; }
        @Override public List<double[]> staticMapOccupiedPoints(double[][] poly,int max) {
            scans++; List<double[]> result=new ArrayList<>();
            for(double[] point:obstacles) if(NavigationGeometry.cellIntersects(poly,point[0],point[1],.05)) result.add(point);
            return result;
        }
        @Override public boolean staticMapPolygonHasOccupied(double[][] p) { return !staticMapOccupiedPoints(p,1).isEmpty(); }
    }
    static class Hardware extends RobotHardwareService {
        int stopped;
        @Override public void resetReverseTracker() {}
        @Override public void emergencyStop() { stopped++; }
    }
    static class Nav extends NavigationService {
        volatile int goals;
        @Override public void sendNavigationGoal(Double x,Double y,Double theta) { goals++; }
    }
    static class Push extends WebSocketPushService {
        final List<JSONObject> events=Collections.synchronizedList(new ArrayList<>());
        @Override public void pushToAll(String type,Object d) { if("maneuver_status".equals(type)) events.add((JSONObject)d); }
    }
    static ManeuverService service(ROS2BridgeService b,Map map,Hardware hw,Nav nav,Push push) throws Exception {
        ManeuverService s=new ManeuverService();set(s,"bridge",b);set(s,"mapController",map);set(s,"hw",hw);set(s,"navigation",nav);set(s,"push",push);
        set(s,"lateralMaxM",1d);set(s,"planTimeoutMs",12000L);set(s,"localizationCheckTimeoutMs",400L);
        return s;
    }
    static void rejected(ManeuverService s,String fragment) {
        try { s.startMission(8,0,0d,0d,false);throw new AssertionError("Goal should be rejected"); }
        catch(ManeuverService.GoalRejectedException e) { check(e.getMessage().contains(fragment),e.getMessage()); }
    }
    static JsonObject amcl(long stamp,double x) {
        JsonObject m=new JsonObject(), header=new JsonObject(),time=new JsonObject();time.addProperty("sec",stamp);time.addProperty("nanosec",0);header.add("stamp",time);m.add("header",header);
        JsonObject cov=new JsonObject(),pose=new JsonObject(),position=new JsonObject(),q=new JsonObject();position.addProperty("x",x);position.addProperty("y",0);q.addProperty("z",0);q.addProperty("w",1);
        pose.add("position",position);pose.add("orientation",q);cov.add("pose",pose);m.add("pose",cov);return m;
    }
    public static void main(String[] args) throws Exception {
        final List<JsonObject> subscriptions=new ArrayList<>();
        ROS2BridgeService transport=new ROS2BridgeService(){
            @Override public void send(String text){subscriptions.add(new com.google.gson.JsonParser().parse(text).getAsJsonObject());}
            @Override public boolean isConnected(){return true;}
        };
        Method subscribe=ROS2BridgeService.class.getDeclaredMethod("subscribeToTopics");subscribe.setAccessible(true);subscribe.invoke(transport);
        transport.resubscribeFastLioTopics();transport.resubscribeNav2Topics();
        int odomSubscriptions=0,amclSubscriptions=0;
        for(JsonObject message:subscriptions) if("subscribe".equals(message.get("op").getAsString())) {
            String topic=message.get("topic").getAsString();
            if("/Odometry".equals(topic)||"/amcl_pose".equals(topic)){
                check(message.get("throttle_rate").getAsInt()==0,"Startup and resubscription must not drop synchronization samples");
                if("/Odometry".equals(topic))odomSubscriptions++;else amclSubscriptions++;
            }
            if("/cloud_registered".equals(topic))check(message.get("throttle_rate").getAsInt()==200&&message.get("queue_length").getAsInt()==1,"Cloud load stays bounded independently of pose synchronization");
        }
        check(odomSubscriptions==2&&amclSubscriptions==2,"Verify initial and recovery subscriptions");
                OdomPoseHistory history=new OdomPoseHistory();
        history.add(1_000_000_000L,new double[]{0,0,Math.toRadians(179)});
        history.add(1_100_000_000L,new double[]{1,0,Math.toRadians(-179)});
        double[] interpolated=history.at(1_050_000_000L);
        check(Math.abs(interpolated[0]-.5)<1e-9 && Math.abs(Math.abs(interpolated[2])-Math.PI)<1e-9,"Timestamp interpolation and yaw wrap");
        check(history.at(999_000_000L)==null && history.at(1_200_000_000L)==null,"No extrapolation outside odometry history");
        history.add(1_500_000_000L,new double[]{8,0,0});
        check(history.at(1_300_000_000L)==null,"Odometry gaps cannot count as valid synchronization");
        history.add(1_050_000_000L,new double[]{99,0,0});
        check(history.at(1_100_000_000L)[0]==1,"Late odometry cannot corrupt ordered history");
        OdomPoseHistory delayed=new OdomPoseHistory();
        delayed.add(2_000_000_000L,new double[]{0,0,0});
        delayed.add(2_100_000_000L,new double[]{.1,0,0});
        delayed.add(2_200_000_000L,new double[]{.2,0,0});
        delayed.add(2_300_000_000L,new double[]{.3,0,0});
        double[] delayedCorrection=NavigationGeometry.correction(new double[]{10,0,0},delayed.at(2_000_000_000L),new double[]{10.1,0,0},delayed.at(2_100_000_000L));
        check(delayedCorrection[0]<1e-9,"Variable arrival delay must not look like vehicle localization jump");
        ROS2BridgeService aligned=new ROS2BridgeService(){
            @Override public double[] getOdomPoseAt(long ns){return delayed.at(ns);}
            @Override public double[] getOdomPose(long age){return new double[]{.3,0,0};}
        };
        JsonObject stamped=amcl(2,10);stamped.getAsJsonObject("header").getAsJsonObject("stamp").addProperty("nanosec",100_000_000);
        check(aligned.updateAmclPose(stamped) && Math.abs(aligned.getAmclSample().odomReference[0]-.1)<1e-9,"AMCL paired with ROS-time odometry, not arrival-time odometry");
        check(Math.abs(aligned.getEstimatedMapPose()[0]-10.2)<1e-9,"Estimated current map pose compensates delayed sample");
                OdomPoseHistory pendingHistory=new OdomPoseHistory();
        pendingHistory.add(3_000_000_000L,new double[]{0,0,0});
        ROS2BridgeService pendingReference=new ROS2BridgeService(){@Override public double[] getOdomPoseAt(long ns){return pendingHistory.at(ns);}};
        JsonObject early=amcl(3,0);early.getAsJsonObject("header").getAsJsonObject("stamp").addProperty("nanosec",50_000_000);
        pendingReference.updateAmclPose(early);
        check(pendingReference.getAmclSample().odomReference==null,"AMCL arriving before bracketing odometry must wait");
        pendingHistory.add(3_100_000_000L,new double[]{.1,0,0});
        check(Math.abs(pendingReference.getAmclSample().odomReference[0]-.05)<1e-9,"Late bracketing odometry fills the same AMCL reference");
                double[][] body={{.55,-.85},{.55,1.2},{-5.2,1.2},{-5.2,-.85}};
        double[][] pad={{.70,-1},{.70,1.35},{-5.35,1.35},{-5.35,-1}};
        check(!NavigationGeometry.cellIntersects(body,-1,-.975,.05),"Margin-only cell must not hit body");
        check(NavigationGeometry.cellIntersects(pad,-1,-.975,.05),"Margin-only cell must hit padding");
        check(NavigationGeometry.cellIntersects(body,-1,-.87,.05),"Whole occupied cell overlaps edge even when centre outside");
        check(!NavigationGeometry.cellIntersects(body,-6,2,.05),"Clear cell");
        double[] c=NavigationGeometry.correction(new double[]{10,20,Math.PI/2},new double[]{0,0,0},new double[]{10,24,Math.PI/2},new double[]{4,0,0});
        check(c[0]<1e-9&&c[1]<1e-9,"Real movement must not be mistaken for AMCL jump");
        c=NavigationGeometry.correction(new double[]{0,0,Math.toRadians(179)},new double[]{0,0,Math.toRadians(179)},new double[]{0,0,Math.toRadians(-179)},new double[]{0,0,Math.toRadians(-179)});
        check(c[1]<1e-9,"Yaw wrap must not look like a jump");
        c=NavigationGeometry.correction(new double[]{-20.93,-6.64,-.974},new double[]{0,0,0},new double[]{-17.16,-11.78,-1.08},new double[]{0,0,0});
        check(c[0]>6,"Reported site jump must be detected");
        ROS2BridgeService cache=new ROS2BridgeService(){@Override public double[] getOdomPose(long ms){return new double[]{0,0,0};}};
        check(cache.updateAmclPose(amcl(100,1)),"First AMCL accepted");
        check(!cache.updateAmclPose(amcl(99,8))&&!cache.updateAmclPose(amcl(100,9)),"Old and duplicate AMCL discarded");
        check(cache.getLastAmclPose()[0]==1&&cache.getAmclSample().sequence==1,"Discarded AMCL cannot move cached footprint");
        Bridge b=new Bridge(new double[]{0,0,0},new double[]{6.4,0,0});Map map=new Map();Hardware hw=new Hardware();Nav nav=new Nav();Push push=new Push();
        ManeuverService s=service(b,map,hw,nav,push);
        rejected(s,"跳变");check(map.scans==0&&nav.goals==0,"Unstable localization must not issue collision verdict or target");s.destroy();
        b=new Bridge(new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0});map=new Map();map.obstacles.add(new double[]{-1,-.84});nav=new Nav();
        s=service(b,map,new Hardware(),nav,new Push());rejected(s,"实车轮廓");check(nav.goals==0,"Body collision must block target");s.destroy();
        b=new Bridge(new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0});map=new Map();map.obstacles.add(new double[]{-1,-.975});nav=new Nav();hw=new Hardware();push=new Push();
        s=service(b,map,hw,nav,push);java.util.Map<String,Object> response=s.startMission(8,0,0d,0d,false);
        java.util.Map<?,?> diagnostic=(java.util.Map<?,?>)response.get("diagnostic");
        check(((Number)diagnostic.get("bodyCount")).intValue()==0&&((Number)diagnostic.get("paddingCount")).intValue()==1,"Body/padding must be separated");
        long deadline=System.currentTimeMillis()+1000;while(nav.goals==0&&System.currentTimeMillis()<deadline)Thread.sleep(10);check(nav.goals==1,"Stable margin-only start reaches Nav2");
        b.poses=new double[][]{{0,0,0},{0,0,0},{0,0,0},{0,0,0},{6.4,0,0}};b.index=4;
        Method monitor=ManeuverService.class.getDeclaredMethod("monitorLocalization");monitor.setAccessible(true);monitor.invoke(s);
        check(hw.stopped==1&&b.cancelled==1,"Running jump must stop hardware and cancel Nav2");
        JSONObject terminal=push.events.get(push.events.size()-1);check("failed".equals(terminal.getString("phase"))&&terminal.getBooleanValue("clearDiagnostic"),"Jump sends explicit failure and clears obsolete collision snapshot");s.destroy();
        b=new Bridge(new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0});
        hw=new Hardware();nav=new Nav();push=new Push();s=service(b,new Map(),hw,nav,push);s.startMission(8,0,0d,0d,false);
        b.poses=new double[][]{{0,0,0},{0,0,0},{0,0,0},{0,0,0},{0,0,0}};b.index=4;b.odom=false;
        monitor.invoke(s);check(hw.stopped==0,"Missing time reference waits briefly for odometry instead of inventing a jump");
        Object running=get(s,"mission");Field unmatched=running.getClass().getDeclaredField("unmatchedSinceMs");unmatched.setAccessible(true);unmatched.setLong(running,System.currentTimeMillis()-1600);
        monitor.invoke(s);check(hw.stopped==1&&b.cancelled==1,"Persistent time-alignment loss must stop and cancel");
        terminal=push.events.get(push.events.size()-1);check(terminal.getString("message").contains("时间无法对齐"),"Alignment loss is distinct from actual localization jump");s.destroy();
                b=new Bridge(new double[]{0,0,0});b.odom=false;map=new Map();nav=new Nav();s=service(b,map,new Hardware(),nav,new Push());rejected(s,"超时");check(map.scans==0&&nav.goals==0,"Missing odometry cannot pass stable gate");s.destroy();
        b=new Bridge(new double[]{0,0,0});nav=new Nav();final ManeuverService cancel=service(b,new Map(),new Hardware(),nav,new Push());set(cancel,"localizationCheckTimeoutMs",2000L);
        AtomicReference<Throwable> error=new AtomicReference<>();Thread request=new Thread(()->{try{cancel.startMission(8,0,0d,0d,false);}catch(Throwable e){error.set(e);}});request.start();Thread.sleep(70);cancel.abort("test cancel");request.join(1000);
        check(!request.isAlive()&&error.get()!=null&&nav.goals==0,"Cancellation interrupts preflight without sending target");cancel.destroy();
        PhaseBridge phase=new PhaseBridge();hw=new Hardware();nav=new Nav();push=new Push();
        s=service(phase,new Map(),hw,nav,push);set(s,"localizationCheckTimeoutMs",1200L);s.startMission(8,0,0d,0d,false);
        for(int i=0;i<4;i++) monitor.invoke(s);
        check(hw.stopped==0&&phase.cancelled==0,"Steady AMCL-before-odom phase must not starve time matching or stop navigation");
        check(phase.getAmclSample().odomReference==null,"Latest AMCL remains ahead: regression exercises superseded pending samples");
        phase.jump=6.4;monitor.invoke(s);phase.jump=0;monitor.invoke(s);
        check(hw.stopped==1&&phase.cancelled==1,"A real jump in queued AMCL is still inspected and stopped");s.destroy();
        OdomPoseHistory reasons=new OdomPoseHistory();check("NO_ODOMETRY".equals(reasons.describe(100).get("reason")),"Missing odometry diagnostic");
        reasons.add(1_000_000_000L,new double[]{0,0,0});reasons.add(1_500_000_000L,new double[]{0,0,0});
        check("ODOM_GAP_TOO_LARGE".equals(reasons.describe(1_200_000_000L).get("reason")),"Gap diagnostic distinct from lidar failure");
        check("AFTER_LATEST".equals(reasons.describe(1_600_000_000L).get("reason"))&&"BEFORE_HISTORY".equals(reasons.describe(500_000_000L).get("reason")),"Time direction diagnostics");
                double yaw38=Math.toRadians(3.8);
        b=new Bridge(new double[]{0,0,0},new double[]{.04,0,yaw38},new double[]{.04,0,yaw38},new double[]{.04,0,yaw38},new double[]{.04,0,yaw38});
        hw=new Hardware();nav=new Nav();s=service(b,new Map(),hw,nav,new Push());s.startMission(8,0,0d,0d,false);
        check(hw.stopped==0,"Stationary 0.04m/3.8deg preflight settles without falsely requiring initial-pose reset");s.destroy();
        b=new Bridge(new double[]{0,0,0},new double[]{0,0,Math.toRadians(16)});nav=new Nav();s=service(b,new Map(),new Hardware(),nav,new Push());
        rejected(s,"跳变");check(nav.goals==0,"Large stationary pose mismatch is still rejected");s.destroy();
        b=new Bridge(new double[]{0,0,0},new double[]{0,0,yaw38},new double[]{0,0,2*yaw38},new double[]{0,0,3*yaw38},new double[]{0,0,4*yaw38},new double[]{0,0,5*yaw38}){
            @Override public synchronized AmclSample getAmclSample(){double yaw=(index++%2)*yaw38;return new AmclSample(index,System.currentTimeMillis(),new double[]{0,0,yaw},new double[]{0,0,0});}
        };
        nav=new Nav();s=service(b,new Map(),new Hardware(),nav,new Push());rejected(s,"超时");check(nav.goals==0,"Persistent stationary drift cannot pass by resetting a stability counter");s.destroy();
        b=new Bridge(new double[]{0,0,0}){
            @Override public synchronized AmclSample getAmclSample(){double yaw=index++*yaw38;return new AmclSample(index,System.currentTimeMillis(),new double[]{0,0,yaw},new double[]{0,0,0});}
        };
        nav=new Nav();s=service(b,new Map(),new Hardware(),nav,new Push());rejected(s,"跳变");check(nav.goals==0,"Small repeated corrections cannot bypass cumulative stationary displacement bound");s.destroy();
        b=new Bridge(new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0},new double[]{0,0,0});hw=new Hardware();push=new Push();s=service(b,new Map(),hw,new Nav(),push);s.startMission(8,0,0d,0d,false);
        b.poses=new double[][]{{0,0,0},{0,0,0},{0,0,0},{0,0,0},{.04,0,yaw38}};b.index=4;monitor.invoke(s);
        check(hw.stopped==1&&b.cancelled==1,"Runtime correction limit remains active after goal submission");s.destroy();
        System.out.println("PASS: stationary 3.8deg settling, persistent drift rejection, sustained AMCL-before-odom phase, queued real jump, explicit failure reasons, ROS-time interpolation, delayed/bracketing odometry, alignment-loss stop, geometry, odometry compensation, site jump, AMCL ordering, preflight rejection, margin diagnostic, runtime stop, missing odometry and cancellation");
    }
}
