package com.rstech.simupack.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启用/禁用的文件名换算。
 *
 * <p>这套规则是"禁用"功能的地基：NSUK 只扫 {@code .zip}，所以状态完全由文件名决定。
 * 一旦这里算错，包要么永远加载不出来，要么禁用不掉——都是用户直接看到的问题，
 * 而且出错时不会有异常，只会静默地不对，所以必须钉住。
 */
class PackActivationTest {

    @Test
    void addsAndRemovesDisabledSuffix() {
        assertEquals("city.zip.disabled", PackActivation.disabledFileName("city.zip"));
        assertEquals("city.zip", PackActivation.enabledFileName("city.zip.disabled"));
    }

    @Test
    void conversionsAreIdempotent() {
        // 重复点"禁用"不能变成 city.zip.disabled.disabled。
        assertEquals("city.zip.disabled", PackActivation.disabledFileName("city.zip.disabled"));
        assertEquals("city.zip", PackActivation.enabledFileName("city.zip"));
    }

    @Test
    void suffixCheckIgnoresCase() {
        assertTrue(PackActivation.isDisabledFileName("city.zip.DISABLED"));
        assertTrue(PackActivation.isDisabledFileName("city.zip.Disabled"));
        assertFalse(PackActivation.isDisabledFileName("city.zip"));
    }

    @Test
    void packageIdIsStableAcrossStates() {
        // 同一个包在启用/禁用两种状态下必须算出同一个 id，
        // 否则列表里会冒出一个"新包"，选中态也会丢。
        assertEquals("official_building", PackActivation.stripExtensions("official_building.zip"));
        assertEquals("official_building",
                PackActivation.stripExtensions("official_building.zip.disabled"));
        assertEquals(PackActivation.stripExtensions("official_building.zip"),
                PackActivation.stripExtensions("official_building.zip.disabled"));
    }

    @Test
    void packageIdHandlesNamesWithoutExtension() {
        assertEquals("city", PackActivation.stripExtensions("city"));
        assertEquals("city", PackActivation.stripExtensions("city.disabled"));
    }

    @Test
    void onlyZipFilesCountAsPacks() {
        assertTrue(PackActivation.isPackFileName("city.zip"));
        assertTrue(PackActivation.isPackFileName("city.zip.disabled"));
        assertTrue(PackActivation.isPackFileName("CITY.ZIP"));
        // 旧包源文件不是新版包，交给旧包转换那条路径。
        assertFalse(PackActivation.isPackFileName("city.simupack"));
        assertFalse(PackActivation.isPackFileName("notes.txt"));
        assertFalse(PackActivation.isPackFileName(null));
    }

    @Test
    void officialPackIsRecognizedInBothStates() {
        assertTrue(PackActivation.isOfficialFileName("official_building.zip"));
        assertTrue(PackActivation.isOfficialFileName("official_building.zip.disabled"));
        assertTrue(PackActivation.isOfficialFileName("Official_Building.ZIP"));
        assertFalse(PackActivation.isOfficialFileName("my_official_building.zip"));
    }

    @Test
    void blankInputDoesNotProduceDottedNames() {
        assertEquals("", PackActivation.disabledFileName(""));
        assertEquals("", PackActivation.enabledFileName(null));
        assertEquals("", PackActivation.stripExtensions(null));
    }

    // ── 官方内置包保护 ─────────────────────────────────────────────────

    /**
     * 官方包禁止禁用：它是 NSUK 的最小可用建筑集，禁用后游戏里一个可建建筑都不剩，
     * 而且 NSUK 只在"目录里完全没有可用包"时才补它，用户没有游戏内恢复手段。
     */
    @Test
    void officialPackCannotBeDisabled(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("official_building.zip"), "not-a-real-zip");
        BuildingPack official = pack(file, true, true);

        assertFalse(PackActivation.canDisable(official));
        assertThrows(PackActivation.ProtectedPackException.class,
                () -> PackActivation.setEnabled(official, false));
        // 被拒绝之后文件必须原样不动（名字没改、内容没改）。
        assertTrue(Files.isRegularFile(file), "被拒绝的禁用操作不能动文件");
        assertFalse(Files.exists(dir.resolve("official_building.zip.disabled")));
    }

    /** 非官方包照常可禁用；"重新启用官方包"这个方向也必须放行（自动恢复要靠它）。 */
    @Test
    void onlyOfficialPacksAreProtected(@TempDir Path dir) throws Exception {
        assertTrue(PackActivation.canDisable(pack(dir.resolve("city.zip"), false, false)));
        assertTrue(PackActivation.canDisable(pack(dir.resolve("city.zip"), true, false)));
        // 官方包即使当前处于"未启用"状态，也不允许再被禁用（它是被手工改坏的待恢复对象）。
        assertFalse(PackActivation.canDisable(pack(dir.resolve("official_building.zip.disabled"), false, true)));
        assertFalse(PackActivation.canDisable(pack(dir.resolve("official_building.zip"), true, true)));
        assertFalse(PackActivation.canDisable(null));
    }

    /**
     * 手工把官方包改成 {@code .disabled}（在资源管理器里改名）之后，扫描前会自动修回来。
     */
    @Test
    void disabledOfficialPackIsRestoredAutomatically(@TempDir Path dir) throws Exception {
        Path enabled = dir.resolve("official_building.zip");
        Path disabled = dir.resolve("official_building.zip.disabled");
        Files.writeString(disabled, "official");

        assertTrue(PackActivation.restoreOfficialIfDisabled(dir), "应该报告修回来了");
        assertTrue(Files.isRegularFile(enabled), "官方包必须回到启用名");
        assertFalse(Files.exists(disabled), "禁用名不应残留");
        assertEquals("official", Files.readString(enabled), "改名不能改内容");
        assertFalse(PackActivation.restoreOfficialIfDisabled(dir), "已经启用时不应重复报告");
    }

    /** 没有禁用的官方包时什么都不做（不能凭空造一个出来）。 */
    @Test
    void restoreDoesNothingWhenOfficialPackIsFine(@TempDir Path dir) throws Exception {
        Path enabled = Files.writeString(dir.resolve("official_building.zip"), "official");
        assertFalse(PackActivation.restoreOfficialIfDisabled(dir));
        assertTrue(Files.isRegularFile(enabled));
        assertEquals(1, Files.list(dir).count(), "不该产生任何新文件");
    }

    /** 造一个只带状态位的 BuildingPack，避免测试依赖包的解析逻辑。 */
    private static BuildingPack pack(Path path, boolean enabled, boolean official) {
        return new BuildingPack("test", "Test", "1", "", "", path, 1, List.of(), official, enabled, true);
    }

    /**
     * 读不出来的包两个方向都不许动：启用它不会让游戏多出任何建筑，禁用也没有意义。
     *
     * <p>界面会把按钮置灰，但服务层必须同样拒绝——否则别处（或界面状态过期时）
     * 仍可能把一个坏包改名。
     */
    @Test
    void unusablePackCannotBeEnabledOrDisabled(@TempDir Path dir) throws Exception {
        Path disabledFile = Files.writeString(dir.resolve("broken.zip.disabled"), "not a zip");
        Path enabledFile = Files.writeString(dir.resolve("broken.zip"), "not a zip");
        // readable=false、0 栋建筑 —— 与 BuildingPackManager 扫到坏包时的结果一致。
        BuildingPack brokenDisabled = new BuildingPack("broken", "broken", "", "", "", disabledFile,
                0, List.of("Cannot read ZIP"), false, false, false);
        BuildingPack brokenEnabled = new BuildingPack("broken", "broken", "", "", "", enabledFile,
                0, List.of("Cannot read ZIP"), false, true, false);

        assertTrue(brokenDisabled.unusable());
        assertThrows(PackActivation.ProtectedPackException.class,
                () -> PackActivation.setEnabled(brokenDisabled, true));
        assertThrows(PackActivation.ProtectedPackException.class,
                () -> PackActivation.setEnabled(brokenEnabled, false));
        assertTrue(Files.isRegularFile(disabledFile), "被拒绝的启用操作不能动文件");
        assertTrue(Files.isRegularFile(enabledFile), "被拒绝的禁用操作不能动文件");
        assertFalse(Files.exists(dir.resolve("broken.zip.zip")));
    }

    /** 批量启用也要跳过坏包，不能绕过单个包的保护。 */
    @Test
    void enableAllSkipsUnusablePacks(@TempDir Path dir) throws Exception {
        Path broken = Files.writeString(dir.resolve("broken.zip.disabled"), "not a zip");
        Path good = Files.writeString(dir.resolve("good.zip.disabled"), "pretend pack");
        BuildingPack brokenPack = new BuildingPack("broken", "broken", "", "", "", broken,
                0, List.of("Cannot read ZIP"), false, false, false);
        BuildingPack goodPack = new BuildingPack("good", "good", "", "", "", good,
                2, List.of(), false, false, true);

        int enabled = PackActivation.enablePacks(List.of(brokenPack, goodPack), dir);

        assertEquals(1, enabled, "只应启用可用的那个");
        assertTrue(Files.isRegularFile(broken), "坏包必须留在原地");
        assertTrue(Files.isRegularFile(dir.resolve("good.zip")), "可用的包应被启用");
    }

    /** 目录之外的包不归这个方法管：只有建筑目录里的文件才该被改名。 */
    @Test
    void enableAllIgnoresPacksOutsideTheBuildingDirectory(@TempDir Path dir) throws Exception {
        Path buildingDir = Files.createDirectories(dir.resolve("simukraftbuilding"));
        Path elsewhere = Files.writeString(dir.resolve("outside.zip.disabled"), "pretend pack");
        BuildingPack outside = new BuildingPack("outside", "outside", "", "", "", elsewhere,
                2, List.of(), false, false, true);

        int enabled = PackActivation.enablePacks(List.of(outside), buildingDir);

        assertEquals(0, enabled);
        assertTrue(Files.isRegularFile(elsewhere), "建筑目录之外的文件不能被改名");
    }

    /**
     * 两种状态各留一份时，必须保住「启用中」的那份。
     *
     * <p>反过来的话，用户磁盘上真正生效的官方包会被旧副本盖掉 —— 那是数据损失，
     * 而不只是显示问题。
     */
    @Test
    void restoreKeepsTheEnabledCopyWhenBothExist(@TempDir Path dir) throws Exception {
        Path enabled = Files.writeString(dir.resolve("official_building.zip"), "in-use");
        Path disabled = Files.writeString(dir.resolve("official_building.zip.disabled"), "stale");

        assertFalse(PackActivation.restoreOfficialIfDisabled(dir), "只是清理副本，不算恢复");
        assertTrue(Files.isRegularFile(enabled), "启用中的官方包必须还在");
        assertEquals("in-use", Files.readString(enabled), "启用中的官方包内容不能被覆盖");
        assertFalse(Files.exists(disabled), "多余的禁用副本应被清理");
    }
}
