package org.jeecg.modules.ros2.service;
import java.util.ArrayDeque;
/** Bounded ROS-time history. Do not pair delayed AMCL with arrival-time odometry. */
public final class OdomPoseHistory {
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private static final class Sample {
        final long stamp; final double[] pose; final long receivedAtMs;
        Sample(long stamp, double[] pose) { this.stamp=stamp; this.pose=pose.clone(); this.receivedAtMs=System.currentTimeMillis(); }
    }
    public synchronized void add(long stamp, double[] pose) {
        if (stamp<=0 || !NavigationGeometry.finitePose(pose)) return;
        if (!samples.isEmpty() && stamp<=samples.getLast().stamp) return;
        samples.addLast(new Sample(stamp,pose));
        while (samples.size()>1500 || stamp-samples.getFirst().stamp>15_000_000_000L) samples.removeFirst();
    }
    public synchronized java.util.Map<String,Object> describe(long stamp) {
        java.util.Map<String,Object> d=new java.util.LinkedHashMap<>();
        d.put("requestedStampNs",stamp);d.put("sampleCount",samples.size());
        String reason=stamp<=0 ? "MISSING_AMCL_STAMP" : samples.isEmpty() ? "NO_ODOMETRY" : "AFTER_LATEST";
        if (!samples.isEmpty()) {
            Sample first=samples.getFirst(), last=samples.getLast();
            d.put("oldestStampNs",first.stamp);d.put("latestStampNs",last.stamp);
            d.put("latestReceiptAgeMs",System.currentTimeMillis()-last.receivedAtMs);
            d.put("amclMinusLatestMs",(stamp-last.stamp)/1e6);
            Sample before=null;
            for(Sample after:samples) {
                if(after.stamp==stamp){reason="MATCHED";break;}
                if(after.stamp>stamp){
                    reason=before==null ? "BEFORE_HISTORY" : after.stamp-before.stamp>200_000_000L ? "ODOM_GAP_TOO_LARGE" : "MATCHED";
                    d.put("afterStampNs",after.stamp);
                    if(before!=null){d.put("beforeStampNs",before.stamp);d.put("bracketGapMs",(after.stamp-before.stamp)/1e6);}
                    break;
                }
                before=after;
            }
        }
        d.put("reason",stamp<=0 ? "MISSING_AMCL_STAMP" : reason);
        return d;
    }
    public synchronized double[] at(long stamp) {
        if (stamp<=0) return null;
        Sample before=null;
        for (Sample after:samples) {
            if (after.stamp==stamp) return after.pose.clone();
            if (after.stamp>stamp) {
                if (before==null || after.stamp-before.stamp>200_000_000L) return null;
                double t=(double)(stamp-before.stamp)/(after.stamp-before.stamp);
                return new double[]{before.pose[0]+t*(after.pose[0]-before.pose[0]),
                    before.pose[1]+t*(after.pose[1]-before.pose[1]),
                    NavigationGeometry.wrap(before.pose[2]+t*NavigationGeometry.wrap(after.pose[2]-before.pose[2]))};
            }
            before=after;
        }
        return null; // Wait for a bracketing sample; never extrapolate unknown motion.
    }
}
