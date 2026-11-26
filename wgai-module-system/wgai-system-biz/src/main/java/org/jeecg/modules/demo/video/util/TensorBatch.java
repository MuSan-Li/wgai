package org.jeecg.modules.demo.video.util;

/** Copies a fixed model batch; repeats the last real frame to pad the tail. */
public final class TensorBatch {
    private TensorBatch() { }
    public static int effectiveSize(boolean enabled, long modelBatch, int dynamicMax) {
        if (!enabled) return 1;
        if (modelBatch > 32 || dynamicMax < 1 || dynamicMax > 32) throw new IllegalArgumentException("batch must be <= 32");
        return modelBatch > 0 ? (int) modelBatch : dynamicMax;
    }
    public static float[] slice(float[] input, int frameSize, int offset, int valid, int modelBatch) {
        if (frameSize < 1 || offset < 0 || valid < 1 || valid > modelBatch
                || (long) (offset + valid) * frameSize > input.length) throw new IllegalArgumentException("invalid batch slice");
        float[] result = new float[Math.multiplyExact(modelBatch, frameSize)];
        System.arraycopy(input, offset * frameSize, result, 0, valid * frameSize);
        for (int i = valid; i < modelBatch; i++) {
            System.arraycopy(result, (valid - 1) * frameSize, result, i * frameSize, frameSize);
        }
        return result;
    }
}
