package org.jeecg.modules.demo.video.util.batch.batch;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.InferenceOptions;
import org.jeecg.modules.demo.video.util.LatestFrameQueue;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded waiting queue and one inference worker per subscription; no hidden downstream queues. */
@Slf4j
public final class SubscriptionBatchProcessor implements AutoCloseable {
    private static final class Item {
        final Mat frame;
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        Item(Mat frame) { this.frame = frame; }
        void discard() { frame.release(); future.complete(false); }
    }
    private final TabAiSubscriptionNew tab;
    private final RedisTemplate redis;
    private final LatestFrameQueue<Item> queue;
    private final Thread worker;
    private final AtomicLong dropped = new AtomicLong();
    private final int batchSize;
    private final int waitMs;
    private volatile boolean closed;

    public SubscriptionBatchProcessor(TabAiSubscriptionNew tab, RedisTemplate redis) {
        this.tab = tab;
        this.redis = redis;
        this.batchSize = resolveBatchSize(tab);
        this.waitMs = InferenceOptions.waitMs(tab);
        this.queue = new LatestFrameQueue<>(InferenceOptions.capacity(tab), item -> {
            dropped.incrementAndGet(); item.discard();
        });
        worker = new Thread(this::run, "SubscriptionBatch-" + tab.getId());
        worker.setDaemon(true);
        worker.start();
    }

    private int resolveBatchSize(TabAiSubscriptionNew tab) {
        if (!InferenceOptions.batchEnabled(tab)) return 1;
        int size = 1;
        for (NetPush model : tab.getNetPushList()) size = Math.max(size, modelBatch(model));
        return Math.min(size, InferenceOptions.capacity(tab));
    }
    private int modelBatch(NetPush model) {
        if (model.getListNetPush() != null && !model.getListNetPush().isEmpty()) {
            int size = 1;
            for (NetPush child : model.getListNetPush()) size = Math.max(size, modelBatch(child));
            return size;
        }
        long[] shape;
        if (Integer.valueOf(100).equals(tab.getModelJmType())) {
            shape = org.jeecg.modules.ai.ascend.AscendOmPool.preload(model.getTabAiModel().getAiWeights()).inputShape();
        } else {
            try {
                ai.onnxruntime.NodeInfo input = model.getSession().getInputInfo().values().iterator().next();
                shape = ((ai.onnxruntime.TensorInfo) input.getInfo()).getShape();
            } catch (ai.onnxruntime.OrtException e) { throw new IllegalStateException("无法读取模型batch", e); }
        }
        if (shape.length != 4 || shape[0] > 32) throw new IllegalArgumentException("批量输入需要[N,C,H,W]，batch不超过32");
        int size = org.jeecg.modules.demo.video.util.TensorBatch.effectiveSize(true, shape[0], InferenceOptions.batchSize(tab));
        log.info("[模型批量能力] taskId={} model={} input={} actualBatch={}", tab.getId(),
                model.getTabAiModel().getAiName(), Arrays.toString(shape), size);
        return size;
    }
    public CompletableFuture<Boolean> submit(Mat frame) {
        Item item = new Item(frame);
        queue.offer(item);
        return item.future;
    }
    public int queued() { return queue.size(); }
    public long dropped() { return dropped.get(); }
    private void run() {
        try {
            while (!closed) {
                List<Item> batch = queue.takeBatch(batchSize, waitMs);
                if (batch.isEmpty()) continue;
                long started = System.nanoTime();
                org.jeecg.modules.demo.video.util.SubscriptionRuntimeStatus.begin(tab);
                try {
                    boolean[] success = new boolean[batch.size()];
                    for (NetPush setting : tab.getNetPushList()) {
                        if (closed) break;
                        processSetting(batch, setting, success);
                    }
                    org.jeecg.modules.demo.video.util.SubscriptionRuntimeStatus.completed(tab);
                    for (int i = 0; i < batch.size(); i++) batch.get(i).future.complete(!closed && success[i]);
                } catch (Exception e) {
                    org.jeecg.modules.demo.video.util.SubscriptionRuntimeStatus.failed(e);
                    log.error("[订阅批量推理失败] taskId={}", tab.getId(), e);
                    batch.forEach(item -> item.future.completeExceptionally(e));
                } finally {
                    org.jeecg.modules.demo.video.util.SubscriptionRuntimeStatus.clear();
                    batch.forEach(item -> item.frame.release());
                    log.info("[订阅推理队列] taskId={} batch={} queued={} capacity={} dropped={} elapsedMs={}",
                            tab.getId(), batch.size(), queue.size(), InferenceOptions.capacity(tab), dropped.get(),
                            (System.nanoTime() - started) / 1000000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally { queue.close(); }
    }
    private void processSetting(List<Item> items, NetPush setting, boolean[] success) {
        List<NetPush> chain = setting.getListNetPush();
        List<Integer> indices = new ArrayList<>();
        List<List<retureBoxInfo>> regions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) { indices.add(i); regions.add(null); }
        if (Integer.valueOf(0).equals(setting.getIsBefor()) && chain != null && !chain.isEmpty()) {
            List<Mat> frames = copies(items, indices);
            List<retureBoxInfo> pre;
            try {
                pre = BatchOnnxInference.batchInferencePreModel(frames, chain.get(0),
                        Collections.nCopies(frames.size(), tab), redis);
            } finally { frames.forEach(Mat::release); }
            indices.clear(); regions.clear();
            for (int i = 0; i < items.size(); i++) {
                retureBoxInfo result = pre.get(i);
                if (result != null && result.isFlag() && result.getInfoList() != null && !result.getInfoList().isEmpty()) {
                    indices.add(i); regions.add(result.getInfoList());
                }
            }
            if (indices.isEmpty()) return;
            for (int i = 1; i < chain.size() && !closed; i++) detect(items, indices, regions, chain.get(i), success);
        } else detect(items, indices, regions, setting, success);
    }
    private void detect(List<Item> items, List<Integer> indices, List<List<retureBoxInfo>> regions,
                        NetPush model, boolean[] success) {
        List<Mat> frames = copies(items, indices);
        try {
            List<Boolean> result = Integer.valueOf(2).equals(model.getDifyType())
                    ? BatchOnnxInference.batchInferencePoseModel(frames, model, Collections.nCopies(frames.size(), tab), regions, redis)
                    : BatchOnnxInference.batchInferencePostModel(frames, model, Collections.nCopies(frames.size(), tab), regions, redis);
            for (int i = 0; i < indices.size(); i++) success[indices.get(i)] |= Boolean.TRUE.equals(result.get(i));
        } finally { frames.forEach(Mat::release); }
    }
    private List<Mat> copies(List<Item> items, List<Integer> indices) {
        List<Mat> result = new ArrayList<>();
        try {
            for (int index : indices) result.add(items.get(index).frame.clone());
            return result;
        } catch (RuntimeException e) { result.forEach(Mat::release); throw e; }
    }
    public void requestStop() {
        closed = true;
        queue.close();
        worker.interrupt();
    }
    @Override public void close() {
        requestStop();
        try { worker.join(5000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
