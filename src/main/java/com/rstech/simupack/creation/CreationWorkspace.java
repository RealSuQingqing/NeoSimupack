package com.rstech.simupack.creation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.pack.BuildingPackManager;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 拓展包创作工程的工作区。
 *
 * <p>工程目录固定在 {@code config/simupack/workspace/<工程 id>/}，
 * 与最终导出的 ZIP 布局一一对应：
 * <pre>
 * &lt;工程&gt;/project.json                      工程元数据（不进入 ZIP）
 * &lt;工程&gt;/buildings/&lt;分类&gt;/&lt;建筑&gt;.sk
 * &lt;工程&gt;/buildings/&lt;分类&gt;/&lt;建筑&gt;.nbt       可暂缺
 * &lt;工程&gt;/buildings/&lt;分类&gt;/&lt;建筑&gt;.json      可暂缺
 * &lt;工程&gt;/lang/&lt;语言&gt;.json
 * </pre>
 */
public final class CreationWorkspace {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String PROJECT_FILE = "project.json";
    private static final String BUILDINGS_DIR = "buildings";
    private static final String LANG_DIR = "lang";

    private CreationWorkspace() {
    }

    /** 工作区根目录：{@code config/simupack/workspace}。 */
    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("simupack").resolve("workspace").toAbsolutePath().normalize();
    }

    public static Path projectDirectory(String projectId) {
        return root().resolve(projectId);
    }

    // ── 工程列表 ───────────────────────────────────────────────────────

    /** 列出工作区中所有已保存的工程 id。 */
    public static List<String> listProjects() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> projects = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve(PROJECT_FILE)))
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .forEach(projects::add);
        } catch (IOException exception) {
            SimuPackMod.LOGGER.error("无法列出创作工程: {}", root, exception);
        }
        return List.copyOf(projects);
    }

    // ── 读写工程 ───────────────────────────────────────────────────────

    public static CreationProject load(String projectId) {
        Path file = projectDirectory(projectId).resolve(PROJECT_FILE);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return CreationProject.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception exception) {
            SimuPackMod.LOGGER.error("无法读取创作工程: {}", file, exception);
            return new CreationProject(projectId, projectId);
        }
    }

    /** 保存工程：写出 project.json、各建筑的 .sk / .json / .nbt 与语言文件。 */
    public static void save(CreationProject project) throws IOException {
        Path directory = projectDirectory(project.id());
        Files.createDirectories(directory);

        Files.writeString(directory.resolve(PROJECT_FILE),
                GSON.toJson(project.toJson()), StandardCharsets.UTF_8);

        for (CreationBuilding building : project.buildings()) {
            Path folder = building.folder(directory);
            Files.createDirectories(folder);
            Files.writeString(building.skPath(directory), building.toSkText(), StandardCharsets.UTF_8);

            if (!building.jsonConfig().isBlank()) {
                Files.writeString(building.jsonPath(directory), building.jsonConfig(), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(building.jsonPath(directory));
            }
        }

        // 清掉已删除建筑留下的文件。删建筑只从 project.json 里摘掉，磁盘上的
        // <id>.sk/.nbt/.json 不会自己消失 —— 留着会让工程目录与导出的 ZIP 不一致，
        // 下次导入这个工程（importZip 按 .sk 扫描）时"删掉的建筑"又回来了。
        pruneDeletedBuildings(directory, project);

        // 多语言编辑已移除：不再生成 lang/<语言>.json。
        // 若旧工程残留语言文件，一并清掉，避免导出包里出现与内容不符的翻译。
        deleteRecursively(directory.resolve(LANG_DIR));
    }

    /**
     * 删掉 {@code buildings/} 下不再属于本工程的建筑文件，并清理随之空掉的目录。
     *
     * <p>判定按「分类 + 文件名主干」：只处理 {@code .sk}/{@code .nbt}/{@code .json}
     * 这三种扩展名，其余文件（用户自己放的说明、截图等）一律不动 ——
     * 清理是为了让工程自洽，不是为了接管用户的目录。
     */
    private static void pruneDeletedBuildings(Path directory, CreationProject project) {
        Path buildings = directory.resolve(BUILDINGS_DIR);
        if (!Files.isDirectory(buildings)) {
            return;
        }
        Set<String> live = new HashSet<>();
        for (CreationBuilding building : project.buildings()) {
            live.add(building.category() + "/" + building.id());
        }

        try (var stream = Files.walk(buildings)) {
            List<Path> stale = stream
                    .filter(Files::isRegularFile)
                    .filter(CreationWorkspace::isBuildingFile)
                    .filter(file -> {
                        Path relative = buildings.relativize(file);
                        if (relative.getNameCount() < 2) {
                            return false;
                        }
                        String category = relative.getName(0).toString();
                        String stem = stripExtension(relative.getFileName().toString());
                        return !live.contains(category + "/" + stem);
                    })
                    .toList();
            for (Path file : stale) {
                Files.deleteIfExists(file);
                SimuPackMod.LOGGER.debug("清理已删除建筑的文件 {}", file);
            }
        } catch (IOException exception) {
            // 清理失败不该让保存失败：文件多留一个只是不够干净，工程本身是好的。
            SimuPackMod.LOGGER.warn("无法清理工程目录 {} 里的残留文件", directory, exception);
        }
        deleteEmptyDirectories(buildings);
    }

    private static boolean isBuildingFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sk") || name.endsWith(".nbt") || name.endsWith(".json");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /** 自底向上删掉空目录（保留 {@code buildings/} 本身）。 */
    private static void deleteEmptyDirectories(Path root) {
        try (var stream = Files.walk(root)) {
            List<Path> directories = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path directory : directories) {
                try (var children = Files.list(directory)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        } catch (IOException exception) {
            SimuPackMod.LOGGER.debug("清理空目录时出错 {}", root, exception);
        }
    }

    public static void delete(String projectId) {
        deleteRecursively(projectDirectory(projectId));
    }

    /**
     * 重命名工程目录（改工程 id 时必须同步）。
     *
     * <p>否则 {@code save()} 会写到新 id 的目录，旧目录原地留下，变成两份工程。
     *
     * @return 成功返回 true；目标已存在或源不存在返回 false
     */
    public static boolean renameProject(String oldId, String newId) {
        if (oldId == null || newId == null || oldId.equals(newId)) {
            return true;
        }
        Path from = projectDirectory(oldId);
        Path to = projectDirectory(newId);
        if (!Files.isDirectory(from) || Files.exists(to)) {
            return false;
        }
        try {
            Files.move(from, to);
            return true;
        } catch (IOException exception) {
            SimuPackMod.LOGGER.error("重命名工程 {} -> {} 失败", oldId, newId, exception);
            return false;
        }
    }

    /**
     * 移动建筑到另一个分类。
     *
     * <p>分类体现在目录上（{@code buildings/&lt;分类&gt;/}），所以改分类必须把
     * {@code .sk / .nbt / .json} 一起移过去，否则 NSUK 找不到这个建筑。
     *
     * @return 成功返回 true
     */
    public static boolean moveBuilding(CreationProject project, CreationBuilding building, String newCategory) {
        if (project == null || building == null || newCategory == null || newCategory.isBlank()) {
            return false;
        }
        String oldCategory = building.category();
        if (oldCategory.equals(newCategory)) {
            return true;
        }
        Path directory = projectDirectory(project.id());
        Path from = building.folder(directory);
        String id = building.id();
        building.setCategory(newCategory);
        Path to = building.folder(directory);
        try {
            Files.createDirectories(to);
            moveIfExists(from.resolve(id + ".sk"), to.resolve(id + ".sk"));
            moveIfExists(from.resolve(id + ".nbt"), to.resolve(id + ".nbt"));
            moveIfExists(from.resolve(id + ".json"), to.resolve(id + ".json"));
            save(project);
            return true;
        } catch (IOException exception) {
            SimuPackMod.LOGGER.error("移动建筑 {} 到分类 {} 失败", id, newCategory, exception);
            return false;
        }
    }

    /**
     * 同步重命名建筑的 id。
     *
     * <p>NSUK 按文件名主干配对 {@code .sk} 与 {@code .nbt}，所以改 id 必须把
     * {@code .sk / .nbt / .json} 一起改名，否则结构文件会和 {@code .sk} 失配，
     * 建筑在游戏里就变成没有结构。
     *
     * @return 成功返回 true；重名或 id 非法返回 false
     */
    public static boolean renameBuilding(CreationProject project, CreationBuilding building, String newId) {
        if (project == null || building == null) {
            return false;
        }
        String cleaned = sanitizeId(newId);
        String oldId = building.id();
        if (cleaned.equals(oldId)) {
            return true;
        }
        // 全包唯一，不是同分类唯一 —— 见 CreationProject.findBuildingById 的注释。
        if (!isValidId(cleaned) || project.findBuildingById(cleaned) != null) {
            return false;
        }

        Path directory = projectDirectory(project.id());
        Path folder = building.folder(directory);
        Path oldSk = folder.resolve(oldId + ".sk");
        Path oldNbt = folder.resolve(oldId + ".nbt");
        Path oldJson = folder.resolve(oldId + ".json");

        building.setId(cleaned);
        try {
            // 先把旧文件移到新名字，再交给 save() 覆盖内容，避免中途失败留下两份不一致的文件。
            moveIfExists(oldSk, building.skPath(directory));
            moveIfExists(oldNbt, building.nbtPath(directory));
            moveIfExists(oldJson, building.jsonPath(directory));
            save(project);
            return true;
        } catch (IOException exception) {
            SimuPackMod.LOGGER.error("重命名建筑 {} -> {} 失败", oldId, cleaned, exception);
            return false;
        }
    }

    private static void moveIfExists(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from) && !from.equals(to)) {
            Files.createDirectories(to.getParent());
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── 校验 ───────────────────────────────────────────────────────────

    /** 返回工程的问题列表；空列表表示可以导出。「缺少结构文件」属于提示而非错误。 */
    public static List<String> validate(CreationProject project) {
        List<String> problems = new ArrayList<>();
        if (project.id().isBlank()) {
            problems.add("工程 id 不能为空");
        } else if (!project.id().matches("[a-z0-9_\\-]+")) {
            problems.add("工程 id 只能包含小写字母、数字、下划线与连字符：" + project.id());
        }
        if (project.name().isBlank()) {
            problems.add("包名称不能为空");
        }
        if (project.buildings().isEmpty()) {
            problems.add("工程中还没有建筑");
        }

        Map<String, String> seen = new LinkedHashMap<>();
        for (CreationBuilding building : project.buildings()) {
            String label = building.displayName();
            if (building.id().isBlank()) {
                problems.add("存在未命名的建筑（分类 " + building.category() + "）");
                continue;
            }
            if (!building.id().matches("[a-z0-9_\\-]+")) {
                problems.add(label + "：建筑 id 只能包含小写字母、数字、下划线与连字符");
            }
            String key = building.key();
            if (seen.putIfAbsent(key, label) != null) {
                problems.add("建筑 id 重复：" + key);
            }
            if (building.skField("name").isBlank()) {
                problems.add(label + "：.sk 的 name 为空，游戏内显示名会退化为文件名");
            }
            if (!building.hasStructure()) {
                problems.add(label + "：尚未关联结构文件（可后续补充）");
            }
        }
        return List.copyOf(problems);
    }

    // ── 导出为可分发 ZIP ───────────────────────────────────────────────

    /**
     * 导出工程到 {@code simukraftbuilding/<工程 id>.zip}，然后刷新建筑目录。
     *
     * @return 生成的 ZIP 路径
     */
    public static Path export(CreationProject project) throws IOException {
        Path target = BuildingPackManager.rootDirectory().resolve(project.id() + ".zip");
        Path written = writePack(project, target);
        // 只有真正写进游戏建筑目录的包才需要让 NSUK 重新扫描。
        BuildingPackManager.reload();
        SimuPackMod.LOGGER.info("已导出拓展包 {} -> {}", project.id(), written);
        return written;
    }

    /**
     * 把工程写成包 ZIP，落在指定路径上。<b>不</b>碰游戏目录（{@code FMLPaths}），
     * 也不触发重新扫描；结构文件从工程目录里找。
     */
    public static Path writePack(CreationProject project, Path target) throws IOException {
        return writePack(project, target,
                base -> projectDirectory(project.id()).resolve(base + ".nbt"));
    }

    /**
     * 同上，但由调用方决定「某个建筑的 {@code .nbt} 在哪儿」。
     *
     * <p>把结构来源做成参数，是为了让「导出的包到底长什么样」能<b>脱机跑测试</b>：
     * 工程目录要靠 {@code FMLPaths.CONFIGDIR} 才定位得到，测试里没有那个类。
     * 与 {@code LegacyPackConverter.convert(source, output, converter)} 注入结构转换器
     * 是同一套思路 —— 包格式是与 NSUK 的契约，靠人工比对官方包太容易走偏。
     *
     * <p>注意 {@code structureOf} 只在<b>真的需要判断</b>时才调用：没有结构的建筑
     * （{@code hasStructure} 为假）连问都不问，避免为了一个必然不存在的结果去做路径解析。
     */
    public static Path writePack(CreationProject project, Path target,
                                 Function<String, Path> structureOf) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            writeEntry(zip, "pack.json", GSON.toJson(packJson(project)));

            for (CreationBuilding building : project.buildings()) {
                String base = BUILDINGS_DIR + "/" + building.category() + "/" + building.id();
                writeEntry(zip, base + ".sk", building.toSkText());
                if (!building.jsonConfig().isBlank()) {
                    // 导出时剥掉编辑器元数据：NSUK 虽然会忽略未知键，但发布出去的包不该带这些。
                    writeEntry(zip, base + ".json", exportJson(building.jsonConfig()));
                }
                if (!building.hasStructure()) {
                    continue;
                }
                Path structure = structureOf.apply(base);
                if (structure != null && Files.isRegularFile(structure)) {
                    zip.putNextEntry(new ZipEntry(base + ".nbt"));
                    Files.copy(structure, zip);
                    zip.closeEntry();
                }
            }
            // 多语言编辑已移除，导出的包里不再写 lang/ 目录。
        }
        return target;
    }

    /**
     * 导出用的 JSON：去掉编辑器私有键（动作链布局等）。
     *
     * <p>单独抽成静态方法是为了能脱离文件系统做回归测试。
     */
    public static String exportJson(String jsonConfig) {
        if (jsonConfig == null || jsonConfig.isBlank()) {
            return jsonConfig;
        }
        try {
            com.google.gson.JsonElement parsed =
                    com.google.gson.JsonParser.parseString(jsonConfig);
            if (!parsed.isJsonObject()) {
                return jsonConfig;
            }
            com.google.gson.JsonObject object = parsed.getAsJsonObject();
            object.remove(com.rstech.simupack.creation.json.BuildingJsonDocument.EDITOR_LAYOUT_KEY);
            return GSON.toJson(object);
        } catch (RuntimeException exception) {
            // 解析不了就原样写出：宁可多带一个键，也不要因为导出把一个坏掉的配置换成空文件。
            return jsonConfig;
        }
    }

    /**
     * 包的 {@code pack.json}，字段与顺序<b>照抄 NSUK 官方包</b>：
     * <pre>{"format":2,"id":...,"name":...,"version":...,"author":...,"description":...}</pre>
     *
     * <p>{@code format} 的取值由 {@link PackFormat} 决定（1.20.1=1、1.21.1=2、26.3=3），
     * 本版本线就是 2 —— 官方 1.21.1 的 {@code official_building.zip} 里也是
     * {@code "format":2}。NSUK 2.2.0 的加载器其实只读 {@code version}
     * （{@code BuildingPackVersionChecker}），但包元数据是给整个生态看的，
     * 写错格式号别的工具就判错版本线。
     *
     * <p>{@code format} 与工程内部的 {@link CreationProject#FORMAT_VERSION}
     * 是两件事：前者是<b>包格式</b>（对 NSUK 的契约），后者是<b>工程文件格式</b>
     * （我们自己的 {@code project.json} 用），所以不能互相代替。
     */
    private static JsonObject packJson(CreationProject project) {
        JsonObject json = new JsonObject();
        json.addProperty("format", PackFormat.expected());
        json.addProperty("id", project.id());
        json.addProperty("name", project.name());
        json.addProperty("version", project.version());
        json.addProperty("author", project.author());
        json.addProperty("description", project.description());
        return json;
    }

    // ── 从已有 ZIP 导入为工程 ──────────────────────────────────────────

    /** 把 {@code simukraftbuilding} 下的既有 ZIP 读入工作区，便于二次编辑。 */
    public static CreationProject importZip(Path zipPath, String projectId) throws IOException {
        CreationProject project = new CreationProject(projectId, projectId);

        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry manifest = zip.getEntry("pack.json");
            if (manifest != null) {
                try (InputStream input = zip.getInputStream(manifest);
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    project.setName(json.has("name") ? json.get("name").getAsString() : projectId);
                    project.setVersion(json.has("version") ? json.get("version").getAsString() : "1.0.0");
                    project.setAuthor(json.has("author") ? json.get("author").getAsString() : "");
                    project.setDescription(json.has("description") ? json.get("description").getAsString() : "");
                }
            }

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                // 多语言已移除：导入时忽略 zip 内的 lang/ 目录。
                if (name.startsWith(BUILDINGS_DIR + "/") && name.endsWith(".sk")) {
                    String relative = name.substring(BUILDINGS_DIR.length() + 1);
                    String category = relative.substring(0, relative.indexOf('/'));
                    String buildingId = relative.substring(relative.indexOf('/') + 1,
                            relative.length() - ".sk".length());
                    CreationBuilding building = new CreationBuilding(buildingId, category);
                    building.applySkText(readText(zip, entry));
                    building.setHasStructure(zip.getEntry(
                            BUILDINGS_DIR + "/" + category + "/" + buildingId + ".nbt") != null);
                    ZipEntry config = zip.getEntry(
                            BUILDINGS_DIR + "/" + category + "/" + buildingId + ".json");
                    if (config != null) {
                        building.setJsonConfig(readText(zip, config));
                    }
                    project.buildings().add(building);
                }
            }
        }

        save(project);
        return project;
    }

    // ── 内部工具 ───────────────────────────────────────────────────────

    private static String readText(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 目录被占用时忽略，下次保存会覆盖。
                }
            });
        } catch (IOException exception) {
            SimuPackMod.LOGGER.warn("无法清理目录 {}", path, exception);
        }
    }

    /** 校验工程 id 是否可作为文件名。 */
    public static boolean isValidId(String id) {
        return id != null && id.matches("[a-z0-9_\\-]+");
    }

    /** 把任意输入规范化为合法 id。 */
    public static String sanitizeId(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\-]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
