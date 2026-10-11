package com.rstech.simupack.creation;

import com.rstech.simupack.pack.BuildingPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已安装拓展包的只读读取：<b>功能定义 JSON 必须一起读出来</b>。
 *
 * <p>这条来自一个真实缺陷：读取器原来只认 {@code .sk} 与 {@code .nbt}，
 * 于是"分类设定 / 动作组 / 商业交易"三个页签在已安装包上全是空的 ——
 * 而这三个页签正是作者来学官方包设计的入口。数据一直在 zip 里，只是没人打开它。
 *
 * <p>全部脱机运行：{@link InstalledPackReader} 与 {@link CreationBuilding} 都不碰
 * Minecraft 类（测试源码集也看不到它们）。
 */
class InstalledPackReaderTest {

    private static final byte[] NBT_BYTES = "vanilla-structure-nbt".getBytes(StandardCharsets.UTF_8);

    private static void writeZip(Path target, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 只用到 path 的极简 BuildingPack（读取器只读这一个字段）。 */
    private static BuildingPack packAt(Path path) {
        return new BuildingPack("test_pack", "测试包", "1.0.0", "作者", "", path, 1, List.of(), false);
    }

    private static CreationBuilding find(List<InstalledPackReader.Entry> entries, String category, String id) {
        return entries.stream()
                .filter(entry -> entry.category().equals(category) && entry.id().equals(id))
                .map(InstalledPackReader.Entry::building)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没读到建筑 " + category + "/" + id));
    }

    @Test
    @DisplayName("同名 .json 会被读进建筑：工业配方不再空白")
    void sameNameJsonIsLoaded(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("official_like.zip");
        writeZip(zip, Map.of(
                "buildings/industry/brick_kiln.sk", utf8("name: 砖窑\namount: 15.0元\n"),
                "buildings/industry/brick_kiln.json", utf8("{\"recipes\":[{\"id\":\"bake\"}]}"),
                "buildings/industry/brick_kiln.nbt", NBT_BYTES));

        InstalledPackReader.Result result = InstalledPackReader.listBuildings(packAt(zip));
        assertTrue(result.ok(), result.error());
        CreationBuilding kiln = find(result.entries(), "industry", "brick_kiln");
        assertEquals("{\"recipes\":[{\"id\":\"bake\"}]}", kiln.jsonConfig());
        assertTrue(kiln.hasStructure());
    }

    @Test
    @DisplayName(".sk 的 industrial: 指针优先于同名文件（NSUK 的两级解析）")
    void explicitPointerWins(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("pointers.zip");
        writeZip(zip, Map.of(
                "buildings/industry/suger_factory.sk",
                utf8("name: 糖厂\nindustrial: custom/def.json\n"),
                "buildings/industry/suger_factory.json", utf8("{\"marker\":\"same-name\"}"),
                "buildings/industry/custom/def.json", utf8("{\"marker\":\"pointed\"}"),
                "buildings/industry/suger_factory.nbt", NBT_BYTES));

        InstalledPackReader.Result result = InstalledPackReader.listBuildings(packAt(zip));
        assertTrue(result.ok(), result.error());
        CreationBuilding factory = find(result.entries(), "industry", "suger_factory");
        assertEquals("{\"marker\":\"pointed\"}", factory.jsonConfig());
    }

    @Test
    @DisplayName("指针指向不存在的文件时回落到同名文件（不能因此整栋没数据）")
    void danglingPointerFallsBack(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("dangling.zip");
        writeZip(zip, Map.of(
                "buildings/commercial/bar.sk", utf8("name: 酒吧\ncommercial: missing.json\n"),
                "buildings/commercial/bar.json", utf8("{\"offers\":[]}"),
                "buildings/commercial/bar.nbt", NBT_BYTES));

        InstalledPackReader.Result result = InstalledPackReader.listBuildings(packAt(zip));
        CreationBuilding bar = find(result.entries(), "commercial", "bar");
        assertEquals("{\"offers\":[]}", bar.jsonConfig());
    }

    @Test
    @DisplayName("商业与医疗的指针键同样生效")
    void otherPointerKeysWork(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("multi.zip");
        writeZip(zip, Map.of(
                "buildings/public/clinic.sk", utf8("name: 诊所\nmedical: clinic_def.json\n"),
                "buildings/public/clinic_def.json", utf8("{\"type\":\"clinic\"}"),
                "buildings/public/clinic.nbt", NBT_BYTES));

        InstalledPackReader.Result result = InstalledPackReader.listBuildings(packAt(zip));
        CreationBuilding clinic = find(result.entries(), "public", "clinic");
        assertEquals("{\"type\":\"clinic\"}", clinic.jsonConfig());
    }

    @Test
    @DisplayName("没有 json 的建筑保持空配置，不会串到别的建筑上")
    void buildingsWithoutJsonStayEmpty(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("mixed.zip");
        writeZip(zip, Map.of(
                "buildings/residential/plain_house.sk", utf8("name: 平房\n"),
                "buildings/residential/plain_house.nbt", NBT_BYTES,
                "buildings/industry/ironworks.sk", utf8("name: 铁厂\n"),
                "buildings/industry/ironworks.json", utf8("{\"recipes\":[]}"),
                "buildings/industry/ironworks.nbt", NBT_BYTES));

        InstalledPackReader.Result result = InstalledPackReader.listBuildings(packAt(zip));
        assertEquals(2, result.entries().size());
        assertFalse(find(result.entries(), "residential", "plain_house").jsonConfig()
                .contains("recipes"), "住宅不该拿到工业的配方文件");
        assertEquals("{\"recipes\":[]}", find(result.entries(), "industry", "ironworks").jsonConfig());
    }

    @Test
    @DisplayName("同一分类下的同名 json 不会被另一个建筑认领")
    void jsonIsNotSharedAcrossBuildings(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("siblings.zip");
        writeZip(zip, Map.of(
                "buildings/industry/a.sk", utf8("name: 甲\n"),
                "buildings/industry/a.json", utf8("{\"who\":\"a\"}"),
                "buildings/industry/b.sk", utf8("name: 乙\n"),
                "buildings/industry/b.json", utf8("{\"who\":\"b\"}")));

        InstalledPackReader.Result result = InstalledPackReader.listBuildings(packAt(zip));
        assertEquals("{\"who\":\"a\"}", find(result.entries(), "industry", "a").jsonConfig());
        assertEquals("{\"who\":\"b\"}", find(result.entries(), "industry", "b").jsonConfig());
    }

    /**
     * 端到端：用<b>官方包里的真实文件</b>跑一遍「读包 → 解析 → 动作组面板要的东西」。
     *
     * <p>前面几条测的是配对规则，这条测的是「作者真去点动作组页签时，里面有没有东西」：
     * 官方 {@code brick_kiln.json} 里有 1 条配方和一条含 {@code move_to_container} 等
     * 动作的步骤链，所以读完包之后 {@code BuildingJsonDocument} 必须能解析出这些。
     * 这条同时挡住「README 里说支持、实际 schema 对不上」这类事故。
     */
    @Test
    @DisplayName("官方包真实工业 JSON：读出来后配方与动作组都能解析")
    void realOfficialIndustrialJsonSurvivesTheRoundTrip(@TempDir Path dir) throws IOException {
        Path realJson = Path.of("build/sk_keys/official_extracted/buildings/industry/brick_kiln.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(realJson),
                "缺少官方包解包样本：" + realJson);

        Path zip = dir.resolve("official_sample.zip");
        writeZip(zip, Map.of(
                "buildings/industry/brick_kiln.sk", utf8("name: 砖窑\namount: 15.0元\n"),
                "buildings/industry/brick_kiln.json", Files.readAllBytes(realJson)));

        InstalledPackReader.Result result = InstalledPackReader.listBuildings(packAt(zip));
        assertTrue(result.ok(), result.error());
        CreationBuilding kiln = find(result.entries(), "industry", "brick_kiln");
        assertFalse(kiln.jsonConfig().isBlank(), "工业定义 json 没被读出来");

        var document = com.rstech.simupack.creation.json.BuildingJsonDocument.parse(kiln.jsonConfig());
        assertEquals(1, document.recipes().size(), "官方砖窑应当有 1 条配方");
        var recipe = document.recipes().get(0);
        assertNotNull(recipe);
        // 动作组页签读的就是这条链。
        assertTrue(recipe.has("steps") && recipe.getAsJsonArray("steps").size() > 0,
                "配方的步骤链为空，动作组页签会是空白");
    }
}
