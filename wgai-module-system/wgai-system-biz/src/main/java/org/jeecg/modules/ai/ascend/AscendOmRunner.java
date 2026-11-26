package org.jeecg.modules.ai.ascend;

import lombok.extern.slf4j.Slf4j;

/** 将订阅帧按 OM 的固定输入 batch 分组执行，尾批补齐后仅返回真实图片的结果。 */
@Slf4j
public final class AscendOmRunner {

    private AscendOmRunner() {
    }

    /** 推理结果：和 ONNX 侧 tensor.getValue() / getInfo().getShape() 对齐 */
    public static class BatchOutput {
        /** float[batchSize][d1][d2] */
        public final float[][][] rawOutput;
        /** [batchSize, d1, d2] */
        public final long[] tensorShape;

        BatchOutput(float[][][] rawOutput, long[] tensorShape) {
            this.rawOutput = rawOutput;
            this.tensorShape = tensorShape;
        }
    }

    /**
     * @param model          已加载的固定 batch OM 模型，可能有多个设备副本
     * @param batchInputData 整批 NCHW 展平数据，长度 = batchSize * 3 * 640 * 640
     * @param batchSize      这一批实际帧数
     * @return 只包含真实图片的输出；无效输出返回 null，由调用方报告 OM 推理失败
     */
    public static BatchOutput runBatch(AscendOmModel model, float[] batchInputData, int batchSize) {
        if (model == null || batchSize <= 0) {
            return null;
        }

        long[] omShape = model.outputShape(0);
        if (omShape == null || omShape.length != 3) {
            log.warn("[昇腾NPU] 输出不是 3 维 (实际 {} 维)，无法解析: {}",
                    omShape == null ? "null" : omShape.length, model.getOmPath());
            return null;
        }
        long[] inputShape = model.inputShape();
        if (inputShape == null || inputShape.length != 4 || inputShape[0] < 1 || inputShape[0] > 32
                || inputShape[1] != 3 || inputShape[2] != 640 || inputShape[3] != 640
                || omShape[0] != inputShape[0]) {
            throw new IllegalArgumentException("OM批量推理需要固定输入[N,3,640,640]及对应批量输出，input="
                    + java.util.Arrays.toString(inputShape) + ", output=" + java.util.Arrays.toString(omShape));
        }
        int modelBatch = (int) inputShape[0];
        int d1 = (int) omShape[1];
        int d2 = (int) omShape[2];

        int frameLen = batchInputData.length / batchSize;
        if (frameLen * batchSize != batchInputData.length) {
            log.warn("[昇腾NPU] 输入长度 {} 不能被 batchSize {} 整除",
                    batchInputData.length, batchSize);
            return null;
        }

        float[][][] out = new float[batchSize][][];
        for (int offset = 0; offset < batchSize; offset += modelBatch) {
            int valid = Math.min(modelBatch, batchSize - offset);
            float[] input = org.jeecg.modules.demo.video.util.TensorBatch.slice(
                    batchInputData, frameLen, offset, valid, modelBatch);
            float[] flat = model.infer(input)[0];
            if (flat.length != modelBatch * d1 * d2) throw new IllegalStateException("OM输出大小与batch不匹配");
            for (int b = 0; b < valid; b++) {
                float[][] plane = new float[d1][d2];
                for (int i = 0; i < d1; i++) {
                    System.arraycopy(flat, b * d1 * d2 + i * d2, plane[i], 0, d2);
                }
                out[offset + b] = plane;
            }
        }
        return new BatchOutput(out, new long[]{batchSize, d1, d2});
    }
}
