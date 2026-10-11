package com.rstech.simupack.pack.legacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.PackFormat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把旧版（1.20.1 时代）建筑拓展包转换成新版格式。
 *
 * <p>设计沿用旧版转换脚本的思路（分组、原子替换、字段映射、绝不静默丢数据），
 * 但按本模组的实际加载器重写过判据，修掉了脚本里会产出"能装不能读"的四个坑：
 * <ol>
 *   <li><b>结构格式</b>：NSUK 只认 {@code <建筑id>.nbt}。旧包的 {@code .litematic} /
 *       {@code .schem} 会被新版加载器静默丢弃，所以这里必须真正转成 {@code .nbt}
 *       （通过 {@link StructureNbtConverter} 注入，转换失败则记录并跳过该建筑）；</li>
 *   <li><b>功能定义文件名</b>：加载器只找 {@code buildings/<分类>/<id>.json}，
 *       旧包的 {@code <id>.meta.json} 之类要改名，否则商店定义等于没装；</li>
 *   <li><b>清单字段</b>：补 {@code format}（本版本线的格式号，见 {@link PackFormat}），
 *       并把建筑 id 列表写进 {@code buildingIds}，
 *       与 {@code CreationWorkspace.export} 的产物保持一致；</li>
 *   <li><b>建筑 id 与 {@code .sk} 的 structure 行</b>：旧包文件名本来就是蛇形 ASCII id，
 *       原样保留（见 {@link #buildingId}）；若 {@code .sk} 里写的结构文件名与它不一致，
 *       必须对齐过去，否则 NSUK 找不到 {@code <id>.nbt} 会把整栋建筑丢掉。</li>
 * </ol>
 *
 * <p><b>实测过的 {@code .sk} 键表</b>（NSUK 2.2.0 字节码，逐个类核对）：
 * 只有 {@code name/size/amount/price/author/description/desc/structure/file/
 * unlockLevel/unlock_level/unit} 以及四个「指向 JSON 的指针键」
 * （{@code commercial:}/{@code industrial:}/{@code medical:}/{@code drilling:}）会被读取。
 * 旧版筑艺坊写在 {@code .sk} 里的 {@code tags:}、{@code job_type:}、{@code job_name:}、
 * {@code held_item:}、{@code home_teleport:}、{@code canParturition:} <b>新版一律不读</b>
 * （它们是死行，官方包自己也写了一堆死行）。其中职业三兄弟的唯一载体是新版
 * {@code <主干>.json} 的 {@code job}，所以这些信息要么由本转换器搬进 JSON，
 * 要么就真的没了。</p>
 *
 * <p>本类不依赖任何 Minecraft 类，结构转换通过接口注入，因此可以脱机跑单元测试。
 */
public final class LegacyPackConverter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** 新版允许的建筑分类目录。 */
    public static final List<String> CATEGORIES = List.of(
            "residential", "commercial", "industry", "public", "other");

    private static final String BUILDINGS_DIR = "buildings";

    /**
     * 旧版筑艺坊会写进 {@code .sk}、但 NSUK 2.2.0 已经不读的键。
     *
     * <p>核实方式：对 {@code BuildingPackageCatalog}/{@code BuildingMetadataReader}/
     * {@code BuildingCatalog} 逐类 javap 反汇编，这些字面量零命中；带这些名字的字符串
     * 只出现在 JSON 侧的 {@code CommercialDefinitionLoader.parseJob(JsonObject)} 与
     * {@code IndustrialDefinitionLoader} 里。{@code home_teleport} 更彻底：全 jar 零引用，
     * 新版也没有任何替代键。
     */
    private static final Set<String> DEAD_SK_KEYS = Set.of(
            "tags", "job_type", "job_name", "held_item",
            "home_teleport", "canparturition");

    private static final Map<String, String> CATEGORY_ALIASES = Map.ofEntries(
            Map.entry("residential", "residential"), Map.entry("residence", "residential"),
            Map.entry("housing", "residential"), Map.entry("house", "residential"),
            Map.entry("houses", "residential"), Map.entry("住宅", "residential"),
            Map.entry("居民", "residential"), Map.entry("居住", "residential"),
            Map.entry("commercial", "commercial"), Map.entry("commerce", "commercial"),
            Map.entry("business", "commercial"), Map.entry("shop", "commercial"),
            Map.entry("shops", "commercial"), Map.entry("商业", "commercial"),
            Map.entry("商铺", "commercial"), Map.entry("商店", "commercial"),
            Map.entry("industry", "industry"), Map.entry("industrial", "industry"),
            Map.entry("factory", "industry"), Map.entry("工业", "industry"),
            Map.entry("工厂", "industry"), Map.entry("公共", "public"),
            Map.entry("public", "public"), Map.entry("medical", "public"),
            Map.entry("市政", "public"), Map.entry("医疗", "public"),
            Map.entry("other", "other"), Map.entry("others", "other"),
            Map.entry("misc", "other"), Map.entry("其他", "other"), Map.entry("其它", "other"));

    /** 结构文件转换器：把旧格式的结构字节转成原版结构 NBT 字节。 */    @FunctionalInterface
    public interface StructureNbtConverter {
        /**
         * @param sourceName 源文件名（只用于错误信息与格式判定）
         * @param bytes      源文件内容
         * @return 原版结构 NBT 字节
         * @throws IOException 无法识别或解析时抛出，调用方会记录并跳过该建筑
         */
        byte[] toStructureNbt(String sourceName, byte[] bytes) throws IOException;
    }

    /** 转换过程中的一条提示（不是失败，但需要让用户知道）。 */
    public record Notice(String kind, String detail) {
        public static final String SKIPPED_STRUCTURE = "skipped_structure";
        public static final String RENAMED_ID = "renamed_id";
        public static final String SKIPPED_DUPLICATE = "skipped_duplicate";
        public static final String SHOP_UPGRADED = "shop_upgraded";
        public static final String MISSING_STRUCTURE = "missing_structure";
        /** 旧包的 mod 依赖清单被原样保留（新版不读它）。 */
        public static final String DEPENDENCIES_KEPT = "dependencies_kept";
        /** 非法 unlockLevel 已删除（留着会让整栋建筑被新版丢弃）。 */
        public static final String UNLOCK_LEVEL_REMOVED = "unlock_level_removed";
        /** 旧包 .sk 里存在新版已不读取的键（tags/job_type/home_teleport 等）。 */
        public static final String DEAD_SK_KEYS = "dead_sk_keys";
        /** 工业 JSON 没有旧格式兜底解析器，旧配方在新版读不出来。 */
        public static final String INDUSTRY_NO_LEGACY = "industry_no_legacy";
    }

    /** 转换结果。 */
    public record Result(
            Path output,
            String packId,
            String packName,
            int skCount,
            int structureCount,
            int convertedStructureCount,
            int functionJsonCount,
            int upgradedShopCount,
            List<String> buildingIds,
            List<Notice> notices
    ) {
        /** 有没有需要用户知道的问题（不是失败，但可能影响体验）。 */
        public boolean hasNotes() {
            return !notices.isEmpty();
        }

        /** 给界面用的一句话摘要：只列前几条，避免提示框被刷屏。 */
        public String noticeSummary(int limit) {
            List<String> lines = new ArrayList<>();
            for (Notice notice : notices) {
                if (lines.size() >= limit) {
                    lines.add("…还有 " + (notices.size() - limit) + " 条");
                    break;
                }
                lines.add(notice.detail());
            }
            return String.join("\n", lines);
        }
    }

    /** 一个待转换的建筑：同一目录、同一名字的多个后缀归为一组。 */
    private static final class Building {
        private final String id;
        private final String category;
        private final String sourceStem;
        private final Map<String, String> files = new LinkedHashMap<>(); // 扩展名(小写) → 包内路径

        private Building(String id, String category, String sourceStem) {
            this.id = id;
            this.category = category;
            this.sourceStem = sourceStem;
        }

        private String file(String extension) {
            return files.get(extension);
        }
    }

    private LegacyPackConverter() {
    }

    /**
     * 转换一个旧包并写到 {@code output}（先写临时文件，全部校验通过再原子替换）。
     *
     * @param source    旧包路径
     * @param output    目标 ZIP 路径
     * @param converter 结构转换器；为 null 时不尝试转换非 NBT 结构（会跳过并提示）
     */
    public static Result convert(Path source, Path output, StructureNbtConverter converter)
            throws IOException {
        List<Notice> notices = new ArrayList<>();
        Map<String, ZipEntry> entries = new TreeMap<>();
        Map<String, String> entriesByLower = new LinkedHashMap<>();
        JsonObject metadata = new JsonObject();

        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = safeName(entry.getName());
                if (name == null) {
                    notices.add(new Notice(Notice.SKIPPED_DUPLICATE, "跳过不安全的路径：" + entry.getName()));
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (entriesByLower.containsKey(lower)) {
                    notices.add(new Notice(Notice.SKIPPED_DUPLICATE, "包内重复路径（后者被忽略）：" + name));
                    continue;
                }
                entriesByLower.put(lower, name);
                entries.put(name, entry);
            }

            metadata = readMetadata(zip, entries);
            if (metadata == null) {
                metadata = new JsonObject();
            }

            Map<String, Building> buildings = new LinkedHashMap<>();
            // 已占用的 id（按分类），用 NSUK 的宽松键比对：它忽略大小写与 _- 和空格，
            // 这里必须用同一套规则，否则会产出两个"看起来不同、NSUK 眼里相同"的建筑。
            Map<String, Set<String>> takenIds = new LinkedHashMap<>();
            String iconPath = null;
            String iconDepthKey = null;

            for (Map.Entry<String, ZipEntry> item : entries.entrySet()) {
                String name = item.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                if (isMetaFile(name)) {
                    continue;
                }
                if ("icon.png".equals(lower) || lower.endsWith("/icon.png")) {
                    // 取层级最浅的一个当包图标；新版清单里图标只能写在包根。
                    String depthKey = String.format("%03d", name.chars().filter(c -> c == '/').count());
                    if (iconPath == null || depthKey.compareTo(iconDepthKey) < 0) {
                        iconPath = name;
                        iconDepthKey = depthKey;
                    }
                    continue;
                }
                String suffix = extension(name);
                if (!isBuildingFile(suffix)) {
                    continue;
                }
                String stem = stemOf(name, suffix);
                String category = categoryFor(name);
                // 同一栋建筑的多个后缀必须落在同一个 id 上，所以先看这个主干是否已经建过组。
                Building existing = findByStem(buildings, category, stem);
                String id = existing != null
                        ? existing.id
                        : buildingId(stem, takenIds.getOrDefault(category, Set.of()));
                if (id.isBlank()) {
                    notices.add(new Notice(Notice.SKIPPED_DUPLICATE, "建筑文件名无法作为 id，已跳过：" + name));
                    continue;
                }
                Building building = existing;
                if (building == null) {
                    building = new Building(id, category, stem);
                    buildings.put(category + "/" + id, building);
                    takenIds.computeIfAbsent(category, ignored -> new LinkedHashSet<>()).add(relaxedKey(id));
                    if (!id.equals(stem)) {
                        notices.add(new Notice(Notice.RENAMED_ID, stem + " → " + id));
                    }
                }
                if (building.files.containsKey(suffix)) {
                    notices.add(new Notice(Notice.SKIPPED_DUPLICATE,
                            "同一建筑出现多个同名文件，保留先出现的：" + name));
                    continue;
                }
                building.files.put(suffix, name);
            }

            // 旧包允许缺 .sk（只有 nbt）：转换时会补一份最小 .sk，否则新版列表里看不到这栋建筑。
            // 完全没有结构文件的建筑则直接跳过——NSUK 找不到 <id>.nbt 会把它丢掉。
            List<Building> ordered = new ArrayList<>();
            for (Building building : buildings.values()) {
                if (building.file(".nbt") == null && structureKeyOf(building) == null) {
                    notices.add(new Notice(Notice.MISSING_STRUCTURE,
                            building.category + "/" + building.id + " 没有结构文件，已跳过"));
                    continue;
                }
                ordered.add(building);
            }
            ordered.sort(Comparator.comparing(building -> building.category + "/" + building.id));

            Files.createDirectories(output.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(),
                    "." + fileNameWithoutExtension(output) + "-", ".tmp");
            List<String> buildingIds = new ArrayList<>();
            int skCount = 0;
            int structureCount = 0;
            int convertedCount = 0;
            int jsonCount = 0;
            int shopCount = 0;
            try {
                try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary),
                        StandardCharsets.UTF_8)) {
                    for (Building building : ordered) {
                        String base = BUILDINGS_DIR + "/" + building.category + "/" + building.id;

                        // ① 结构：优先用 .nbt；旧格式转成 .nbt。
                        byte[] structureBytes = null;
                        String structure = building.file(".nbt");
                        if (structure != null) {
                            structureBytes = readAll(zip, entries.get(structure));
                        } else {
                            String legacyKey = structureKeyOf(building);
                            if (legacyKey != null && converter != null) {
                                try {
                                    structureBytes = converter.toStructureNbt(legacyKey,
                                            readAll(zip, entries.get(legacyKey)));
                                    convertedCount++;
                                } catch (IOException | RuntimeException exception) {
                                    notices.add(new Notice(Notice.SKIPPED_STRUCTURE,
                                            building.id + " 的结构转换失败（"
                                                    + exception.getMessage() + "），该建筑未写入"));
                                    continue;
                                }
                            } else if (legacyKey != null) {
                                notices.add(new Notice(Notice.SKIPPED_STRUCTURE,
                                        building.id + " 的结构是 " + extension(legacyKey)
                                                + "，没有可用的转换器，该建筑未写入"));
                                continue;
                            }
                        }
                        if (structureBytes == null) {
                            continue;
                        }
                        writeEntry(out, base + ".nbt", structureBytes);
                        structureCount++;
                        // id 只在建筑真的写进包以后才登记：转换失败被跳过的建筑不能出现在清单里。
                        buildingIds.add(building.category + "/" + building.id);

                        // ② 元数据：旧 .sk 原样保留，只做三处必要处理。
                        String sk = building.file(".sk");
                        String skText = sk == null ? minimalSk(building.id) : readText(zip, entries.get(sk));
                        String normalizedSk = normalizeSk(skText, building.id, notices);
                        normalizedSk = alignStructureLine(normalizedSk, building.id);
                        normalizedSk = guardUnlockLevel(normalizedSk, building.id, notices);
                        collectDeadSkKeys(normalizedSk, building.id, notices);
                        writeEntry(out, base + ".sk", normalizedSk.getBytes(StandardCharsets.UTF_8));
                        skCount++;

                        // ③ 功能定义：旧别名 JSON 改名成 <id>.json，旧商店字段升级成 offers。
                        for (String extension : functionJsonExtensions()) {
                            String jsonPath = building.file(extension);
                            if (jsonPath == null) {
                                continue;
                            }
                            String raw = readText(zip, entries.get(jsonPath));
                            JsonObject converted = LegacyShopDefinition.upgrade(raw,
                                    jsonPath, building.id, notices);
                            writeEntry(out, base + ".json", GSON.toJson(converted).getBytes(StandardCharsets.UTF_8));
                            jsonCount++;
                            if (LegacyShopDefinition.looksLikeLegacyShop(raw)) {
                                shopCount++;
                            } else if ("industry".equals(building.category)) {
                                // 工业 JSON 是最坏的一档：新版没有工业版"旧格式解析器"
                                // （jar 里只有 CommercialLegacyDefinitionParser），旧配方
                                // 读不出来，只能靠用户进编辑器重做。宁可说清楚，也别让人
                                // 以为转换把工厂也搬过来了。
                                notices.add(new Notice(Notice.INDUSTRY_NO_LEGACY,
                                        building.id + " 的工业定义没有被新版识别（旧配方格式），"
                                                + "文件已原样搬过去，需要进游戏用编辑器重做配方"));
                            }
                            break;
                        }
                    }

                    JsonObject manifest = manifest(metadata, source, buildingIds, notices);
                    writeEntry(out, "pack.json", GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
                    if (iconPath != null) {
                        writeEntry(out, "icon.png", readAll(zip, entries.get(iconPath)));
                    }
                }

                // 清单在写包时就定好了，这里重算会多出一条重复的依赖提示，所以从头再取一次幂等的副本。
                JsonObject finalManifest = manifest(metadata, source, buildingIds, new ArrayList<>());
                Result result = new Result(output, finalManifest.get("id").getAsString(),
                        finalManifest.get("name").getAsString(),
                        skCount, structureCount, convertedCount, jsonCount, shopCount,
                        List.copyOf(buildingIds), List.copyOf(notices));

                // 输出自检：能重新打开、pack.json 与建筑结构都在，才允许替换目标文件。
                // 建筑全被跳过时也必须失败：产出一个空包会让用户以为转换成功。
                try (ZipFile check = new ZipFile(temporary.toFile(), StandardCharsets.UTF_8)) {
                    if (check.getEntry("pack.json") == null) {
                        throw new IOException("输出包缺少 pack.json");
                    }
                    long structures = check.stream()
                            .filter(entry -> !entry.isDirectory())
                            .filter(entry -> entry.getName().startsWith(BUILDINGS_DIR + "/"))
                            .filter(entry -> entry.getName().endsWith(".nbt"))
                            .count();
                    if (structures == 0) {
                        throw new IOException("没有任何建筑被转换成功：" + skipSummary(notices));
                    }
                }
                Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return result;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    // ── 清单 ───────────────────────────────────────────────────────────

    /** 把"为什么没转成"的原因拼成一句人话，避免只报一个"失败"。 */
    private static String skipSummary(List<Notice> notices) {
        List<String> reasons = new ArrayList<>();
        for (Notice notice : notices) {
            if (Notice.SKIPPED_STRUCTURE.equals(notice.kind())
                    || Notice.MISSING_STRUCTURE.equals(notice.kind())) {
                reasons.add(notice.detail());
            }
        }
        if (reasons.isEmpty()) {
            return "请确认包内建筑都带有可转换的结构文件";
        }
        return String.join("；", reasons);
    }

    /**
     * 生成新版 {@code pack.json}。
     *
     * <p>字段名要按<b>旧版筑艺坊导出的真实元数据</b>来找：它写的是
     * {@code packId}/{@code packName}/{@code packVersion}（见旧版 {@code ExportManager
     * .createMetadataJson}），不是 {@code name}/{@code version}。只认后者的话，
     * 真实旧包转出来的包名会默默退化成文件名、版本永远停在 1.0.0。
     */
    private static JsonObject manifest(JsonObject metadata, Path source, List<String> buildingIds,
                                       List<Notice> notices) {
        JsonObject manifest = new JsonObject();
        String fallback = fileNameWithoutExtension(source);
        String name = firstText(metadata, "name", "packName", "pack_name", "title");
        String version = firstText(metadata, "version", "packVersion", "pack_version");
        manifest.addProperty("format", PackFormat.expected());
        manifest.addProperty("id", "converted." + sanitizeId(
                firstText(metadata, "packId", "pack_id", "id") == null
                        ? fallback
                        : firstText(metadata, "packId", "pack_id", "id")));
        manifest.addProperty("name", name == null ? fallback : name);
        manifest.addProperty("version", version == null ? "1.0.0" : version);
        manifest.addProperty("author", text(metadata, "author", ""));
        manifest.addProperty("description", text(metadata, "description", "由旧版建筑拓展包转换"));
        manifest.addProperty("generated", Instant.now().toString());
        JsonArray ids = new JsonArray();
        buildingIds.forEach(ids::add);
        manifest.add("buildingIds", ids);

        // 旧包的 mod 依赖清单：新版运行时不读它（PackVersionChecker 只要 version），
        // 但它记录了这座包用到了哪些 mod 的哪个版本，丢掉就找不回来了 —— 原样带着并提示。
        JsonArray dependencies = asArray(metadata.get("dependencies"));
        if (!dependencies.isEmpty()) {
            manifest.add("dependencies", dependencies.deepCopy());
            List<String> modIds = new ArrayList<>();
            for (JsonElement element : dependencies) {
                if (element.isJsonObject()) {
                    String modId = firstText(element.getAsJsonObject(), "modid", "modId", "mod_id");
                    if (modId != null) {
                        modIds.add(modId);
                    }
                }
            }
            notices.add(new Notice(Notice.DEPENDENCIES_KEPT,
                    "原包的依赖清单已原样写进 pack.json 的 dependencies（新版不读它，仅作记录）"
                            + (modIds.isEmpty() ? "" : "：" + String.join(", ", modIds))));
        }
        return manifest;
    }

    private static JsonObject readMetadata(ZipFile zip, Map<String, ZipEntry> entries) throws IOException {
        for (Map.Entry<String, ZipEntry> item : entries.entrySet()) {
            String name = item.getKey();
            if (!isMetaFile(name)) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(readText(zip, item.getValue()));
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (RuntimeException ignored) {
                // 元数据坏了不该让整包转不动：退回用文件名当包名。
            }
        }
        return null;
    }

    // ── .sk 处理 ───────────────────────────────────────────────────────

    /** 只清掉 {@code amount}/{@code price} 值里的货币后缀；其余行一字不动。 */
    static String normalizeSk(String text, String id, List<Notice> notices) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        for (String line : text.split("\\R", -1)) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                out.append(line).append('\n');
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if ("amount".equals(key) || "price".equals(key)) {
                String value = line.substring(colon + 1).trim();
                String stripped = value.replaceAll("[\\s元￥$¥]*$", "");
                if (!stripped.equals(value)) {
                    out.append(line, 0, colon + 1).append(' ').append(stripped).append('\n');
                    changed = true;
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        if (changed) {
            notices.add(new Notice(Notice.SHOP_UPGRADED, id + " 的金额已去掉货币单位"));
        }
        return out.toString();
    }

    /**
     * 把已存在的 {@code structure:} / {@code file:} 行对齐到实际写入的文件名。
     *
     * <p>只在旧包**自己写了**这一行时才改。旧版筑艺坊导出的 {@code .sk} 从来没有这一行
     * （结构文件固定叫 {@code <id>.nbt}），默认解析就是按主干找同名 {@code .nbt}；
     * 凭空补一行反而是多余的改动，还可能把别的解析器带偏。
     */
    static String alignStructureLine(String sk, String id) {
        if (sk == null || sk.isBlank()) {
            return minimalSk(id);
        }
        String[] lines = sk.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = lines[index].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if ("structure".equals(key) || "file".equals(key)) {
                lines[index] = lines[index].substring(0, colon + 1) + " " + id + ".nbt";
            }
        }
        return String.join("\n", lines);
    }

    /** 旧包连 .sk 都缺时补的最小元数据：只写显示名，不写结构行（默认按主干配对）。 */
    private static String minimalSk(String id) {
        return "name: " + id + "\n";
    }

    /**
     * 处理 {@code unlockLevel} 这个"整栋丢"的坑。
     *
     * <p>新版 {@code BuildingPackageCatalog.readUnlockLevel} 对非正整数直接抛
     * {@code IllegalArgumentException}，被 {@code scanCategory} 捕获后只留一行 warn，
     * <b>整栋建筑不进目录</b>。旧包写 {@code unlockLevel: 0} 是很自然的事（"不要等级"），
     * 搬过去就等于这栋建筑凭空消失。
     *
     * <p>所以非法值一律<b>删掉这一行</b>（缺省即 0，语义完全等价），而不是去猜一个等级。
     */
    static String guardUnlockLevel(String sk, String id, List<Notice> notices) {
        if (sk == null || sk.isBlank()) {
            return sk;
        }
        String[] lines = sk.split("\\R", -1);
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if ("unlocklevel".equals(key) || "unlock_level".equals(key)) {
                    String value = line.substring(colon + 1).trim();
                    if (!value.matches("[1-9][0-9]*")) {
                        removed = true;
                        continue;
                    }
                }
            }
            kept.add(line);
        }
        if (removed) {
            notices.add(new Notice(Notice.UNLOCK_LEVEL_REMOVED,
                    id + " 的 unlockLevel 不是正整数（新版会导致整栋建筑被丢弃），已删掉该行"));
        }
        return String.join("\n", kept);
    }

    /** 旧包写在 .sk 里、新版已经不读的键；发现就提示，避免用户以为它们还在生效。 */
    private static void collectDeadSkKeys(String sk, String id, List<Notice> notices) {
        if (sk == null || sk.isBlank()) {
            return;
        }
        List<String> dead = new ArrayList<>();
        for (String line : sk.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (DEAD_SK_KEYS.contains(key) && !dead.contains(key)) {
                dead.add(key);
            }
        }
        if (!dead.isEmpty()) {
            notices.add(new Notice(Notice.DEAD_SK_KEYS,
                    id + " 的 " + String.join("/", dead) + " 新版不再读取，已原样留在 .sk 里备用"));
        }
    }

    // ── 路径与归档规则 ─────────────────────────────────────────────────

    /** 包根目录的清单类文件：不参与建筑分组，也不进新包。 */
    public static boolean isMetaFile(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.contains("/")) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.equals(LegacyPackDetector.MANIFEST)
                || lower.equals("metadata.json")
                || lower.equals("index.json")
                || lower.equals("_files.txt");
    }

    /** 旧格式结构文件返回扩展名；{@code .nbt} 与其它返回 null。 */
    static String structureKind(String name) {
        String suffix = extension(name);
        if (LegacyPackDetector.NON_NBT_STRUCTURES.contains(suffix)) {
            return suffix;
        }
        return null;
    }

    private static List<String> functionJsonExtensions() {
        List<String> all = new ArrayList<>(LegacyPackDetector.JSON_ALIASES);
        all.add(".json");
        return all;
    }

    private static boolean isBuildingFile(String suffix) {
        return suffix.equals(".sk") || suffix.equals(".nbt")
                || LegacyPackDetector.JSON_ALIASES.contains(suffix)
                || suffix.equals(".json")
                || LegacyPackDetector.NON_NBT_STRUCTURES.contains(suffix);
    }

    /**
     * 这栋建筑用来出结构的源文件：优先 {@code .nbt}，其次旧格式。
     *
     * <p>返回 null 表示这栋建筑没有任何结构文件（会被跳过）。
     */
    private static String structureKeyOf(Building building) {
        String nbt = building.file(".nbt");
        if (nbt != null) {
            return nbt;
        }
        for (String extension : LegacyPackDetector.NON_NBT_STRUCTURES) {
            String legacy = building.file(extension);
            if (legacy != null) {
                return legacy;
            }
        }
        return null;
    }

    /** 建筑 id：与编辑器导出的约束一致（小写字母、数字、下划线、连字符）。 */
    static String sanitizeId(String stem) {
        if (stem == null) {
            return "";
        }
        String ascii = java.text.Normalizer.normalize(stem, java.text.Normalizer.Form.NFKD);
        String cleaned = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\-]+", "_")
                .replaceAll("^_+|_+$", "");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /**
     * 建筑 id：旧包的文件名主干（蛇形 ASCII）原样保留，只处理真正非法的名字。
     *
     * <p>旧包的建筑文件名本身就是 {@code small_house} 这种蛇形 id，中文只出现在
     * {@code .sk} 的 {@code name:} 行里（那是显示名，不进文件名）。所以正常路径上
     * 这里<b>什么都不改</b>：id 保持原样意味着 {@code .sk} 里可能写着的
     * {@code structure: small_house.nbt} 依旧对得上，编辑器也能重新导入这份包。
     *
     * <p>只有主干不是合法 ASCII id 时（大写、空格、中文、纯标点）才退化：
     * 先试 NFKD 折叠（{@code Café} → {@code cafe}），再不行才用确定性的短摘要名。
     * 退化出来的名字必须检查过不会与同分类里别的建筑撞车——NSUK 按"去掉 {@code _-}
     * 后的小写名"配对文件，撞车会让两栋建筑互相抢结构。
     */
    static String buildingId(String stem, Set<String> unavailable) {
        if (stem == null) {
            return "";
        }
        Set<String> taken = unavailable == null ? Set.of() : unavailable;
        if (isSafeId(stem) && !taken.contains(relaxedKey(stem))) {
            return stem;
        }
        String folded = java.text.Normalizer.normalize(stem, java.text.Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (folded.length() > 40) {
            folded = folded.substring(0, 40);
        }
        if (!folded.isBlank() && !taken.contains(relaxedKey(folded))) {
            return folded;
        }
        String digest = Integer.toHexString(stem.hashCode()).replace("-", "n");
        String fallback = "building_" + digest;
        int index = 2;
        while (taken.contains(relaxedKey(fallback))) {
            fallback = "building_" + digest + "_" + index;
            index++;
        }
        return fallback;
    }

    /** id 是否是合法、且不会被 NSUK 的宽松匹配搞混的文件名主干。 */
    private static boolean isSafeId(String id) {
        if (id.isEmpty() || id.length() > 64) {
            return false;
        }
        return id.matches("[a-z0-9_]+");
    }

    /**
     * NSUK 比对文件名的宽松键：小写并去掉 {@code _}、{@code -} 和空格
     * （{@code BuildingPackageCatalog.relaxedFileKey}）。
     *
     * <p>撞名判定必须用同一套规则，否则 {@code small_house} 与 {@code smallhouse}
     * 会被当成两个不同建筑，NSUK 却只认其中一个。
     */
    private static String relaxedKey(String value) {
        return value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    /**
     * 同一分类下是否已经有这个主干建好的组。
     *
     * <p>用它把一栋建筑的 {@code .sk}/{@code .nbt}/{@code .json} 归到同一个（可能已被
     * 退化改名的）id 上，而不是每个后缀各算一次 id。
     */
    private static Building findByStem(Map<String, Building> buildings, String category, String stem) {
        for (Building building : buildings.values()) {
            if (building.category.equals(category) && building.sourceStem.equals(stem)) {
                return building;
            }
        }
        return null;
    }

    /** 分类：取路径里最靠近文件的那一级能识别的目录名；认不出来算 other。 */
    static String categoryFor(String name) {
        String normalized = name.replace('\\', '/');
        String[] parts = normalized.split("/");
        for (int index = parts.length - 2; index >= 0; index--) {
            String mapped = CATEGORY_ALIASES.get(parts[index].toLowerCase(Locale.ROOT));
            if (mapped != null) {
                return mapped;
            }
        }
        return "other";
    }

    /** 去掉 {@code .meta.json} 这类双后缀，返回建筑名主干。 */
    static String stemOf(String name, String suffix) {
        String file = name.substring(name.lastIndexOf('/') + 1);
        String lower = file.toLowerCase(Locale.ROOT);
        for (String alias : LegacyPackDetector.JSON_ALIASES) {
            if (lower.endsWith(alias)) {
                return file.substring(0, file.length() - alias.length());
            }
        }
        if (!suffix.isEmpty() && lower.endsWith(suffix)) {
            return file.substring(0, file.length() - suffix.length());
        }
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /**
     * 扩展名（含双后缀别名），全部小写。
     *
     * <p>先按双后缀别名整体匹配，再退回最后一个点。不区分双后缀会把
     * {@code shop.meta.json} 认成 {@code .json} 而漏掉改名规则。
     */
    static String extension(String name) {
        String file = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        for (String alias : LegacyPackDetector.JSON_ALIASES) {
            if (file.endsWith(alias)) {
                return alias;
            }
        }
        int dot = file.lastIndexOf('.');
        // 前导点（.sk 这种没有主干的文件名）不算扩展名。
        return dot > 0 ? file.substring(dot) : "";
    }

    /** 规范化包内路径；含 {@code ..}、绝对路径、空段时返回 null。 */
    static String safeName(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("\u0000")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return null;
            }
            parts.add(part);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private static String fileNameWithoutExtension(Path path) {
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        String text = value.getAsString();
        return text == null || text.isBlank() ? fallback : text.trim();
    }

    /** 按顺序取第一个有值的字符串键；都没有返回 null。 */
    private static String firstText(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive()) {
                continue;
            }
            String text = value.getAsString();
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    /** 取数组成员；不是数组就返回空数组。 */
    private static JsonArray asArray(JsonElement value) {
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    // ── ZIP 读写 ───────────────────────────────────────────────────────

    private static byte[] readAll(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry == null) {
            return new byte[0];
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static String readText(ZipFile zip, ZipEntry entry) throws IOException {
        byte[] bytes = readAll(zip, entry);
        // 旧包可能带 UTF-8 BOM（Windows 记事本另存为 UTF-8 就会加）。
        // 必须在这里剥掉：NSUK 读 .sk 时**不剥** BOM（它只在 _files.txt 上剥），
        // 带 BOM 的首行 \uFEFFname: 会让 name 这个键认不出来、建筑名退化成文件名。
        // 这条注释以前写成"NSUK 自己会剥"，是错的。
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    /** 供结构转换器实现复用的输入流包装。 */
    static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}