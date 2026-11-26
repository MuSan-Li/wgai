package org.jeecg.modules.demo.video.util;
import java.util.*;
import java.lang.reflect.*;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
public class SubscriptionRuntimeStatusCheck {
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
 static void status(TabAiSubscriptionNew t,String expected){check(expected.equals(SubscriptionRuntimeStatus.snapshot(t,60).get("code")),expected+": "+SubscriptionRuntimeStatus.snapshot(t,60));}
 public static void main(String[]args)throws Exception{
  TabAiSubscriptionNew t=new TabAiSubscriptionNew().setId("runtime-check").setRunState(1);
  status(t,"UNREGISTERED");SubscriptionHeartbeatState.started(t);status(t,"WAITING");
  SubscriptionRuntimeStatus.begin(t);SubscriptionHeartbeatState.completed(t);status(t,"WAITING"); // A frame loop is not model output.
  SubscriptionRuntimeStatus.inferred();SubscriptionHeartbeatState.completed(t);status(t,"RUNNING");
  SubscriptionRuntimeStatus.begin(t);SubscriptionRuntimeStatus.failed(new IllegalStateException("native allocation failed"));SubscriptionHeartbeatState.completed(t);status(t,"ERROR");
  SubscriptionRuntimeStatus.begin(t);SubscriptionRuntimeStatus.inferred();SubscriptionHeartbeatState.completed(t);status(t,"RUNNING");
  Field active=SubscriptionRuntimeStatus.class.getDeclaredField("ACTIVE");active.setAccessible(true);Object entry=((Map<?,?>)active.get(null)).get(t.getId());Field last=entry.getClass().getDeclaredField("lastInference");last.setAccessible(true);last.setLong(entry,System.currentTimeMillis()-61000);status(t,"STALLED");
  SubscriptionHeartbeatState.stopped(t.getId());SubscriptionRuntimeStatus.inferred();status(t,"UNREGISTERED");
  TabAiSubscriptionNew replacement=new TabAiSubscriptionNew().setId(t.getId()).setRunState(1);SubscriptionHeartbeatState.started(replacement);SubscriptionRuntimeStatus.begin(t);SubscriptionRuntimeStatus.inferred();SubscriptionRuntimeStatus.failed(new Exception("old worker"));SubscriptionHeartbeatState.completed(t);status(replacement,"WAITING");
  SubscriptionRuntimeStatus.begin(replacement);SubscriptionRuntimeStatus.inferred();SubscriptionHeartbeatState.completed(replacement);status(replacement,"RUNNING");
  int hour=java.time.LocalTime.now().getHour();replacement.setDifyStartEnd((hour+1)%24).setDifyStartTime((hour+2)%24);status(replacement,"SCHEDULED");replacement.setDifyStartEnd(null).setDifyStartTime(null);
  replacement.setRunState(0);status(replacement,"STOPPED");SubscriptionHeartbeatState.stopped(t.getId());SubscriptionRuntimeStatus.clear();
  System.out.println("PASS: real model outputs, frame-only false-positive protection, errors, recovery, stale, stopped and restart identity");
 }
}
