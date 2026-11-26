package org.jeecg.modules.tab.AIModel.onnx;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Java ONNX results -> annotated H.264 stream. Model session/device is inherited. */
@Slf4j
public final class JavaVideoPushWorker implements Runnable {
    private static final ConcurrentHashMap<String, JavaVideoPushWorker> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, JSONObject> STATUS = new ConcurrentHashMap<>();
    private final VideoReadOnnx detector;
    private final TabAiModelBund config;
    private final AtomicReference<Frame> pending = new AtomicReference<>();
    private volatile JSONObject result;
    private volatile long resultAt;
    private volatile boolean running = true;
    private volatile String inferenceError;
    private Thread inferenceThread;

    private JavaVideoPushWorker(VideoReadOnnx detector) {
        this.detector = detector;
        this.config = detector.tabAiModelBund;
    }

    public static boolean isRunning(String id) { return id != null && ACTIVE.containsKey(id); }

    public static boolean start(VideoReadOnnx detector) {
        JavaVideoPushWorker worker = new JavaVideoPushWorker(detector);
        if (ACTIVE.putIfAbsent(worker.config.getId(), worker) != null) return false;
        worker.update("starting", "正在连接推理视频源与 ZLM");
        Thread thread = new Thread(worker, "java-video-push-" + worker.config.getId());
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public static JSONObject status(String id) {
        JSONObject result = STATUS.get(id);
        if (result != null) return new JSONObject(result);
        JSONObject idle = new JSONObject();
        idle.put("state", "idle");
        idle.put("message", "尚未启动 Java 推流任务");
        return idle;
    }

    private void update(String state, String message) {
        JSONObject info = new JSONObject();
        info.put("state", state);
        info.put("message", message);
        info.put("updatedAt", System.currentTimeMillis());
        info.put("executionEngine", "java");
        info.put("inferenceFps", config.getInferenceFps());
        info.put("gpuDecode", config.getGpuDecode());
        STATUS.put(config.getId(), info);
    }

    private void infer() {
        long nextAt = 0;
        int interval = Math.max(1, (int) Math.ceil(1000.0 / config.getInferenceFps()));
        try {
            while (running && detector.isStreamActive()) {
                long now = System.currentTimeMillis();
                if (now < nextAt) { Thread.sleep(Math.min(20, nextAt - now)); continue; }
                Frame frame = pending.getAndSet(null);
                if (frame == null) { Thread.sleep(10); continue; }
                nextAt = now + interval;
                // processFrame owns/closes this frame and reuses the existing ONNX session.
                detector.processFrame(frame, now, frame.timestamp / 1000L, frame.timestamp / 1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            inferenceError = "推理失败：" + e.getMessage();
        }
    }

    @Override
    public void run() {
        detector.frameResultSink = payload -> { result = payload; resultAt = System.currentTimeMillis(); };
        detector.inferenceFailureSink = message -> inferenceError = message;
        inferenceThread = new Thread(this::infer, "java-video-infer-" + config.getId());
        inferenceThread.setDaemon(true);
        inferenceThread.start();
        String finalError = null;
        int failures = 0;
        try {
            while (running && detector.isStreamActive()) {
                FFmpegFrameGrabber grabber = null;
                FFmpegFrameRecorder recorder = null;
                try {
                    grabber = detector.createOptimizedGrabber();
                    int width = grabber.getImageWidth() / 2 * 2;
                    int height = grabber.getImageHeight() / 2 * 2;
                    if (width <= 0 || height <= 0) throw new IllegalStateException("视频尺寸无效");
                    double fps = grabber.getFrameRate();
                    if (!Double.isFinite(fps) || fps < 1 || fps > 60) fps = 25;
                    recorder = new FFmpegFrameRecorder(config.getZlmPushUrl(), width, height, 0);
                    boolean rtsp = config.getZlmPushUrl().toLowerCase().startsWith("rtsp://");
                    recorder.setFormat(rtsp ? "rtsp" : "flv");
                    if (rtsp) recorder.setOption("rtsp_transport", "tcp");
                    recorder.setOption("rw_timeout", "10000000");
                    recorder.setVideoCodecName("libx264");
                    recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
                    recorder.setFrameRate(fps);
                    recorder.setGopSize((int) Math.round(fps * 2));
                    recorder.setVideoOption("preset", "veryfast");
                    recorder.setVideoOption("tune", "zerolatency");
                    recorder.setVideoOption("bf", "0");
                    recorder.start();
                    update("running", "Java 推流中；推理设备沿用模型配置");
                    Java2DFrameConverter convert = new Java2DFrameConverter();
                    BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
                    long openedAt = System.currentTimeMillis(), lastTimestamp = -1;
                    int frames = 0;
                    while (running && detector.isStreamActive()) {
                        if (inferenceError != null) throw new IllegalStateException(inferenceError);
                        Frame frame = grabber.grabImage();
                        if (frame == null) throw new IllegalStateException("视频流中断或已结束");
                        try {
                            Frame discarded = pending.getAndSet(frame.clone());
                            if (discarded != null) discarded.close();
                            BufferedImage source = convert.getBufferedImage(frame);
                            if (source == null) continue;
                            JSONObject boxes = System.currentTimeMillis() - resultAt <= 1500 ? result : null;
                            drawFrame(output, source, boxes);
                            long timestamp = Math.max(lastTimestamp + 1, (System.currentTimeMillis() - openedAt) * 1000L);
                            recorder.setTimestamp(timestamp);
                            recorder.record(convert.getFrame(output));
                            lastTimestamp = timestamp;
                            if (++frames == 100) failures = 0;
                        } finally { frame.close(); }
                    }
                } catch (Exception e) {
                    if (!running || !detector.isStreamActive()) break;
                    finalError = e.getMessage();
                    log.error("[Java视频推流失败] id={}", config.getId(), e);
                    if (inferenceError != null || ++failures >= 5) {
                        update("error", finalError);
                        break;
                    }
                    update("retrying", "连接或推流异常，准备重连：" + finalError);
                } finally {
                    result = null;
                    resultAt = 0;
                    Frame discarded = pending.getAndSet(null);
                    if (discarded != null) discarded.close();
                    if (recorder != null) try { recorder.stop(); } catch (Exception ignored) { }
                    if (recorder != null) try { recorder.release(); } catch (Exception ignored) { }
                    if (grabber != null) try { grabber.stop(); } catch (Exception ignored) { }
                    if (grabber != null) try { grabber.release(); } catch (Exception ignored) { }
                }
                if (running && detector.isStreamActive()) Thread.sleep(3000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            RedisCacheHolder.put(config.getId() + "videoRead", false);
            inferenceThread.interrupt();
            // Keep the task reserved until ONNX releases the in-flight frame/session.
            try { inferenceThread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Frame discarded = pending.getAndSet(null);
            if (discarded != null) discarded.close();
            if (!"error".equals(status(config.getId()).getString("state"))) update("stopped", "Java 推流已停止");
            ACTIVE.remove(config.getId(), this);
        }
    }

    static void drawFrame(BufferedImage target, BufferedImage source, JSONObject payload) {
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, target.getWidth(), target.getHeight(), null);
            if (payload == null) return;
            JSONArray boxes = payload.getJSONArray("list");
            if (boxes == null) return;
            double sx = target.getWidth() / (double) source.getWidth();
            double sy = target.getHeight() / (double) source.getHeight();
            graphics.setColor(Color.GREEN);
            graphics.setStroke(new BasicStroke(2));
            for (int i = 0; i < boxes.size(); i++) {
                JSONObject box = boxes.getJSONObject(i);
                int x = (int) Math.round(box.getDoubleValue("x") * sx);
                int y = (int) Math.round(box.getDoubleValue("y") * sy);
                int width = (int) Math.round(box.getDoubleValue("width") * sx);
                int height = (int) Math.round(box.getDoubleValue("height") * sy);
                graphics.drawRect(x, y, width, height);
                String label = box.getString("className");
                if (label == null) label = box.getString("name");
                if (label != null) graphics.drawString(label + " " + box.getString("conf"), x, Math.max(16, y - 5));
            }
        } finally { graphics.dispose(); }
    }
}
