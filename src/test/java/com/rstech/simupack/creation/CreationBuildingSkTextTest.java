package com.rstech.simupack.creation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code .sk} 文本的解析与再生成。
 *
 * <p><b>为什么专门测 BOM</b>：NSUK 的 {@code BuildingPackageCatalog} 读 {@code .sk} 时
 * <b>不剥 UTF-8 BOM</b>（它只在 {@code _files.txt} 上剥），而行键是按「行首 {@code key:}」
 * 匹配的。带 BOM 的文件首行是 {@code \uFEFFname: 某某}，于是 {@code name} 这个键整个认不出来，
 * 建筑在游戏里显示成文件名——建筑没丢，所以<b>没有任何报错</b>，只能靠肉眼发现名字不对。
 *
 * <p>Windows 记事本「另存为 UTF-8」就会加 BOM，而本模组的主要输入正是别人做的包，
 * 所以这条不是理论问题。
 */
class CreationBuildingSkTextTest {

    private static final String BOM = "\uFEFF";

    @Test
    @DisplayName("带 BOM 的 .sk：首行 name 仍要认出来，不能被 BOM 吞掉")
    void bomDoesNotSwallowTheFirstKey() {
        CreationBuilding building = new CreationBuilding("modern_apartment_small_1", "residential");
        building.applySkText(BOM + "name: 现代公寓（小）\nauthor: 某人\n");

        assertEquals("现代公寓（小）", building.skField("name"));
        assertEquals("现代公寓（小）", building.displayName());
        assertEquals("某人", building.skField("author"));
    }

    @Test
    @DisplayName("再生成时不能把 BOM 写回去：否则每编辑一次就多一层")
    void roundTripDropsTheBom() {
        CreationBuilding building = new CreationBuilding("house", "residential");
        building.applySkText(BOM + "name: 小屋\n");

        String written = building.toSkText();
        assertFalse(written.startsWith(BOM), "写出的 .sk 不该带 BOM");
        assertTrue(written.startsWith("name: 小屋\n"), "首行就该是 name，实测：" + written);
    }

    @Test
    @DisplayName("没有 BOM 的文件一个字都不改（这条改动不该影响正常输入）")
    void plainTextIsUntouched() {
        CreationBuilding building = new CreationBuilding("house", "residential");
        building.applySkText("name: 小屋\namount: 120\nhome_teleport: 1,2,3\n");

        assertEquals("小屋", building.skField("name"));
        assertEquals("120", building.skField("amount"));
        // 新版不读的键要原样留着，导出时不丢用户数据。
        assertEquals(1, building.extraSkLines().size());
        assertTrue(building.extraSkLines().get(0).contains("home_teleport"));
    }

    @Test
    @DisplayName("空文本 / null / 只有一个 BOM 都不该抛异常")
    void degenerateInputsAreSafe() {
        CreationBuilding building = new CreationBuilding("house", "residential");
        building.applySkText("");
        building.applySkText(null);
        building.applySkText(BOM);

        assertEquals("", building.skField("name"));
        // 没有任何 name 时展示名退化成 id —— 与 NSUK 的行为一致。
        assertEquals("house", building.displayName());
    }

    // ── 造价（amount）的取值范围 ───────────────────────────────────────
    //
    // 这一组来自一个真实缺陷：界面把造价输入限成 [0-9]*，而 NSUK 的 amount 是纯字符串、
    // 官方包大量写着 180.00元。于是打开任何一座官方公寓，造价框都是红的、还改不动。

    @Test
    @DisplayName("造价：合法值涵盖官方包里的真实写法")
    void amountAcceptsRealWorldValues() {
        // 官方包实测样本（见 build/sk_keys/official_extracted）
        assertTrue(CreationBuilding.isValidAmount("865.00"));
        assertTrue(CreationBuilding.isValidAmount("865.00元"));
        assertTrue(CreationBuilding.isValidAmount("1543.20元"));
        assertTrue(CreationBuilding.isValidAmount("180.00元"));
        assertTrue(CreationBuilding.isValidAmount("1000元"));
        assertTrue(CreationBuilding.isValidAmount("30"));
        assertTrue(CreationBuilding.isValidAmount(".5"));
        assertTrue(CreationBuilding.isValidAmount("20.00元 "));
        assertTrue(CreationBuilding.isValidAmount("$12.5"));
        // 留空合法：等同于不写这个键，NSUK 会回退成 "-" 占位。
        assertTrue(CreationBuilding.isValidAmount(""));
        assertTrue(CreationBuilding.isValidAmount("   "));
        assertTrue(CreationBuilding.isValidAmount(null));
    }

    @Test
    @DisplayName("造价：真正读不懂的值要拒绝")
    void amountRejectsGarbage() {
        assertFalse(CreationBuilding.isValidAmount("abc"));
        assertFalse(CreationBuilding.isValidAmount("8.6.5"));
        assertFalse(CreationBuilding.isValidAmount("元"));
        assertFalse(CreationBuilding.isValidAmount("865元元"));
        assertFalse(CreationBuilding.isValidAmount("-5"));
        assertFalse(CreationBuilding.isValidAmount("8 65"));
    }

    @Test
    @DisplayName("造价输入正则与键值正则完全一致（同一套数字语法）")
    void amountRegexAgreesWithKeyRegex() {
        Pattern input = Pattern.compile(CreationBuilding.AMOUNT_INPUT_REGEX);
        Pattern key = Pattern.compile(CreationBuilding.AMOUNT_KEY_REGEX);
        List<String> samples = List.of(
                "865", "865.", "865.00", "865.00元", "1543.20元", "1000元", "30", ".5",
                "$12.5", "abc", "8.6.5", "元", "865元元", "-5", "8 65", ".");
        for (String sample : samples) {
            assertEquals(key.matcher(sample).matches(), input.matcher(sample).matches(),
                    "两个正则对「" + sample + "」的结论不一致");
            // 键值校验器就是键值正则 + 空值放行，两者必须同结论。
            assertEquals(key.matcher(sample).matches(), CreationBuilding.isValidAmount(sample),
                    "校验器与键值正则对「" + sample + "」的结论不一致");
        }

        // 中间态必须能敲出来，否则用户永远写不到小数部分。
        assertTrue(input.matcher("865.").matches(), "输入中途的 865. 必须能敲出来");
        // 但单独一个 "." 不是值。
        assertFalse(input.matcher(".").matches());
        assertFalse(key.matcher(".").matches());

        // 唯一分歧（有意）：空串不是"键的值"（空值由 isValidAmount 单独放行），输入框正则也不接受。
        assertFalse(input.matcher("").matches());
        assertTrue(CreationBuilding.isValidAmount(""), "空值 = 不写这个键，合法");
    }

    // ── 解锁等级（unlockLevel）的取值范围 ─────────────────────────────

    @Test
    @DisplayName("解锁等级：0 与负数必须拒绝（会让整栋建筑被 NSUK 丢弃）")
    void unlockLevelMustBePositive() {
        assertTrue(CreationBuilding.isValidUnlockLevel(""));
        assertTrue(CreationBuilding.isValidUnlockLevel(null));
        assertTrue(CreationBuilding.isValidUnlockLevel("1"));
        assertTrue(CreationBuilding.isValidUnlockLevel("42"));
        assertTrue(CreationBuilding.isValidUnlockLevel(" 7 "));

        assertFalse(CreationBuilding.isValidUnlockLevel("0"));
        assertFalse(CreationBuilding.isValidUnlockLevel("-3"));
        assertFalse(CreationBuilding.isValidUnlockLevel("abc"));
        assertFalse(CreationBuilding.isValidUnlockLevel("1.5"));
        // 超出 int 范围：NSUK 那边 NumberFormatException，同样丢弃。
        assertFalse(CreationBuilding.isValidUnlockLevel("99999999999999999999"));
    }

    @Test
    @DisplayName("解锁等级：输入正则只多要求「非全零」，其余与键值校验一致")
    void unlockLevelRegexAgreesWithValidator() {
        Pattern input = Pattern.compile(CreationBuilding.UNLOCK_LEVEL_INPUT_REGEX);
        for (String sample : List.of("1", "42", "007", "0", "00", "-3", "abc", "1.5")) {
            assertEquals(CreationBuilding.isValidUnlockLevel(sample), input.matcher(sample).matches(),
                    "两个规则对「" + sample + "」的结论不一致");
        }
        // 前导零不是错误：NSUK 的 Integer.parseInt("007") 就是 7，界面不该拦它。
        assertTrue(CreationBuilding.isValidUnlockLevel("007"));
        assertTrue(input.matcher("007").matches());
        // 全零则必须拦下（等于 0，会让整栋建筑被丢弃）。
        assertFalse(CreationBuilding.isValidUnlockLevel("0"));
        assertFalse(input.matcher("0").matches());

        // 唯一分歧：空值对键合法（键不写 = 没有等级要求），对输入框不合法。
        assertTrue(CreationBuilding.isValidUnlockLevel(""));
        assertFalse(input.matcher("").matches());
    }

    // ── 写出时的格式规范 ───────────────────────────────────────────────

    @Test
    @DisplayName("size 写出去要跟官方包一样带空格：24 x 55 x 24")
    void sizeIsWrittenInTheOfficialSpacing() {
        assertEquals("24 x 55 x 24", CreationBuilding.normalizeSize("24x55x24"));
        assertEquals("24 x 55 x 24", CreationBuilding.normalizeSize("24 x 55 x 24"));
        assertEquals("24 x 55 x 24", CreationBuilding.normalizeSize("  24 X 55 * 24 "));
        // 认不出来就原样留着：官方包里甚至有一栋写的是「见预览」。
        assertEquals("见预览", CreationBuilding.normalizeSize("见预览"));
        assertEquals("", CreationBuilding.normalizeSize(null));
        assertEquals("4x5", CreationBuilding.normalizeSize("4x5"));
    }

    @Test
    @DisplayName("写出的 .sk 里 size 已规范化，读取时仍是原值")
    void toSkTextNormalizesSizeButParsingKeepsTheRawValue() {
        CreationBuilding building = new CreationBuilding("house", "residential");
        building.applySkText("name: 小屋\nsize:16x29x11\n");
        // 字段里存的还是用户写的形态（界面展示用原值）。
        assertEquals("16x29x11", building.skField("size"));
        // 但写出去的是官方格式。
        assertTrue(building.toSkText().contains("size: 16 x 29 x 11\n"), building.toSkText());
    }
}
