package org.jeecg.modules.demo.config.updater;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Assemble the JDK-only updater from classes already packaged in the business JAR. */
public final class EmbeddedUpdater {
    private EmbeddedUpdater() { }
    public static void verifyAvailable() throws IOException {
        try (InputStream source=PlatformUpdater.class.getResourceAsStream("PlatformUpdater.class")) {
            if(source==null)throw new IOException("业务JAR缺少内置更新器，请重新完整打包部署");
        }
    }
    public static Path extract(Path work) throws IOException {
        verifyAvailable();
        Path destination=work.resolve("platform-updater.jar");
        Manifest manifest=new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,PlatformUpdater.class.getName());
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW),manifest)) {
            append(PlatformUpdater.class,out,new HashSet<String>());
        }
        return destination;
    }
    private static void append(Class<?> type,JarOutputStream out,Set<String> included) throws IOException {
        String path=type.getName().replace('.','/')+".class";
        if(!included.add(path))return;
        try(InputStream input=type.getResourceAsStream("/"+path)) {
            if(input==null)throw new IOException("内置更新器缺少类文件: "+path);
            out.putNextEntry(new JarEntry(path));
            byte[] bytes=new byte[16384];int length;
            while((length=input.read(bytes))!=-1)out.write(bytes,0,length);
            out.closeEntry();
        }
        for(Class<?> nested:type.getDeclaredClasses())append(nested,out,included);
    }
}
