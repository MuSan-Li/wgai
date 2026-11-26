package org.jeecg.modules.demo.video;
import org.jeecg.modules.demo.video.util.identifyTypeNewOnnx;
import org.jeecg.modules.demo.video.util.identifyTypeNewOnnxTest;
import org.opencv.core.*;
import java.lang.reflect.*;
import java.io.*;
public class OnnxNativeMemoryCheck {
 static long memory()throws Exception{
  String pid=java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
  Process p=new ProcessBuilder("powershell.exe","-NoProfile","-Command","(Get-Process -Id "+pid+").PrivateMemorySize64").start();
  BufferedReader in=new BufferedReader(new InputStreamReader(p.getInputStream()));String value=in.readLine();if(p.waitFor()!=0)throw new AssertionError("memory probe");return Long.parseLong(value.trim());
 }
 static void run(Mat image,int n)throws Exception{
  for(int i=0;i<n;i++){
   Mat first=identifyTypeNewOnnx.letterboxResize(image,640,640);
   try{if(first.rows()!=640 || first.cols()!=640)throw new AssertionError("shape");}finally{first.release();}
   Mat second=identifyTypeNewOnnxTest.letterboxResize(image,640,640);second.release();
  }
 }
 public static void main(String[]args)throws Exception{
  nu.pattern.OpenCV.loadLocally();Mat image=new Mat(1080,1920,CvType.CV_8UC3,new Scalar(1,2,3));
  try{
   boolean rejected=false;try{identifyTypeNewOnnx.letterboxResize(image,0,640);}catch(IllegalArgumentException expected){rejected=true;}
   if(!rejected || image.empty())throw new AssertionError("invalid resize / caller ownership");
   run(image,100);long before=memory();run(image,1000);long growth=memory()-before;
   if(growth>192L*1024*1024)throw new AssertionError("Native memory growth: "+growth);
   for(Class<?> type:new Class<?>[]{identifyTypeNewOnnx.class,identifyTypeNewOnnxTest.class}){
    Object instance=type.newInstance();
    Class<?> detections=Class.forName(type.getName()+"$DetectionResult");Constructor<?> constructor=detections.getDeclaredConstructor();constructor.setAccessible(true);Object boxes=constructor.newInstance();
    Method add=detections.getDeclaredMethod("addDetection",double.class,double.class,double.class,double.class,float.class,int.class);add.setAccessible(true);add.invoke(boxes,0d,0d,100d,100d,0.9f,0);add.invoke(boxes,1d,1d,100d,100d,0.8f,0);
    for(String methodName:new String[]{"performNMS","performNMSROI"}) {
      Method nms=type.getDeclaredMethod(methodName,detections,float.class,float.class);nms.setAccessible(true);
      for(int i=0;i<100;i++)if(((int[])nms.invoke(instance,boxes,0.4f,0.5f)).length!=1)throw new AssertionError("NMS changed");
    }
    Method resize=type.getDeclaredMethod("smartResize",Mat.class,int.class);resize.setAccessible(true);
    for(int i=0;i<100;i++){Object result=resize.invoke(instance,image,640);Field field=result.getClass().getDeclaredField("resizedImage");field.setAccessible(true);((Mat)field.get(result)).release();}
    Method preprocess=type.getDeclaredMethod("preprocessImage",Mat.class);preprocess.setAccessible(true);Mat rgb=new Mat(640,640,CvType.CV_8UC3,new Scalar(10,20,30));
    try{float[] data=(float[])preprocess.invoke(instance,rgb);if(Math.abs(data[0]-10/255f)>0.00001 || Math.abs(data[640*640]-20/255f)>0.00001)throw new AssertionError("tensor changed");}finally{rgb.release();}
   }
   System.out.println("PASS: 2000 released letterbox results without GC, native private-memory growth="+growth+", smartResize ownership and preprocessing values");
  }finally{image.release();}
 }
}
