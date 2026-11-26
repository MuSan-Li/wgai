package org.jeecg.modules.demo.config.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jeecg.modules.demo.config.entity.TabUpdateConfig;
import org.jeecg.modules.demo.config.updater.PlatformUpdater;
import org.jeecg.modules.demo.config.updater.PlatformUpdater.Progress;
import org.jeecg.modules.demo.config.mapper.TabUpdateConfigMapper;
import org.jeecg.modules.demo.config.service.ITabUpdateConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class TabUpdateConfigServiceImpl extends ServiceImpl<TabUpdateConfigMapper, TabUpdateConfig> implements ITabUpdateConfigService {
    @Autowired private DataSource dataSource;
    @Autowired(required = false) private org.springframework.cache.CacheManager cacheManager;
    @Value("${app-update.allowed-hosts:}") private String allowedHosts;
    @Value("${app-update.java:}") private String javaCommand;
    @Value("${app-update.stop-script:}") private String stopScript;
    @Value("${app-update.start-script:}") private String startScript;
    @Value("${app-update.health-url:}") private String healthUrl;
    @Value("${app-update.frontend-preserve-paths:static}") private String frontendPreservePaths = "static";
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "app-update-prepare"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService reconciler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"app-update-reconcile");t.setDaemon(true);return t;});
    private final java.util.concurrent.atomic.AtomicBoolean reconcilerStarted=new java.util.concurrent.atomic.AtomicBoolean();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TabUpdateConfigServiceImpl.class);
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reconcileAfterStartup(){
        if(!reconcilerStarted.compareAndSet(false,true))return;
        reconciler.scheduleWithFixedDelay(()->{try{
            refreshResults();
            for(TabUpdateConfig cfg:list(new QueryWrapper<TabUpdateConfig>().eq("update_status","SUCCESS"))){
                Path work=UpdateJobRecords.latestWork(cfg);
                if(work!=null&&Files.isDirectory(work))try{cleanup(String.valueOf(cfg.getId()));}catch(Exception ex){LOG.warn("[更新清理稍后重试] id={} reason={}",cfg.getId(),ex.toString());}
            }
        }catch(Exception ex){LOG.warn("[更新终态核对稍后重试] {}",ex.toString());}},2,15,TimeUnit.SECONDS);
    }
    @PreDestroy public void close() { executor.shutdown();reconciler.shutdownNow(); }
    private boolean yes(Integer v) { return Integer.valueOf(1).equals(v); }
    private TabUpdateConfig require(String id) {
        TabUpdateConfig c = getById(id); if (c == null) throw new IllegalArgumentException("更新配置不存在"); return c;
    }
    public synchronized void assertMutable(Collection<String> ids) {
        for (String id : ids) if ("RUNNING".equals(require(id).getUpdateStatus())) throw new IllegalArgumentException("更新中的配置不能修改或删除");
    }
    public Map<String, Object> preview(String id) throws Exception {
        TabUpdateConfig c = require(id); Map<String, String> execution = validate(c);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("config", c);
        result.put("execution", execution);result.put("retryAllowed",UpdateJobRecords.retryAllowed(c));
        result.put("updaterVersion",PlatformUpdater.VERSION);
        result.put("executionEnabled", true); result.put("message", "配置校验通过，执行时下载版本清单并验证SHA256；后端更新会停止并重新启动服务");
        return result;
    }
    private String configured(String databaseValue, String ymlValue) {
        if (databaseValue != null && !databaseValue.trim().isEmpty()) return databaseValue.trim();
        return ymlValue == null ? "" : ymlValue.trim();
    }
    public synchronized void prepareConfiguration(TabUpdateConfig c) throws Exception {
        validate(c,false,true);
    }
    public Map<String,Object> checkConfiguration(TabUpdateConfig c) throws Exception {
        Map<String,String> execution=validate(c,false,false);
        Map<String,Object> result=new LinkedHashMap<>();result.put("execution",execution);
        result.put("platform",UpdateControlScripts.windows()?"windows":"linux");
        List<String> created=new ArrayList<>(),existing=new ArrayList<>();
        if(yes(c.getBackendEnabled()))for(String key:Arrays.asList("stopScript","startScript")){
            Path path=Paths.get(execution.get(key));boolean present=Files.exists(path);
            UpdateControlScripts.ensure(path,UpdateFiles.absolute(c.getBackendLibDir(),"lib目录"),UpdateFiles.absolute(c.getLauncherJarPath(),"启动JAR"),key.equals("startScript"));
            (present?existing:created).add(path.toString());
        }
        result.put("createdScripts",created);result.put("existingScripts",existing);
        result.put("message",created.isEmpty()?"全部配置检查通过，现有脚本已保留":"全部配置检查通过，已在所填路径创建缺失的启停脚本");
        List<String> checks=new ArrayList<>(Arrays.asList("下载/备份目录及路径隔离","Java运行程序","发布清单可访问且模块有效"));
        if(yes(c.getFrontendEnabled()))checks.add("前端目录及index.html");
        if(yes(c.getBackendEnabled())){checks.add("lib与启动JAR");checks.add("启停脚本已就绪（已有文件保留，缺失文件已创建；未执行）");checks.add("健康接口免登录返回HTTP 200");}
        if(yes(c.getDbSyncEnabled()))checks.add("数据库来源与结构清单");result.put("checks",checks);
        return result;
    }
    private Map<String, String> validate(TabUpdateConfig c) throws Exception { return validate(c,true,true); }
    private void deployedDirectory(Path path,String label) throws IOException {
        if(!Files.isDirectory(path) || !Files.isReadable(path))throw new IOException(label+"不存在或不可读取，请填写服务器上已部署的真实目录: "+path);
        if(!Files.isWritable(path.getParent()))throw new IOException(label+"的上级目录不可写，无法切换更新文件: "+path.getParent());
    }
    private void writableDirectory(Path path,String label) throws IOException {
        Path existing=path;
        while(existing!=null && !Files.exists(existing))existing=existing.getParent();
        if(existing==null || !Files.isDirectory(existing) || !Files.isWritable(existing))
            throw new IOException(label+"或其最近的已存在上级目录不可写: "+path);
    }
    private Map<String, String> validate(TabUpdateConfig c,boolean requireEnabled,boolean createScripts) throws Exception {
        Map<String, String> execution = new LinkedHashMap<>();
        execution.put("stopScript", configured(c.getStopScript(), stopScript));
        execution.put("startScript", configured(c.getStartScript(), startScript));
        execution.put("healthUrl", configured(c.getHealthUrl(), healthUrl));
        execution.put("allowedHosts",configured(c.getAllowedHosts(),allowedHosts));
        execution.put("frontendPreservePaths",configured(c.getFrontendPreservePaths(),frontendPreservePaths));
        execution.put("javaExecutable",configured(c.getJavaExecutable(),javaCommand));
        if (requireEnabled && !yes(c.getEnabled())) throw new IOException("配置已停用");
        if (!yes(c.getFrontendEnabled()) && !yes(c.getBackendEnabled()) && !yes(c.getDbSyncEnabled())) throw new IOException("未选择更新范围");
        UpdateFiles.preservePaths(execution.get("frontendPreservePaths"));
        List<Path> dirs = new ArrayList<>();
        Path download=UpdateFiles.absolute(c.getDownloadDir(),"下载目录"),backup=UpdateFiles.absolute(c.getBackupDir(),"备份目录");
        dirs.add(download);dirs.add(backup);
        writableDirectory(download,"下载目录");writableDirectory(backup,"备份目录");
        if (yes(c.getFrontendEnabled())) {
            Path front=UpdateFiles.absolute(c.getFrontendDir(),"前端目录");dirs.add(front);deployedDirectory(front,"前端目录");
            if(!Files.isRegularFile(front.resolve("index.html")) || !Files.isReadable(front.resolve("index.html")))
                throw new IOException("前端目录中未找到可读的index.html，请填写已经部署的前端目录: "+front);
        }
        Path lib=null,launcher=null;
        if (yes(c.getBackendEnabled())) {
            if(execution.get("stopScript").isEmpty()||execution.get("startScript").isEmpty())throw new IOException("启用后端更新时，启动和停止脚本路径不能为空；请使用页面显示的默认地址或填写已有脚本地址");
            lib=UpdateFiles.absolute(c.getBackendLibDir(),"lib目录");dirs.add(lib);deployedDirectory(lib,"lib目录");
            try(java.util.stream.Stream<Path> files=Files.list(lib)) {
                if(!files.anyMatch(file->Files.isRegularFile(file) && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")))
                    throw new IOException("lib目录没有JAR文件，请填写正在使用的依赖目录: "+lib);
            }
            launcher=UpdateFiles.absolute(c.getLauncherJarPath(),"启动JAR");
            if (!Files.isRegularFile(launcher) || !Files.isReadable(launcher) || !launcher.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") || launcher.startsWith(lib))
                throw new IOException("启动JAR必须是已存在、可读取且位于lib目录之外的JAR文件: "+launcher);
            for (Path path : dirs) if (launcher.startsWith(path)) throw new IOException("启动JAR不能位于更新/暂存/备份目录内");
            Path stop=UpdateControlScripts.select(execution.get("stopScript"),lib,launcher,false);
            Path start=UpdateControlScripts.select(execution.get("startScript"),lib,launcher,true);
            if(stop.equals(start))throw new IOException("停止脚本与启动脚本不能使用同一个文件");
            execution.put("stopScript",UpdateFiles.absolute(stop.toString(),"停止脚本").toString());
            execution.put("startScript",UpdateFiles.absolute(start.toString(),"启动脚本").toString());
            for(Path script:Arrays.asList(stop,start)) {
                for(Path dir:dirs)if(script.startsWith(dir))throw new IOException("启停脚本不能位于部署、下载或备份目录内: "+script);
                if(!Files.exists(script))writableDirectory(script.getParent(),"脚本目录");
            }
            if(!Files.exists(stop) || !Files.exists(start) || UpdateControlScripts.generated(stop) || UpdateControlScripts.generated(start)) {
                if(!Files.isReadable(lib.resolve("opencv-4.8.1-0.jar")) || !Files.isRegularFile(lib.resolve("opencv-4.8.1-0.jar")))
                    throw new IOException("自动启停脚本需要lib目录中存在opencv-4.8.1-0.jar: "+lib);
                // Render before creating any directories, rejecting unsafe embedded path characters early.
                UpdateControlScripts.render(lib,launcher,true,UpdateControlScripts.windows());
                UpdateControlScripts.render(lib,launcher,false,UpdateControlScripts.windows());
            }
            if (execution.get("healthUrl").isEmpty())throw new IOException("请在页面填写健康检查地址，或配置app-update.health-url");
            java.net.URI health = new java.net.URI(execution.get("healthUrl"));
            if (!("http".equalsIgnoreCase(health.getScheme()) || "https".equalsIgnoreCase(health.getScheme())) || health.getHost() == null || health.getUserInfo() != null)
                throw new IOException("健康检查地址必须为当前后端服务的HTTP(S)绝对地址");
        }
        for (int i = 0; i < dirs.size(); i++) for (int j = i + 1; j < dirs.size(); j++) UpdateFiles.separate(dirs.get(i), dirs.get(j));
        org.jeecg.modules.demo.config.updater.EmbeddedUpdater.verifyAvailable();
        if (execution.get("allowedHosts").isEmpty()) throw new IOException("请在页面填写下载域名白名单，或配置app-update.allowed-hosts");
        if (c.getReleaseManifestUrl() == null || c.getReleaseManifestUrl().trim().isEmpty()) throw new IOException("请配置版本清单地址");
        UpdateJobRecords.root(c); // Audit records must also be isolated from deployment/backup paths.
        verifyJava(execution);
        verifyManifest(c,execution);
        if(yes(c.getBackendEnabled()))verifyHealth(execution.get("healthUrl"));
        if(yes(c.getDbSyncEnabled())){
            if(dataSource instanceof DynamicRoutingDataSource){
                if(c.getDbDatasourceKey()==null||!((DynamicRoutingDataSource)dataSource).getCurrentDataSources().containsKey(c.getDbDatasourceKey()))throw new IOException("数据库数据源不存在: "+c.getDbDatasourceKey());
            }else if(!"master".equals(c.getDbDatasourceKey()))throw new IOException("数据库数据源应填写master");
        }
        if(createScripts && yes(c.getBackendEnabled())) {
            Path stop=Paths.get(execution.get("stopScript")),start=Paths.get(execution.get("startScript"));
            try {
                UpdateControlScripts.ensure(stop,lib,launcher,false);UpdateControlScripts.ensure(start,lib,launcher,true);
            } catch(IOException ex) { throw new IOException("无法创建启停脚本，请检查脚本目录写入权限: "+ex.getMessage(),ex); }
            if(configured(c.getStopScript(),stopScript).isEmpty() || !UpdateFiles.absolute(configured(c.getStopScript(),stopScript),"停止脚本").equals(stop))c.setStopScript(stop.toString());
            if(configured(c.getStartScript(),startScript).isEmpty() || !UpdateFiles.absolute(configured(c.getStartScript(),startScript),"启动脚本").equals(start))c.setStartScript(start.toString());
        }
        return Collections.unmodifiableMap(execution);
    }
    protected void verifyHealth(String address)throws IOException {
        String reason=PlatformUpdater.healthFailure(address);if(reason!=null)throw new IOException("后端健康检查失败: "+reason);
    }
    private void verifyJava(Map<String,String> execution)throws Exception {
        String java=javaExecutable(execution);Path path=Paths.get(java);
        if(path.isAbsolute()){
            if(!Files.isRegularFile(path)||!Files.isExecutable(path))throw new IOException("Java可执行文件不存在或不可执行: "+path);
        }else if(!java.equals("java")&&!java.equals("java.exe"))throw new IOException("Java路径需为现有可执行文件的绝对路径，或留空沿用当前Java");
    }
    protected void verifyManifest(TabUpdateConfig c,Map<String,String> execution)throws Exception {
        Set<String> hosts=new HashSet<>();for(String host:execution.get("allowedHosts").split(","))hosts.add(host.trim().toLowerCase(Locale.ROOT));
        java.net.URL url=UpdateFiles.downloadUrl(c.getReleaseManifestUrl(),hosts);
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control","no-cache");connection.setRequestProperty("Accept-Encoding","identity");
        try{
            int status=connection.getResponseCode();if(status!=200)throw new IOException("发布清单返回HTTP "+status+": "+url.getPath());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()){byte[] block=new byte[16384];int n;while((n=in.read(block))!=-1){if(bytes.size()+n>8*1048576)throw new IOException("发布清单超过8MiB");bytes.write(block,0,n);}}
            JSONObject manifest;
            try{manifest=JSON.parseObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));}catch(RuntimeException ex){throw new IOException("发布清单不是有效JSON",ex);}
            if(manifest==null||manifest.getString("version")==null||manifest.getString("version").trim().isEmpty())throw new IOException("发布清单缺少版本号");
            if(!manifest.getString("version").equals(c.getCurrentVersion())&&(manifest.getJSONArray("fromVersions")==null||!manifest.getJSONArray("fromVersions").contains(c.getCurrentVersion())))
                throw new IOException("发布版本不允许从当前版本升级，请核对fromVersions");
            for(String kind:Arrays.asList("frontend","backend","database")){
                if(!(kind.equals("frontend")?yes(c.getFrontendEnabled()):kind.equals("backend")?yes(c.getBackendEnabled()):yes(c.getDbSyncEnabled())))continue;
                JSONObject spec=manifest.getJSONObject(kind);if(spec==null)throw new IOException("发布清单缺少已启用模块: "+kind);
                String configured=kind.equals("frontend")?c.getFrontendDownloadUrl():kind.equals("backend")?c.getBackendDownloadUrl():c.getDbSchemaUrl();
                if("files".equals(spec.getString("mode"))||spec.containsKey("files")){
                    if(kind.equals("database"))throw new IOException("数据库清单应为schema.sql文件地址");
                    UpdateFileManifest.validateSpec(kind,spec,configured,hosts,kind.equals("frontend")?UpdateFiles.preservePaths(execution.get("frontendPreservePaths")):Collections.emptyList());
                }else{
                    UpdateFiles.downloadUrl(selectUrl(configured,spec),hosts);
                    if(spec.getString("sha256")==null||!spec.getString("sha256").matches("(?i)[a-f0-9]{64}"))throw new IOException(kind+"清单缺少有效SHA256");
                }
            }
        }catch(java.net.SocketTimeoutException ex){throw new IOException("发布清单访问超时",ex);}finally{connection.disconnect();}
    }
    public synchronized Map<String,Object> syncUpdate(String id)throws Exception { return submitUpdate(id,false); }
    public synchronized Map<String,Object> retryUpdate(String id)throws Exception { return submitUpdate(id,true); }
    private Map<String, Object> submitUpdate(String id,boolean retry) throws Exception {

        refreshResults(); TabUpdateConfig cfg=require(id);Map<String,String> execution=new LinkedHashMap<>(validate(cfg));
        if(retry)execution.put("retrySource",UpdateJobRecords.retryWork(cfg).toString());
        // A database advisory lock serializes submissions across application instances.
        try (Connection lock = dataSource.getConnection(); Statement st = lock.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT GET_LOCK('wgai_application_update', 0)")) {
                if (!rs.next() || rs.getInt(1) != 1) throw new IOException("其他实例正在提交更新");
            }
            try {
                if (count(new QueryWrapper<TabUpdateConfig>().eq("update_status", "RUNNING")) > 0) throw new IOException("已有更新任务执行中，请等待完成");
                String token = cfg.getId() + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
                Path work = UpdateFiles.absolute(cfg.getDownloadDir(), "下载目录").resolve(token);
                Files.createDirectories(work);
                Progress queued=new Progress(work);queued.number("startedAt",System.currentTimeMillis());
                queued.update("QUEUED",0,"任务已提交，准备读取完整发布清单");
                update(new UpdateWrapper<TabUpdateConfig>().eq("id", cfg.getId()).set("update_status", "RUNNING").set("last_update_time",null).set("last_update_message", "准备更新；任务目录: " + work));
                try { executor.submit(() -> prepare(cfg, work, execution)); }
                catch (RuntimeException ex) { finish(cfg.getId(), "FAILED", "更新任务提交失败", null); throw ex; }
                Map<String, Object> result = new LinkedHashMap<>(); result.put("id", cfg.getId()); result.put("taskDirectory", work.toString()); result.put("status", "RUNNING"); return result;
            } finally { st.executeQuery("SELECT RELEASE_LOCK('wgai_application_update')").close(); }
        }
    }
    private void prepare(TabUpdateConfig cfg, Path work, Map<String, String> execution) {
        Progress progress=null;
        try {
            progress=new Progress(work);progress.update("MANIFEST",2,"读取完整版本清单");
            Set<String> hosts = new HashSet<>(); for (String h : execution.get("allowedHosts").split(",")) hosts.add(h.trim().toLowerCase(Locale.ROOT));
            Path mf = work.resolve("manifest.json"); UpdateFiles.download(cfg.getReleaseManifestUrl(), mf, 8 * 1048576L, hosts);
            JSONObject manifest = JSON.parseObject(new String(Files.readAllBytes(mf), StandardCharsets.UTF_8));
            String version = manifest.getString("version");
            if (version == null || version.trim().isEmpty() || version.length() > 64) throw new IOException("版本清单缺少有效version");
            // A fixed release label may publish new content. Compare files/schema even at the same version.
            // Keep the source-version compatibility gate when moving between different release labels.
            if (!version.equals(cfg.getCurrentVersion()) && (manifest.getJSONArray("fromVersions") == null
                    || !manifest.getJSONArray("fromVersions").contains(cfg.getCurrentVersion())))
                throw new IOException("目标版本不允许从当前版本升级");
            Path backup = UpdateFiles.absolute(cfg.getBackupDir(), "备份目录").resolve(work.getFileName());
            JSONObject plan = new JSONObject(); plan.put("work", work.toString()); plan.put("version", version); plan.put("backup", backup.toString());
            plan.putAll(execution);plan.put("startScriptManaged",yes(cfg.getBackendEnabled())&&UpdateControlScripts.generated(Paths.get(execution.get("startScript"))));plan.put("updaterVersion",PlatformUpdater.VERSION);plan.put("windowsForegroundStart",true); // Use the same database/YML settings validated at submission.
            plan.put("schemaChanged", false); plan.put("warnings", new ArrayList<String>());
            Map<String,UpdateFileManifest.Inventory> inventories=new LinkedHashMap<>();
            // Inspect ALL selected modules before the first payload download.
            if(yes(cfg.getFrontendEnabled()))inventories.put("frontend",inspect("frontend",cfg.getFrontendDownloadUrl(),cfg.getFrontendDir(),manifest,hosts,progress,execution,work,cfg));
            if(yes(cfg.getBackendEnabled()))inventories.put("backend",inspect("backend",cfg.getBackendDownloadUrl(),cfg.getBackendLibDir(),manifest,hosts,progress,execution,work,cfg));
            for(String key:Arrays.asList("totalFiles","downloadedFiles","reusedFiles","removedFiles","downloadBytes","addedFiles","modifiedFiles","cachedFiles","cachedBytes","compareCacheHits")){
                long sum=0;for(UpdateFileManifest.Inventory inventory:inventories.values())if(inventory!=null)sum+=inventory.stats.getLongValue(key);
                progress.number(key,sum);
            }
            progress.update("PLANNED",20,"比对完成，仅下载新增/变化文件；未变化内容从本地复用");
            List<JSONObject> deployments = new ArrayList<>();
            if (yes(cfg.getFrontendEnabled())) { JSONObject entry = packagePlan("frontend", cfg.getFrontendDownloadUrl(), cfg.getFrontendDir(), manifest, work, backup, hosts,inventories.get("frontend"),progress,execution); if (entry != null) deployments.add(entry); }
            if (yes(cfg.getBackendEnabled())) { JSONObject entry = packagePlan("backend", cfg.getBackendDownloadUrl(), cfg.getBackendLibDir(), manifest, work, backup, hosts,inventories.get("backend"),progress,execution); if (entry != null) deployments.add(entry); }
            progress.update("BACKUP",72,"本地备份（不产生网络下载）");
            for(JSONObject deployment:deployments)UpdateFiles.copyTree(Paths.get(deployment.getString("target")),backup.resolve(deployment.getString("kind")));
            plan.put("backupReady",true);
            if(yes(cfg.getBackendEnabled()))plan.put("maintenanceFile",Paths.get(cfg.getLauncherJarPath()).getParent().resolve(".platform-update-maintenance").toString());
            plan.put("deployments", deployments); plan.put("restart", deployments.stream().anyMatch(entry -> "backend".equals(entry.getString("kind"))));
            progress.update("DATABASE",76,"校验数据库结构及基础数据");
            UpdateSchema.Plan ddl = null; UpdateSeedData.Payload seed = null; DataSource ds = dataSource;
            if (yes(cfg.getDbSyncEnabled())) {
                JSONObject spec = manifest.getJSONObject("database"); if (spec == null) throw new IOException("版本清单缺少database");
                Path schema = work.resolve("schema.sql");
                UpdateFiles.download(selectUrl(cfg.getDbSchemaUrl(), spec), schema, 20 * 1024 * 1024L, hosts); UpdateFiles.verify(schema, spec.getString("sha256"));
                String key = cfg.getDbDatasourceKey();
                if (ds instanceof DynamicRoutingDataSource) {
                    DynamicRoutingDataSource dynamic = (DynamicRoutingDataSource) ds;
                    if (key == null || !dynamic.getCurrentDataSources().containsKey(key)) throw new IOException("本地数据源不存在: " + key);
                    ds = dynamic.getCurrentDataSources().get(key);
                } else if (key != null && !key.equals("master")) throw new IOException("当前未启用多数据源");
                try (Connection conn = ds.getConnection()) { ddl = UpdateSchema.plan(conn, new String(Files.readAllBytes(schema), StandardCharsets.UTF_8), cfg, backup); }
                plan.put("schemaChanged", !ddl.statements.isEmpty()); plan.put("warnings", ddl.warnings);
                JSONObject dataSpec = spec.getJSONObject("data");
                if (dataSpec != null) {
                    Path data = work.resolve("base-data.sql");
                    UpdateFiles.download(selectUrl(null, dataSpec), data, 20 * 1024 * 1024L, hosts);
                    UpdateFiles.verify(data, dataSpec.getString("sha256"));
                    JSONObject rules = null; JSONObject ruleSpec = dataSpec.getJSONObject("rules");
                    if (ruleSpec != null) {
                        Path ruleFile = work.resolve("data-rules.json");
                        UpdateFiles.download(selectUrl(null, ruleSpec), ruleFile, 1024 * 1024L, hosts);
                        UpdateFiles.verify(ruleFile, ruleSpec.getString("sha256"));
                        rules = JSON.parseObject(new String(Files.readAllBytes(ruleFile), StandardCharsets.UTF_8));
                        if (rules == null) throw new IOException("基础数据匹配规则不能为空");
                    }
                    seed = UpdateSeedData.parse(new String(Files.readAllBytes(data), StandardCharsets.UTF_8), rules);
                }
            }
            // Release bundled JDK-only classes outside lib before stopping this JVM.
            Path updaterJar = org.jeecg.modules.demo.config.updater.EmbeddedUpdater.extract(work);
            // Validate the extracted worker before making any live change.
            Process check = new ProcessBuilder(javaExecutable(execution), "-jar", updaterJar.toString(), "--check-v3").redirectErrorStream(true).redirectOutput(work.resolve("worker-check.log").toFile()).start();
            if (!check.waitFor(30, TimeUnit.SECONDS)) { check.destroyForcibly(); throw new IOException("独立更新程序自检超时"); }
            if (check.exitValue() != 0) throw new IOException("独立更新程序自检失败");
            if (ddl != null) try (Connection conn = ds.getConnection()) { UpdateSchema.execute(conn, ddl, backup); }
            if (seed != null) {
                JSONObject imported;
                try (Connection conn = ds.getConnection()) { imported = UpdateSeedData.execute(conn, seed, backup); }
                plan.put("dataChanged", imported.getIntValue("inserted") > 0);
                com.alibaba.fastjson.JSONArray warnings = plan.getJSONArray("warnings");
                warnings.add("基础数据新增" + imported.getIntValue("inserted") + "条，保留已有/已删除记录" + imported.getIntValue("skipped") + "条。");
                if (cacheManager != null && imported.getIntValue("inserted") > 0) {
                    try {
                        for (String cacheName : cacheManager.getCacheNames()) if (cacheName.toLowerCase(Locale.ROOT).contains("dict") || cacheName.toLowerCase(Locale.ROOT).contains("permission")) {
                            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName); if (cache != null) cache.clear();
                        }
                    } catch (Exception cacheFailure) { warnings.add("基础数据已提交，字典缓存清理失败，请重启服务或手动清理字典缓存。"); }
                }
            }
            Files.write(work.resolve("plan.json"), plan.toJSONString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            writeWorkerPlan(plan, work.resolve("plan.properties"));
            progress.update("HANDOFF",79,"文件已准备，交给独立更新器");
            new ProcessBuilder(javaExecutable(execution), "-jar", updaterJar.toString(), "--detach", work.resolve("plan.properties").toString())
                    .redirectErrorStream(true).redirectOutput(work.resolve("worker-launch.log").toFile()).start();
            // Poll only the receipt; backend execution continues independently when this JVM stops.
            for (int i = 0; i < 30; i++) {
                if (Files.exists(work.resolve("accepted"))) return;
                if (Files.exists(work.resolve("result.json"))) { refreshResults(); return; }
                Thread.sleep(1000);
            }
            // Do not mark FAILED: detached worker might still be alive. Keep RUNNING to prevent a second update.
            update(new UpdateWrapper<TabUpdateConfig>().eq("id", cfg.getId()).set("last_update_message", "更新程序未及时确认，请检查任务目录，禁止重复提交: " + work));
        } catch (Exception ex) {
            String message="准备更新失败（如已执行数据库变更，请核对数据库日志）: "+ex.getMessage();
            try{
                if(progress!=null)progress.update("FAILED",(int)progress.number("percent"),message);
                JSONObject failure=new JSONObject();failure.put("success",false);failure.put("filesSafe",true);failure.put("message",message);
                failure.put("finishedAt",System.currentTimeMillis());
                // Never overwrite a detached worker's receipt.
                if(!Files.exists(work.resolve("accepted")))Files.write(work.resolve("result.json"),failure.toJSONString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            }catch(Exception ignored){ }
            finish(cfg.getId(),"FAILED",message+"；任务目录: "+work,null);
            if(!Files.exists(work.resolve("accepted")))try{
                executor.submit(()->{try{cleanup(String.valueOf(cfg.getId()));}catch(Exception ignored){ }});
            }catch(RejectedExecutionException ignored){ }
        }
    }
    private String javaExecutable(Map<String,String> execution) {
        String configuredJava=execution.get("javaExecutable");
        if (configuredJava != null && !configuredJava.trim().isEmpty()) return configuredJava.trim();
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return Paths.get(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }
    private void writeWorkerPlan(JSONObject plan, Path destination) throws IOException {
        Properties properties = new Properties();
        for (String key : Arrays.asList("version", "restart", "healthUrl", "stopScript", "startScript", "schemaChanged", "dataChanged", "backupReady", "backup", "maintenanceFile", "windowsForegroundStart", "updaterVersion", "startScriptManaged"))
            properties.setProperty(key, Objects.toString(plan.get(key), ""));
        com.alibaba.fastjson.JSONArray entries = plan.getJSONArray("deployments");
        properties.setProperty("deployment.count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.getJSONObject(i); String prefix = "deployment." + i + ".";
            for (String key : Arrays.asList("kind", "target", "staged")) properties.setProperty(prefix + key, entry.getString(key));
            com.alibaba.fastjson.JSONArray preserve = entry.getJSONArray("preservePaths");
            properties.setProperty(prefix + "preserve.count", Integer.toString(preserve == null ? 0 : preserve.size()));
            if (preserve != null) for (int n = 0; n < preserve.size(); n++) properties.setProperty(prefix + "preserve." + n, preserve.getString(n));
            JSONObject transfer = entry.getJSONObject("transfer");
            if (transfer != null) for (String key : Arrays.asList("mode", "downloadedFiles", "reusedFiles", "removedFiles"))
                properties.setProperty(prefix + key, Objects.toString(transfer.get(key), "0"));
        }
        com.alibaba.fastjson.JSONArray warnings = plan.getJSONArray("warnings");
        properties.setProperty("warning.count", Integer.toString(warnings.size()));
        for (int i = 0; i < warnings.size(); i++) properties.setProperty("warning." + i, warnings.getString(i));
        try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "Standalone Java updater plan");
        }
    }
    private UpdateFileManifest.Inventory inspect(String kind,String configuredUrl,String target,JSONObject manifest,Set<String> hosts,Progress progress,Map<String,String> execution,Path work,TabUpdateConfig cfg)throws Exception {
        JSONObject spec=manifest.getJSONObject(kind);if(spec==null)throw new IOException("版本清单缺少"+kind);
        Path live=UpdateFiles.absolute(target,kind+"目录");if(!Files.isDirectory(live))throw new IOException("部署目录不存在: "+live);
        if(!"files".equals(spec.getString("mode")) && !spec.containsKey("files"))return null;
        Path previous=execution.containsKey("retrySource")?Paths.get(execution.get("retrySource")):null;
        UpdateFileManifest.Resume resume=new UpdateFileManifest.Resume(previous==null?null:previous.resolve(kind+"-compare.properties"),work.resolve(kind+"-compare.properties"),UpdateJobRecords.retryRoots(cfg,previous,kind,live));
        return UpdateFileManifest.inspect(kind,spec,configuredUrl,live,hosts,kind.equals("frontend")?UpdateFiles.preservePaths(execution.get("frontendPreservePaths")):Collections.emptyList(),progress,resume);
    }
    private JSONObject packagePlan(String kind, String configuredUrl, String target, JSONObject manifest, Path work, Path backup, Set<String> hosts,UpdateFileManifest.Inventory inventory,Progress progress,Map<String,String> execution) throws Exception {
        JSONObject spec = manifest.getJSONObject(kind); if (spec == null) throw new IOException("版本清单缺少" + kind);
        Path live = UpdateFiles.absolute(target, kind + "目录");
        if (!Files.isDirectory(live)) throw new IOException("部署目录不存在: " + live);
        List<String> preserve = kind.equals("frontend") ? UpdateFiles.preservePaths(execution.get("frontendPreservePaths")) : Collections.emptyList();
        Path staged = work.resolve(kind); JSONObject stats;
        if ("files".equals(spec.getString("mode")) || spec.containsKey("files")) {
            stats = UpdateFileManifest.stage(inventory, staged, work.resolve("download.log"), hosts, progress,false);
            Files.write(work.resolve(kind + "-transfer.json"), stats.toJSONString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            if (!stats.getBooleanValue("changed")) return null;
        } else {
            progress.update("TRANSFER",20,"兼容ZIP整包下载："+kind+"；使用散文件清单可只下载差异");
            Path zip = work.resolve(kind + ".zip"); UpdateFiles.download(selectUrl(configuredUrl, spec), zip, 2L * 1024 * 1024 * 1024, hosts); UpdateFiles.verify(zip, spec.getString("sha256"));
            UpdateFiles.unzip(zip, staged, 4L * 1024 * 1024 * 1024, kind.equals("backend"), preserve);
            if (kind.equals("frontend")) UpdateFiles.copyPreserved(live, staged, preserve);
            stats = new JSONObject(); stats.put("mode", "zip");
        }
        JSONObject entry = new JSONObject(); entry.put("kind", kind); entry.put("staged", staged.toString()); entry.put("target", live.toString()); entry.put("transfer", stats); entry.put("preservePaths", preserve); return entry;
    }
    private String selectUrl(String configured, JSONObject spec) throws IOException {
        String url = spec.getString("url");
        if (url == null || url.trim().isEmpty()) throw new IOException("版本清单缺少下载url");
        if (configured != null && !configured.trim().isEmpty() && !configured.trim().equals(url)) throw new IOException("配置下载地址与版本清单不一致");
        return url;
    }
    public synchronized void refreshResults() {
        for (TabUpdateConfig cfg : list(new QueryWrapper<TabUpdateConfig>().eq("update_status", "RUNNING"))) {
            try {
                Path root = UpdateFiles.absolute(cfg.getDownloadDir(), "下载目录");
                String message = cfg.getLastUpdateMessage(); int pos = message == null ? -1 : message.lastIndexOf(": ");
                if (pos < 0) continue;
                Path work = Paths.get(message.substring(pos + 2)).normalize();
                if (!work.startsWith(root) || !root.equals(work.getParent())) continue;
                Path receipt = work.resolve("result.json"); if (!Files.isRegularFile(receipt) || Files.size(receipt) > 1024 * 1024) continue;
                JSONObject r = JSON.parseObject(new String(Files.readAllBytes(receipt), StandardCharsets.UTF_8));
                boolean ok = r.getBooleanValue("success");
                long ended=r.getLongValue("finishedAt");if(ended<=0)ended=Files.getLastModifiedTime(receipt).toMillis();
                finish(cfg.getId(), ok ? "SUCCESS" : "FAILED", r.getString("message") + "；任务目录: " + work, ok ? r.getString("version") : null,new java.util.Date(ended));
                try{executor.submit(()->{
                    try{Map<String,Object> cleaned=cleanup(String.valueOf(cfg.getId()));
                        LOG.info("[更新清理完成] id={} {}",cfg.getId(),JSON.toJSONString(cleaned));
                    }catch(Exception ignored){ /* Update receipt is authoritative; manual cleanup remains available. */ }
                });}catch(RejectedExecutionException ignored){ }
            } catch (Exception ignored) { /* Keep RUNNING rather than permit overlapping updates on an unreadable receipt. */ }
        }
    }
    private void finish(Integer id, String status, String message, String version) {
        finish(id,status,message,version,new java.util.Date());
    }
    private void finish(Integer id, String status, String message, String version,java.util.Date ended) {
        UpdateWrapper<TabUpdateConfig> w = new UpdateWrapper<TabUpdateConfig>().eq("id", id).set("update_status", status).set("last_update_message", message).set("last_update_time", ended);
        if (version != null) w.set("current_version", version); update(w);
    }
    public Map<String,Object> defaults() {
        boolean win=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String base=win?"D:/Ailelon":"/home/javaai";
        Map<String,Object> result=new LinkedHashMap<>();
        String backend=win?base+"/jar":base;
        result.put("platform",win?"windows":"linux");result.put("backendLibDir",backend+"/lib");
        result.put("frontendDir",win?base+"/nginx-1.20.0/html":"/var/www");
        result.put("launcherJarPath",backend+"/wgai-system-start-3.4.3.jar");
        result.put("backupDir",base+"/backup");result.put("downloadDir",base+"/load");
        result.put("stopScript",configured("",stopScript).isEmpty()?backend+"/control/stop"+(win?".bat":".sh"):configured("",stopScript));
        result.put("startScript",configured("",startScript).isEmpty()?backend+"/control/start"+(win?".bat":".sh"):configured("",startScript));
        result.put("healthUrl","http://127.0.0.1:9998/wgai/tab/tabAiHistory/list");
        result.put("allowedHosts","zhy116.com");result.put("frontendPreservePaths","static");result.put("javaExecutable","");
        return result;
    }
    public Map<String,Object> progress(String id)throws Exception {
        refreshResults();TabUpdateConfig cfg=require(id);Map<String,Object> result=new LinkedHashMap<>();
        String message=cfg.getLastUpdateMessage();int pos=message==null?-1:message.lastIndexOf(": ");
        if(pos>=0){
            Path root=UpdateFiles.absolute(cfg.getDownloadDir(),"下载目录");
            Path work=UpdateFiles.absolute(message.substring(pos+2),"任务目录");
            if(root.equals(work.getParent())){
                Path folder=Files.isRegularFile(work.resolve("progress.properties"))?work:UpdateJobRecords.root(cfg).resolve(work.getFileName());
                Properties values=Progress.read(folder);
                for(String key:values.stringPropertyNames()){
                    String value=values.getProperty(key);
                    result.put(key,value.matches("[0-9]{1,18}")?Long.parseLong(value):value);
                }
            }
        }
        result.put("updaterVersion",PlatformUpdater.VERSION);result.put("id",cfg.getId());result.put("status",cfg.getUpdateStatus());result.put("lastUpdateMessage",cfg.getLastUpdateMessage());
        result.put("currentVersion",cfg.getCurrentVersion());result.put("retryAllowed",UpdateJobRecords.retryAllowed(cfg));
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        result.put("lastUpdateTime",cfg.getLastUpdateTime()==null?null:format.format(cfg.getLastUpdateTime()));
        if("SUCCESS".equals(cfg.getUpdateStatus())){result.put("percent",100);result.put("phase","SUCCESS");}
        if("FAILED".equals(cfg.getUpdateStatus()))result.put("phase","FAILED");
        return result;
    }
    public Map<String,Object> fileChanges(String id,int page,int size,String action)throws Exception {
        refreshResults();return UpdateJobRecords.changes(require(id),page,size,action);
    }
    public synchronized Map<String,Object> cleanup(String id)throws Exception {
        try(Connection lock=dataSource.getConnection();Statement st=lock.createStatement()){
            try(ResultSet rs=st.executeQuery("SELECT GET_LOCK('wgai_application_update', 0)")){
                if(!rs.next()||rs.getInt(1)!=1)throw new IOException("更新正在执行，暂不能清理");
            }
            try{
                if(count(new QueryWrapper<TabUpdateConfig>().eq("update_status","RUNNING"))>0)throw new IOException("更新中不能清理");
                return new LinkedHashMap<>(UpdateMaintenance.cleanup(require(id)));
            }finally{st.executeQuery("SELECT RELEASE_LOCK('wgai_application_update')").close();}
        }
    }

}