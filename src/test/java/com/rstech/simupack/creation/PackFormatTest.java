package com.rstech.simupack.creation;

import com.rstech.simupack.creation.PackFormat.Compatibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包格式号与 Minecraft 版本线的对应关系。
 *
 * <p>规定：<b>1.20.1 → format=1、1.21.1 → format=2、26.3 → format=3</b>。
 *
 * <p>这条测试是把规定钉死的地方。这个数字会同时出现在导出（写进 {@code pack.json}）、
 * 读取已安装包（解析、展示）与导入转换（判定兼容）三处，任何一处写错都会导致
 * "自己刚导出的包被自己判成别的版本线"，所以值本身也必须有测试。
 */
class PackFormatTest {

    @Test
    @DisplayName("三个版本线的格式号：1.20.1=1、1.21.1=2、26.3=3")
    void formatNumbersMatchTheRule() {
        assertEquals(1, PackFormat.FORMAT_1_20_1);
        assertEquals(2, PackFormat.FORMAT_1_21_1);
        assertEquals(3, PackFormat.FORMAT_26_3);
    }

    @Test
    @DisplayName("本模组所在的版本线（1.21.1）期望 format=2，导出就写这个值")
    void expectedIsTwoOnThisLine() {
        assertEquals(PackFormat.FORMAT_1_21_1, PackFormat.expected());
        assertEquals(2, PackFormat.expected(), "1.21.1 线必须是 2，官方包也是这样");
    }

    @Test
    @DisplayName("格式号能反查出 Minecraft 版本名；未知格式给 null")
    void formatMapsBackToMinecraftVersion() {
        assertEquals("1.20.1", PackFormat.minecraftVersion(PackFormat.FORMAT_1_20_1));
        assertEquals("1.21.1", PackFormat.minecraftVersion(PackFormat.FORMAT_1_21_1));
        assertEquals("26.3", PackFormat.minecraftVersion(PackFormat.FORMAT_26_3));
        assertNull(PackFormat.minecraftVersion(99));
        assertNull(PackFormat.minecraftVersion(PackFormat.UNKNOWN));
    }

    @Test
    @DisplayName("兼容判定：同版本线 MATCH、更早 OLDER、更新 NEWER、没写 UNDECLARED")
    void compatibilityFollowsTheOrdinalRelation() {
        assertEquals(Compatibility.MATCH, PackFormat.check(PackFormat.FORMAT_1_21_1));
        assertEquals(Compatibility.OLDER, PackFormat.check(PackFormat.FORMAT_1_20_1));
        assertEquals(Compatibility.NEWER, PackFormat.check(PackFormat.FORMAT_26_3));
        assertEquals(Compatibility.UNDECLARED, PackFormat.check(PackFormat.UNKNOWN));
    }

    @Test
    @DisplayName("只有 MATCH 与 UNDECLARED 能用；OLDER/NEWER 都要提示")
    void usableOnlyForMatchAndUndeclared() {
        assertTrue(Compatibility.MATCH.usable());
        assertTrue(Compatibility.UNDECLARED.usable(),
                "老包没写 format 不该被拦（1.20.1 时代的包大多没写）");
        assertFalse(Compatibility.OLDER.usable());
        assertFalse(Compatibility.NEWER.usable());
    }
}
