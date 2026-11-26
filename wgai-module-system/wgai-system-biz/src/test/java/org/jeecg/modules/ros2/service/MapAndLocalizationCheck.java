package org.jeecg.modules.ros2.service;
import org.jeecg.modules.ros2.controller.NavigationController;
import org.jeecg.modules.ros2.model.NavigationGoalDTO;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Local file tests and simulated AMCL only; never connects to ROS or PLC. */
public class MapAndLocalizationCheck {
    static void check(boolean ok,String msg) { if(!ok)throw new AssertionError(msg); }
    static void set(Object o,String key,Object value)throws Exception { Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);f.set(o,value); }
    static Object get(Object o,String key)throws Exception { Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o); }
    static class Bridge extends ROS2BridgeService {
        long sequence; boolean fresh=true, covarianceOk=true;
        @Override public synchronized AmclSample getAmclSample(){ if(fresh)sequence++;return new AmclSample(sequence,System.currentTimeMillis(),new double[]{0,0,0},new double[]{0,0,0}); }
        @Override public double[] getLastAmclPose(){return new double[]{0,0,0};}
        @Override public double getLastAmclCovXX(){return covarianceOk ? .01 : 1.0;}
        @Override public double getLastAmclCovYY(){return covarianceOk ? .01 : 1.0;}
        @Override public boolean isAmclConverged(double threshold){return covarianceOk;}
        @Override public JsonObject callService(String s,String t,JsonObject args,long timeout){return new JsonObject();}
    }
    static class Preflight extends ManeuverService {
        int checks;
        @Override public java.util.Map<String,Object> startMission(double x,double y,Double theta,Double side,boolean precise) {
            checks++; throw new IllegalStateException("fresh-pose-preflight-refused");
        }
    }
    static class Push extends WebSocketPushService {
        volatile Object last;
        @Override public void pushToAll(String type,Object data){last=data;}
    }
    static void refinement(boolean fresh,boolean covarianceOk)throws Exception {
        NavigationController c=new NavigationController();Bridge b=new Bridge();b.fresh=fresh;b.covarianceOk=covarianceOk;Push push=new Push();
        set(c,"bridgeService",b);set(c,"pushService",push);set(c,"initialRefineMaxRounds",3);
        Method m=NavigationController.class.getDeclaredMethod("refineLocalization",double.class,double.class,double.class,double.class,double.class);m.setAccessible(true);m.invoke(c,0.,0.,0.,.5,15.);
        check(!c.setGoal(new NavigationGoalDTO()).isSuccess(),"no navigation during refinement");
        check(!c.triggerGlobalLocalization().isSuccess(),"no global reset during refinement");
        long end=System.currentTimeMillis()+8000;
        while((Boolean)get(c,"refiningLocalization")&&System.currentTimeMillis()<end)Thread.sleep(20);
        check(!(Boolean)get(c,"refiningLocalization"),"bounded refinement must finish");
        check((Boolean)get(c,"lastRefinementConverged")== (fresh && covarianceOk),"initial convergence remains diagnostic");
        check(push.last!=null,"explicit refinement result");
        Preflight preflight = new Preflight(); set(c,"maneuverService",preflight);
        NavigationGoalDTO goal = new NavigationGoalDTO();goal.setX(1.);goal.setY(2.);
        org.jeecg.common.api.vo.Result<?> rejected = c.setGoal(goal);
        check(!rejected.isSuccess() && preflight.checks==1
                && rejected.getMessage().contains("fresh-pose-preflight-refused"),
                "failed initial refinement must reach fresh preflight; preflight failure still rejects");
        check(Boolean.TRUE.equals(c.isLocalizationReady().getResult().get("ready")),"readiness is not latched by past refinement");
        set(c,"initialPoseMismatchMessage","initial-pose-mismatch-2m");
        check(c.setGoal(goal).getMessage().contains("initial-pose-mismatch-2m") && preflight.checks==1,
                "gross initial mismatch protection remains distinct from covariance advisory");
        ((java.util.concurrent.ExecutorService)get(c,"refineExecutor")).shutdownNow();
    }
    public static void main(String[] args)throws Exception {
        Path base=Paths.get("target/navigation-fix").toAbsolutePath().normalize();
        Path dir=Files.createTempDirectory(base,"map-delete-");
        String[] suffixes={".yaml",".pgm",".png","_last_pose.json",".pgm.orig","_erase.json"};
        for(String suffix:suffixes)Files.write(dir.resolve("old"+suffix),new byte[]{1});
        Files.write(dir.resolve("current.yaml"),new byte[]{1});
        for(String bad:Arrays.asList("../outside","a/b","a\\b","last_used","nav2_params_fastlio","")) {
            try {MapFileStore.delete(dir,bad,null);throw new AssertionError("accepted "+bad);}catch(IllegalArgumentException expected){}
        }
        try{MapFileStore.delete(dir,"current","current");throw new AssertionError("deleted active map");}catch(IllegalStateException expected){}
        check(Files.exists(dir.resolve("current.yaml")),"active map intact");
        check(MapFileStore.delete(dir,"old","current")==6,"delete six artifacts");
        check(MapFileStore.delete(dir,"old","current")==0,"absent map count");
        Files.createDirectory(dir.resolve("directory.pgm"));Files.write(dir.resolve("directory.yaml"),new byte[]{1});
        try{MapFileStore.delete(dir,"directory",null);throw new AssertionError("deleted directory");}catch(java.io.IOException expected){}
        check(Files.exists(dir.resolve("directory.yaml")),"validate all targets before deleting");
        Files.delete(dir.resolve("directory.pgm"));Files.delete(dir.resolve("directory.yaml"));Files.delete(dir.resolve("current.yaml"));Files.delete(dir);
        refinement(true,true);refinement(false,true);refinement(true,false);
        System.out.println("PASS: map artifacts, active-map protection, traversal rejection, directory protection, fresh/stale/high-covariance refinement, fresh preflight retry and navigation/reset exclusion");
    }
}
