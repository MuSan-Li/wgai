package org.jeecg.modules.demo.config.service.impl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.regex.*;

/** Generates only missing scripts; existing custom scripts are never replaced or executed here. */
public final class UpdateControlScripts {
    private UpdateControlScripts() { }
    private static final String MARKER="AILELON-AUTO-CONTROL v1";
    public static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    public static Path defaultPath(Path lib,Path jar,boolean start,boolean windows) throws Exception {
        return jar.getParent().resolve("control").resolve((start?"start":"stop")+(windows?".bat":".sh"));
    }
    public static boolean generated(Path path) throws IOException {
        if(!Files.isRegularFile(path) || Files.size(path)>65536)return false;
        try(InputStream in=Files.newInputStream(path)) {
            byte[] head=new byte[128];int count=in.read(head);
            return count>0 && new String(head,0,count,StandardCharsets.UTF_8).contains(MARKER);
        }
    }
    public static Path select(String configured,Path lib,Path jar,boolean start) throws Exception {
        boolean windows=windows(),explicit=configured!=null&&!configured.trim().isEmpty();
        Path path=explicit?UpdateFiles.absolute(configured,start?"启动脚本":"停止脚本"):defaultPath(lib,jar,start,windows);
        // A supplied address is authoritative, including legacy managed scripts and manually edited ones.
        if(explicit)verifyGeneratedDeployment(path,lib,jar,windows);
        else if(generated(path) && !new String(Files.readAllBytes(path),StandardCharsets.UTF_8).equals(render(lib,jar,start,windows)))
            throw new IOException("默认自动脚本内容已修改；请明确填写该脚本地址按现有内容使用，或指定新的脚本地址: "+path);
        if(Files.exists(path) && (!Files.isRegularFile(path) || !Files.isReadable(path)))throw new IOException("脚本不是可读文件: "+path);
        if(!Files.exists(path)) {
            String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
            if(windows?!(name.endsWith(".bat")||name.endsWith(".cmd")):!name.endsWith(".sh"))
                throw new IOException("自动创建脚本需使用"+(windows?".bat或.cmd":".sh")+"后缀: "+path);
        }
        return path;
    }
    private static void verifyGeneratedDeployment(Path path,Path lib,Path jar,boolean windows)throws IOException {
        if(!windows||!generated(path))return;
        String body=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
        String[] names={"LOADER_PATH","JAR_PATH"};Path[] expected={lib,jar};
        for(int i=0;i<names.length;i++){
            Matcher assignment=Pattern.compile("(?im)^set \""+names[i]+"=([^\"\\r\\n]+)\"\\r?$").matcher(body);
            if(!assignment.find())continue;
            String configured=assignment.group(1).replace("%%","%").replace('\\','/');
            String actual=expected[i].toString().replace('\\','/');
            if(!configured.equalsIgnoreCase(actual))throw new IOException("脚本中的"+names[i]+"与当前配置不一致；请修改该脚本或填写新的完整脚本地址，不会自动换用其他文件: "+path);
        }
    }
    public static synchronized void ensure(Path path,Path lib,Path jar,boolean start) throws Exception {
        if(Files.exists(path))return;
        Files.createDirectories(path.getParent());UpdateFiles.absolute(path.toString(),"启停脚本");
        String body=render(lib,jar,start,windows());
        Path temp=Files.createTempFile(path.getParent(),".platform-control-",".tmp");
        try {
            Files.write(temp,body.getBytes(StandardCharsets.UTF_8),StandardOpenOption.TRUNCATE_EXISTING);
            if(!windows())Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));
            try { Files.move(temp,path); } // Publish complete content only; never replace an existing script.
            catch(FileAlreadyExistsException ex) { if(!Files.isRegularFile(path) || Files.isSymbolicLink(path))throw ex; }
        } finally { try{Files.deleteIfExists(temp);}catch(IOException ignored){ } }
    }
    private static String batch(String value) throws IOException {
        if(value.indexOf('"')>=0 || value.indexOf('\n')>=0 || value.indexOf('\r')>=0)throw new IOException("脚本路径不能包含引号或换行");
        return value.replace("%","%%");
    }
    private static String shell(String value) throws IOException {
        if(value.indexOf('\n')>=0 || value.indexOf('\r')>=0)throw new IOException("脚本路径不能包含换行");
        return "'"+value.replace("'","'\"'\"'")+"'";
    }
    public static String render(Path lib,Path jar,boolean start,boolean windows) throws IOException {
        String root=jar.getParent().toString(),loader=lib.toString(),launcher=jar.toString();
        if(windows){root=root.replace('\\','/');loader=loader.replace('\\','/');launcher=launcher.replace('\\','/');}
        Map<String,String> replacements=new HashMap<>();
        replacements.put("APP",windows?batch(root):shell(root));
        replacements.put("LIB",windows?batch(loader):shell(loader));
        replacements.put("JAR",windows?batch(launcher):shell(launcher));
        String template=windows?(start?WINDOWS_START:WINDOWS_STOP):(start?LINUX_START:LINUX_STOP);
        Matcher matcher=Pattern.compile("@(APP|LIB|JAR)@").matcher(template);StringBuffer result=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(result,Matcher.quoteReplacement(replacements.get(matcher.group(1))));
        matcher.appendTail(result);return result.toString();
    }
    private static final String WINDOWS_START =
        "@echo off\r\n"
        + "rem AILELON-AUTO-CONTROL v1\r\n"
        + "chcp 65001 >nul\r\n"
        + "setlocal DisableDelayedExpansion\r\n"
        + "set \"AILELON_ROOT=@APP@\"\r\n"
        + "set \"LOADER_PATH=@LIB@\"\r\n"
        + "set \"JAR_PATH=@JAR@\"\r\n"
        + "set \"JAVA_COMMAND=java\"\r\n"
        + "cd /d \"%AILELON_ROOT%\" || goto startup_failed\r\n"
        + "if not exist \"%JAR_PATH%\" goto startup_failed\r\n"
        + "if not exist \"%LOADER_PATH%/opencv-4.8.1-0.jar\" goto startup_failed\r\n"
        + "powershell.exe -NoLogo -NoProfile -NonInteractive -Command \"$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; $mode=if($env:AILELON_START_EXIT_FILE){'update'}else{'manual'}; $java=(Get-Command $env:JAVA_COMMAND -CommandType Application | Select-Object -First 1).Source; $vc=@(($env:windir+'/System32/msvcp140.dll'),($env:windir+'/System32/vcruntime140.dll'),(Join-Path (Split-Path $java) 'msvcp140.dll'),(Join-Path (Split-Path $java) 'vcruntime140.dll')); $lines=@(('timeUtc='+[DateTime]::UtcNow.ToString('o')),('mode='+$mode),('user='+[Environment]::UserName),('cwd='+(Get-Location).Path),('java='+$java),('JAVA_HOME='+$env:JAVA_HOME),('PATHEXT='+$env:PATHEXT),('lib='+$env:LOADER_PATH),('launcher='+$env:JAR_PATH),('AILELON_JAVA.present='+[bool]$env:AILELON_JAVA),('PATH='+$env:PATH),('TEMP='+$env:TEMP),('TMP='+$env:TMP),('JAVA_TOOL_OPTIONS.present='+[bool]$env:JAVA_TOOL_OPTIONS),('_JAVA_OPTIONS.present='+[bool]$env:_JAVA_OPTIONS)); foreach($file in @($java,$env:JAR_PATH)+(Get-ChildItem -LiteralPath $env:LOADER_PATH -Filter 'onnxruntime*.jar' -File | ForEach-Object {$_.FullName})){if(Test-Path -LiteralPath $file -PathType Leaf){$stream=[IO.File]::OpenRead($file); $sha=[Security.Cryptography.SHA256]::Create(); try{$lines+=($file+' sha256='+([BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')))}finally{$stream.Dispose();$sha.Dispose()}}}; foreach($file in $vc){if(Test-Path -LiteralPath $file){$item=Get-Item -LiteralPath $file;$lines+=($item.FullName+' version='+$item.VersionInfo.FileVersion)}}; $folder=Join-Path $env:AILELON_ROOT 'control'; [IO.Directory]::CreateDirectory($folder) | Out-Null; [IO.File]::WriteAllLines((Join-Path $folder ('start-'+$mode+'-context.log')),$lines,(New-Object Text.UTF8Encoding($false))); if($env:AILELON_START_EXIT_FILE){[IO.File]::WriteAllLines(($env:AILELON_START_EXIT_FILE+'.context.log'),$lines,(New-Object Text.UTF8Encoding($false)))}\"\r\n"
        + "\"%JAVA_COMMAND%\" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \"-Dloader.path=%LOADER_PATH%/opencv-4.8.1-0.jar,%LOADER_PATH%\" -jar \"%JAR_PATH%\"\r\n"
        + "set \"JAVA_EXIT_CODE=%ERRORLEVEL%\"\r\n"
        + "goto startup_finished\r\n"
        + ":startup_failed\r\n"
        + "set \"JAVA_EXIT_CODE=1\"\r\n"
        + ":startup_finished\r\n"
        + "if defined AILELON_START_EXIT_FILE >\"%AILELON_START_EXIT_FILE%\" echo %JAVA_EXIT_CODE%\r\n"
        + "exit /b %JAVA_EXIT_CODE%\r\n";
    private static final String WINDOWS_STOP =
        "@echo off\r\n"
        + "rem AILELON-AUTO-CONTROL v1\r\n"
        + "chcp 65001 >nul\r\n"
        + "setlocal DisableDelayedExpansion\r\n"
        + "set \"AILELON_ROOT=@APP@\"\r\n"
        + "set \"LOADER_PATH=@LIB@\"\r\n"
        + "set \"JAR_PATH=@JAR@\"\r\n"
        + "powershell.exe -NoLogo -NoProfile -NonInteractive -Command \"$ErrorActionPreference='Stop'; $lib=$env:LOADER_PATH.Replace('\\','/').TrimEnd('/'); $jar=$env:JAR_PATH.Replace('\\','/'); $jarPattern='(?i)(?:^|\\s)-jar\\s+(?:'+[regex]::Escape($jar)+'|'+[regex]::Escape([IO.Path]::GetFileName($jar))+')(?:\\s|$)'; $loaderPattern='(?i)(?:^|\\s)-Dloader\\.path=(?:'+[regex]::Escape($lib+'/opencv-4.8.1-0.jar,')+')?'+[regex]::Escape($lib)+'(?:\\s|$)'; $targets=@(Get-CimInstance Win32_Process | Where-Object { if ($_.Name -notin @('java.exe','javaw.exe') -or -not $_.CommandLine) { return $false }; $c=$_.CommandLine.Replace('\\','/').Replace([string][char]34,''); ($c -match $jarPattern) -and ($c -match $loaderPattern) }); foreach ($target in $targets) { $appProc=Get-Process -Id $target.ProcessId -ErrorAction SilentlyContinue; if ($appProc) { $null=$appProc.Handle; Write-Host ('Stopping PID '+$target.ProcessId); Stop-Process -InputObject $appProc -Force; if (-not $appProc.WaitForExit(30000)) { throw 'Process did not exit' } } }; if ($targets.Count -eq 0) { Write-Host 'No matching Ailelon process found' }\"\r\n"
        + "exit /b %ERRORLEVEL%\r\n";
    private static final String LINUX_START =
        "#!/bin/sh\n"
        + "# AILELON-AUTO-CONTROL v1\n"
        + "set -eu\n"
        + "AILELON_ROOT=@APP@\n"
        + "LOADER_PATH=@LIB@\n"
        + "JAR_PATH=@JAR@\n"
        + "export LOADER_PATH JAR_PATH\n"
        + "cd \"$AILELON_ROOT\"\n"
        + "JAVA_COMMAND=${AILELON_JAVA:-java}\n"
        + "command -v \"$JAVA_COMMAND\" >/dev/null 2>&1 || { echo 'Java not found in PATH' >&2; exit 1; }\n"
        + "[ -f \"$JAR_PATH\" ] || { echo 'Startup JAR not found' >&2; exit 1; }\n"
        + "[ -f \"$LOADER_PATH/opencv-4.8.1-0.jar\" ] || { echo 'OpenCV JAR not found' >&2; exit 1; }\n"
        + "nohup \"$JAVA_COMMAND\" \"-Dloader.path=${LOADER_PATH}/opencv-4.8.1-0.jar,${LOADER_PATH}\" -jar \"$JAR_PATH\" >> \"$AILELON_ROOT/platform-start.log\" 2>&1 < /dev/null &\n"
        + "echo \"Started Java PID $!\"\n";
    private static final String LINUX_STOP =
        "#!/bin/sh\n"
        + "# AILELON-AUTO-CONTROL v1\n"
        + "set -eu\n"
        + "AILELON_ROOT=@APP@\n"
        + "LOADER_PATH=@LIB@\n"
        + "JAR_PATH=@JAR@\n"
        + "export LOADER_PATH JAR_PATH\n"
        + "[ -d /proc ] || { echo 'Linux /proc is required for safe process matching' >&2; exit 1; }\n"
        + "matches_pid() {\n"
        + "    [ -r \"/proc/$1/cmdline\" ] || return 1\n"
        + "    [ \"$(cat \"/proc/$1/comm\" 2>/dev/null)\" = java ] || return 1\n"
        + "    tr '\\000' '\\n' < \"/proc/$1/cmdline\" 2>/dev/null | awk '\n"
        + "        BEGIN { jar=ENVIRON[\"JAR_PATH\"]; name=jar; sub(\".*/\", \"\", name); lib=ENVIRON[\"LOADER_PATH\"] }\n"
        + "        previous == \"-jar\" && ($0 == jar || $0 == name) { foundJar=1 }\n"
        + "        $0 == \"-Dloader.path=\" lib || $0 == \"-Dloader.path=\" lib \"/opencv-4.8.1-0.jar,\" lib { foundLoader=1 }\n"
        + "        { previous=$0 }\n"
        + "        END { exit !(foundJar && foundLoader) }\n"
        + "    '\n"
        + "}\n"
        + "targets=''\n"
        + "for entry in /proc/[0-9]*; do\n"
        + "    pid=${entry##*/}\n"
        + "    if matches_pid \"$pid\"; then targets=\"$targets $pid\"; fi\n"
        + "done\n"
        + "if [ -z \"$targets\" ]; then echo 'No matching Ailelon process found'; exit 0; fi\n"
        + "for pid in $targets; do\n"
        + "    echo \"Stopping PID $pid\"\n"
        + "    if ! kill -TERM \"$pid\" 2>/dev/null; then\n"
        + "        if matches_pid \"$pid\"; then echo \"Cannot stop PID $pid\" >&2; exit 1; fi\n"
        + "    fi\n"
        + "done\n"
        + "for pid in $targets; do\n"
        + "    elapsed=0\n"
        + "    while matches_pid \"$pid\" && [ \"$elapsed\" -lt 30 ]; do sleep 1; elapsed=$((elapsed+1)); done\n"
        + "    if matches_pid \"$pid\"; then kill -KILL \"$pid\"; fi\n"
        + "    elapsed=0\n"
        + "    while matches_pid \"$pid\" && [ \"$elapsed\" -lt 5 ]; do sleep 1; elapsed=$((elapsed+1)); done\n"
        + "    if matches_pid \"$pid\"; then echo \"Process did not exit: $pid\" >&2; exit 1; fi\n"
        + "done\n";
}
