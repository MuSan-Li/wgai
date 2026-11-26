package org.jeecg.modules.demo.config.service.impl;

import com.alibaba.fastjson.*;
import org.jeecg.modules.demo.config.entity.TabUpdateConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small audit records are separate from disposable downloads and the single rollback backup. */
public final class UpdateJobRecords {
    private UpdateJobRecords() { }
    public static Path root(TabUpdateConfig c)throws IOException {
        Path load=UpdateFiles.absolute(c.getDownloadDir(),"下载目录");
        Path records=UpdateFiles.absolute(load.resolveSibling(load.getFileName()+"-records").toString(),"更新记录目录");
        UpdateFiles.separate(records,load);UpdateFiles.separate(records,UpdateFiles.absolute(c.getBackupDir(),"备份目录"));
        for(String value:Arrays.asList(c.getFrontendDir(),c.getBackendLibDir()))if(value!=null&&!value.trim().isEmpty())
            UpdateFiles.separate(records,UpdateFiles.absolute(value,"部署目录"));
        if(c.getLauncherJarPath()!=null&&!c.getLauncherJarPath().trim().isEmpty()&&UpdateFiles.absolute(c.getLauncherJarPath(),"启动JAR").startsWith(records))
            throw new IOException("更新记录目录不能包含启动JAR");
        return records;
    }
    public static boolean named(TabUpdateConfig c,Path work){return work.getFileName().toString().matches(c.getId()+"-[0-9]{10,17}-[0-9a-fA-F-]{36}");}
    public static Path latestWork(TabUpdateConfig c)throws IOException {
        String message=c.getLastUpdateMessage();int p=message==null?-1:message.lastIndexOf(": ");
        if(p<0)return null;
        Path work=UpdateFiles.absolute(message.substring(p+2),"任务目录");
        Path load=UpdateFiles.absolute(c.getDownloadDir(),"下载目录");
        return load.equals(work.getParent())&&named(c,work)?work:null;
    }
    public static JSONObject json(Path file)throws IOException {
        if(!Files.isRegularFile(file)||Files.isSymbolicLink(file)||Files.size(file)>1048576)return null;
        return JSONObject.parseObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
    }
    public static boolean safe(JSONObject result){
        if(result==null)return false;String message=result.getString("message");
        if(message!=null&&message.contains("恢复失败"))return false;
        return result.getBooleanValue("success")||result.getBooleanValue("filesSafe")||(message!=null&&(message.contains("已恢复原文件")||message.contains("尚未替换部署文件")));
    }
    public static boolean retryAllowed(TabUpdateConfig c)throws IOException {
        Path work=latestWork(c);JSONObject result=work==null?null:json(work.resolve("result.json"));
        return "FAILED".equals(c.getUpdateStatus())&&work!=null&&Files.isDirectory(work)&&safe(result)&&!result.getBooleanValue("success");
    }
    public static Path retryWork(TabUpdateConfig c)throws IOException {
        if(!retryAllowed(c))throw new IOException("该任务没有安全可用的失败缓存；恢复失败需先人工处理，缓存已清理则请重新同步");
        return latestWork(c);
    }
    public static Path readable(TabUpdateConfig c,Path work)throws IOException {
        if(work==null)return null;
        return Files.isDirectory(work)?work:root(c).resolve(work.getFileName());
    }
    public static List<Path> retryRoots(TabUpdateConfig c,Path old,String kind,Path target)throws IOException {
        if(old==null)return Collections.emptyList();
        List<Path> roots=new ArrayList<>();roots.add(old.resolve(kind));
        JSONObject plan=json(old.resolve("plan.json"));
        if(plan!=null&&old.equals(UpdateFiles.absolute(plan.getString("work"),"旧计划目录"))){
            JSONArray entries=plan.getJSONArray("deployments");
            if(entries!=null)for(int i=0;i<entries.size();i++){
                JSONObject entry=entries.getJSONObject(i);
                if(kind.equals(entry.getString("kind"))&&target.equals(UpdateFiles.absolute(entry.getString("target"),"旧部署目录"))){
                    roots.add(target.resolveSibling(target.getFileName()+".failed-"+old.getFileName()));
                    roots.add(target.resolveSibling(target.getFileName()+".incoming-"+old.getFileName()));
                }
            }
        }
        return roots;
    }
    public static void archive(TabUpdateConfig c,Path work,Path backup)throws IOException {
        Path records=root(c),dest=records.resolve(work.getFileName());Files.createDirectories(dest);
        for(String name:Arrays.asList("result.json","plan.json","manifest.json","progress.properties","file-changes.jsonl","download.log","worker.log","worker-launch.log","worker-check.log","worker-stdout.log","frontend-compare.properties","backend-compare.properties"))
            copy(work.resolve(name),dest.resolve(name));
        try(DirectoryStream<Path> contexts=Files.newDirectoryStream(work,"startup-exit-*.txt.context.log")){
            for(Path file:contexts)copy(file,dest.resolve(file.getFileName()));
        }
        if(Files.isDirectory(backup))try(DirectoryStream<Path> files=Files.newDirectoryStream(backup)){
            for(Path file:files){String name=file.getFileName().toString();
                if(name.endsWith(".log")||name.endsWith(".sql")||name.endsWith(".json"))copy(file,dest.resolve("database-"+name));
            }
        }
    }
    private static void copy(Path source,Path dest)throws IOException {
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.size(source)>64L*1024*1024)throw new IOException("更新审计文件超过64MiB，保留原任务以免丢失记录: "+source);
        UpdateFiles.absolute(dest.toString(),"审计文件");Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING);
    }
    public static Map<String,Object> changes(TabUpdateConfig c,int page,int size,String action)throws Exception {
        page=Math.max(1,page);size=Math.max(1,Math.min(100,size));
        Path work=latestWork(c),folder=readable(c,work);Map<String,JSONObject> latest=new LinkedHashMap<>();
        JSONObject receipt=folder==null?null:json(folder.resolve("result.json"));
        if(receipt==null&&work!=null)receipt=json(root(c).resolve(work.getFileName()).resolve("result.json"));
        if(folder!=null&&!Files.isRegularFile(folder.resolve("file-changes.jsonl"))&&work!=null)folder=root(c).resolve(work.getFileName());
        if(folder!=null&&Files.isRegularFile(folder.resolve("file-changes.jsonl"))){
            Path file=UpdateFiles.absolute(folder.resolve("file-changes.jsonl").toString(),"文件明细");
            if(Files.size(file)>64L*1024*1024)throw new IOException("文件明细超过读取上限");
            try(BufferedReader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
                String line;while((line=in.readLine())!=null){try{
                    JSONObject row=JSONObject.parseObject(line);latest.put(row.getString("kind")+"/"+row.getString("path"),row);
                }catch(RuntimeException incomplete){ /* Writer may be appending the last line. */ }}
            }
        }
        List<JSONObject> rows=new ArrayList<>();
        for(JSONObject row:latest.values()){
            if("CHANGED".equals(action)){if("REUSE".equals(row.getString("action")))continue;}else if(action!=null&&!action.isEmpty()&&!action.equals(row.getString("action")))continue;
            if(receipt!=null){
                if(receipt.getBooleanValue("success"))row.put("state","REUSE".equals(row.getString("action"))?"UNCHANGED":"APPLIED");
                else row.put("state",safe(receipt)?"NOT_APPLIED":"CHECK_REQUIRED");
            }
            rows.add(row);
        }
        int from=(int)Math.min(rows.size(),(long)(page-1)*size),to=Math.min(rows.size(),from+size);
        Map<String,Object> result=new LinkedHashMap<>();result.put("records",rows.subList(from,to));result.put("total",rows.size());
        result.put("current",page);result.put("size",size);result.put("retryAllowed",retryAllowed(c));return result;
    }
}
