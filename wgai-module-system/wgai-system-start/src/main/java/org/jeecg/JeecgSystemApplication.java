package org.jeecg;

import ai.onnxruntime.OrtEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.jeecg.common.util.oConvertUtils;
import org.opencv.core.Core;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
* 单体启动类
* 报错提醒: 未集成mongo报错，可以打开启动类上面的注释 exclude={MongoAutoConfiguration.class}
*/
@Slf4j
@ComponentScan(value = {"org.jeecg"})
@SpringBootApplication
//@EnableAutoConfiguration(exclude={MongoAutoConfiguration.class})
public class JeecgSystemApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(JeecgSystemApplication.class);
    }



    /** Fail before Spring publishes ApplicationReadyEvent and starts subscription recovery. */
    private static void verifyOnnxNative() {
        log.info("[ONNX启动自检] java={} javaHome={} arch={} os={} {} javaSource={}",
                System.getProperty("java.version"), System.getProperty("java.home"), System.getProperty("os.arch"),
                System.getProperty("os.name"), System.getProperty("os.version"),
                OrtEnvironment.class.getProtectionDomain().getCodeSource().getLocation());
        try {
            java.util.Enumeration<java.net.URL> resources = JeecgSystemApplication.class.getClassLoader()
                    .getResources("ai/onnxruntime/OrtEnvironment.class");
            int count = 0;
            while (resources.hasMoreElements()) { count++; log.info("[ONNX类来源] {}", resources.nextElement()); }
            if (count > 1) log.warn("[ONNX依赖重复] 检测到{}份OrtEnvironment，请核对lib中CPU/GPU或不同版本的重复依赖", count);
        } catch (java.io.IOException ex) { log.warn("[ONNX类来源读取失败]", ex); }
        try {
            log.info("[ONNX支持的配置：]: {}", OrtEnvironment.getAvailableProviders());
        } catch (LinkageError ex) {
            log.error("[ONNX启动自检失败] 原生运行库无法初始化，停止启动，尚未启动订阅自动恢复。请使用同一Java运行Run-OnnxDiagnostics.bat，检查实际ORT版本、x64 VC运行库及DLL冲突；不能在当前JVM内通过重启任务恢复。", ex);
            throw ex;
        }
    }
    /** Resolve configured native library and test JNI before any subscription beans or recovery. */
    private static void initializeOpenCv(Environment env) {
        String paths = env.getProperty("opencv", "");
        log.info("[OpenCV Java来源] Mat={} Imgproc={}", Core.class.getProtectionDomain().getCodeSource().getLocation(),
                org.opencv.imgproc.Imgproc.class.getProtectionDomain().getCodeSource().getLocation());
        try {
            org.opencv.imgproc.Imgproc.class.getDeclaredMethod("cvtColor_1", long.class, long.class, int.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("OpenCV Java绑定不匹配4.8.1。请将loader.path改为先lib/opencv-4.8.1-0.jar再lib；保留JavaCPP所需依赖。", ex);
        }
        File selected = null;
        for (String candidate : paths.split(";")) {
            if (!candidate.trim().isEmpty() && new File(candidate.trim()).isFile()) { selected = new File(candidate.trim()); break; }
        }
        if (selected == null) throw new IllegalStateException("未找到配置的OpenCV 4.8.1原生库，请检查opencv配置: " + paths);
        System.load(selected.getAbsolutePath());
        org.opencv.core.Mat source = null, converted = null;
        try {
            String javaVersion;
            try { javaVersion = String.valueOf(Core.class.getField("VERSION").get(null)); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException("无法读取OpenCV Java版本", ex); }
            if (!"4.8.1".equals(javaVersion)) throw new IllegalStateException("OpenCV Java版本不是4.8.1: " + javaVersion);
            String nativeVersion = Core.getVersionString();
            if (!"4.8.1".equals(nativeVersion)) throw new IllegalStateException("配置的OpenCV原生库版本不是4.8.1: " + nativeVersion);
            source = new org.opencv.core.Mat(2, 2, org.opencv.core.CvType.CV_8UC3);
            converted = new org.opencv.core.Mat();
            org.opencv.imgproc.Imgproc.cvtColor(source, converted, org.opencv.imgproc.Imgproc.COLOR_BGR2RGB);
            Core.setNumThreads(env.getProperty("numThread", Integer.class, 1));
            Core.setUseOptimized(true);
            log.info("[OpenCV自检通过] nativeVersion={} library={} cvtColor=PASS", nativeVersion, selected.getAbsolutePath());
        } finally {
            if (converted != null) converted.release();
            if (source != null) source.release();
        }
    }

    public static void main(String[] args) throws UnknownHostException {
       // System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
// 在方法开始时添加，设置全局FFmpeg日志级别
//        System.setProperty("org.bytedeco.javacpp.logger.debug", "false");
//        System.setProperty("org.bytedeco.ffmpeg.logger", "quiet");
        // Load the supported ORT runtime before JavaCPP/FFmpeg can load another VC runtime.
        verifyOnnxNative();
        avutil.av_log_set_level(avutil.AV_LOG_QUIET);
       // System.load("C:\\JAVAAI\\opencv\\build\\java\\x64\\opencv_java481.dll");
        SpringApplication boot = new SpringApplication(JeecgSystemApplication.class);
        boot.addInitializers(context -> initializeOpenCv(context.getEnvironment()));
        ConfigurableApplicationContext application = boot.run(args);
        Environment env = application.getEnvironment();
        String ip = InetAddress.getLocalHost().getHostAddress();
        String port = env.getProperty("server.port");
        String path = oConvertUtils.getString(env.getProperty("server.servlet.context-path"));
        String opencvpath = env.getProperty("opencv");
        String audiopath = env.getProperty("audio.dll");

        log.info("\n----------------------------------------------------------\n\t" +
                "Application WGAI is running! Access URLs:\n\t" +
                "Local: \t\thttp://localhost:" + port + path + "/\n\t" +
                "External: \thttp://" + ip + ":" + port + path + "/\n\t" +
                "Swagger文档: \thttp://" + ip + ":" + port + path + "/doc.html\n\t" +
                "opencvpath:"+opencvpath+"\n\t  "+
                "audio:"+audiopath+"\n\t  "+
                "----------------------------------------------------------");
//        if(StringUtils.isNotEmpty(audiopath)){
//            String [] audiopathList=audiopath.split(";");
//            for (int i = 0; i <audiopathList.length ; i++) {
//                File opencv=new File(audiopathList[i]);
//                if(opencv.exists()){
//                    System.load(audiopathList[i]);
//                    log.info("[audio加载成功]:{}",audiopathList[i]);
//                    break;
//                }else{
//                    log.error("[audio文件不存在！请检查地址是否正确 或 是否编译audio]");
//                }
//            }
//        }
//        System.setProperty("onnxruntime.native." + System.getProperty("os.name"),
//                "C:\\Users\\Administrator\\AppData\\Local\\Temp\\onnxruntime-java152680534200004934\\onnxruntime.dll");


    }

}