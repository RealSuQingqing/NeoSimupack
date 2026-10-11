package com.rstech.simupack.creation.residential;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 住宅分户相关语言键的回归。
 *
 * <p>这条测试来自一个真实缺陷：{@code MiddleTabKind.RESIDENTIAL_ARRAY} 曾经指向
 * {@code gui.simupack.residential.array}，而两个语言文件里都没有这个键 ——
 * 表现是页签标题直接显示原始 key。界面代码里的 key 都是字面量，
 * 编译器不会报错，所以只能靠这种「列清单」的测试兜住。
 */
class ResidentialLangKeyTest {

    /** 界面代码里直接写死、必须存在的键（改动面板时同步补这里）。 */
    private static final List<String> REQUIRED_KEYS = List.of(
            // 住宅设置页
            "gui.simupack.residential.section.units",
            "gui.simupack.residential.add_unit",
            "gui.simupack.residential.unit_default_name",
            "gui.simupack.residential.no_units",
            "gui.simupack.residential.no_range",
            "gui.simupack.residential.capacity",
            "gui.simupack.residential.unassigned_beds",
            "gui.simupack.residential.pick_range",
            "gui.simupack.residential.need_two_points",
            "gui.simupack.residential.pick_scope_changed",
            // 阵列页签
            "gui.simupack.residential.array",
            "gui.simupack.residential.array.sources",
            "gui.simupack.residential.array.select_all",
            "gui.simupack.residential.array.select_none",
            "gui.simupack.residential.array.select_source",
            "gui.simupack.residential.array.picked",
            "gui.simupack.residential.array.unpicked",
            "gui.simupack.residential.array.pick_overlap",
            "gui.simupack.residential.array.pick_outside",
            "gui.simupack.residential.array.miss",
            "gui.simupack.residential.array.no_source",
            "gui.simupack.residential.array.parameters",
            "gui.simupack.residential.array.offset_x",
            "gui.simupack.residential.array.offset_y",
            "gui.simupack.residential.array.offset_z",
            "gui.simupack.residential.array.new_count",
            "gui.simupack.residential.array.template",
            "gui.simupack.residential.array.name_tooltip",
            "gui.simupack.residential.array.name_preview",
            "gui.simupack.residential.array.preview_count",
            "gui.simupack.residential.array.blocked",
            "gui.simupack.residential.array.confirm",
            "gui.simupack.residential.array.generated",
            // 阵列校验问题码（ResidentialArrayValidator 里的小写字面量）
            "gui.simupack.residential.array.issue.empty",
            "gui.simupack.residential.array.issue.too_many",
            "gui.simupack.residential.array.issue.out_of_bounds",
            "gui.simupack.residential.array.issue.overlap_existing",
            "gui.simupack.residential.array.issue.overlap_self",
            "gui.simupack.residential.array.issue.duplicate_in_batch",
            "gui.simupack.residential.array.issue.duplicate_with_existing",
            "gui.simupack.residential.array.issue.source_no_range",
            "gui.simupack.residential.array.issue.invalid_spec");

    private JsonObject language(String locale) throws Exception {
        return JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/assets/simupack/lang/" + locale + ".json"))).getAsJsonObject();
    }

    @Test
    @DisplayName("中英两份语言文件的键集合完全一致")
    void languageFilesShareTheSameKeys() throws Exception {
        assertEquals(language("zh_cn").keySet(), language("en_us").keySet());
    }

    @Test
    @DisplayName("界面用到的住宅键在两份文件里都存在")
    void requiredResidentialKeysExist() throws Exception {
        JsonObject zh = language("zh_cn");
        JsonObject en = language("en_us");
        for (String key : REQUIRED_KEYS) {
            assertTrue(zh.has(key), "zh_cn 缺少 " + key);
            assertTrue(en.has(key), "en_us 缺少 " + key);
        }
    }

    @Test
    @DisplayName("带占位符的文案必须真的含 %s，否则用户看不到数字")
    void placeholderKeysKeepTheirPlaceholders() throws Exception {
        JsonObject zh = language("zh_cn");
        JsonObject en = language("en_us");
        List<String> withPlaceholder = List.of(
                "gui.simupack.residential.array.preview_count",
                "gui.simupack.residential.array.generated",
                "gui.simupack.residential.array.name_preview",
                "gui.simupack.residential.array.issue.too_many");
        for (String key : withPlaceholder) {
            assertTrue(zh.get(key).getAsString().contains("%s"), "zh_cn 的 " + key + " 缺少 %s");
            assertTrue(en.get(key).getAsString().contains("%s"), "en_us 的 " + key + " 缺少 %s");
        }
    }
}
