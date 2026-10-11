package com.rstech.simupack.pack.legacy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.PackFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧版拓展包识别与转换的回归测试。
 *
 * <p>全部脱机运行：结构转换通过桩函数注入，因此不需要 Minecraft 运行时，
 * 但要覆盖的判据（旧/新版分流、.meta.json 改名、结构改 .nbt、offers 升级、id 清洗）
 * 一个都不能少——这些正是旧版转换脚本踩过的坑。
 */
class LegacyPackConversionTest {

    private static final byte[] NBT_BYTES = "vanilla-structure-nbt".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONVERTED_BYTES = "converted-structure-nbt".getBytes(StandardCharsets.UTF_8);

    /**
     * 正常路径：旧包的建筑文件名本来就是蛇形 ASCII id，转换时一个字符都不该改。
     *
     * <p>改了 id 就意味着 {@code .sk} 里可能写着的 {@code structure: small_house.nbt}
     * 对不上同名结构，NSUK 会把这栋建筑丢掉；不改还顺带保住了"能回编辑器二次编辑"。
     */
    @Test
    void keepsSnakeCaseAsciiIdsUntouched(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("old_city.simupack");
        writeZip(source, Map.of(
                "residential/small_house.nbt", NBT_BYTES,
                "residential/small_house.sk",
                "name: 小房子\nstructure: small_house.nbt\n".getBytes(StandardCharsets.UTF_8),
                "commercial/corner_shop.nbt", NBT_BYTES,
                "commercial/corner_shop.sk", "name: 街角商店\n".getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);

        assertEquals(List.of("commercial/corner_shop", "residential/small_house"), result.buildingIds());
        // 蛇形 id 不该产生任何"改名"提示。
        assertFalse(result.notices().stream()
                .anyMatch(n -> n.kind().equals(LegacyPackConverter.Notice.RENAMED_ID)),
                "蛇形 ASCII id 不该被改名：" + result.notices());

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertNotNull(zip.getEntry("buildings/residential/small_house.nbt"));
            assertNotNull(zip.getEntry("buildings/residential/small_house.sk"));
            assertNotNull(zip.getEntry("buildings/commercial/corner_shop.nbt"));
            assertTrue(read(zip, "buildings/residential/small_house.sk")
                    .contains("structure: small_house.nbt"));
        }
    }

    /**
     * 只有主干确实非法时（这里整串中文）才退化，且退化名必须仍然能与它的结构配对。
     */
    @Test
    void nonAsciiStemFallsBackToSafeIdThatStillPairsWithItsStructure(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("旧城建筑.simupack");
        writeZip(source, Map.of(
                "工业/大工厂.litematic", "fake litematic".getBytes(StandardCharsets.UTF_8),
                "工业/大工厂.sk", "name: 大工厂\n".getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);

        assertEquals(1, result.convertedStructureCount());
        assertEquals(1, result.structureCount());
        String id = result.buildingIds().get(0).substring("industry/".length());
        assertTrue(id.matches("[a-z0-9_]+"), "退化后的 id 必须是合法蛇形 ASCII：" + id);
        assertTrue(result.notices().stream()
                        .anyMatch(n -> n.kind().equals(LegacyPackConverter.Notice.RENAMED_ID)),
                "退化改名必须留提示：" + result.notices());

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertNotNull(zip.getEntry("buildings/industry/" + id + ".nbt"));
            assertNotNull(zip.getEntry("buildings/industry/" + id + ".sk"));
            // 没有显式 structure 行时，加载器按 .sk 主干找同名 .nbt —— 两者用同一个 id
            // 写出，配对就成立（旧包本来就不写这一行）。
            String sk = read(zip, "buildings/industry/" + id + ".sk");
            assertFalse(sk.contains("structure:"), sk);
            assertEquals(CONVERTED_BYTES.length,
                    (int) zip.getEntry("buildings/industry/" + id + ".nbt").getSize());
        }
    }

    /**
     * NSUK 比对文件名时会忽略 {@code _} 和 {@code -}，所以两种写法在它眼里是同一栋建筑。
     * 转换器必须用同一套规则判重，否则会产出互相抢结构的两栋建筑。
     */
    @Test
    void idsThatNSUKWouldConfuseAreDisambiguated(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("clash.simupack");
        writeZip(source, Map.of(
                "residential/small_house.nbt", NBT_BYTES,
                "residential/small_house.sk", "name: A\n".getBytes(StandardCharsets.UTF_8),
                "residential/smallhouse.nbt", NBT_BYTES,
                "residential/smallhouse.sk", "name: B\n".getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);

        assertEquals(2, result.structureCount(), "两栋建筑都要保留，不能被宽松匹配吃掉一栋");
        // 真正的宽松撞名发生在同分类同主干的两个不同 id 上：smallhouse 与 small_house。
        Set<String> relaxed = new HashSet<>();
        for (String key : result.buildingIds()) {
            assertTrue(relaxed.add(key.substring(key.indexOf('/') + 1)
                            .replace("_", "").replace("-", "")),
                    "宽松键重复，NSUK 会认错： " + result.buildingIds());
        }
        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            long structures = zip.stream()
                    .filter(entry -> entry.getName().startsWith("buildings/residential/"))
                    .filter(entry -> entry.getName().endsWith(".nbt"))
                    .count();
            assertEquals(2, structures);
        }
    }

    private static final LegacyPackConverter.StructureNbtConverter STUB = (name, bytes) -> {
        if (!name.toLowerCase().endsWith(".litematic") && !name.toLowerCase().endsWith(".schem")) {
            throw new IOException("桩函数只接受旧格式结构，收到 " + name);
        }
        return CONVERTED_BYTES;
    };

    // ── 识别 ───────────────────────────────────────────────────────────

    @Test
    void detectsLegacyPackWithoutManifest(@TempDir Path dir) throws IOException {        Path source = dir.resolve("OldCity.simupack");
        writeZip(source, Map.of(
                "住宅/cottage.nbt", NBT_BYTES,
                "住宅/cottage.sk", "name: 小屋\n".getBytes(StandardCharsets.UTF_8)));

        LegacyPackDetector.LegacyPackInfo info = LegacyPackDetector.detect(source);
        assertTrue(info.legacy(), "没有 pack.json 但有建筑文件应判为旧版");
        assertTrue(info.convertible());
        assertNull(info.error());
        assertEquals(1, info.skCount());
        assertEquals(1, info.nbtCount());
        assertEquals(0, info.nonNbtCount());
    }

    @Test
    void modernPackIsNotReportedAsLegacy(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("modern.zip");
        writeZip(source, Map.of(
                "pack.json", "{\"id\":\"m\",\"name\":\"Modern\",\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8),                "buildings/residential/cottage.sk", "name: 小屋\n".getBytes(StandardCharsets.UTF_8),
                "buildings/residential/cottage.nbt", NBT_BYTES));

        LegacyPackDetector.LegacyPackInfo info = LegacyPackDetector.detect(source);
        assertFalse(info.legacy(), "有 pack.json 的包不该被当成旧版");
        assertEquals("Modern", info.name());
    }

    @Test
    void zipWithoutBuildingsIsNotAPack(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("random.zip");
        writeZip(source, Map.of("readme.txt", "hello".getBytes(StandardCharsets.UTF_8)));

        LegacyPackDetector.LegacyPackInfo info = LegacyPackDetector.detect(source);
        assertFalse(info.legacy());
        assertNotNull(info.error());
    }

    @Test
    void brokenZipReportsErrorInsteadOfThrowing(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("broken.zip");
        Files.writeString(source, "this is not a zip");

        LegacyPackDetector.LegacyPackInfo info = LegacyPackDetector.detect(source);
        assertFalse(info.legacy());
        assertNotNull(info.error());
        assertFalse(info.convertible());
    }

    // ── 转换 ───────────────────────────────────────────────────────────

    @Test
    void convertsNbtPackKeepingSkAndAligningStructure(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("OldCity.simupack");
        writeZip(source, Map.of(
                "metadata.json", "{\"name\":\"旧城\",\"version\":\"2.1\",\"author\":\"老王\"}"
                        .getBytes(StandardCharsets.UTF_8),
                "residential/cottage.nbt", NBT_BYTES,
                // structure 行故意指向旧文件名/中文名：转换后必须被对齐到实际输出名。
                "residential/cottage.sk", "name: 小屋\namount: 60.0元\nstructure: 小房子.nbt\n"
                        .getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);

        assertEquals(1, result.skCount());
        assertEquals(1, result.structureCount());
        assertEquals(List.of("residential/cottage"), result.buildingIds());

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertNotNull(zip.getEntry("buildings/residential/cottage.nbt"));
            assertNotNull(zip.getEntry("buildings/residential/cottage.sk"));
            String sk = read(zip, "buildings/residential/cottage.sk");
            assertTrue(sk.contains("structure: cottage.nbt"), "structure 行必须指向实际存在的 .nbt：\n" + sk);
            assertTrue(sk.contains("amount: 60.0"), "金额里的货币单位应被去掉：\n" + sk);
            assertFalse(sk.contains("60.0元"));

            JsonObject manifest = JsonParser.parseString(read(zip, "pack.json")).getAsJsonObject();
            assertEquals("旧城", manifest.get("name").getAsString());
            assertEquals("2.1", manifest.get("version").getAsString());
            assertEquals("老王", manifest.get("author").getAsString());
            // 转换产物必须写当前版本线的格式号（1.21.1 → 2），且不再自造 formatVersion。
            assertEquals(PackFormat.expected(), manifest.get("format").getAsInt());
            assertFalse(manifest.has("formatVersion"),
                    "转换产物写 formatVersion 就与官方包格式不符");
            assertTrue(manifest.get("id").getAsString().startsWith("converted."));
            assertEquals(1, manifest.getAsJsonArray("buildingIds").size());
            // 旧清单里的 metadata.json 不该被搬进新包。
            assertNull(zip.getEntry("metadata.json"));
            assertNull(zip.getEntry("index.json"));
        }
    }

    /**
     * 商业定义：字段名与语义按旧版筑艺坊导出的真实格式（{@code ExportManager
     * .createPackStructure}：{@code <id>.json} + {@code shopMode/trades/buyTrades}），
     * 换算规则按新版运行时自带的 {@code CommercialLegacyDefinitionParser} 逐条核对。
     */
    @Test
    void upgradesLegacyShopJsonAndRenamesMetaJson(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("shops.simupack");
        String legacyShop = """
                {"buildingId":"shop_a","buildingName":"杂货铺","jobType":"shopkeeper","heldItem":"minecraft:bread",
                 "shopMode":"NPC_SELL","requireMaterialsForSale":true,"customFlag":{"a":1},
                 "trades":[
                   {"item":"minecraft:bread","sellPrice":12.5,"maxStock":32,"retail":false,
                    "requiredMaterial":"minecraft:oak_planks","requiredMaterialCount":4},
                   {"item":"minecraft:cake","sellPrice":40.0,"maxStock":8,"retail":true}],
                 "buyTrades":[{"item":"minecraft:wheat","buyPrice":3.0,"amount":8,"maxBuyAmount":8}]}
                """;
        writeZip(source, Map.of(
                "commercial/shop_a.nbt", NBT_BYTES,
                "commercial/shop_a.sk", "name: 杂货铺\njob_type:shopkeeper\ncommercial:\n"
                        .getBytes(StandardCharsets.UTF_8),
                "commercial/shop_a.meta.json", legacyShop.getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);
        assertEquals(1, result.functionJsonCount());
        assertEquals(1, result.upgradedShopCount());

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertEquals("buildings/commercial/shop_a.sk", zip.getEntry("buildings/commercial/shop_a.sk").getName());
            assertNull(zip.getEntry("buildings/commercial/shop_a.meta.json"),
                    ".meta.json 必须改名成 .json，否则加载器找不到功能定义");
            JsonObject json = JsonParser.parseString(
                    read(zip, "buildings/commercial/shop_a.json")).getAsJsonObject();

            assertEquals("shop_a", json.get("id").getAsString());
            assertEquals("杂货铺", json.get("name").getAsString());
            assertEquals("shopkeeper", json.getAsJsonObject("job").get("id").getAsString());
            assertEquals("minecraft:bread", json.getAsJsonObject("job").get("heldItem").getAsString());
            // 未知键不能丢。
            assertTrue(json.has("customFlag"));
            assertEquals(1, json.getAsJsonObject("customFlag").get("a").getAsInt());
            // requireMaterialsForSale 是旧键：搬走，但材料明细会进 stock.materials。
            assertFalse(json.has("requireMaterialsForSale"));

            var offers = json.getAsJsonArray("offers");
            assertEquals(3, offers.size());

            JsonObject bulkSell = offers.get(0).getAsJsonObject();
            assertEquals("npc", bulkSell.get("visibleTo").getAsString(), "NPC_SELL 应映射成 npc");
            assertEquals(12.5, bulkSell.getAsJsonArray("cost").get(0).getAsJsonObject()
                    .get("money").getAsDouble());
            JsonObject bulkResult = bulkSell.getAsJsonArray("result").get(0).getAsJsonObject();
            assertEquals("minecraft:bread", bulkResult.get("item").getAsString());
            // retail 缺省 false = 攒批卖：旧格式解析器给 64 个，转换必须照抄，
            // 否则一次买卖 1 个，标价等于被放大了 64 倍。
            assertEquals(64, bulkResult.get("count").getAsInt());
            assertEquals(32, bulkSell.getAsJsonObject("stock").get("max").getAsInt());
            JsonObject material = bulkSell.getAsJsonObject("stock")
                    .getAsJsonArray("materials").get(0).getAsJsonObject();
            assertEquals("minecraft:oak_planks", material.get("item").getAsString());
            assertEquals(4, material.get("count").getAsInt());

            JsonObject retailSell = offers.get(1).getAsJsonObject();
            assertEquals(1, retailSell.getAsJsonArray("result").get(0).getAsJsonObject()
                    .get("count").getAsInt(), "retail=true 是零售，结果数量必须是 1");

            JsonObject buy = offers.get(2).getAsJsonObject();
            assertEquals("minecraft:wheat", buy.getAsJsonArray("cost").get(0).getAsJsonObject()
                    .get("item").getAsString());
            assertEquals(64, buy.getAsJsonArray("cost").get(0).getAsJsonObject().get("count").getAsInt(),
                    "旧格式收购固定按 64 个一组");
            assertEquals(3.0, buy.getAsJsonArray("result").get(0).getAsJsonObject()
                    .get("money").getAsDouble());
            // maxBuyAmount 才是"每次最多收多少"，库存上限 = 8 × 64。
            assertEquals(512, buy.getAsJsonObject("stock").get("max").getAsInt());
        }
    }

    /**
     * 旧版筑艺坊的 {@code .sk} 里根本没有 {@code structure:} 行（结构固定叫
     * {@code <id>.nbt}，默认按主干配对）。转换不该凭空补一行——那是对旧包的改动，
     * 而且没有任何收益。
     */
    /**
     * 旧版筑艺坊导出的元数据用的是 {@code packId}/{@code packName}/{@code packVersion}
     * （见源码 {@code ExportManager.createMetadataJson}），外加一份 mod 依赖清单。
     * 只认新版键名会让包名退化成文件名、版本永远停在 1.0.0。
     */
    @Test
    void readsLegacyMetadataKeyNamesAndKeepsDependencies(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("OldCity.simupack");
        String metadata = """
                {"packId":"old_city","packName":"旧城建筑合集","packVersion":"2.4.1","author":"老王",
                 "description":"测试包",
                 "dependencies":[{"modid":"minecraft","versionRange":"[1.20.1,)"},
                                 {"modid":"forge","versionRange":"[47,)"},
                                 {"modid":"create","versionRange":"[0.5.1,)"}]}
                """;
        writeZip(source, Map.of(
                "metadata.json", metadata.getBytes(StandardCharsets.UTF_8),
                "residential/cottage.nbt", NBT_BYTES,
                "residential/cottage.sk", "name: 小屋\n".getBytes(StandardCharsets.UTF_8)));

        LegacyPackDetector.LegacyPackInfo info = LegacyPackDetector.detect(source);
        assertEquals("旧城建筑合集", info.name());
        assertEquals("2.4.1", info.version());

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            JsonObject manifest = JsonParser.parseString(read(zip, "pack.json")).getAsJsonObject();
            assertEquals("旧城建筑合集", manifest.get("name").getAsString());
            assertEquals("2.4.1", manifest.get("version").getAsString());
            assertEquals("老王", manifest.get("author").getAsString());
            assertEquals("测试包", manifest.get("description").getAsString());
            // 依赖清单新版不读，但不能丢：丢掉就再也找不回"这包要哪些 mod"。
            assertEquals(3, manifest.getAsJsonArray("dependencies").size());
            assertEquals("create", manifest.getAsJsonArray("dependencies").get(2).getAsJsonObject()
                    .get("modid").getAsString());
        }
        assertTrue(result.notices().stream()
                        .anyMatch(n -> n.kind().equals(LegacyPackConverter.Notice.DEPENDENCIES_KEPT)),
                "保留依赖清单要给提示：" + result.notices());
    }

    /**
     * 旧包躺在 {@code simukraftbuilding/} 里时，不该被包列表当成"缺 pack.json 的坏包"
     * 反复报格式错误 —— {@code BuildingPackManager} 靠这个判定把它们分流走。
     */
    @Test
    void legacyPacksAreDistinguishableFromBrokenOnes(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("old.zip");
        writeZip(legacy, Map.of(
                "residential/cottage.nbt", NBT_BYTES,
                "residential/cottage.sk", "name: 小屋\n".getBytes(StandardCharsets.UTF_8)));
        Path modern = dir.resolve("modern.zip");
        writeZip(modern, Map.of(
                "pack.json", "{\"id\":\"m\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8),
                "buildings/residential/cottage.sk", "name: 小屋\n".getBytes(StandardCharsets.UTF_8)));
        Path junk = dir.resolve("junk.zip");
        writeZip(junk, Map.of("readme.txt", "hi".getBytes(StandardCharsets.UTF_8)));

        assertTrue(LegacyPackDetector.isLegacyBuildingPack(legacy));
        assertFalse(LegacyPackDetector.isLegacyBuildingPack(modern), "有 pack.json 的是新版包");
        assertFalse(LegacyPackDetector.isLegacyBuildingPack(junk), "什么都没有的才是真坏包");
        assertFalse(LegacyPackDetector.isLegacyBuildingPack(dir.resolve("missing.zip")),
                "文件不存在时返回 false，不能抛异常");
    }

    /**
     * 被筑艺坊禁用的包必须退出"待转换旧包"的候选集合。
     *
     * <p>转换产物本身就叫 {@code <包名>.zip.disabled}：如果这里不排除，界面每打开一次
     * 都会把它当成"发现旧版拓展包"再问一遍要不要转换。
     */
    @Test
    void disabledFilesAreNotPackCandidates(@TempDir Path dir) throws IOException {
        Path enabled = dir.resolve("old.zip");
        writeZip(enabled, Map.of(
                "residential/cottage.nbt", NBT_BYTES,
                "residential/cottage.sk", "name: 小屋\n".getBytes(StandardCharsets.UTF_8)));
        Path disabled = dir.resolve("old.zip.disabled");
        writeZip(disabled, Map.of(
                "residential/cottage.nbt", NBT_BYTES,
                "residential/cottage.sk", "name: 小屋\n".getBytes(StandardCharsets.UTF_8)));

        assertTrue(LegacyPackDetector.looksLikePackFile(enabled));
        assertFalse(LegacyPackDetector.looksLikePackFile(disabled),
                "禁用后缀的包已经处理过了，不该再出现在待转换列表里");
        assertFalse(LegacyPackDetector.looksLikePackFile(dir.resolve("city.zip.DISABLED")),
                "后缀判定要忽略大小写");
        assertTrue(LegacyPackDetector.looksLikePackFile(dir.resolve("old.simupack")),
                "旧版源文件仍然要能被识别");
        assertEquals(List.of(), LegacyPackDetector.scanDirectory(dir).stream()
                .filter(info -> info.path().getFileName().toString().endsWith(".disabled"))
                .toList(), "扫描结果里不能出现禁用文件");
    }

    @Test
    void doesNotInventStructureLineInSk(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("plain.simupack");
        writeZip(source, Map.of(
                "residential/cottage.nbt", NBT_BYTES,
                "residential/cottage.sk", "name:小屋\nsize:9 x 4 x 7\namount:60元\n"
                        .getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.convert(source, output, STUB);

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            String sk = read(zip, "buildings/residential/cottage.sk");
            assertFalse(sk.contains("structure:"), "不该凭空补 structure 行：\n" + sk);
            assertFalse(sk.contains("file:"), "不该凭空补 file 行：\n" + sk);
            // 金额里的货币后缀仍然要清掉（新版按数值解析）。
            assertTrue(sk.contains("amount: 60"), sk);
            assertFalse(sk.contains("60元"));
        }
    }

    /**
     * {@code unlockLevel} 非正整数会让新版 {@code readUnlockLevel} 抛异常、
     * {@code scanCategory} 只留一行 warn，**整栋建筑不进目录**。旧包写
     * {@code unlockLevel: 0} 是很自然的事，搬过去等于建筑凭空消失。
     */
    @Test
    void dropsNonPositiveUnlockLevelInsteadOfLosingTheBuilding(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("levels.simupack");
        writeZip(source, Map.of(
                "residential/zero_level.nbt", NBT_BYTES,
                "residential/zero_level.sk", "name: 零级\nunlockLevel: 0\n"
                        .getBytes(StandardCharsets.UTF_8),
                "residential/negative.nbt", NBT_BYTES,
                "residential/negative.sk", "name: 负数\nunlock_level: -3\n"
                        .getBytes(StandardCharsets.UTF_8),
                "residential/garbage.nbt", NBT_BYTES,
                "residential/garbage.sk", "name: 乱写\nunlockLevel: abc\n"
                        .getBytes(StandardCharsets.UTF_8),
                "residential/valid.nbt", NBT_BYTES,
                "residential/valid.sk", "name: 正常\nunlockLevel: 3\n"
                        .getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);
        assertEquals(4, result.structureCount(), "四栋建筑都必须留下");

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            for (String id : List.of("zero_level", "negative", "garbage")) {
                String sk = read(zip, "buildings/residential/" + id + ".sk");
                assertFalse(sk.toLowerCase(Locale.ROOT).contains("unlocklevel"), sk);
                assertFalse(sk.toLowerCase(Locale.ROOT).contains("unlock_level"), sk);
            }
            assertTrue(read(zip, "buildings/residential/valid.sk").contains("unlockLevel: 3"),
                    "合法等级必须原样保留");
        }
        assertEquals(3, result.notices().stream()
                .filter(n -> n.kind().equals(LegacyPackConverter.Notice.UNLOCK_LEVEL_REMOVED))
                .count());
    }

    /**
     * 旧包写在 {@code .sk} 里的 {@code tags}/{@code job_type}/{@code home_teleport} 等
     * 新版一律不读（字节码逐类核对过）。文件照原样留着，但必须给提示，
     * 否则用户会以为这些信息还在生效。
     */
    @Test
    void warnsAboutSkKeysTheNewRuntimeIgnores(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("deadkeys.simupack");
        writeZip(source, Map.of(
                "commercial/shop_b.nbt", NBT_BYTES,
                "commercial/shop_b.sk", ("name: 商店\njob_type: shopkeeper\n"
                        + "tags: cheap,wooden\n").getBytes(StandardCharsets.UTF_8),
                "residential/home_a.nbt", NBT_BYTES,
                "residential/home_a.sk", "name: 住宅\nhome_teleport: 1,2,3\n"
                        .getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);

        List<String> details = result.notices().stream()
                .filter(n -> n.kind().equals(LegacyPackConverter.Notice.DEAD_SK_KEYS))
                .map(LegacyPackConverter.Notice::detail)
                .toList();
        assertEquals(2, details.size(), details.toString());
        assertTrue(details.stream().anyMatch(text -> text.contains("job_type") && text.contains("tags")),
                details.toString());
        assertTrue(details.stream().anyMatch(text -> text.contains("home_teleport")), details.toString());

        // 键本身仍然原样留在 .sk 里（不删用户的内容，只是提示它不生效了）。
        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertTrue(read(zip, "buildings/commercial/shop_b.sk").contains("job_type: shopkeeper"));
            assertTrue(read(zip, "buildings/residential/home_a.sk").contains("home_teleport: 1,2,3"));
        }
    }

    /**
     * 新版只有商业的旧格式解析器（{@code CommercialLegacyDefinitionParser}），
     * 工业没有。旧工业配方搬过去读不出来，必须明说，别让用户以为工厂也转好了。
     */
    @Test
    void warnsThatIndustrialJsonHasNoLegacyPath(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("industry.simupack");
        writeZip(source, Map.of(
                "industry/sawmill.nbt", NBT_BYTES,
                "industry/sawmill.sk", "name: 锯木厂\njob_type: sawyer\n".getBytes(StandardCharsets.UTF_8),
                "industry/sawmill.json", "{\"jobType\":\"sawyer\",\"recipes\":[{\"input\":\"minecraft:oak_log\"}]}"
                        .getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, STUB);
        assertTrue(result.notices().stream()
                        .anyMatch(n -> n.kind().equals(LegacyPackConverter.Notice.INDUSTRY_NO_LEGACY)),
                "工业定义没被识别时必须提示：" + result.notices());
    }

    @Test
    void keepsIndustrialJsonUntouched(@TempDir Path dir) throws IOException {        Path source = dir.resolve("industry.simupack");
        String industrial = "{\"id\":\"sawmill\",\"recipes\":[{\"input\":\"minecraft:log\"}]}";
        writeZip(source, Map.of(
                "industry/sawmill.nbt", NBT_BYTES,
                "industry/sawmill.sk", "name: 锯木厂\n".getBytes(StandardCharsets.UTF_8),
                "industry/sawmill.json", industrial.getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        LegacyPackConverter.convert(source, output, STUB);

        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseString(
                    read(zip, "buildings/industry/sawmill.json")).getAsJsonObject();
            assertEquals("sawmill", json.get("id").getAsString());
            assertEquals(1, json.getAsJsonArray("recipes").size());
        }
    }

    @Test
    void skipsBuildingWhoseStructureFailsToConvert(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("broken-structure.simupack");
        writeZip(source, Map.of(
                "residential/good.nbt", NBT_BYTES,
                "residential/good.sk", "name: 好的\n".getBytes(StandardCharsets.UTF_8),
                "residential/bad.litematic", "not really litematic".getBytes(StandardCharsets.UTF_8),
                "residential/bad.sk", "name: 坏的\n".getBytes(StandardCharsets.UTF_8)));

        LegacyPackConverter.StructureNbtConverter failing = (name, bytes) -> {
            throw new IOException("结构损坏");
        };
        Path output = dir.resolve("out.zip");
        LegacyPackConverter.Result result = LegacyPackConverter.convert(source, output, failing);

        assertEquals(1, result.structureCount(), "转换失败的建筑不该被写进包");
        assertEquals(List.of("residential/good"), result.buildingIds());
        assertTrue(result.notices().stream()
                .anyMatch(n -> n.kind().equals(LegacyPackConverter.Notice.SKIPPED_STRUCTURE)));
    }

    /**
     * 建筑全被跳过时必须直接失败。
     *
     * <p>产出一个只有 pack.json、没有建筑的空包是最坏的结果：用户会以为转换成功了，
     * 进游戏却看不到任何建筑，而目标文件已经把上一次的正确结果覆盖掉。
     */
    @Test
    void packWithoutAnyStructureFailsInsteadOfWritingEmptyZip(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("no-structure.simupack");
        writeZip(source, Map.of(
                "residential/orphan.sk", "name: 只有属性\n".getBytes(StandardCharsets.UTF_8)));

        Path output = dir.resolve("out.zip");
        IOException failure = assertThrows(IOException.class,
                () -> LegacyPackConverter.convert(source, output, STUB));
        assertTrue(failure.getMessage().contains("orphan"), "错误信息要指出是哪栋建筑：" + failure.getMessage());
        assertFalse(Files.exists(output), "失败时不能留下输出文件");
    }

    // ── 路径与命名规则 ─────────────────────────────────────────────────

    @Test
    void unsafeEntryNamesAreRejected() {
        assertNull(LegacyPackConverter.safeName("../evil.nbt"));
        assertNull(LegacyPackConverter.safeName("/absolute.nbt"));
        assertNull(LegacyPackConverter.safeName("a//b.nbt"));
        assertNull(LegacyPackConverter.safeName("a/./b.nbt"));
        assertEquals("a/b.nbt", LegacyPackConverter.safeName("a\\b.nbt"));
    }

    @Test
    void doubleSuffixIsParsedAsOneExtension() {
        assertEquals(".meta.json", LegacyPackConverter.extension("buildings/commercial/shop.meta.json"));
        assertEquals(".sk", LegacyPackConverter.extension("buildings/commercial/shop.sk"));
        assertEquals("shop", LegacyPackConverter.stemOf("buildings/commercial/shop.meta.json", ".meta.json"));
    }

    @Test
    void categoryAliasesCoverChineseDirectories() {
        assertEquals("residential", LegacyPackConverter.categoryFor("住宅/a.nbt"));
        assertEquals("commercial", LegacyPackConverter.categoryFor("商业/a.nbt"));
        assertEquals("industry", LegacyPackConverter.categoryFor("工业/a.nbt"));
        assertEquals("public", LegacyPackConverter.categoryFor("医疗/a.nbt"));
        assertEquals("other", LegacyPackConverter.categoryFor("随便/a.nbt"));
        assertEquals("commercial", LegacyPackConverter.categoryFor("buildings/commercial/a.nbt"));
    }

    @Test
    void outputFileNameIsStableAndCollisionFreeForChineseNames() {
        Path chinese = Path.of("旧城建筑.simupack");
        String first = LegacyPackInstaller.outputFileName(chinese);
        assertEquals(first, LegacyPackInstaller.outputFileName(chinese), "同一来源必须映射到同一文件名");
        assertTrue(first.endsWith(".zip"));
        assertEquals(first, LegacyPackInstaller.outputFileName(Path.of("旧城建筑.zip")),
                "改后缀不该改变输出名");
        assertFalse(first.equals(LegacyPackInstaller.outputFileName(Path.of("另一座城.simupack"))),
                "两个不同的中文包名不能落到同一个输出文件");

        assertEquals("old-city.zip", LegacyPackInstaller.outputFileName(Path.of("Old-City.simupack")));
    }

    // ── 工具 ───────────────────────────────────────────────────────────

    private static void writeZip(Path target, Map<String, byte[]> entries) throws IOException {
        Map<String, byte[]> ordered = new LinkedHashMap<>(entries);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry, "包内缺少条目：" + name);
        try (var input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
