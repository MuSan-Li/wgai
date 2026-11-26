package org.jeecg.modules.ros2.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Only named map artifacts are removed; never directories or referenced image paths. */
public final class MapFileStore {
    private MapFileStore() {}
    public static int delete(Path directory, String name, String active) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 180 || name.contains("..")
                || !name.matches("[\\p{L}\\p{N}_\\- .]+") || !name.equals(name.trim())
                || name.equals("last_used") || name.startsWith("nav2_params"))
            throw new IllegalArgumentException("非法或受保护的地图名称");
        if (name.equals(active)) throw new IllegalStateException("不能删除当前地图，请先切换到其他地图");
        Path root = directory.toAbsolutePath().normalize();
        Path last = root.resolve("last_used.yaml");
        if (Files.isSymbolicLink(last)) {
            String pointed = Files.readSymbolicLink(last).getFileName().toString();
            if (pointed.equals(name + ".yaml"))
                throw new IllegalStateException("不能删除启动地图，请先切换到其他地图");
        }
        List<Path> targets = new ArrayList<>();
        for (String suffix : new String[]{".yaml", ".pgm", ".png", "_last_pose.json", ".pgm.orig", "_erase.json"}) {
            Path target = root.resolve(name + suffix).normalize();
            if (!root.equals(target.getParent())) throw new IllegalArgumentException("地图路径超出目录");
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("地图文件位置是目录，拒绝删除: " + target.getFileName());
            targets.add(target);
        }
        int count = 0;
        for (Path target : targets) if (Files.deleteIfExists(target)) count++;
        return count;
    }
}
