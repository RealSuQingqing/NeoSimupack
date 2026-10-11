package com.rstech.simupack.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.creation.PackFormat;
import com.rstech.simupack.pack.legacy.LegacyPackDetector;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BuildingPackManager {
    public static final String ROOT_DIRECTORY = "simukraftbuilding";
    private static final AtomicReference<List<BuildingPack>> PACKS = new AtomicReference<>(List.of());
    private static volatile long lastScanEpochMillis;
    /** 目录指纹用于避免每次打开 P 管理器都重复解压/枚举所有 ZIP。 */
    private static volatile long lastDirectoryFingerprint = Long.MIN_VALUE;
    private static volatile boolean scanInitialized;

    private BuildingPackManager() {
    }

    public static Path rootDirectory() {
        return FMLPaths.GAMEDIR.get().resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
    }

    public static List<BuildingPack> packs() {
        return PACKS.get();
    }

    /** 最近一次扫描完成的时间；界面用它显示「最后检查时间」。 */
    public static long lastScanEpochMillis() {
        return lastScanEpochMillis;
    }

    public static void reload() {
        Path root = rootDirectory();
        try {
            Files.createDirectories(root);
            long fingerprint = directoryFingerprint(root);
            if (scanInitialized && fingerprint == lastDirectoryFingerprint) {
                return;
            }
            // 扫描前先把被（误）禁用的官方内置包修回来：它是整个建筑体系的最小可用集，
            // 禁用它会让游戏里一个可建建筑都不剩。放在扫描前是为了让下面的结果一次就是对的。
            PackActivation.restoreOfficialIfDisabled();
            List<BuildingPack> scanned = new ArrayList<>();
            try (var stream = Files.list(root)) {
                stream.filter(path -> PackActivation.isPackFileName(path.getFileName().toString()))
                        // 旧版建筑包（没有 pack.json 但有建筑素材）不是"坏包"，是待转换的旧包。
                        // 把它们排除掉，否则每轮扫描都会给用户报一条莫须有的格式错误。
                        .filter(path -> !LegacyPackDetector.isLegacyBuildingPack(path))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .map(BuildingPackManager::scan)
                        .forEach(scanned::add);
            }
            PACKS.set(List.copyOf(scanned));
            lastDirectoryFingerprint = fingerprint;
            scanInitialized = true;
            SimuPackMod.LOGGER.info("Scanned {} Sim-U-Kraft building packs ({} disabled) from {}",
                    scanned.size(), scanned.stream().filter(pack -> !pack.enabled()).count(), root);
        } catch (IOException exception) {
            PACKS.set(List.of());
            SimuPackMod.LOGGER.error("Unable to scan Sim-U-Kraft building packs in {}", root, exception);
        } finally {
            lastScanEpochMillis = System.currentTimeMillis();
        }
    }

    /**
     * 指纹只读取目录项名称、文件大小和修改时间，不打开 ZIP。
     * 文件内容被原地覆盖时通常会更新大小或 mtime；若平台无法读取元数据则返回变化值，
     * 让下一次 reload 保守地重新扫描。
     */
    private static long directoryFingerprint(Path root) throws IOException {
        long hash = 0xcbf29ce484222325L;
        try (var stream = Files.list(root)) {
            var entries = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> PackActivation.isPackFileName(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path path : entries) {
                hash ^= path.getFileName().toString().hashCode();
                hash *= 0x100000001b3L;
                hash ^= Files.size(path);
                hash *= 0x100000001b3L;
                hash ^= Files.getLastModifiedTime(path).toMillis();
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static BuildingPack scan(Path path) {
        List<String> errors = new ArrayList<>();
        String fileName = path.getFileName().toString();
        boolean enabled = !PackActivation.isDisabled(path);
        // 包 id 的回退值必须跨启用/禁用两种文件名保持一致：
        // official_building.zip 与 official_building.zip.disabled 要指向同一个包。
        String fallbackId = PackActivation.stripExtensions(fileName);
        String id = fallbackId;
        String name = fallbackId;
        String version = "";
        String author = "";
        String description = "";
        int buildings = 0;
        // 包格式号：1.20.1=1、1.21.1=2、26.3=3，没声明就是 0（见 PackFormat）。
        int format = PackFormat.UNKNOWN;
        // 「读得出来」= ZIP 打得开 且 pack.json 解析成功。NSUK 必须先有 pack.json
        // 才会认这个包，所以这两点决定它到底是不是一个能被加载的包。
        boolean readable = false;
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry manifest = zip.getEntry("pack.json");
            if (manifest != null) {
                try (InputStream input = zip.getInputStream(manifest);
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    id = string(json, "id", fallbackId);
                    name = string(json, "name", id);
                    // 禁用的包也要读得出真正的名字：文件名带 .disabled 只是磁盘上的状态，
                    // 不该影响展示（用户看到的是包自己声明的名字）。
                    version = string(json, "version", "");
                    author = string(json, "author", "");
                    description = string(json, "description", "");
                    format = integer(json, "format", PackFormat.UNKNOWN);
                    readable = true;
                } catch (RuntimeException exception) {
                    errors.add("Invalid pack.json: " + exception.getMessage());
                }
            } else {
                errors.add("Missing pack.json");
            }

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (isBuildingMetadata(entry)) {
                    String category = entry.substring("buildings/".length(), entry.indexOf('/', "buildings/".length()));
                    String file = entry.substring(entry.lastIndexOf('/') + 1);
                    String structure = "buildings/" + category + "/" + stripExtension(file) + ".nbt";
                    if (zip.getEntry(structure) == null) {
                        errors.add("Missing structure for " + entry);
                    } else {
                        buildings++;
                    }
                }
            }
        } catch (IOException exception) {
            errors.add("Cannot read ZIP: " + exception.getMessage());
        }
        return new BuildingPack(id, name, version, author, description, path, buildings,
                List.copyOf(errors), PackActivation.isOfficialFileName(fileName), enabled, readable,
                format);
    }

    private static boolean isBuildingMetadata(String entry) {
        if (!entry.startsWith("buildings/") || !entry.endsWith(".sk")) {
            return false;
        }
        String relative = entry.substring("buildings/".length());
        int slash = relative.indexOf('/');
        return slash > 0 && slash == relative.lastIndexOf('/');
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    /**
     * 读一个整数键。
     *
     * <p>刻意不放行字符串形态的 {@code "2"}：{@code pack.json} 是给机器读的，
     * 写成字符串说明作者手抖了，静默接受只会让格式越来越松。读不出来就用 {@code fallback}
     * （{@link PackFormat#UNKNOWN}），后续按"未声明格式"处理并提示，而不是让整包读不出。
     */
    private static int integer(JsonObject object, String key, int fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String stripExtension(String value) {
        int index = value.lastIndexOf('.');
        return index > 0 ? value.substring(0, index) : value;
    }
}
