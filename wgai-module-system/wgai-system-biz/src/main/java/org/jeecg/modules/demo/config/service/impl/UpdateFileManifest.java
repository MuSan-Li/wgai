package org.jeecg.modules.demo.config.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.jeecg.modules.demo.config.updater.PlatformUpdater.Progress;

/** Complete inventories, bounded parallel verification, and resumable incremental transfer. */
public final class UpdateFileManifest {
    private UpdateFileManifest() { }
    private static final long LIMIT=4L*1024*1024*1024;
    private static final int HASH_WORKERS=2,DOWNLOAD_WORKERS=4;
    private static final class Item {
        String path,sha256,url,fingerprint,localSha256;long size;boolean reuse,existed,comparisonCached;Path cached;
    }
    public static final class Resume {
        final Path previous,output;final List<Path> roots;
        public Resume(Path previous,Path output,List<Path> roots){this.previous=previous;this.output=output;this.roots=roots;}
    }
    public static final class Inventory {
        public final String kind;public final Path live;public final Collection<String> preserve;public final JSONObject stats;
        private final Map<String,Item> items;private final List<JSONObject> removals;
        Inventory(String kind,Path live,Collection<String> preserve,JSONObject stats,Map<String,Item> items,List<JSONObject> removals){
            this.kind=kind;this.live=live;this.preserve=new ArrayList<>(preserve);this.stats=stats;this.items=items;this.removals=removals;
        }
    }
    public static String relative(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 500 || value.startsWith("/") || value.contains("\\") || value.contains(":")) throw new IOException("清单文件路径非法: " + value);
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?") || part.matches(".*[\\x00-\\x1f<>\"|?*].*")) throw new IOException("清单文件路径不支持: " + value);
        }
        return value;
    }
    public static String fileUrl(String base, String path) throws Exception {
        StringBuilder url = new StringBuilder(base.endsWith("/") ? base : base + "/");
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) { if (i > 0) url.append('/'); url.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20")); }
        return url.toString();
    }
    public static JSONObject stage(String kind,JSONObject spec,String configuredUrl,Path live,Path staged,Path journal,Set<String> hosts)throws Exception {
        return stage(kind,spec,configuredUrl,live,staged,journal,hosts,kind.equals("frontend")?Arrays.asList("static"):Collections.emptyList());
    }
    public static JSONObject stage(String kind,JSONObject spec,String configuredUrl,Path live,Path staged,Path journal,Set<String> hosts,Collection<String> preserve)throws Exception {
        return stage(inspect(kind,spec,configuredUrl,live,hosts,preserve,null),staged,journal,hosts,null);
    }
    /** Metadata-only checks shared by save validation and actual comparison; no local hashing or downloads. */
    public static void validateSpec(String kind,JSONObject spec,String configuredUrl,Set<String> hosts,Collection<String> preserve)throws Exception {
        inventory(kind,spec,configuredUrl,hosts,preserve);
    }
    private static Map<String,Item> inventory(String kind,JSONObject spec,String configuredUrl,Set<String> hosts,Collection<String> preserve)throws Exception {
        if(kind.equals("frontend")&&spec.getJSONArray("preservePaths")!=null)for(Object value:spec.getJSONArray("preservePaths")){
            String omitted=relative(String.valueOf(value));if(!UpdateFiles.preserved(omitted,preserve))throw new IOException("发布端已排除目录 "+omitted+"，本机保留范围必须包含它，以免清理本机文件");
        }
        if(!spec.getBooleanValue("complete"))throw new IOException("散文件清单必须标记complete=true，并列出该模块全部文件（网络只下载差异）");
        String base=spec.getString("baseUrl");if(base==null||!base.endsWith("/"))throw new IOException("散文件baseUrl必须为以/结尾的HTTP(S)目录地址");
        UpdateFiles.downloadUrl(base,hosts);
        if(configuredUrl!=null&&!configuredUrl.trim().isEmpty()&&!trimSlash(configuredUrl.trim()).equals(trimSlash(base)))throw new IOException("配置下载目录与清单baseUrl不一致，请修改目录地址或留空使用清单");
        JSONArray files=spec.getJSONArray("files");if(files==null||files.isEmpty()||files.size()>20000)throw new IOException("散文件清单数量必须为1至20000");
        Map<String,Item> inventory=new LinkedHashMap<>();long total=0;
        for(int i=0;i<files.size();i++){
            JSONObject entry=files.getJSONObject(i);Item item=new Item();item.path=relative(entry.getString("path"));
            if(kind.equals("frontend")&&UpdateFiles.preserved(item.path,preserve))continue;
            if(kind.equals("backend")&&!item.path.toLowerCase(Locale.ROOT).endsWith(".jar"))throw new IOException("后端散文件清单只能包含JAR: "+item.path);
            item.sha256=entry.getString("sha256");Long size=entry.getLong("size");
            if(item.sha256==null||!item.sha256.matches("(?i)[a-f0-9]{64}")||size==null||size<0||size>LIMIT||total>LIMIT-size)throw new IOException("清单SHA256或文件大小无效: "+item.path);
            item.size=size;total+=size;item.url=entry.getString("url");if(item.url==null)item.url=fileUrl(base,item.path);UpdateFiles.downloadUrl(item.url,hosts);
            if(inventory.put(item.path.toLowerCase(Locale.ROOT),item)!=null)throw new IOException("清单文件重名（含大小写冲突）: "+item.path);
        }
        for(Item item:inventory.values()){
            String parent=item.path;while(parent.contains("/")){parent=parent.substring(0,parent.lastIndexOf('/'));if(inventory.containsKey(parent.toLowerCase(Locale.ROOT)))throw new IOException("清单中文件与目录冲突: "+item.path);}
        }
        if(kind.equals("frontend")&&!inventory.containsKey("index.html"))throw new IOException("前端清单必须包含根目录index.html");
        return inventory;
    }
    public static Inventory inspect(String kind,JSONObject spec,String configuredUrl,Path live,Set<String> hosts,Collection<String> preserve,Progress progress)throws Exception {
        return inspect(kind,spec,configuredUrl,live,hosts,preserve,progress,null);
    }
    public static Inventory inspect(String kind,JSONObject spec,String configuredUrl,Path live,Set<String> hosts,Collection<String> preserve,Progress progress,Resume resume)throws Exception {
        Map<String,Item> inventory=inventory(kind,spec,configuredUrl,hosts,preserve);long total=0;
        for(Item item:inventory.values())total+=item.size;
        final Properties old=readCache(resume==null?null:resume.previous);
        final boolean matchingRoot=live.toString().equals(old.getProperty("root"));
        final List<Path> retryRoots=resume==null?Collections.emptyList():resume.roots;
        compare(inventory.values(),item->{
            Path existing=UpdateFiles.absolute(live.resolve(item.path).toString(),"本地文件");item.existed=Files.isRegularFile(existing);
            item.fingerprint=fingerprint(existing);String key=key(item.path);
            if(matchingRoot&&item.fingerprint!=null&&item.fingerprint.equals(old.getProperty(key+".fingerprint"))&&item.sha256.equalsIgnoreCase(old.getProperty(key+".sha256",""))){
                item.reuse=Boolean.parseBoolean(old.getProperty(key+".reuse"));item.localSha256=old.getProperty(key+".localSha256","");item.comparisonCached=true;
            }else {
                boolean sameSize=item.existed&&Files.size(existing)==item.size;
                if(sameSize||(item.existed&&item.path.toLowerCase(Locale.ROOT).startsWith("onnxruntime")))item.localSha256=UpdateFiles.sha256(existing);
                item.reuse=sameSize&&item.sha256.equalsIgnoreCase(item.localSha256);
            }
            if(!item.reuse)for(Path root:retryRoots){
                Path candidate=UpdateFiles.absolute(root.resolve(item.path).toString(),"重试缓存文件");
                if(Files.isRegularFile(candidate)&&Files.size(candidate)==item.size&&UpdateFiles.sha256(candidate).equalsIgnoreCase(item.sha256)){item.cached=candidate;break;}
            }
        },kind,progress);
        List<JSONObject> removals=new ArrayList<>();
        Files.walkFileTree(live,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attributes)throws IOException {
                if(Files.isSymbolicLink(dir))throw new IOException("本地目录不能包含符号链接");
                String name=live.relativize(dir).toString().replace('\\','/');
                return kind.equals("frontend")&&UpdateFiles.preserved(name,preserve)?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {
                if(!attributes.isRegularFile()||Files.isSymbolicLink(file))throw new IOException("本地目录包含不支持的特殊文件");
                String name=live.relativize(file).toString().replace('\\','/');if(kind.equals("frontend")&&UpdateFiles.preserved(name,preserve))return FileVisitResult.CONTINUE;
                Item desired=inventory.get(name.toLowerCase(Locale.ROOT));
                if(desired==null||!desired.path.equals(name)){JSONObject row=new JSONObject(true);row.put("kind",kind);row.put("path",name);row.put("action","REMOVE");row.put("source","NONE");row.put("size",attributes.size());row.put("state","PLANNED");removals.add(row);}return FileVisitResult.CONTINUE;
            }
        });
        // Cached comparisons are only an accelerator when a verified full snapshot will be staged.
        // A no-op decision is independently rehashed, so metadata alone never proves integrity.
        boolean wouldChange=!removals.isEmpty();for(Item item:inventory.values())wouldChange|=!item.reuse;
        if(!wouldChange){List<Item> confirm=new ArrayList<>();for(Item item:inventory.values())if(item.comparisonCached&&item.reuse)confirm.add(item);
            compare(confirm,item->{item.reuse=UpdateFiles.sha256(live.resolve(item.path)).equalsIgnoreCase(item.sha256);item.comparisonCached=false;},kind,progress);
        }
        int downloads=0,reused=0,cached=0,added=0,modified=0,hits=0;long networkBytes=0,cachedBytes=0;
        for(Item item:inventory.values()){
            if(item.comparisonCached)hits++;
            if(item.reuse)reused++;else{
                if(item.existed)modified++;else added++;
                if(item.cached!=null){cached++;cachedBytes+=item.size;}else{downloads++;networkBytes+=item.size;}
            }
        }
        JSONObject stats=new JSONObject(true);stats.put("kind",kind);stats.put("mode","files");stats.put("preservePaths",preserve);
        stats.put("totalFiles",inventory.size());stats.put("downloadedFiles",downloads);stats.put("reusedFiles",reused);stats.put("cachedFiles",cached);stats.put("cachedBytes",cachedBytes);
        stats.put("removedFiles",removals.size());stats.put("downloadBytes",networkBytes);stats.put("changed",downloads+cached>0||!removals.isEmpty());
        stats.put("addedFiles",added);stats.put("modifiedFiles",modified);stats.put("totalBytes",total);stats.put("compareCacheHits",hits);
        if(resume!=null&&resume.output!=null){Properties next=new Properties();next.setProperty("root",live.toString());
            for(Item item:inventory.values())if(item.fingerprint!=null){String key=key(item.path);next.setProperty(key+".fingerprint",item.fingerprint);next.setProperty(key+".sha256",item.sha256);next.setProperty(key+".reuse",Boolean.toString(item.reuse));next.setProperty(key+".localSha256",item.localSha256==null?"":item.localSha256);}
            try(OutputStream out=Files.newOutputStream(resume.output)){next.store(out,"Comparison metadata; staged bytes are always verified independently");}
        }
        return new Inventory(kind,live,preserve,stats,inventory,removals);
    }
    private interface Inspect {void run(Item item)throws Exception;}
    private static void compare(Collection<Item> items,Inspect inspect,String kind,Progress progress)throws Exception {
        if(items.isEmpty())return;ExecutorService pool=Executors.newFixedThreadPool(HASH_WORKERS);
        CompletionService<Item> done=new ExecutorCompletionService<>(pool);int scanned=0;
        try{
            for(Item item:items)done.submit(()->{inspect.run(item);return item;});
            for(int i=0;i<items.size();i++){Item item=done.take().get();
                if(progress!=null)progress.update("COMPARE",5+(++scanned*15/Math.max(1,items.size())),(kind.equals("backend")?"后端":"前端")+" 比对 "+scanned+"/"+items.size()+"："+item.path);
            }
        }catch(ExecutionException ex){throw cause(ex);}finally{pool.shutdownNow();pool.awaitTermination(60,TimeUnit.SECONDS);}
    }
    private static Exception cause(ExecutionException ex){Throwable t=ex.getCause();return t instanceof Exception?(Exception)t:new IOException(t);}
    private static Properties readCache(Path file)throws IOException {
        Properties p=new Properties();if(file!=null&&Files.isRegularFile(file)&&Files.size(file)<=16L*1024*1024)try(InputStream in=Files.newInputStream(file)){p.load(in);}return p;
    }
    private static String key(String path){return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));}
    private static String fingerprint(Path path)throws IOException {
        if(!Files.isRegularFile(path))return null;BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        return Objects.toString(a.fileKey(),"no-file-key")+"|"+a.size()+"|"+a.lastModifiedTime()+"|"+a.creationTime();
    }
    private static JSONObject row(Inventory plan,Item item,String state){
        JSONObject row=new JSONObject(true);row.put("kind",plan.kind);row.put("path",item.path);row.put("action",item.reuse?"REUSE":item.existed?"REPLACE":"ADD");
        row.put("source",item.reuse?"LOCAL":item.cached!=null?"CACHE":"NETWORK");row.put("size",item.size);row.put("sha256",item.sha256);row.put("state",state);row.put("previousSha256",item.localSha256);return row;
    }
    public static JSONObject stage(Inventory plan,Path staged,Path journal,Set<String> hosts,Progress progress)throws Exception {
        return stage(plan,staged,journal,hosts,progress,true);
    }
    public static JSONObject stage(Inventory plan,Path staged,Path journal,Set<String> hosts,Progress progress,boolean includePreserved)throws Exception {
        JSONObject stats=plan.stats;Path changes=journal.resolveSibling("file-changes.jsonl");
        try(BufferedWriter log=Files.newBufferedWriter(journal,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            BufferedWriter details=Files.newBufferedWriter(changes,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND)){
            log.write("SUMMARY "+stats.toJSONString());log.newLine();log.flush();
            for(Item item:plan.items.values()){details.write(row(plan,item,"PLANNED").toJSONString());details.newLine();}
            for(JSONObject removed:plan.removals){details.write(removed.toJSONString());details.newLine();}details.flush();
            if(stats.getBooleanValue("changed")){
                Files.createDirectory(staged);for(Item item:plan.items.values())Files.createDirectories(staged.resolve(item.path).getParent());
                ExecutorService pool=Executors.newFixedThreadPool(DOWNLOAD_WORKERS);List<Future<?>> futures=new ArrayList<>();
                AtomicLong received=new AtomicLong(progress==null?0:progress.number("doneBytes"));
                try{
                    for(Item item:plan.items.values())if(!item.reuse)futures.add(pool.submit(()->{
                        try{transfer(plan,item,staged,hosts,progress,received);complete(plan,item,log,details,progress);return null;}
                        catch(Exception ex){throw new CompletionException(ex);}
                    }));
                    for(Item item:plan.items.values())if(item.reuse){
                        if(progress!=null)progress.update("TRANSFER",20,"本地复用："+plan.kind+"/"+item.path);
                        UpdateFiles.copyVerified(plan.live.resolve(item.path),staged.resolve(item.path),item.size,item.sha256);
                        complete(plan,item,log,details,progress);
                    }
                    for(Future<?> task:futures)try{task.get();}catch(ExecutionException ex){Throwable t=ex.getCause();if(t instanceof CompletionException&&t.getCause() instanceof Exception)throw(Exception)t.getCause();throw cause(ex);}
                }finally{for(Future<?> task:futures)task.cancel(true);pool.shutdownNow();if(!pool.awaitTermination(60,TimeUnit.SECONDS))throw new IOException("下载连接尚未结束，请稍后重试");}
                if(plan.kind.equals("frontend")&&includePreserved)UpdateFiles.copyPreserved(plan.live,staged,plan.preserve);
                if(plan.kind.equals("frontend")&&!Files.isRegularFile(staged.resolve("index.html")))throw new IOException("前端根目录必须为index.html（注意大小写）");
            }else if(progress!=null)synchronized(progress){progress.number("doneFiles",progress.number("doneFiles")+plan.items.size());}
        }
        return stats;
    }
    private static void transfer(Inventory plan,Item item,Path staged,Set<String> hosts,Progress progress,AtomicLong received)throws Exception {
        if(progress!=null)progress.update("TRANSFER",20,(item.cached!=null?"重试缓存：":"并行下载：")+plan.kind+"/"+item.path);
        Path dest=staged.resolve(item.path);
        if(item.cached!=null){UpdateFiles.copyVerified(item.cached,dest,item.size,item.sha256);return;}
        final long[] last={0};
        UpdateFiles.downloadVerified(item.url,dest,item.size,item.sha256,hosts,total->{
            long value=received.addAndGet(total-last[0]);last[0]=total;
            if(progress!=null)synchronized(progress){progress.number("doneBytes",received.get());
                try{progress.update("TRANSFER",20+(int)(50*value/Math.max(1,progress.number("downloadBytes"))),"并行下载："+plan.kind+"/"+item.path);}catch(IOException ex){throw new UncheckedIOException(ex);}}
        });
    }
    private static void complete(Inventory plan,Item item,BufferedWriter log,BufferedWriter details,Progress progress)throws IOException {
        synchronized(log){log.write((item.reuse?"REUSE ":item.cached!=null?"CACHE ":"DOWNLOAD ")+item.path+" bytes="+item.size);log.newLine();log.flush();
            details.write(row(plan,item,"READY").toJSONString());details.newLine();details.flush();}
        if(progress!=null)synchronized(progress){progress.number("doneFiles",progress.number("doneFiles")+1);}
    }
    private static String trimSlash(String value){return value.endsWith("/")?value.substring(0,value.length()-1):value;}
}
