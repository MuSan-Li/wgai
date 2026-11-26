package org.jeecg.modules.demo.config.service.impl;

import com.alibaba.fastjson.*;
import org.jeecg.modules.demo.config.entity.TabUpdateConfig;
import org.jeecg.modules.demo.config.updater.PlatformUpdater;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Success downloads are disposable; only one complete rollback backup and small audit records remain. */
public final class UpdateMaintenance {
    private UpdateMaintenance() { }
    private static long erase(Path path,Path root)throws IOException{return PlatformUpdater.deleteOwned(path,root);}
    public static JSONObject cleanup(TabUpdateConfig cfg)throws Exception {
        Path load=UpdateFiles.absolute(cfg.getDownloadDir(),"下载目录"),backup=UpdateFiles.absolute(cfg.getBackupDir(),"备份目录"),records=UpdateJobRecords.root(cfg);
        UpdateFiles.separate(load,backup);
        Map<String,Path> targets=new HashMap<>();
        if(cfg.getFrontendDir()!=null&&!cfg.getFrontendDir().trim().isEmpty())targets.put("frontend",UpdateFiles.absolute(cfg.getFrontendDir(),"前端目录"));
        if(cfg.getBackendLibDir()!=null&&!cfg.getBackendLibDir().trim().isEmpty())targets.put("backend",UpdateFiles.absolute(cfg.getBackendLibDir(),"lib目录"));
        for(Path target:targets.values()){UpdateFiles.separate(load,target);UpdateFiles.separate(backup,target);}
        Set<String> tokens=new HashSet<>();
        for(Path root:Arrays.asList(load,records))if(Files.isDirectory(root))try(DirectoryStream<Path> stream=Files.newDirectoryStream(root)){
            for(Path job:stream)if(UpdateJobRecords.named(cfg,job)&&Files.isDirectory(job,LinkOption.NOFOLLOW_LINKS)){
                if(tokens.size()>=10000)throw new IOException("更新任务过多，请先整理目录");
                if(UpdateJobRecords.json(job.resolve("result.json"))!=null)tokens.add(job.getFileName().toString());
            }
        }
        List<String> sorted=new ArrayList<>(tokens);sorted.sort(Comparator.comparingLong((String token)->Long.parseLong(token.split("-")[1])).reversed());
        String retained=null;Path latest=UpdateJobRecords.latestWork(cfg);String latestToken=latest==null?"":latest.getFileName().toString();
        for(String token:sorted){
            Path job=load.resolve(token),audit=records.resolve(token);JSONObject result=UpdateJobRecords.json(job.resolve("result.json"));if(result==null)result=UpdateJobRecords.json(audit.resolve("result.json"));
            Path archive=backup.resolve(token);
            if(result!=null&&result.getBooleanValue("success")&&(Files.isDirectory(archive.resolve("frontend"))||Files.isDirectory(archive.resolve("backend")))){retained=token;break;}
        }
        long freed=0;int pruned=0,skipped=0,retainedRetries=0,auditCount=0;List<String> warnings=new ArrayList<>();
        for(String token:sorted){
            Path job=load.resolve(token),audit=records.resolve(token),archive=backup.resolve(token);
            JSONObject result=UpdateJobRecords.json(job.resolve("result.json"));if(result==null)result=UpdateJobRecords.json(audit.resolve("result.json"));
            if(!UpdateJobRecords.safe(result)){skipped++;continue;}
            boolean success=result.getBooleanValue("success"),retry=!success&&"FAILED".equals(cfg.getUpdateStatus())&&token.equals(latestToken);
            try {
                if(Files.isDirectory(job))UpdateJobRecords.archive(cfg,job,archive);
                JSONObject plan=UpdateJobRecords.json(job.resolve("plan.json"));if(plan==null)plan=UpdateJobRecords.json(audit.resolve("plan.json"));
                if(plan!=null){
                    if(!job.equals(UpdateFiles.absolute(plan.getString("work"),"计划任务目录")))throw new IOException("计划任务目录不匹配");
                    if(!archive.equals(UpdateFiles.absolute(plan.getString("backup"),"计划备份目录")))throw new IOException("计划备份目录不匹配");
                    JSONArray entries=plan.getJSONArray("deployments");
                    if(entries!=null)for(int i=0;i<entries.size();i++){
                        JSONObject entry=entries.getJSONObject(i);Path target=targets.get(entry.getString("kind"));
                        if(target==null||!target.equals(UpdateFiles.absolute(entry.getString("target"),"计划部署目录")))continue;
                        if(!success||Files.isDirectory(archive.resolve(entry.getString("kind"))))freed+=erase(target.resolveSibling(target.getFileName()+".previous-"+token),target.getParent());
                        if(!retry){
                            freed+=erase(target.resolveSibling(target.getFileName()+".incoming-"+token),target.getParent());
                            freed+=erase(target.resolveSibling(target.getFileName()+".failed-"+token),target.getParent());
                        }
                    }
                }
                if(!token.equals(retained)&&Files.exists(archive)){freed+=erase(archive,backup);pruned++;}
                if(retry){retainedRetries++;}else if(Files.exists(job)){freed+=erase(job,load);}
                if(++auditCount>10&&!token.equals(retained)&&!retry)freed+=erase(audit,records);
            }catch(Exception ex){warnings.add(token+": "+ex.getMessage());}
        }
        // Old versions left empty named backup shells. Remove only verified empty folders, never unknown data.
        if(Files.isDirectory(backup))try(DirectoryStream<Path> stream=Files.newDirectoryStream(backup)){
            for(Path dir:stream)if(UpdateJobRecords.named(cfg,dir)&&Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)&&!dir.getFileName().toString().equals(retained)){
                try(DirectoryStream<Path> contents=Files.newDirectoryStream(dir)){if(contents.iterator().hasNext())continue;}
                try{Files.delete(dir);}catch(IOException ex){warnings.add(dir.getFileName()+": "+ex.getMessage());}
            }
        }
        JSONObject result=new JSONObject(true);result.put("freedBytes",freed);result.put("prunedBackups",pruned);result.put("retainedSuccessBackups",retained==null?0:1);
        result.put("retainedRetryJobs",retainedRetries);result.put("skippedUnsafeJobs",skipped);result.put("recordsDirectory",records.toString());result.put("warnings",warnings);return result;
    }
}
