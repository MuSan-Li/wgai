package org.jeecg.modules.demo.config.updater;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Java 8 standalone update supervisor. No Python, JSON library, Maven, or application classes. */
public final class PlatformUpdater {
    public static final String VERSION="2026-10-28-explicit-control-v9";
    /** Shared atomic progress journal: business JVM prepares, detached worker continues. */
    public static final class Progress {
        private final Path work;
        private final Properties values;
        private long lastAttempt;
        private long lastWarning;
        public Progress(Path work) throws IOException {
            this.work=work;
            Properties loaded;
            try { loaded=read(work); }
            catch(IOException ex) { loaded=new Properties(); warn(ex); }
            this.values=loaded;
        }
        public static Properties read(Path work) throws IOException {
            Path file=work.resolve("progress.properties");
            for(int attempt=0;;attempt++) {
                try {
                    Properties p=new Properties();
                    if(Files.isRegularFile(file)) {
                        if(Files.size(file)>1048576)throw new IOException("Progress file too large");
                        // Close the Windows file handle before parsing, to shorten the replacement conflict window.
                        byte[] bytes=Files.readAllBytes(file);
                        if(bytes.length>1048576)throw new IOException("Progress file too large");
                        p.load(new ByteArrayInputStream(bytes));
                    }
                    return p;
                } catch(AccessDeniedException ex) {
                    if(attempt>=2)throw ex;
                    retryPause(attempt);
                }
            }
        }
        private static void retryPause(int attempt) throws InterruptedIOException {
            try { Thread.sleep(25L << attempt); }
            catch(InterruptedException ex) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted=new InterruptedIOException("Progress retry interrupted");
                interrupted.initCause(ex);throw interrupted;
            }
        }
        private void warn(IOException ex) {
            long now=System.currentTimeMillis();
            if(lastWarning==0 || now-lastWarning>=30000) {
                System.err.println("[Platform update progress unavailable] Update execution continues; will retry progress later. work="+work+"; "+ex);
                lastWarning=now;
            }
        }
        public synchronized void number(String key,long value){values.setProperty(key,Long.toString(value));}
        public synchronized long number(String key){return Long.parseLong(values.getProperty(key,"0"));}
        public synchronized void update(String phase,int percent,String detail)throws IOException {
            boolean changed=!phase.equals(values.getProperty("phase"));
            values.setProperty("phase",phase);values.setProperty("detail",detail);
            values.setProperty("percent",Integer.toString(Math.max((int)number("percent"),Math.min(100,percent))));
            long now=System.currentTimeMillis();
            if(!changed && now-lastAttempt<300 && percent!=100)return;
            values.setProperty("updatedAt",Long.toString(now));
            lastAttempt=now;
            Path temp=null;
            try {
                // Instances/processes must never truncate or move each other's in-flight temporary file.
                temp=Files.createTempFile(work,"progress-",".tmp");
                try(OutputStream out=Files.newOutputStream(temp)){values.store(out,"Platform update progress");}
                for(int attempt=0;;attempt++) {
                    try {
                        try{Files.move(temp,work.resolve("progress.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                        catch(AtomicMoveNotSupportedException ex){Files.move(temp,work.resolve("progress.properties"),StandardCopyOption.REPLACE_EXISTING);}
                        break;
                    } catch(AccessDeniedException ex) {
                        if(attempt>=3)throw ex;
                        retryPause(attempt);
                    }
                }
                lastWarning=0;
            } catch(InterruptedIOException ex) { throw ex; }
            catch(IOException ex) {
                // Telemetry is best-effort. Deployment validation and final receipts remain fail-fast.
                warn(ex);
            } finally {
                if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ex){warn(ex);}
            }
        }
    }

    /** Deletes only a checked descendant; never follows links or deletes the allowed root. */
    public static long deleteOwned(Path target,Path allowedRoot)throws IOException {
        target=absolute(target.toAbsolutePath().normalize().toString());
        allowedRoot=absolute(allowedRoot.toAbsolutePath().normalize().toString());
        if(target.equals(allowedRoot) || !target.startsWith(allowedRoot))throw new IOException("Cleanup outside allowed root: "+target);
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return 0;
        if(!target.toRealPath().startsWith(allowedRoot.toRealPath()))throw new IOException("Cleanup escaped real root: "+target);
        DeleteVisitor visitor=new DeleteVisitor(allowedRoot.toRealPath());
        Files.walkFileTree(target,visitor);return visitor.bytes;
    }
    private static final class DeleteVisitor extends SimpleFileVisitor<Path> {
        private final Path root;long bytes;
        DeleteVisitor(Path root){this.root=root;}
        private void check(Path path)throws IOException {
            if(Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root))throw new IOException("Linked cleanup entry: "+path);
        }
        public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes a)throws IOException{check(path);return FileVisitResult.CONTINUE;}
        public FileVisitResult visitFile(Path path,BasicFileAttributes a)throws IOException{check(path);if(!a.isRegularFile())throw new IOException("Special cleanup file");bytes+=a.size();Files.delete(path);return FileVisitResult.CONTINUE;}
        public FileVisitResult postVisitDirectory(Path path,IOException failure)throws IOException{if(failure!=null)throw failure;check(path);Files.delete(path);return FileVisitResult.CONTINUE;}
    }
    private static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    private static String required(Properties p, String key) throws IOException {
        String value=p.getProperty(key); if(value==null || value.trim().isEmpty())throw new IOException("Missing plan property: "+key); return value;
    }
    private static Path absolute(String value) throws IOException {
        Path path=Paths.get(value).normalize();if(!path.isAbsolute() || path.getParent()==null)throw new IOException("Invalid directory: "+value);
        for(Path part=path;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IOException("Links are not supported: "+part);
        return path;
    }
    private static void copy(Path source, Path target) throws IOException {
        if(!Files.isDirectory(source))throw new IOException("Missing directory: "+source);
        copy(source,target,Collections.emptyList(),false);
    }
    private static boolean preserved(String value,Collection<String> paths) {
        String name=value.replace('\\','/').toLowerCase(Locale.ROOT);
        for(String path:paths) if(name.equals(path) || name.startsWith(path+"/"))return true;
        return false;
    }
    private static void copy(Path source,Path target,Collection<String> preserve,boolean onlyPreserved)throws IOException {
        Files.walkFileTree(source,new CopyVisitor(source,target,preserve,onlyPreserved));
    }
    private static final class CopyVisitor extends SimpleFileVisitor<Path> {
        private final Path source,target;
        private final Collection<String> preserve;
        private final boolean onlyPreserved;
        CopyVisitor(Path source,Path target,Collection<String> preserve,boolean onlyPreserved) {
            this.source=source;this.target=target;this.preserve=preserve;this.onlyPreserved=onlyPreserved;
        }
            public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes a)throws IOException{
                if(Files.isSymbolicLink(path))throw new IOException("Linked directory: "+path);
                String name=source.relativize(path).toString();
                if(!onlyPreserved && preserved(name,preserve))return FileVisitResult.SKIP_SUBTREE;
                if(onlyPreserved && !name.isEmpty() && !preserved(name,preserve)){
                    String normalized=name.replace('\\','/').toLowerCase(Locale.ROOT);
                    boolean ancestor=false;for(String keep:preserve)if(keep.startsWith(normalized+"/"))ancestor=true;
                    if(!ancestor)return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(path)));return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path path,BasicFileAttributes a)throws IOException{
                if(!a.isRegularFile() || Files.isSymbolicLink(path))throw new IOException("Unsupported file: "+path);
                boolean keep=preserved(source.relativize(path).toString(),preserve);
                if(onlyPreserved != keep)return FileVisitResult.CONTINUE;
                Path dest=target.resolve(source.relativize(path));Files.createDirectories(dest.getParent());
                Files.copy(path,dest);return FileVisitResult.CONTINUE;
            }
    }
    public static String healthFailure(String address) {
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(address).openConnection();connection.setConnectTimeout(1000);connection.setReadTimeout(2000);connection.setInstanceFollowRedirects(false);
            int status=connection.getResponseCode();
            return status==200 ? null : address + " 返回 HTTP " + status + "，要求免登录返回 HTTP 200";
        }catch(Exception ex){return address + " 请求失败: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();}
        finally{if(connection!=null)connection.disconnect();}
    }
    private static boolean healthy(String address) { return healthFailure(address)==null; }
    private static void waitHealth(String address,boolean expected,int seconds)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        do{if(healthy(address)==expected)return;Thread.sleep(500);}while(System.nanoTime()<until);
        throw new IOException(expected ? "启动后健康检查超时，请查看Windows前台窗口或Linux启动日志，并核对健康地址" :
            "停止服务超时：健康地址仍返回HTTP 200。请检查停止脚本是否匹配到PID，以及保活/计划任务是否重新启动了服务");
    }
    private static void script(String value,Path log)throws Exception{
        Path path=absolute(value);if(!Files.isRegularFile(path))throw new IOException("Missing control script: "+path);
        List<String> command;
        if(windows()){
            if(value.toLowerCase(Locale.ROOT).endsWith(".ps1"))command=Arrays.asList("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-File",path.toString());
            else command=Arrays.asList("cmd.exe","/d","/c","call",path.toString());
        }else command=Arrays.asList("/bin/bash",path.toString());
        Files.write(log,("执行控制脚本: "+path+System.lineSeparator()).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        ProcessBuilder control=new ProcessBuilder(command).directory(path.getParent().toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        configureControlEnvironment(control);
        Process process=control.start();
        if(!process.waitFor(180,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Control script timed out: "+path);}
        if(process.exitValue()!=0)throw new IOException("Control script failed, exit="+process.exitValue()+": "+path);
    }
    /** Reset only this short-lived PowerShell helper, never the business JVM. */
    public static String windowsDllReset() {
        return "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false); Add-Type -TypeDefinition 'using System; using System.Text; using System.Runtime.InteropServices; public static class WgaiDllSearch { [DllImport(\"kernel32.dll\", EntryPoint=\"SetDllDirectoryW\", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool Reset(string path); [DllImport(\"kernel32.dll\", EntryPoint=\"GetDllDirectoryW\", CharSet=CharSet.Unicode)] public static extern uint Read(int size, StringBuilder value); }'; " +
            "$dllBefore=New-Object System.Text.StringBuilder 32768; $null=[WgaiDllSearch]::Read($dllBefore.Capacity,$dllBefore); Write-Output ('Inherited DLL search directory='+$dllBefore.ToString()); if(-not [WgaiDllSearch]::Reset($null)){throw ('SetDllDirectory reset failed: '+[Runtime.InteropServices.Marshal]::GetLastWin32Error())}; Write-Output 'DLL search directory reset to Windows default'; ";
    }
    private static String foregroundCommand(Path path) {
        String command=path.toString().toLowerCase(Locale.ROOT).endsWith(".ps1") ?
            "Start-Process -WindowStyle Normal -FilePath 'powershell.exe' -ArgumentList @('-NoExit','-File',"+ps(quoted(path.toString()))+")" :
            "Start-Process -WindowStyle Normal -FilePath 'cmd.exe' -ArgumentList @('/d','/k',"+ps("call "+quoted(path.toString()))+")";
        return windowsDllReset()+"$window="+command+" -WorkingDirectory "+ps(path.getParent().toString())+" -PassThru; Write-Output ('Foreground window PID='+$window.Id)";
    }
    /** The Java setting selects the updater, not the Java command inside a user's BAT. */
    private static void configureControlEnvironment(ProcessBuilder builder) {
        if(windows())builder.environment().remove("AILELON_JAVA"); // Also discard the v7 override inherited by a restarted server.
        else builder.environment().put("AILELON_JAVA",Paths.get(System.getProperty("java.home"),"bin","java").toString());
    }
    private static void startService(String value,Path log,boolean foreground)throws Exception { startService(value,log,foreground,null); }
    private static void startService(String value,Path log,boolean foreground,Path exitFile)throws Exception {
        if(!windows() || !foreground){script(value,log);return;}
        Path path=absolute(value);if(!Files.isRegularFile(path))throw new IOException("Missing start script: "+path);
        String encoded=Base64.getEncoder().encodeToString(foregroundCommand(path).getBytes(StandardCharsets.UTF_16LE));
        Files.write(log,("新开可见前台窗口执行启动脚本: "+path+System.lineSeparator()).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        ProcessBuilder builder=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-OutputFormat","Text","-EncodedCommand",encoded);
        configureControlEnvironment(builder);
        if(exitFile!=null)builder.environment().put("AILELON_START_EXIT_FILE",exitFile.toString());
        else builder.environment().remove("AILELON_START_EXIT_FILE");
        Process launcher=builder.directory(path.getParent().toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        if(!launcher.waitFor(30,TimeUnit.SECONDS)){launcher.destroyForcibly();throw new IOException("Foreground window launcher timed out");}
        if(launcher.exitValue()!=0)throw new IOException("Foreground window launcher failed; see worker.log");
        // The visible CMD keeps Java in the foreground; health checks confirm readiness independently.
    }
    public interface StartAttempt { void launch(Path exitFile)throws Exception; }
    /** A failed native initialization is not repaired by repeating the identical launch. */
    public static void startWithReadiness(Path work,String health,int seconds,Path log,boolean managed,StartAttempt starter)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        Path exit=work.resolve("startup-exit-"+UUID.randomUUID()+".txt");starter.launch(exit);
        while(System.nanoTime()<deadline){
            if(healthy(health))return;
            if(managed&&Files.isRegularFile(exit)&&Files.size(exit)>0){
                String code=new String(Files.readAllBytes(exit),StandardCharsets.US_ASCII).trim();
                if(!code.matches("-?[0-9]{1,12}"))throw new IOException("无效启动退出记录");
                throw new IOException("Java启动进程已退出，exit="+code+"；不重复打开失败窗口，请比较自动/手动启动上下文与原/目标依赖SHA256");
            }
            Thread.sleep(500);
        }
        throw new IOException("启动后健康检查超时；没有进程退出证据时不会重复拉起Java。"+healthFailure(health));
    }
    private static void startAndCheck(Properties p,Path work,String health,int seconds,Path log)throws Exception {
        boolean managed=windows()&&Boolean.parseBoolean(p.getProperty("startScriptManaged","false"));
        startWithReadiness(work,health,seconds,log,managed,exit->startService(required(p,"startScript"),log,Boolean.parseBoolean(p.getProperty("windowsForegroundStart","true")),managed?exit:null));
    }
    private static String json(String value){
        StringBuilder out=new StringBuilder("\"");for(char c:value.toCharArray()){
            if(c=='"' || c=='\\')out.append('\\').append(c);else if(c=='\n')out.append("\\n");else if(c=='\r')out.append("\\r");else if(c=='\t')out.append("\\t");else if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);
        }return out.append('"').toString();
    }
    private static void receipt(Path work,boolean success,String version,String message)throws IOException{
        String result="{\"success\":"+success+",\"version\":"+json(version)+",\"message\":"+json(message)+",\"finishedAt\":"+System.currentTimeMillis()+"}";
        Path temp=work.resolve("result.json.tmp");Files.write(temp,result.getBytes(StandardCharsets.UTF_8));
        try{Files.move(temp,work.resolve("result.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException ex){Files.move(temp,work.resolve("result.json"),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static final class Entry{
        Path target,staged,incoming,previous;String kind;List<String> preserve=new ArrayList<>();
    }
    public static void run(Path planFile)throws Exception{
        Properties p=new Properties();try(InputStream in=Files.newInputStream(planFile)){p.load(in);}
        Path work=planFile.toAbsolutePath().normalize().getParent();String version=required(p,"version");
        boolean restart=Boolean.parseBoolean(p.getProperty("restart","false")),attemptedStop=false;
        String health=p.getProperty("healthUrl","");int timeout=Integer.parseInt(p.getProperty("healthTimeoutSeconds","180"));
        List<Entry> prepared=new ArrayList<>(),switched=new ArrayList<>();boolean success=false;
        String message="";Path log=work.resolve("worker.log");Progress progress=new Progress(work);Path maintenance=null;
        try{
            Files.write(log,("Updater version="+VERSION+"; foreground="+p.getProperty("windowsForegroundStart","true")+System.lineSeparator()).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            Files.write(work.resolve("accepted"),Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII),StandardOpenOption.CREATE_NEW);
            progress.update("HEALTH_CHECK",80,"更新前健康检查");
            if(restart){String failure=healthFailure(health);if(failure!=null)throw new IOException("更新前健康检查失败: " + failure);}
            int count=Integer.parseInt(required(p,"deployment.count"));if(count<0 || count>2)throw new IOException("Invalid deployment count");
            for(int i=0;i<count;i++){
                String prefix="deployment."+i+".";Entry entry=new Entry();entry.kind=required(p,prefix+"kind");
                entry.target=absolute(required(p,prefix+"target"));entry.staged=absolute(required(p,prefix+"staged"));
                if(!entry.staged.startsWith(work) || entry.target.startsWith(work) || work.startsWith(entry.target))throw new IOException("Overlapping plan paths");
                entry.incoming=entry.target.resolveSibling(entry.target.getFileName()+".incoming-"+work.getFileName());
                entry.previous=entry.target.resolveSibling(entry.target.getFileName()+".previous-"+work.getFileName());
                if(!Files.isDirectory(entry.target) || Files.exists(entry.incoming) || Files.exists(entry.previous))throw new IOException("Missing live directory or existing update candidate");
                int preserveCount=Integer.parseInt(p.getProperty(prefix+"preserve.count",entry.kind.equals("frontend")?"1":"0"));
                if(preserveCount<0 || preserveCount>100)throw new IOException("Invalid preserve path count");
                for(int n=0;n<preserveCount;n++){
                    String path=(!p.containsKey(prefix+"preserve.count") && n==0?"static":required(p,prefix+"preserve."+n)).replace('\\','/').toLowerCase(Locale.ROOT);
                    if(!entry.kind.equals("frontend") || path.startsWith("/") || path.contains(":") || path.contains("..") || path.equals("index.html") || path.endsWith("/"))throw new IOException("Invalid preserve path");
                    entry.preserve.add(path);
                }
                progress.update("PREPARE_SWITCH",82,"准备切换："+entry.kind);
                boolean moved=false;
                if(Boolean.parseBoolean(p.getProperty("backupReady","false"))){
                    try{Files.move(entry.staged,entry.incoming,StandardCopyOption.ATOMIC_MOVE);moved=true;}
                    catch(AtomicMoveNotSupportedException crossVolume){ /* Copy only when a rename is not available. */ }
                }
                if(moved){
                    for(String preserved:entry.preserve)deleteOwned(entry.incoming.resolve(preserved),entry.incoming);
                    Files.write(log,("同盘移动已校验暂存，跳过重复全量复制: "+entry.kind+System.lineSeparator()).getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                }else copy(entry.staged,entry.incoming,entry.preserve,false);
                prepared.add(entry);
            }
            if(restart && !p.getProperty("maintenanceFile","").isEmpty()){
                Path marker=absolute(p.getProperty("maintenanceFile"));
                if(marker.startsWith(work))throw new IOException("Invalid maintenance marker");
                for(Entry entry:prepared)if(marker.startsWith(entry.target))throw new IOException("Maintenance marker inside deployment");
                Files.write(marker,work.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
                maintenance=marker;
            }
            if(restart){progress.update("STOPPING",86,"停止后端服务");attemptedStop=true;script(required(p,"stopScript"),log);waitHealth(health,false,Math.min(90,timeout));}
            progress.update("SWITCHING",90,"切换部署文件");
            for(Entry entry:prepared){if(!entry.preserve.isEmpty())copy(entry.target,entry.incoming,entry.preserve,true);Files.move(entry.target,entry.previous);switched.add(entry);Files.move(entry.incoming,entry.target);}
            if(restart){progress.update("STARTING",94,"启动后端并等待健康检查");startAndCheck(p,work,health,timeout,log);}
            success=true;message="同步更新成功；保留最近一次备份。";
            progress.update("CLEANUP",98,"清理本次暂存与重复旧目录");
            try {
                for(Entry entry:prepared) {
                    deleteOwned(entry.staged,work);
                    if(Boolean.parseBoolean(p.getProperty("backupReady","false"))) {
                        Path backup=absolute(required(p,"backup"));
                        if(Files.isDirectory(backup.resolve(entry.kind)))deleteOwned(entry.previous,entry.target.getParent());
                    }
                }
                for(String name:Arrays.asList("frontend.zip","backend.zip"))deleteOwned(work.resolve(name),work);
            }catch(IOException cleanup){message+=" 部分残留未清理："+cleanup.getMessage();}
            for(int i=0;i<count;i++){
                String prefix="deployment."+i+".";
                if("files".equals(p.getProperty(prefix+"mode")))message+=" "+p.getProperty(prefix+"kind")+": 下载"+p.getProperty(prefix+"downloadedFiles")+"个、复用"+p.getProperty(prefix+"reusedFiles")+"个、移除"+p.getProperty(prefix+"removedFiles")+"个。";
            }
            for(int i=0;i<Integer.parseInt(p.getProperty("warning.count","0"));i++)message+=" "+p.getProperty("warning."+i,"");
        }catch(Exception ex){
            message="同步更新失败: "+ex.getMessage();
            if(Boolean.parseBoolean(p.getProperty("schemaChanged","false")) || Boolean.parseBoolean(p.getProperty("dataChanged","false")))message+=" 数据库已变更，文件恢复不会回滚数据库。";
            try{
                if(restart && !switched.isEmpty()){script(required(p,"stopScript"),log);waitHealth(health,false,Math.min(90,timeout));}
                boolean filesWereSwitched=!switched.isEmpty();
                Collections.reverse(switched);
                for(Entry entry:switched){
                    if(Files.exists(entry.target))Files.move(entry.target,entry.target.resolveSibling(entry.target.getFileName()+".failed-"+work.getFileName()));
                    Files.move(entry.previous,entry.target);
                }
                if(restart && attemptedStop && !healthy(health)){startAndCheck(p,work,health,timeout,log);}
                message+=filesWereSwitched ? " 已恢复原文件，请核对数据库执行日志。" : " 尚未替换部署文件，原文件未改动。";
            }catch(Exception recovery){message+=" 恢复失败，需人工处理: "+recovery.getMessage();}
        }finally{
            if(maintenance!=null)try{if(new String(Files.readAllBytes(maintenance),StandardCharsets.UTF_8).equals(work.toString()))Files.delete(maintenance);}catch(IOException ignored){ }
            try{progress.update(success?"SUCCESS":"FAILED",success?100:(int)progress.number("percent"),message);}catch(IOException ignored){ }
            try{Files.write(log,(message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
            catch(IOException ignored){ }
            receipt(work,success,version,message);
        }
    }
    private static String ps(String value){return "'"+value.replace("'","''")+"'";}
    private static String quoted(String value){return "\""+value+"\"";}
    private static void detach(Path plan)throws Exception{
        String java=Paths.get(System.getProperty("java.home"),"bin",windows()?"java.exe":"java").toString();
        String jar=Paths.get(PlatformUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path work=plan.toAbsolutePath().getParent();ProcessBuilder builder;
        if(windows()){
            String command=windowsDllReset()+"Start-Process -WindowStyle Hidden -FilePath "+ps(java)+" -ArgumentList @('-jar',"+ps(quoted(jar))+",'--run',"+ps(quoted(plan.toAbsolutePath().toString()))+") -WorkingDirectory "+ps(work.toString())+" -RedirectStandardOutput "+ps(work.resolve("worker-stdout.log").toString())+" -RedirectStandardError "+ps(work.resolve("worker-stderr.log").toString());
            String encoded=Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
            builder=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-OutputFormat","Text","-EncodedCommand",encoded);
            Process process=builder.redirectErrorStream(true).inheritIO().start();
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Detached launcher timed out");}
            if(process.exitValue()!=0)throw new IOException("Detached launcher failed");
        }else{
            builder=new ProcessBuilder("nohup",java,"-jar",jar,"--run",plan.toAbsolutePath().toString());
            builder.directory(work.toFile()).redirectInput(new File("/dev/null")).redirectErrorStream(true).redirectOutput(work.resolve("worker-stdout.log").toFile()).start();
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length==1 && (args[0].equals("--check") || args[0].equals("--check-v2") || args[0].equals("--check-v3"))){System.out.println("Java platform updater ready; version="+VERSION+"; windows=visible-console");return;}
        if(args.length!=2)throw new IllegalArgumentException("Usage: java -jar platform-updater.jar --check | --detach PLAN | --run PLAN");
        Path plan=Paths.get(args[1]).toAbsolutePath().normalize();
        if(args[0].equals("--detach"))detach(plan);else if(args[0].equals("--run"))run(plan);else throw new IllegalArgumentException("Unknown command");
    }
}
