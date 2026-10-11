package com.rstech.simupack.creation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出的包必须与 NSUK 官方包<b>同构</b>。
 *
 * <p>这条来自真实反馈：导出的包 NSUK 读不对、而且删掉的建筑还会出现在包里。
 * 包格式是与 NSUK 的契约，但此前<b>没有任何测试覆盖 {@code export()} 本身</b> ——
 * 只有「剥编辑器元数据」那个纯函数被测过，于是格式跑偏了也没人发现。
 *
 * <p>对照物是官方包解包样本 {@code build/sk_keys/official_extracted/}，其
 * {@code pack.json} 为：
 * {@code {"format":2,"id":...,"name":...,"version":...,"author":...,"description":...}}
 *
 * <p>全部脱机：走 {@code CreationWorkspace.writePack(project, target)}（不碰
 * {@code FMLPaths.GAMEDIR}），与 {@code LegacyPackConverter.convert(source, output, ...)}
 * 同一套思路。
 */
class CreationWorkspaceExportTest {

    private static final Path OFFICIAL = Path.of("build/sk_keys/official_extracted");

    private static CreationBuilding building(String id, String category) {
        CreationBuilding building = new CreationBuilding(id, category);
        building.setSkField("name", "测试建筑 " + id);
        building.setSkField("author", "测试作者");
        building.setSkField("size", "4x5x6");
        building.setSkField("amount", "12.50");
        building.setSkField("description", "描述");
        building.setSkField("unlockLevel", "3");
        building.setHasStructure(true);
        return building;
    }

    private static CreationProject project(String id, CreationBuilding... buildings) {
        CreationProject project = new CreationProject(id, "测试包");
        project.setVersion("1.0.0");
        project.setAuthor("作者");
        project.setDescription("说明");
        project.buildings().addAll(List.of(buildings));
        return project;
    }

    /**
     * 导出到一个临时文件，结构来源一律注入。
     *
     * <p>生产路径的默认结构来源是 {@code CreationWorkspace.projectDirectory()}，
     * 那个要 {@code FMLPaths.CONFIGDIR}，测试里没有这个类；所以这里全部走可注入的重载，
     * 顺带把「结构在哪」这件事在测试里变成显式的。
     */
    private static void write(CreationProject project, Path target, Map<String, Path> structures)
            throws IOException {
        CreationWorkspace.writePack(project, target,
                base -> structures == null ? null : structures.get(base));
    }

    private static void write(CreationProject project, Path target) throws IOException {
        write(project, target, null);
    }

    private static JsonObject packJson(ZipFile zip) throws IOException {
        String text = read(zip, "pack.json");
        assertNotNull(text, "包里没有 pack.json，NSUK 根本不会认这个包");
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (var input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> entryNames(ZipFile zip) {
        List<String> names = new ArrayList<>();
        zip.entries().asIterator().forEachRemaining(entry -> names.add(entry.getName()));
        return names;
    }

    @Test
    @DisplayName("pack.json 与官方包同构：键名与顺序都是 format/id/name/version/author/description")
    void packJsonMatchesTheOfficialShape(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("my_pack.zip");
        write(project("my_pack", building("house", "residential")), out);

        try (ZipFile zip = new ZipFile(out.toFile())) {
            JsonObject json = packJson(zip);
            assertEquals(List.of("format", "id", "name", "version", "author", "description"),
                    new ArrayList<>(json.keySet()), "pack.json 的键名或顺序与官方包不一致");

            // format 必须是数字 2（1.21.1 线的格式号）；官方包里没有 formatVersion 这个键。
            assertTrue(json.get("format").isJsonPrimitive(), "format 必须是数字");
            assertEquals(PackFormat.FORMAT_1_21_1, json.get("format").getAsInt());
            assertEquals(2, json.get("format").getAsInt(), "1.21.1 线写的是 format:2");
            assertEquals(PackFormat.expected(), json.get("format").getAsInt(),
                    "导出必须用本版本线期望的格式号");
            assertFalse(json.has("formatVersion"),
                    "官方包没有 formatVersion 这个键；写它等于自造格式");

            assertEquals("my_pack", json.get("id").getAsString());
            assertEquals("测试包", json.get("name").getAsString());
            assertEquals("1.0.0", json.get("version").getAsString());
            assertEquals("作者", json.get("author").getAsString());
            assertEquals("说明", json.get("description").getAsString());
        }
    }

    @Test
    @DisplayName("如果官方包样本在，逐键比对：我们写的键官方都有")
    void everyKeyWeWriteAlsoExistsInTheOfficialPack(@TempDir Path dir) throws IOException {
        Path officialPack = OFFICIAL.resolve("pack.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(officialPack),
                "缺少官方包样本：" + officialPack);
        JsonObject official = JsonParser.parseString(
                Files.readString(officialPack, StandardCharsets.UTF_8)).getAsJsonObject();

        Path out = dir.resolve("mine.zip");
        write(project("mine", building("house", "residential")), out);
        try (ZipFile zip = new ZipFile(out.toFile())) {
            for (String key : packJson(zip).keySet()) {
                assertTrue(official.has(key),
                        "我们写了官方包里不存在的键「" + key + "」，别的工具按官方格式解析会认不出来");
            }
            assertEquals(official.keySet(), packJson(zip).keySet(), "键集合应当完全一致");
        }
    }

    @Test
    @DisplayName("目录结构：buildings/<分类>/<id>.sk|.nbt|.json，与官方包一致")
    void zipLayoutMatchesTheOfficialLayout(@TempDir Path dir) throws IOException {
        CreationProject project = project("layout",
                building("house", "residential"),
                building("kiln", "industry"));
        project.findBuilding("industry", "kiln").setJsonConfig("{\"recipes\":[]}");

        // 结构来源注入进来，不去碰 FMLPaths.CONFIGDIR（测试里没有那个类）。
        Map<String, Path> structures = new HashMap<>();
        Path nbt = dir.resolve("kiln.nbt");
        Files.write(nbt, new byte[]{1, 2, 3});
        structures.put("buildings/industry/kiln", nbt);

        Path out = dir.resolve("layout.zip");
        write(project, out, structures);

        try (ZipFile zip = new ZipFile(out.toFile())) {
            List<String> names = entryNames(zip);
            assertTrue(names.contains("pack.json"), names.toString());
            assertTrue(names.contains("buildings/residential/house.sk"), names.toString());
            assertTrue(names.contains("buildings/industry/kiln.sk"), names.toString());
            assertTrue(names.contains("buildings/industry/kiln.nbt"), names.toString());
            assertTrue(names.contains("buildings/industry/kiln.json"), names.toString());
            // 没有结构的建筑不该凭空多出一个 .nbt。
            assertFalse(names.contains("buildings/residential/house.nbt"), names.toString());
            // 旧版曾写出 lang/ 与编辑器私有文件；都不该出现在发布包里。
            assertFalse(names.stream().anyMatch(name -> name.startsWith("lang/")), names.toString());
            assertFalse(names.contains("project.json"), names.toString());
        }
    }

    @Test
    @DisplayName("hasStructure 为假的建筑：「找结构」这一步都不该被调用")
    void structureLookupIsSkippedWhenFlagIsFalse(@TempDir Path dir) throws IOException {
        CreationBuilding noStructure = building("plain", "residential");
        noStructure.setHasStructure(false);
        List<String> asked = new ArrayList<>();

        Path out = dir.resolve("nostructure.zip");
        CreationWorkspace.writePack(project("nostructure", noStructure), out, base -> {
            asked.add(base);
            return null;
        });

        assertTrue(asked.isEmpty(), "没有结构的建筑不该去问结构在哪：" + asked);
        try (ZipFile zip = new ZipFile(out.toFile())) {
            assertTrue(entryNames(zip).contains("buildings/residential/plain.sk"));
            assertFalse(entryNames(zip).contains("buildings/residential/plain.nbt"));
        }
    }

    @Test
    @DisplayName("删掉的建筑不能出现在包里（重新导出也不该复活）")
    void deletedBuildingsAreNotExported(@TempDir Path dir) throws IOException {
        CreationProject project = project("pruned",
                building("keep", "residential"),
                building("drop", "residential"),
                building("also_keep", "commercial"));
        Path out = dir.resolve("pruned.zip");

        // 第一次导出：三个都在。
        write(project, out);
        try (ZipFile zip = new ZipFile(out.toFile())) {
            assertTrue(entryNames(zip).contains("buildings/residential/drop.sk"));
        }

        // 从工程里删掉一栋（编辑器右键删除走的就是这条路径）。
        CreationBuilding removed = project.findBuilding("residential", "drop");
        assertNotNull(removed);
        project.buildings().remove(removed);

        // 第二次导出到同一个文件：被删的那栋必须消失。
        write(project, out);
        try (ZipFile zip = new ZipFile(out.toFile())) {
            List<String> names = entryNames(zip);
            assertFalse(names.contains("buildings/residential/drop.sk"),
                    "删掉的建筑又被导出了：" + names);
            assertFalse(names.contains("buildings/residential/drop.nbt"), names.toString());
            assertFalse(names.contains("buildings/residential/drop.json"), names.toString());
            assertTrue(names.contains("buildings/residential/keep.sk"), names.toString());
            assertTrue(names.contains("buildings/commercial/also_keep.sk"), names.toString());
        }
    }

    @Test
    @DisplayName("空的工程仍然产出合法包（pack.json 在，没有 buildings/ 条目）")
    void emptyProjectStillProducesAValidPack(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("empty.zip");
        write(project("empty"), out);

        try (ZipFile zip = new ZipFile(out.toFile())) {
            assertEquals(2, packJson(zip).get("format").getAsInt());
            assertTrue(entryNames(zip).stream().noneMatch(name -> name.startsWith("buildings/")),
                    entryNames(zip).toString());
        }
    }

    @Test
    @DisplayName("导出的 .sk 带 size / amount / unlockLevel，NSUK 才认得出这栋建筑")
    void skCarriesTheKeysNuskReads(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("sk.zip");
        write(project("sk", building("house", "residential")), out);

        try (ZipFile zip = new ZipFile(out.toFile())) {
            String sk = read(zip, "buildings/residential/house.sk");
            assertNotNull(sk);
            assertTrue(sk.contains("name: 测试建筑 house"), sk);
            assertTrue(sk.contains("amount: 12.50"), sk);
            assertTrue(sk.contains("unlockLevel: 3"), sk);
            // 缺 size 不影响加载（NSUK 当纯字符串），但编辑器写了就该在，且按官方格式带空格。
            assertTrue(sk.contains("size: 4 x 5 x 6"), sk);
        }
    }
}
