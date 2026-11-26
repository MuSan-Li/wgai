package org.jeecg.modules.demo.video.util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;

/** In-memory observations, independent of heartbeat enablement; no IO in model threads. */
public final class SubscriptionRuntimeStatus {
    private SubscriptionRuntimeStatus() { }
    private static final class Entry {
        final TabAiSubscriptionNew owner;
        final long started = System.currentTimeMillis();
        final AtomicLong calls = new AtomicLong(), errors = new AtomicLong();
        volatile long lastInference, lastFrame, lastError;
        volatile String error = "";
        volatile boolean failing;
        Entry(TabAiSubscriptionNew owner) { this.owner=owner; }
    }
    private static final class Cycle {
        final Entry entry; final long errors, calls;
        Cycle(Entry entry) { this.entry=entry; errors=entry.errors.get(); calls=entry.calls.get(); }
    }
    private static final ConcurrentHashMap<String,Entry> ACTIVE=new ConcurrentHashMap<>();
    private static final ThreadLocal<Cycle> CURRENT=new ThreadLocal<>();
    public static void started(TabAiSubscriptionNew task) { ACTIVE.put(task.getId(),new Entry(task)); }
    public static void stopped(String id) { ACTIVE.remove(id); }
    public static void begin(TabAiSubscriptionNew task) { Entry e=ACTIVE.get(task.getId()); if(e==null || e.owner!=task)CURRENT.remove();else CURRENT.set(new Cycle(e)); }
    public static void clear() { CURRENT.remove(); }
    private static Entry current() {
        Cycle c=CURRENT.get(); return c!=null && ACTIVE.get(c.entry.owner.getId())==c.entry ? c.entry : null;
    }
    public static void inferred() { Entry e=current(); if(e!=null){e.lastInference=System.currentTimeMillis();e.calls.incrementAndGet();} }
    public static void failed(Throwable cause) {
        Entry e=current(); if(e==null)return;
        String text=cause.getClass().getSimpleName()+": "+Objects.toString(cause.getMessage(),"");
        e.error=text.length()>500?text.substring(0,500):text;
        e.lastError=System.currentTimeMillis();e.errors.incrementAndGet();e.failing=true;
    }
    public static void completed(TabAiSubscriptionNew task) {
        Entry e=ACTIVE.get(task.getId());if(e==null || e.owner!=task)return;
        e.lastFrame=System.currentTimeMillis();Cycle c=CURRENT.get();
        if(c!=null && c.entry==e && e.errors.get()==c.errors && e.calls.get()>c.calls)e.failing=false;
    }
    private static boolean outsideSchedule(TabAiSubscriptionNew task) {
        Integer start=task.getDifyStartEnd(), end=task.getDifyStartTime();
        if(start==null || end==null || start.equals(end) || start<0 || start>23 || end<0 || end>23)return false;
        int hour=java.time.LocalTime.now().getHour();
        return start<end ? hour<start || hour>=end : hour<start && hour>=end;
    }
    public static Map<String,Object> snapshot(TabAiSubscriptionNew task,long staleSeconds) {
        Entry e=ACTIVE.get(task.getId());Map<String,Object> out=new LinkedHashMap<>();
        long now=System.currentTimeMillis();String code,label;
        if(!Integer.valueOf(1).equals(task.getRunState())){code="STOPPED";label="未运行";}
        else if(e==null){code="UNREGISTERED";label="本机无运行任务";}
        else if(outsideSchedule(task)){code="SCHEDULED";label="非识别时段";}
        else if(e.failing){code="ERROR";label="处理异常";}
        else if(e.lastInference==0){code=now-e.started>staleSeconds*1000?"STALLED":"WAITING";label=code.equals("WAITING")?"等待首次推理":"尚无推理结果";}
        else if(now-e.lastInference>staleSeconds*1000){code="STALLED";label="推理长时间无进展";}
        else {code="RUNNING";label="推理有进展";}
        out.put("code",code);out.put("label",label);out.put("observedAt",now);out.put("staleSeconds",staleSeconds);
        out.put("lastInferenceAt",e==null?0:e.lastInference);out.put("lastFrameAt",e==null?0:e.lastFrame);
        out.put("inferenceCalls",e==null?0:e.calls.get());out.put("lastErrorAt",e==null?0:e.lastError);out.put("lastError",e==null?"":e.error);
        return out;
    }
}
