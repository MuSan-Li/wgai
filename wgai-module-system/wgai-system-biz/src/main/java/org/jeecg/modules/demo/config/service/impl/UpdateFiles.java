package org.jeecg.modules.demo.config.service.impl;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Downloads and extraction are bounded and never target a live deployment. */
public final class UpdateFiles {
    private UpdateFiles() { }
    public static Path absolute(String value, String label) throws IOException {
        if (value == null || value.trim().isEmpty()) throw new IOException(label + "未配置");
        Path p = Paths.get(value.trim()).normalize();
        if (!p.isAbsolute() || p.getParent() == null) throw new IOException(label + "必须为非根目录的绝对路径");
        p = p.normalize();
        for (Path part = p; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException(label + "不能包含符号链接");
        }
        return p;
    }
    public static void separate(Path a, Path b) throws IOException {
        if (a.startsWith(b) || b.startsWith(a)) throw new IOException("目录不能相同或互相包含: " + a + " / " + b);
    }
    public static URL downloadUrl(String value, Set<String> hosts) throws IOException {
        URL url = new URL(value);
        if (!("https".equals(url.getProtocol()) || "http".equals(url.getProtocol()))
                || url.getUserInfo() != null || !hosts.contains(url.getHost().toLowerCase(Locale.ROOT))) {
            throw new IOException("下载地址必须为HTTP(S)，且主机必须在生效的下载域名白名单中");
        }
        return url;
    }
    public static void download(String value, Path dest, long limit, Set<String> hosts) throws Exception {
        download(value, dest, limit, hosts, null);
    }
    public static void download(String value, Path dest, long limit, Set<String> hosts, java.util.function.LongConsumer progress) throws Exception {
        downloadVerified(value,dest,limit,null,hosts,progress);
    }
    public static void downloadVerified(String value,Path dest,long limit,String expected,Set<String> hosts,java.util.function.LongConsumer progress)throws Exception {
        URL url=downloadUrl(value,hosts);
        if(expected!=null && !expected.matches("(?i)[0-9a-f]{64}"))throw new IOException("版本清单缺少有效SHA256: "+dest.getFileName());
        MessageDigest digest=expected==null?null:MessageDigest.getInstance("SHA-256");
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();boolean complete=false;
        conn.setConnectTimeout(10000);conn.setReadTimeout(30000);conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);conn.setRequestProperty("Cache-Control","no-cache");conn.setRequestProperty("Accept-Encoding","identity");
        try {
            int status=conn.getResponseCode();
            if(status!=200)throw new IOException("下载返回HTTP "+status+": "+url.getPath());
            long length=conn.getContentLengthLong();
            if(length>limit)throw new IOException("下载文件大小超过清单或上限: "+url.getPath()+"；实际="+length+"字节，清单/上限="+limit+"字节。请确认发布文件与latest.json一致");
            long total=0;
            try(InputStream in=conn.getInputStream();OutputStream out=Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[262144];int count;
                while((count=in.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("下载取消");
                    total+=count;if(total>limit)throw new IOException("下载文件大小超过清单或上限: "+url.getPath()+"；已收="+total+"，上限="+limit);
                    out.write(buffer,0,count);if(digest!=null)digest.update(buffer,0,count);if(progress!=null)progress.accept(total);
                }
                complete=true;
            }
            if(expected!=null && (total!=limit || !expected.equalsIgnoreCase(hex(digest.digest()))))throw new IOException("下载文件大小或SHA256校验失败: "+url.getPath());
        } finally { if(!complete)conn.disconnect(); } // Fully consumed success responses return to the HTTP keep-alive pool.
    }
    public static void copyVerified(Path source,Path dest,long size,String expected)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
        try(InputStream in=Files.newInputStream(source);OutputStream out=Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW)){
            byte[] buffer=new byte[262144];int n;
            while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("复制取消");total+=n;if(total>size)throw new IOException("本地文件在比对后发生变化: "+source);out.write(buffer,0,n);digest.update(buffer,0,n);}
        }
        if(total!=size || !expected.equalsIgnoreCase(hex(digest.digest())))throw new IOException("本地文件大小或SHA256变化，请重新同步: "+source);
    }
    private static String hex(byte[] bytes){char[] digits="0123456789abcdef".toCharArray();char[] out=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){out[2*i]=digits[(bytes[i]&255)>>>4];out[2*i+1]=digits[bytes[i]&15];}return new String(out);}
    private static long transfer(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[65536]; long total = 0; int n;
        while ((n = in.read(buf)) != -1) { total += n; if (total > limit) throw new IOException("文件超过大小限制"); out.write(buf, 0, n); }
        return total;
    }
    public static void verify(Path file, String expected) throws Exception {
        if (expected == null || !expected.matches("(?i)[0-9a-f]{64}")) throw new IOException("版本清单缺少有效SHA256");
        if (!expected.equalsIgnoreCase(sha256(file))) throw new IOException("SHA256校验失败: " + file.getFileName());
    }
    public static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) { byte[] b = new byte[262144]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        return hex(digest.digest());
    }
    public static boolean preserved(String path, Collection<String> paths) {
        String key = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String value : paths) {
            String prefix = value.toLowerCase(Locale.ROOT);
            if (key.equals(prefix) || key.startsWith(prefix + "/")) return true;
        }
        return false;
    }
    public static List<String> preservePaths(String configured) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String value : configured.split(",")) {
            if (value.trim().isEmpty()) continue;
            String path = UpdateFileManifest.relative(value.trim());
            if (path.equalsIgnoreCase("index.html")) throw new IOException("不能排除前端入口index.html");
            paths.add(path);
        }
        return paths;
    }
    public static void copyPreserved(Path live, Path staged, Collection<String> paths) throws IOException {
        Files.walkFileTree(live, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attr) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("保留目录不能为符号链接");
                String name = live.relativize(dir).toString().replace('\\', '/');
                if (preserved(name, paths)) {
                    copyTree(dir, staged.resolve(live.relativize(dir)));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {
                String name = live.relativize(file).toString().replace('\\', '/');
                if (preserved(name, paths)) {
                    if (!attr.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("不支持的保留文件");
                    Path dest = staged.resolve(live.relativize(file));
                    Files.createDirectories(dest.getParent()); Files.copy(file, dest);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
    public static void unzip(Path zip, Path root, long limit, boolean jars) throws Exception {
        unzip(zip, root, limit, jars, jars ? Collections.emptyList() : Arrays.asList("static"));
    }
    public static void unzip(Path zip, Path root, long limit, boolean jars, Collection<String> preserve) throws Exception {
        Files.createDirectory(root); long total = 0; int entries = 0; int files = 0; Set<Path> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (++entries > 100000) throw new IOException("ZIP文件数量过多");
                String name = e.getName();
                if (name.contains("\\") || name.contains(":") || name.startsWith("/")) throw new IOException("ZIP路径非法");
                Path p = root.resolve(name).normalize();
                if (!p.startsWith(root) || p.equals(root) || !names.add(p)) throw new IOException("ZIP路径越界或重复");
                if (!jars && preserved(name.replaceAll("/+$", ""), preserve)) {
                    if (!e.isDirectory()) total += transfer(in, new OutputStream() { public void write(int b) { } public void write(byte[] b,int off,int len) { } }, limit-total);
                    continue;
                }
                if (e.isDirectory()) { Files.createDirectories(p); continue; }
                if (jars && !name.toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IOException("后端ZIP只能包含JAR");
                Files.createDirectories(p.getParent());
                try (OutputStream out = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW)) { total += transfer(in, out, limit - total); }
                files++;
            }
        }
        if (files == 0) throw new IOException("更新ZIP为空");
        if (!jars && !Files.isRegularFile(root.resolve("index.html"))) throw new IOException("前端ZIP根目录必须包含index.html（打包dist目录内的内容）");
    }
    public static void copyTree(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attr) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("部署目录不能包含符号链接");
                Files.createDirectories(dest.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {
                if (!attr.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("不支持特殊文件: " + file);
                Files.copy(file, dest.resolve(source.relativize(file))); return FileVisitResult.CONTINUE;
            }
        });
    }
}