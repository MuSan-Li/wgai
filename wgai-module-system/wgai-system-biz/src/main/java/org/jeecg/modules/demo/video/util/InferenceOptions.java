package org.jeecg.modules.demo.video.util;

import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;

/** Persisted per-subscription options. Null values preserve safe defaults. */
public final class InferenceOptions {
    private InferenceOptions() { }
    public static int capacity(TabAiSubscriptionNew tab) {
        return range(tab.getInferenceQueueCapacity(), 5, 1, 200, "等待帧上限");
    }
    public static int batchSize(TabAiSubscriptionNew tab) {
        int maxBatch = range(tab.getInferenceBatchSize(), 4, 1, 32, "批量图片数");
        return batchEnabled(tab) ? maxBatch : 1;
    }
    public static boolean batchEnabled(TabAiSubscriptionNew tab) {
        return range(tab.getInferenceBatchEnabled(), 0, 0, 1, "批量处理开关") == 1;
    }
    public static int waitMs(TabAiSubscriptionNew tab) {
        return range(tab.getInferenceBatchWaitMs(), 30, 0, 5000, "凑批等待时间");
    }
    public static void validate(TabAiSubscriptionNew tab) {
        SubscriptionHeartbeatOptions.validate(tab);
        capacity(tab);
        waitMs(tab);
        batchSize(tab);
        if (batchEnabled(tab) && !Integer.valueOf(20).equals(tab.getModelJmType())
                && !Integer.valueOf(100).equals(tab.getModelJmType())) {
            throw new IllegalArgumentException("OpenCV推理当前仅支持单张识别，请关闭批量处理");
        }
    }
    private static int range(Integer value, int fallback, int min, int max, String label) {
        int result = value == null ? fallback : value;
        if (result < min || result > max) throw new IllegalArgumentException(label + "必须在" + min + "到" + max + "之间");
        return result;
    }
}
