package com.rstech.simupack.pack.legacy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.pack.PackActivation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 旧版建筑拓展包的「只读识别器」。
 *
 * <p>判定<b>不靠文件名</b>，只靠包内结构：把 {@code .simupack} 改名成 {@code .zip} 的旧包、
 * 以及直接放在建筑目录里的旧 {@code .zip}，都要能被认出来。
 *
 * <p>判据（与 {@link LegacyPackConverter} 共用一套归档规则，避免"能识别却转不动"）：
 * <ul>
 *   <li>有 {@code pack.json} → 新版，直接放行；</li>
 *   <li>没有 {@code pack.json}，但包内有 {@code .sk} 元数据或
 *       {@code .nbt/.litematic/.schem} 结构文件 → 旧版；</li>
 *   <li>两者都没有 → 不是建筑包（返回 {@code null}，由调用方按"格式错误"处理）。</li>
 * </ul>
 *
 * <p>本类刻意不依赖任何 Minecraft 类：识别逻辑要能在无客户端环境下跑单元测试。
 */
public final class LegacyPackDetector {

    /** 旧包结构文件里会被新版加载器忽略、必须转换或放弃的扩展名。 */
    public static final List<String> NON_NBT_STRUCTURES = List.of(".litematic", ".schem");

    /** 旧包可能用来装功能定义的"别名 JSON"后缀，转换时统一改名成 {@code .json}。 */
    public static final List<String> JSON_ALIASES = List.of(".meta.json", ".shops.json");

    /** 新版包清单文件名。 */
    public static final String MANIFEST = "pack.json";

    /** 旧版筑艺坊的元数据文件名（键名是 packName/packVersion）。 */
    public static final String LEGACY_METADATA = "metadata.json";

    /** 识别结果。 */
    public record LegacyPackInfo(
            Path path,
            String name,
            String version,
            String author,
            String description,
            boolean hasManifest,
            boolean legacy,
            int skCount,
            int nbtCount,
            int nonNbtCount,
            int jsonCount,
            boolean hasIcon,
            String error
    ) {
        /** 是否可以转换。 */
        public boolean convertible() {
            return legacy && error == null && skCount > 0;
        }

        /** 需要转换（而不是直接忽略）的结构文件数量。 */
        public int structuresToConvert() {
            return nonNbtCount;
        }

        /** 列表里显示的包名：旧包加"（旧版）"标记由调用方决定，这里只给原始名字。 */
        public String displayName() {
            return name == null || name.isBlank() ? fallbackName() : name;
        }

        private String fallbackName() {
            String file = path.getFileName().toString();
            int dot = file.lastIndexOf('.');
            return dot > 0 ? file.substring(0, dot) : file;
        }
    }

    private LegacyPackDetector() {
    }

    /**
     * 识别一个建筑包文件。
     *
     * @return 识别结果；文件不是 ZIP 或读不了时，返回带 {@code error} 的结果（不抛异常）
     */
    public static LegacyPackInfo detect(Path path) {
        String fallback = fallbackName(path);
        if (path == null || !Files.isRegularFile(path)) {
            return new LegacyPackInfo(path, fallback, "", "", "", false, false, 0, 0, 0, 0, false, "文件不存在");
        }
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            return scan(path, zip, fallback);
        } catch (ZipException exception) {
            return new LegacyPackInfo(path, fallback, "", "", "", false, false, 0, 0, 0, 0, false,
                    "不是有效的 ZIP：" + exception.getMessage());
        } catch (IOException exception) {
            return new LegacyPackInfo(path, fallback, "", "", "", false, false, 0, 0, 0, 0, false,
                    "无法读取：" + exception.getMessage());
        }
    }

    private static LegacyPackInfo scan(Path path, ZipFile zip, String fallback) throws IOException {
        String name = fallback;
        String version = "";
        String author = "";
        String description = "";
        boolean hasManifest = false;
        int sk = 0;
        int nbt = 0;
        int nonNbt = 0;
        int json = 0;
        boolean icon = false;

        // 新版清单是 pack.json；旧包用 metadata.json（旧版筑艺坊 ExportManager 的产物）。
        // 两个都读，列表才能显示真实包名而不是文件名。
        ZipEntry manifest = zip.getEntry(MANIFEST);
        if (manifest != null && !manifest.isDirectory()) {
            hasManifest = true;
        } else {
            manifest = zip.getEntry(LEGACY_METADATA);
            if (manifest == null || manifest.isDirectory()) {
                manifest = null;
            }
        }
        if (manifest != null) {
            JsonObject meta = readManifest(zip, manifest);
            if (meta != null) {
                name = text(meta, "name", text(meta, "packName",
                        text(meta, "pack_name", fallback)));
                version = text(meta, "version", text(meta, "packVersion",
                        text(meta, "pack_version", "")));
                author = text(meta, "author", "");
                description = text(meta, "description", "");
            }
        }

        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();
            if (entryName == null || entryName.isBlank()) {
                continue;
            }
            String lower = entryName.toLowerCase(Locale.ROOT);
            if ("icon.png".equals(lower)) {
                icon = true;
                continue;
            }
            if (LegacyPackConverter.isMetaFile(entryName)) {
                continue;
            }
            if (lower.endsWith(".sk")) {
                sk++;
            } else if (lower.endsWith(".nbt")) {
                nbt++;
            } else if (LegacyPackConverter.structureKind(entryName) != null) {
                nonNbt++;
            } else if (lower.endsWith(".json")) {
                json++;
            }
        }

        // 没有 pack.json 但有建筑素材 → 旧版。catalog 之外的杂项文件（README 等）不参与判定。
        boolean legacy = !hasManifest && (sk > 0 || nbt > 0 || nonNbt > 0);
        String error = null;
        if (!hasManifest && !legacy) {
            error = "包内没有 pack.json，也没有 .sk/.nbt 建筑文件";
        }
        return new LegacyPackInfo(path, name, version, author, description, hasManifest, legacy,
                sk, nbt, nonNbt, json, icon, error);
    }

    /** 读包内 {@code pack.json}；坏了就返回 null（元数据缺失不该让整包不可用）。 */
    private static JsonObject readManifest(ZipFile zip, ZipEntry entry) {
        try (InputStream input = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(new java.io.BufferedInputStream(input),
                     StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            // 少数旧包把真实元数据塞在 pack 子对象里。
            JsonElement nested = object.get("pack");
            if (nested != null && nested.isJsonObject()) {
                Map<String, JsonElement> merged = new LinkedHashMap<>();
                nested.getAsJsonObject().entrySet().forEach(e -> merged.put(e.getKey(), e.getValue()));
                object.entrySet().forEach(e -> merged.put(e.getKey(), e.getValue()));
                JsonObject mergedObject = new JsonObject();
                merged.forEach(mergedObject::add);
                return mergedObject;
            }
            return object;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        String text = value.getAsString();
        return text == null || text.isBlank() ? fallback : text.trim();
    }

    private static String fallbackName(Path path) {
        if (path == null || path.getFileName() == null) {
            return "未命名拓展包";
        }
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /**
     * 一个目录下所有需要转换的旧包；按文件名排序，保证界面顺序稳定。
     *
     * <p>只扫描 {@code .zip} 与 {@code .simupack}：其它扩展名不可能是建筑包。
     */
    public static List<LegacyPackInfo> scanDirectory(Path directory) {
        return scanDirectory(directory, null);
    }

    /**
     * 同上，但允许调用方过滤。
     *
     * <p>过滤器在这里而不是在调用方循环里，是为了让"用户已经按过不再提示"的旧包
     * 连读取都省掉（大包扫一次要解压清单）。
     *
     * @param filter 返回 false 的文件直接跳过；null 表示不过滤
     */
    public static List<LegacyPackInfo> scanDirectory(Path directory, Predicate<Path> filter) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(LegacyPackDetector::looksLikePackFile)
                    .filter(path -> filter == null || filter.test(path))
                    .sorted((left, right) -> left.getFileName().toString()
                            .compareToIgnoreCase(right.getFileName().toString()))
                    .forEach(candidates::add);
        } catch (IOException exception) {
            return List.of();
        }
        List<LegacyPackInfo> result = new ArrayList<>();
        for (Path candidate : candidates) {
            LegacyPackInfo info = detect(candidate);
            if (info.legacy()) {
                result.add(info);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 扩展名像是建筑包（含旧版专用后缀）。
     *
     * <p>被筑艺坊禁用的包（{@code xxx.zip.disabled}）刻意不算：它们已经处理过（转换完成或
     * 用户主动关掉），再当成"待转换旧包"会把它们重新拉回转换提示里。
     */
    public static boolean looksLikePackFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        if (PackActivation.isDisabled(path)) {
            return false;
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".simupack");
    }

    /**
     * 是不是"旧版建筑包"——比 {@link #detect} 便宜，只查有没有 {@code pack.json} 与 {@code .sk}。
     *
     * <p>给 {@code BuildingPackManager} 用：旧包（无论叫 {@code .zip} 还是 {@code .simupack}）
     * 躺在新版建筑目录里时，它本来会被当成"缺 pack.json 的坏包"每轮扫描都报一次格式错误。
     * 这里先把它们认出来，交给旧包转换那条路径处理，不再当坏包展示。
     */
    public static boolean isLegacyBuildingPack(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            if (zip.getEntry(MANIFEST) != null) {
                return false;
            }
            var entries = zip.entries();
            boolean hasSk = false;
            boolean hasStructure = false;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".sk")) {
                    hasSk = true;
                } else if (lower.endsWith(".nbt")) {
                    hasStructure = true;
                }
            }
            return hasSk || hasStructure;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}
