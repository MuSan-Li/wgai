package org.jeecg.modules.ros2.service;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import org.jeecg.modules.ros2.config.ROS2WebSocketHandler;
import java.nio.*;
import java.util.Base64;
import java.lang.reflect.Method;
public class PointCloudPreviewCheck {
    static class Push extends WebSocketPushService {
        JSONObject last;
        @Override public boolean isCloudPushWanted(){return true;}
        @Override public void pushToAll(String type,Object data){if(data instanceof JSONObject && ((JSONObject)data).containsKey("pushCount"))last=(JSONObject)data;}
    }
    public static void main(String[] args)throws Exception {
        ByteBuffer points=ByteBuffer.allocate(120000).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<10000;i++)points.putFloat(i*.001f).putFloat(1).putFloat(i%2==0?20:1);
        JsonObject msg=new JsonObject();msg.addProperty("width",10000);msg.addProperty("point_step",12);msg.addProperty("data",Base64.getEncoder().encodeToString(points.array()));
        Method handle=ROS2WebSocketHandler.class.getDeclaredMethod("handlePointCloud",JsonObject.class);handle.setAccessible(true);
        Push normal=new Push();ROS2WebSocketHandler h=new ROS2WebSocketHandler(null,normal,null,null,null,null,null,null,null,null);handle.invoke(h,msg);
        if(normal.last==null || normal.last.getIntValue("pushCount")>=10000)throw new AssertionError("Normal mode must retain its budget and height filter");
        Push full=new Push();h=new ROS2WebSocketHandler(null,full,null,null,null,null,null,null,null,null);h.setNavMode(false); h.setFullResolutionPreview(true);handle.invoke(h,msg);
        if(full.last==null || full.last.getIntValue("pushCount")!=10000 || !full.last.getBooleanValue("fullResolution"))throw new AssertionError("Diagnostic preview must preserve every finite point, including z=20");
        Push nav=new Push();h=new ROS2WebSocketHandler(null,nav,null,null,null,null,null,null,null,null);h.setFullResolutionPreview(true);handle.invoke(h,msg);
        if(nav.last==null || nav.last.getBooleanValue("fullResolution") || nav.last.getIntValue("pushCount")>=10000)throw new AssertionError("Navigation must cap diagnostic preview traffic");
        System.out.println("PASS: normal preview remains filtered; diagnostic preview publishes all 10000 points including outside normal Z range");
    }
}
