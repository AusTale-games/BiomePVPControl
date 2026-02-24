package games.austale.zonepvpcontrol.util;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class DataPaths {

    private DataPaths() {
    }

    public static Path resolveDataPath(JavaPlugin plugin) {
        Path baseDir = extractPath(plugin, "getDataDirectory");
        if (baseDir == null) {
            baseDir = extractPath(plugin, "getDataFolder");
        }
        if (baseDir == null) {
            baseDir = extractPath(plugin, "getDataPath");
        }
        if (baseDir == null) {
            baseDir = Paths.get(".");
        }
        Path serverRoot = resolveServerRoot(baseDir);
        Path configDir = serverRoot.resolve("config").resolve("ZonePVPControl");
        return ensureDirectory(configDir);
    }

    private static Path resolveServerRoot(Path dataDir) {
        if (dataDir == null) {
            return Paths.get(".");
        }
        Path current = dataDir.toAbsolutePath().normalize();
        while (current != null) {
            Path name = current.getFileName();
            if (name != null && name.toString().equalsIgnoreCase("mods")) {
                Path parent = current.getParent();
                return parent == null ? current : parent;
            }
            current = current.getParent();
        }
        return dataDir.toAbsolutePath().normalize();
    }

    private static Path extractPath(JavaPlugin plugin, String methodName) {
        if (plugin == null) {
            return null;
        }
        try {
            Method method = plugin.getClass().getMethod(methodName);
            Object result = method.invoke(plugin);
            if (result instanceof Path path) {
                return path;
            }
            if (result instanceof File file) {
                return file.toPath();
            }
        } catch (Exception ignored) {
            // ignore and fall back
        }
        return null;
    }

    private static Path ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception ignored) {
            // ignore and fall back
        }
        return path;
    }
}
