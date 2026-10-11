package com.rstech.simupack.pack;

import com.rstech.simupack.creation.PackFormat;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拓展包的状态判定。
 *
 * <p>这三个方法直接决定列表里那些指示灯的颜色和节点 key，判定错了用户就会看到
 * "绿灯亮着但其实这个包一个建筑都没有"这类谎报，所以单独钉一遍。
 */
class BuildingPackTest {

    private static BuildingPack pack(String id, int buildings, List<String> errors, boolean enabled) {
        return new BuildingPack(id, id, "1", "author", "", Path.of(id + ".zip"),
                buildings, errors, false, enabled, true);
    }

    private static BuildingPack pack(String id, int buildings, List<String> errors, boolean enabled,
                                     boolean readable) {
        return new BuildingPack(id, id, "1", "author", "", Path.of(id + ".zip"),
                buildings, errors, false, enabled, readable);
    }

    @Test
    void validMeansNoErrors() {
        assertTrue(pack("a", 3, List.of(), true).valid());
        assertFalse(pack("a", 3, List.of("Missing pack.json"), true).valid());
    }

    /**
     * 「读得出来但一栋建筑都没有」也要算故障：它对游戏没有任何贡献，
     * 给绿灯会让用户以为它已经在生效了。
     */
    @Test
    void emptyPackCountsAsFaultyEvenWithoutErrors() {
        assertTrue(pack("a", 0, List.of(), true).faulty());
        assertTrue(pack("a", 0, List.of(), true).hasNoBuildings());
        assertFalse(pack("a", 1, List.of(), true).faulty());
    }

    @Test
    void unreadablePackCountsAsFaultyEvenWithBuildings() {
        assertTrue(pack("a", 5, List.of("Cannot read ZIP"), true, false).faulty());
    }

    @Test
    void healthyPackIsNotFaulty() {
        assertFalse(pack("a", 12, List.of(), true).faulty());
        assertFalse(pack("a", 12, List.of(), false).faulty());
    }

    // ── unusable：界面置灰按钮、服务层拒绝改名的判据 ───────────────────

    /** 读不出来（NSUK 不认）或一栋建筑都没有的包：不许启用，也不许禁用。 */
    @Test
    void unreadableOrEmptyPacksCannotBeToggled() {
        assertTrue(pack("a", 5, List.of("Cannot read ZIP"), true, false).unusable(), "ZIP 打不开");
        assertTrue(pack("a", 3, List.of("Missing pack.json"), true, false).unusable(), "没有 pack.json");
        assertTrue(pack("a", 0, List.of(), true, true).unusable(), "读得出来但零建筑");
    }

    /** 个别建筑缺结构文件：有红灯，但这个包整体仍可用，所以按钮不能锁。 */
    @Test
    void partiallyBrokenPackStaysToggleable() {
        BuildingPack partially = pack("a", 3, List.of("Missing structure for buildings/residential/x.sk"),
                true, true);
        assertTrue(partially.faulty(), "有问题就该亮红灯");
        assertFalse(partially.unusable(), "还有能用的建筑，不该锁按钮");
    }

    @Test
    void healthyPackIsUsable() {
        assertFalse(pack("a", 12, List.of(), true, true).unusable());
    }

    /**
     * 节点 key 必须区分启用/禁用两种状态。
     *
     * <p>同一个包两种状态下 {@code id} 相同，而 {@code TreeList} 用 key 去重：
     * 不区分的话"启用中的包"和"被禁用的包"会被折叠成一个节点。
     */
    @Test
    void packIdDistinguishesActivationState() {
        String enabled = pack("city", 3, List.of(), true).packId();
        String disabled = pack("city", 3, List.of(), false).packId();
        assertEquals("city", enabled);
        assertFalse(enabled.equals(disabled), "启用与禁用不能用同一个节点 key");
        // 但包自身的身份要稳定，否则同名覆盖判定会失效。
        assertEquals(pack("city", 3, List.of(), true).id(), pack("city", 3, List.of(), false).id());
    }

    // ── 包格式号（1.20.1=1、1.21.1=2、26.3=3） ─────────────────────────

    private static BuildingPack withFormat(int format) {
        return new BuildingPack("city", "City", "1", "author", "", Path.of("city.zip"),
                3, List.of(), false, true, true, format);
    }

    @Test
    void formatMismatchOnlyFlagsTheOtherVersionLines() {
        // 本版本线（1.21.1 → 2）：一致，不算不匹配。
        assertFalse(withFormat(PackFormat.FORMAT_1_21_1).formatMismatch());
        // 没声明格式的老包：按约定放行（1.20.1 时代的包大多没写这个键）。
        assertFalse(withFormat(PackFormat.UNKNOWN).formatMismatch());
        // 别的版本线：提示。
        assertTrue(withFormat(PackFormat.FORMAT_1_20_1).formatMismatch());
        assertTrue(withFormat(PackFormat.FORMAT_26_3).formatMismatch());
    }

    /**
     * 格式不符<b>不能</b>让它变成「不可用」。
     *
     * <p>可用性只由"读得出来 + 有建筑"决定：NSUK 2.2.0 的加载器其实只读 {@code version}，
     * 拦下去可能挡掉本来能用的包。格式只影响提示，所以要单独确认这两个判定没有互相污染。
     */
    @Test
    void formatMismatchDoesNotMakeThePackUnusable() {
        BuildingPack outdated = withFormat(PackFormat.FORMAT_1_20_1);
        assertTrue(outdated.formatMismatch());
        // 跨版本线的包仍应可以启用（usable() 挂在 PackFormat.Compatibility 上，
        // BuildingPack 这层只有 unusable()/faulty()）。
        assertFalse(outdated.unusable(), "跨版本线的包仍应可以启用");
        assertFalse(outdated.faulty(), "格式提示不该让包在列表里显示成故障");
    }

    @Test
    void formatMapsToTheMinecraftVersionLine() {
        assertEquals("1.20.1", withFormat(PackFormat.FORMAT_1_20_1).formatMinecraftVersion());
        assertEquals("1.21.1", withFormat(PackFormat.FORMAT_1_21_1).formatMinecraftVersion());
        assertEquals("26.3", withFormat(PackFormat.FORMAT_26_3).formatMinecraftVersion());
        assertNull(withFormat(99).formatMinecraftVersion());
    }

    /** 老调用点（不传 format）要退化成「未声明」，不能变成"格式不符"。 */
    @Test
    void legacyConstructorsDefaultToUndeclared() {
        BuildingPack legacy = pack("city", 3, List.of(), true);
        assertEquals(PackFormat.UNKNOWN, legacy.format());
        assertFalse(legacy.formatMismatch());
    }
}
