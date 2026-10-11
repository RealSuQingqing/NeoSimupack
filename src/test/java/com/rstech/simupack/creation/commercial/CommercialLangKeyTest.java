package com.rstech.simupack.creation.commercial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商业交易的界面文案在两个语言文件里都存在。
 *
 * <p>为什么单独一个测试：通用的那份「中英键集合一致」只比较两份文件彼此相等 ——
 * <b>两边同时漏掉一个键它是发现不了的</b>，而漏键的后果是界面上直接显示
 * {@code gui.simupack.commercial.issue.xxx} 这种原始键名。库存方向那几条警告是本编辑器
 * 用来解释「收购为什么没有每日上限」的唯一出口，不能悄悄变成键名。
 *
 * <p>反过来，校验器产出的 {@code code} 与语言键的对应关系（{@code issue.<code>}）
 * 依赖人工保持一致，所以这里把与库存相关的码逐一列出来当契约。
 */
class CommercialLangKeyTest {

    /** 库存方向（出售 / 收购）相关的键：校验码 + 按方向换的 tooltip。 */
    private static final List<String> STOCK_KEYS = List.of(
            "gui.simupack.commercial.issue.stock_orphan_item",
            "gui.simupack.commercial.issue.stock_no_tracking",
            "gui.simupack.commercial.issue.initial_over_max",
            "gui.simupack.commercial.issue.materials_disable_restock",
            "gui.simupack.commercial.issue.restock_disabled",
            "gui.simupack.commercial.issue.duplicate_stock_item",
            "gui.simupack.commercial.issue.result_without_stock",
            "gui.simupack.commercial.issue.intake_without_stock",
            "gui.simupack.commercial.issue.intake_stock_full_at_start",
            "gui.simupack.commercial.issue.intake_restock_raises_stock",
            "gui.simupack.commercial.issue.intake_materials_ignored",
            "gui.simupack.commercial.field.stock.max.tip.intake",
            "gui.simupack.commercial.field.stock.initial.tip.intake",
            "gui.simupack.commercial.restock_interval_tip",
            "gui.simupack.commercial.materials_tip",
            "gui.simupack.commercial.supply.unlimited",
            "gui.simupack.commercial.supply.stock",
            "gui.simupack.commercial.supply.materials");

    /** 会把交易里的物品名带进文案的键：少了 {@code %s} 用户就看不到是哪个物品。 */
    private static final List<String> ITEM_PLACEHOLDER_KEYS = List.of(
            "gui.simupack.commercial.issue.intake_without_stock",
            "gui.simupack.commercial.issue.intake_stock_full_at_start",
            "gui.simupack.commercial.issue.intake_restock_raises_stock",
            "gui.simupack.commercial.issue.intake_materials_ignored");

    private static JsonObject language(String locale) throws Exception {
        return JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/assets/simupack/lang/" + locale + ".json"))).getAsJsonObject();
    }

    @Test
    @DisplayName("库存相关的界面键在两个语言文件里都存在")
    void stockKeysExistInBothLocales() throws Exception {
        JsonObject zh = language("zh_cn");
        JsonObject en = language("en_us");
        for (String key : STOCK_KEYS) {
            assertTrue(zh.has(key), "zh_cn 缺少 " + key);
            assertTrue(en.has(key), "en_us 缺少 " + key);
        }
    }

    @Test
    @DisplayName("带物品名的收购警告必须真的含 %s")
    void intakeWarningsKeepTheirPlaceholder() throws Exception {
        JsonObject zh = language("zh_cn");
        JsonObject en = language("en_us");
        for (String key : ITEM_PLACEHOLDER_KEYS) {
            assertTrue(zh.get(key).getAsString().contains("%s"), "zh_cn 的 " + key + " 缺少 %s");
            assertTrue(en.get(key).getAsString().contains("%s"), "en_us 的 " + key + " 缺少 %s");
        }
    }
}
