package com.rstech.simupack.creation.commercial;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.json.DefinitionIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommercialOfferModel} 的数据层回归测试。
 *
 * <p>夹具用的是官方建筑包里的真实文件（{@code run/simukraftbuilding/official_building.zip}
 * 内 {@code buildings/commercial/bread_shop.json} 与 {@code simu_fried_chicken.json}）——
 * 这两个覆盖了单资源与多资源两种形态。刻意内联成常量而不是运行时读 zip：
 * 单测要能脱离游戏目录跑。
 */
class CommercialOfferModelTest {

    /** 官方 {@code bread_shop.json} 原文。 */
    private static final String BREAD_SHOP = """
            {
              "id": "bread_shop",
              "name": "面包店",
              "job": {
                "id": "bread_shop_owner",
                "name": "面包店老板",
                "heldItem": "minecraft:bread"
              },
              "containers": {
                "input": { "type": "structure_pos", "positions": [[4,1,3], [5,1,3]] },
                "output": { "type": "structure_pos", "positions": [[4,1,3], [5,1,3]] }
              },
              "offers": [
                {
                  "id": "shop_sells_bread",
                  "visibleTo": "mixed",
                  "cost": [ { "money": 0.25 } ],
                  "result": [ { "item": "minecraft:bread", "count": 1 } ],
                  "stock": {
                    "item": "minecraft:bread",
                    "max": 64,
                    "materials": [ { "item": "minecraft:wheat", "count": 3 } ]
                  }
                },
                {
                  "id": "shop_buys_wheat",
                  "visibleTo": "player",
                  "cost": [ { "item": "minecraft:wheat", "count": 16 } ],
                  "result": [ { "money": 3.2 } ],
                  "stock": { "item": "minecraft:wheat", "max": 256 }
                },
                {
                  "id": "exchange_wheat_for_bread",
                  "visibleTo": "player",
                  "cost": [ { "item": "minecraft:wheat", "count": 3 } ],
                  "result": [ { "item": "minecraft:bread", "count": 1 } ],
                  "stock": { "item": "minecraft:bread", "max": 64 }
                }
              ]
            }
            """;

    /** 官方 {@code simu_fried_chicken.json} 的节选：保留多成本 + 多材料 + 无补货字段的库存。 */
    private static final String FRIED_CHICKEN = """
            {
              "id": "simu_fried_chicken",
              "name": "肯打鸡",
              "job": { "id": "fried_chicken_owner", "name": "肯打鸡服务员",
                       "heldItem": "minecraft:cooked_chicken" },
              "containers": { "input": { "type": "structure_pos", "positions": [[4,1,5]] } },
              "offers": [
                {
                  "id": "shop_sells_cheese_burger",
                  "visibleTo": "mixed",
                  "cost": [ { "money": 4.0 } ],
                  "result": [ { "item": "simukraft:cheese_burger", "count": 1 } ],
                  "stock": {
                    "item": "simukraft:cheese_burger",
                    "max": 64,
                    "materials": [
                      { "item": "minecraft:bread", "count": 1 },
                      { "item": "minecraft:beef", "count": 1 },
                      { "item": "simukraft:cheese_chunk", "count": 1 }
                    ]
                  }
                },
                {
                  "id": "exchange_bread_beef_for_hamburger",
                  "visibleTo": "player",
                  "cost": [
                    { "item": "minecraft:bread", "count": 1 },
                    { "item": "minecraft:beef", "count": 1 }
                  ],
                  "result": [ { "item": "simukraft:hamburger", "count": 1 } ],
                  "stock": { "item": "simukraft:hamburger", "max": 16 }
                },
                {
                  "id": "with_future_keys",
                  "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:stone", "count": 1 } ],
                  "stock": { "item": "minecraft:stone", "max": 8 },
                  "someFutureModKey": { "nested": [1, 2, 3], "flag": true }
                }
              ]
            }
            """;

    private static CommercialOfferModel wrap(String text) {
        return CommercialOfferModel.wrap(JsonParser.parseString(text).getAsJsonObject());
    }

    private static Set<String> codes(List<DefinitionIssue> issues) {
        return issues.stream().map(DefinitionIssue::code).collect(Collectors.toSet());
    }

    // ── 往返无损 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("往返无损：只读操作后整棵 JSON 逐字段不变（含 job.name / containers / 未来键）")
    void readOnlyUseNeverMutatesTheTree() {
        for (String text : List.of(BREAD_SHOP, FRIED_CHICKEN)) {
            JsonObject original = JsonParser.parseString(text).getAsJsonObject();
            CommercialOfferModel model = CommercialOfferModel.wrap(original);

            // 跑一遍全部只读 API。
            model.offers().forEach(offer -> {
                model.offerId(offer);
                model.visibleTo(offer);
                CommercialOfferModel.tabsOf(offer);
                CommercialOfferModel.isUnclassified(offer);
                CommercialOfferModel.costArray(offer, false);
                CommercialOfferModel.resultArray(offer, false);
                JsonObject stock = model.stock(offer, false);
                CommercialOfferModel.stockMode(stock);
                CommercialOfferModel.restockEnabled(stock);
                CommercialOfferModel.stockFlow(offer, stock);
            });
            model.validate();

            assertEquals(JsonParser.parseString(text).getAsJsonObject(), model.root(),
                    "只读操作不得改动任何键");
        }
    }

    @Test
    @DisplayName("往返无损：官方面包店与肯打鸡的报价数与 id 都读得出来")
    void officialFilesParseAsExpected() {
        CommercialOfferModel bread = wrap(BREAD_SHOP);
        assertEquals(3, bread.offers().size());
        assertEquals(List.of("shop_sells_bread", "shop_buys_wheat", "exchange_wheat_for_bread"),
                bread.offers().stream().map(bread::offerId).toList());

        CommercialOfferModel chicken = wrap(FRIED_CHICKEN);
        assertEquals(3, chicken.offers().size());
        assertEquals(3, CommercialOfferModel.materialsArray(
                chicken.stock(chicken.offerById("shop_sells_cheese_burger"), false), false).size());
        assertEquals(2, CommercialOfferModel.costArray(
                chicken.offerById("exchange_bread_beef_for_hamburger"), false).size());
    }

    @Test
    @DisplayName("官方文件本身是干净的：只有「产出物品缺库存规则」这类提示，没有错误级问题")
    void officialFilesHaveNoErrors() {
        for (String text : List.of(BREAD_SHOP, FRIED_CHICKEN)) {
            List<DefinitionIssue> issues = wrap(text).validate();
            List<DefinitionIssue> errors = issues.stream()
                    .filter(issue -> issue.severity() == DefinitionIssue.Severity.ERROR).toList();
            assertEquals(List.of(), errors, "官方文件不该有错误级校验：" + errors);
        }
    }

    // ── 最坑的一条：money 遮蔽 item ────────────────────────────────────

    @Test
    @DisplayName("money 遮蔽 item：同元素两者都有时 item 被静默忽略，且校验会报出来（但不改 JSON）")
    void moneyShadowsItemIsReportedWithoutMutating() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                    "id": "both",
                    "cost": [ { "money": 3.0, "item": "minecraft:wheat", "count": 16 } ],
                    "result": [ { "item": "minecraft:bread", "count": 1 } ]
                } ] }
                """);
        JsonObject resource = CommercialOfferModel.costArray(model.offers().get(0), false)
                .get(0).getAsJsonObject();

        // NSUK 按「有没有 money 键」判断：这笔实际只扣钱，写进去的物品不会生效。
        assertEquals(CommercialOfferModel.ResourceKind.MONEY, CommercialOfferModel.kindOf(resource));
        assertEquals(3.0D, CommercialOfferModel.moneyOf(resource), 1e-9);
        assertEquals("", CommercialOfferModel.itemOf(resource));

        assertTrue(codes(model.validate()).contains("money_shadows_item"));

        // 校验是只读的：两个键都必须原样留着，不能替用户「修好」。
        assertTrue(resource.has("money"));
        assertTrue(resource.has("item"));
    }

    @Test
    @DisplayName("写入资金/物品形态时会主动清掉另一形态的键（避免制造 money 遮蔽 item）")
    void writingOneResourceKindClearsTheOther() {
        JsonObject resource = JsonParser.parseString(
                "{ \"money\": 3.0, \"item\": \"minecraft:wheat\", \"count\": 16 }").getAsJsonObject();

        CommercialOfferModel.writeMoney(resource, 2.5D);
        assertEquals(1, resource.size());
        assertEquals(2.5D, CommercialOfferModel.moneyOf(resource), 1e-9);

        CommercialOfferModel.writeItem(resource, "minecraft:bread", 4);
        assertEquals(2, resource.size());
        assertEquals("minecraft:bread", CommercialOfferModel.itemOf(resource));
        assertEquals(4, CommercialOfferModel.countOf(resource));
        assertFalse(resource.has("money"));
    }

    // ── 最危险的一条：静默回退旧格式 ──────────────────────────────────

    @Test
    @DisplayName("offers 全部无效 == offers 为空：都会让 NSUK 静默改用旧格式解析器")
    void allInvalidOffersEqualsEmptyForNsukFallback() {
        // 每条都缺 result → NSUK 的 valid() 为假 → 解析结果为空 → 回退旧格式。
        CommercialOfferModel allInvalid = wrap("""
                { "offers": [
                    { "id": "a", "cost": [ { "money": 1.0 } ], "result": [] },
                    { "id": "b", "cost": [], "result": [ { "item": "minecraft:stone" } ] }
                ] }
                """);
        Set<String> invalidCodes = codes(allInvalid.validate());
        assertTrue(invalidCodes.contains("offers_legacy_fallback"), "必须报静默回退");
        assertTrue(invalidCodes.contains("empty_result"));
        assertTrue(invalidCodes.contains("empty_cost"));

        CommercialOfferModel empty = wrap("{ \"offers\": [] }");
        assertTrue(codes(empty.validate()).contains("missing_offers"));
    }

    @Test
    @DisplayName("只要有一条有效报价，旧格式键就完全不被读取 —— 但仍要提示用户它们在那儿")
    void legacyKeysAreIgnoredOnceOneOfferIsValid() {
        CommercialOfferModel model = wrap("""
                {
                  "shopMode": "NPC_SELL",
                  "trades": [ { "item": "minecraft:bread", "sellPrice": 0.5 } ],
                  "offers": [ {
                    "id": "ok", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:stone", "count": 1 } ],
                    "stock": { "item": "minecraft:stone", "max": 8 }
                  } ]
                }
                """);
        Set<String> issues = codes(model.validate());
        assertTrue(issues.contains("legacy_keys_present"));
        assertFalse(issues.contains("offers_legacy_fallback"), "有一条有效报价就不该回退");
    }

    // ── 上限（全部静默截断） ───────────────────────────────────────────

    @Test
    @DisplayName("报价上限 256：刚好不报，超一条报 too_many_offers")
    void offerLimitBoundary() {
        assertFalse(codes(wrap(offersJson(CommercialOfferModel.MAX_OFFERS)).validate())
                .contains("too_many_offers"));
        assertTrue(codes(wrap(offersJson(CommercialOfferModel.MAX_OFFERS + 1)).validate())
                .contains("too_many_offers"));
    }

    @Test
    @DisplayName("单侧资源上限 4：刚好不报，第 5 条报 too_many_resources（会被静默丢弃）")
    void resourceLimitBoundary() {
        assertFalse(codes(wrap(costResourcesJson(CommercialOfferModel.MAX_RESOURCES)).validate())
                .contains("too_many_resources"));
        assertTrue(codes(wrap(costResourcesJson(CommercialOfferModel.MAX_RESOURCES + 1)).validate())
                .contains("too_many_resources"));
    }

    @Test
    @DisplayName("材料上限 4：第 5 条报 too_many_materials")
    void materialLimitBoundary() {
        assertTrue(codes(wrap(materialsJson(CommercialOfferModel.MAX_RESOURCES + 1)).validate())
                .contains("too_many_materials"));
        assertFalse(codes(wrap(materialsJson(CommercialOfferModel.MAX_RESOURCES)).validate())
                .contains("too_many_materials"));
    }

    // ── 库存三模式 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("库存三模式：材料供给 / SQLite 自动补货 / 完全不受限")
    void stockModes() {
        CommercialOfferModel model = wrap("""
                { "offers": [
                  { "id": "material", "cost": [ { "money": 0.25 } ],
                    "result": [ { "item": "minecraft:bread", "count": 1 } ],
                    "stock": { "item": "minecraft:bread", "max": 64,
                               "materials": [ { "item": "minecraft:wheat", "count": 3 } ] } },
                  { "id": "sqlite", "cost": [ { "money": 2.0 } ],
                    "result": [ { "item": "minecraft:cake", "count": 1 } ],
                    "stock": { "item": "minecraft:cake", "max": 16, "initial": 8,
                               "restockAmount": 4, "restockInterval": 12000 } },
                  { "id": "untracked", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:stick", "count": 1 } ],
                    "stock": { "item": "minecraft:stick", "max": 0 } }
                ] }
                """);

        JsonObject material = model.stock(model.offerById("material"), false);
        assertEquals(CommercialOfferModel.StockMode.MATERIAL_BACKED,
                CommercialOfferModel.stockMode(material));
        assertFalse(CommercialOfferModel.restockEnabled(material));

        JsonObject sqlite = model.stock(model.offerById("sqlite"), false);
        assertEquals(CommercialOfferModel.StockMode.SQLITE_BACKED,
                CommercialOfferModel.stockMode(sqlite));
        assertTrue(CommercialOfferModel.restockEnabled(sqlite));

        JsonObject untracked = model.stock(model.offerById("untracked"), false);
        assertEquals(CommercialOfferModel.StockMode.NONE, CommercialOfferModel.stockMode(untracked));
        assertFalse(CommercialOfferModel.restockEnabled(untracked));
    }

    @Test
    @DisplayName("materials 非空会禁用自动补货：三个补货字段全被忽略，要提示而不是当错误")
    void materialsDisableRestockIsAWarningNotAnError() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "x", "cost": [ { "money": 0.25 } ],
                  "result": [ { "item": "minecraft:bread", "count": 1 } ],
                  "stock": { "item": "minecraft:bread", "max": 64, "initial": 8,
                             "restockAmount": 4, "restockInterval": 12000,
                             "materials": [ { "item": "minecraft:wheat", "count": 3 } ] }
                } ] }
                """);
        List<DefinitionIssue> issues = model.validate();
        DefinitionIssue issue = issues.stream()
                .filter(i -> i.code().equals("materials_disable_restock")).findFirst().orElse(null);
        assertNotNull(issue, "写了 materials 又写补货字段时必须提示");
        // 这是设计意图而不是 bug，语气要中性，所以不能是错误级。
        assertEquals(DefinitionIssue.Severity.WARNING, issue.severity());
        // 而且它确实不再自动补货。
        assertFalse(CommercialOfferModel.restockEnabled(
                model.stock(model.offerById("x"), false)));
    }

    @Test
    @DisplayName("initial 超过 max：报 initial_over_max（NSUK 会静默夹到 max）")
    void initialOverMaxIsReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "x", "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:cake", "count": 1 } ],
                  "stock": { "item": "minecraft:cake", "max": 4, "initial": 99 }
                } ] }
                """);
        assertTrue(codes(model.validate()).contains("initial_over_max"));
    }

    @Test
    @DisplayName("孤儿库存：stock.item 既不在 cost 也不在 result 里，这条规则什么都不做")
    void orphanStockIsReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "x", "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:bread", "count": 1 } ],
                  "stock": { "item": "minecraft:apple", "max": 64 }
                } ] }
                """);
        assertTrue(codes(model.validate()).contains("stock_orphan_item"));
    }

    @Test
    @DisplayName("产出物品全库没有库存规则 → 不受库存限制（经济漏洞，警告级）")
    void resultWithoutAnyStockRuleIsReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "x", "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:diamond", "count": 1 } ]
                } ] }
                """);
        assertTrue(codes(model.validate()).contains("result_without_stock"));
    }

    @Test
    @DisplayName("同一物品在多条报价里带库存规则 → 只有第一次遇到的生效")
    void duplicateStockItemAcrossOffersIsReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [
                  { "id": "a", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:bread", "count": 1 } ],
                    "stock": { "item": "minecraft:bread", "max": 64 } },
                  { "id": "b", "cost": [ { "item": "minecraft:wheat", "count": 3 } ],
                    "result": [ { "item": "minecraft:bread", "count": 1 } ],
                    "stock": { "item": "minecraft:bread", "max": 16 } }
                ] }
                """);
        assertTrue(codes(model.validate()).contains("duplicate_stock_item"));
    }

    // ── 库存方向（出售 / 收购） ────────────────────────────────────────

    @Test
    @DisplayName("库存流向：产出里是出库、成本里是入库，两侧都有时看净数量")
    void stockFlowFollowsTheSideTheItemSitsOn() {
        CommercialOfferModel sells = wrap("""
                { "offers": [ {
                  "id": "sell", "cost": [ { "money": 0.25 } ],
                  "result": [ { "item": "minecraft:bread", "count": 1 } ],
                  "stock": { "item": "minecraft:bread", "max": 64 }
                } ] }
                """);
        assertEquals(CommercialOfferModel.StockFlow.LEAVES,
                CommercialOfferModel.stockFlow(sells.offerById("sell"),
                        sells.stock(sells.offerById("sell"), false)));

        // 官方面包店那条收购：成本 16 小麦、产出资金 —— 成交时小麦是往库存里加的。
        CommercialOfferModel bread = wrap(BREAD_SHOP);
        JsonObject buys = bread.offerById("shop_buys_wheat");
        assertEquals(CommercialOfferModel.StockFlow.ENTERS,
                CommercialOfferModel.stockFlow(buys, bread.stock(buys, false)));
        // 入库为正、出库为负，与 NSUK 的 stockDeltas 同号。
        assertEquals(16, CommercialOfferModel.stockNetDelta(buys, "minecraft:wheat"));

        // 两侧都有：净数量为正就算入库（这里 3 出、1 进不了库存 → 净 −2 = 出库）。
        CommercialOfferModel both = wrap("""
                { "offers": [ {
                  "id": "both", "cost": [ { "item": "minecraft:wheat", "count": 1 } ],
                  "result": [ { "item": "minecraft:wheat", "count": 3 } ],
                  "stock": { "item": "minecraft:wheat", "max": 64 }
                } ] }
                """);
        JsonObject mixed = both.offerById("both");
        assertEquals(CommercialOfferModel.StockFlow.LEAVES,
                CommercialOfferModel.stockFlow(mixed, both.stock(mixed, false)));

        // 两侧都没有 → 死规则（已有 stock_orphan_item 覆盖）。
        assertEquals(CommercialOfferModel.StockFlow.NONE,
                CommercialOfferModel.stockFlow(buys, "minecraft:apple"));
        assertEquals(CommercialOfferModel.StockFlow.NONE,
                CommercialOfferModel.stockFlow(buys, ""));
    }

    @Test
    @DisplayName("收购（入库型）：初始 = 上限 → 开局满仓，报 intake_stock_full_at_start")
    void intakeStockFullAtStartIsReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "buy", "cost": [ { "item": "minecraft:wheat", "count": 64 } ],
                  "result": [ { "money": 3.2 } ],
                  "stock": { "item": "minecraft:wheat", "max": 1024, "initial": 1024 }
                } ] }
                """);
        Set<String> issues = codes(model.validate());
        assertTrue(issues.contains("intake_stock_full_at_start"),
                "收购站开局就满仓必须报出来（NSUK 会一直回 stock_full）");
        // 同一条配置在出售上是完全正常的（货架一开始就是满的），不能误伤。
        CommercialOfferModel sell = wrap("""
                { "offers": [ {
                  "id": "sell", "cost": [ { "money": 0.25 } ],
                  "result": [ { "item": "minecraft:bread", "count": 1 } ],
                  "stock": { "item": "minecraft:bread", "max": 64, "initial": 64 }
                } ] }
                """);
        assertFalse(codes(sell.validate()).contains("intake_stock_full_at_start"));
    }

    @Test
    @DisplayName("收购（入库型）：写了补货 → 只会把「已收」往上推，报 intake_restock_raises_stock")
    void intakeRestockIsReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "buy", "cost": [ { "item": "minecraft:wheat", "count": 64 } ],
                  "result": [ { "money": 3.2 } ],
                  "stock": { "item": "minecraft:wheat", "max": 4096, "initial": 0,
                             "restockAmount": 512, "restockInterval": 14400 }
                } ] }
                """);
        Set<String> issues = codes(model.validate());
        assertTrue(issues.contains("intake_restock_raises_stock"));
        // 没有「开局满仓」这条 —— 两者是不同的故障，别混在一起报。
        assertFalse(issues.contains("intake_stock_full_at_start"));

        // 补货字段留空的收购（正确的「总量上限」写法）什么也不该报。
        CommercialOfferModel clean = wrap("""
                { "offers": [ {
                  "id": "buy", "cost": [ { "item": "minecraft:wheat", "count": 64 } ],
                  "result": [ { "money": 3.2 } ],
                  "stock": { "item": "minecraft:wheat", "max": 4096, "initial": 0 }
                } ] }
                """);
        Set<String> cleanIssues = codes(clean.validate());
        assertFalse(cleanIssues.contains("intake_restock_raises_stock"));
        assertFalse(cleanIssues.contains("intake_stock_full_at_start"));
        assertFalse(cleanIssues.contains("intake_without_stock"));
    }

    @Test
    @DisplayName("收购（入库型）：材料供给不生效还会让上限失效 → 报 intake_materials_ignored")
    void intakeMaterialsAreReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "buy", "cost": [ { "item": "minecraft:wheat", "count": 64 } ],
                  "result": [ { "money": 3.2 } ],
                  "stock": { "item": "minecraft:wheat", "max": 1024,
                             "materials": [ { "item": "minecraft:bone_meal", "count": 1 } ] }
                } ] }
                """);
        assertTrue(codes(model.validate()).contains("intake_materials_ignored"));
        // 官方面包店那条材料供给是出库型，不受这条影响。
        assertFalse(codes(wrap(BREAD_SHOP).validate()).contains("intake_materials_ignored"));
    }

    @Test
    @DisplayName("库存物品为空时没有方向可言：收购报价不报 intake_*（规则本来就不跟踪）")
    void blankStockItemHasNoFlow() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "buy", "cost": [ { "item": "minecraft:wheat", "count": 64 } ],
                  "result": [ { "money": 3.2 } ],
                  "stock": { "max": 1024, "initial": 1024 }
                } ] }
                """);
        JsonObject offer = model.offerById("buy");
        // 方向只能从**写下来的**物品判：物品为空时两个判定都是 false，这条规则什么都不做。
        assertEquals(CommercialOfferModel.StockFlow.NONE,
                CommercialOfferModel.stockFlow(offer, model.stock(offer, false)));
        Set<String> issues = codes(model.validate());
        assertTrue(issues.contains("stock_no_tracking"));
        assertFalse(issues.contains("intake_stock_full_at_start"),
                "面板也按同一判据，物品为空时不能凭空推断方向");
    }

    @Test
    @DisplayName("收购物品全库没有库存规则 → 无限收货（与 result_without_stock 对称）")
    void intakeWithoutAnyStockRuleIsReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "buy", "cost": [ { "item": "minecraft:wheat", "count": 64 } ],
                  "result": [ { "money": 3.2 } ]
                } ] }
                """);
        assertTrue(codes(model.validate()).contains("intake_without_stock"));

        // 交换（成本物品、产出也是物品且没有资金）不是收购，成本物品本来就不跟踪。
        CommercialOfferModel barter = wrap("""
                { "offers": [ {
                  "id": "trade", "cost": [ { "item": "minecraft:wheat", "count": 3 } ],
                  "result": [ { "item": "minecraft:bread", "count": 1 } ]
                } ] }
                """);
        assertFalse(codes(barter.validate()).contains("intake_without_stock"));
    }

    @Test
    @DisplayName("阶段 3 的形态切换：money ⇄ item 之后只剩一个形态的键，且 Tab 归类随之改变")
    void switchingResourceKindChangesShapeAndKeepsSingleForm() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "x",
                  "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:stone", "count": 1 } ],
                  "stock": { "item": "minecraft:stone", "max": 8 }
                } ] }
                """);
        JsonObject offer = model.offers().get(0);
        JsonObject cost = CommercialOfferModel.costArray(offer, false).get(0).getAsJsonObject();

        // 钱换物 → 出售
        assertEquals(Set.of(CommercialOfferModel.TradeTab.SELL),
                CommercialOfferModel.tabsOf(offer));

        // 把成本那项从资金切成物品：键必须换干净，不能两个形态并存
        CommercialOfferModel.writeItem(cost, "minecraft:cobblestone", 2);
        assertFalse(cost.has("money"), "切成物品后不能再留着 money 键");
        assertEquals("minecraft:cobblestone", CommercialOfferModel.itemOf(cost));
        assertEquals(2, CommercialOfferModel.countOf(cost));

        // 于是形态变成「物换物」
        assertEquals(Set.of(CommercialOfferModel.TradeTab.BARTER),
                CommercialOfferModel.tabsOf(offer));
        assertFalse(codes(model.validate()).contains("money_shadows_item"));

        // 再切回资金
        CommercialOfferModel.writeMoney(cost, 3.5D);
        assertFalse(cost.has("item"));
        assertFalse(cost.has("count"));
        assertEquals(Set.of(CommercialOfferModel.TradeTab.SELL),
                CommercialOfferModel.tabsOf(offer));
    }

    @Test
    @DisplayName("删掉全部成本资源 == 空成本：整条交易失效，进而等价于 offers 为空（会静默回退旧格式）")
    void removingAllCostResourcesCascadesToTheLegacyFallback() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "only_one",
                  "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:stone", "count": 1 } ],
                  "stock": { "item": "minecraft:stone", "max": 8 }
                } ] }
                """);
        JsonArray cost = CommercialOfferModel.costArray(model.offers().get(0), false);
        cost.remove(0);

        Set<String> issues = codes(model.validate());
        assertTrue(issues.contains("empty_cost"));
        // 唯一一条交易失效之后，NSUK 解析结果为空 → 会去读旧格式键。
        assertTrue(issues.contains("offers_legacy_fallback"),
                "唯一一条交易被清空后必须报「会回退旧格式」，这是最危险的状态");
    }

    @Test
    @DisplayName("阶段 4：新建库存规则时的候选物品取自产出/成本，从构造上避开「孤儿库存」")
    void suggestedStockItemComesFromResultThenCost() {
        CommercialOfferModel model = wrap("""
                { "offers": [
                  { "id": "has_result_item", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:bread", "count": 1 } ] },
                  { "id": "money_result_item_cost",
                    "cost": [ { "item": "minecraft:wheat", "count": 16 } ],
                    "result": [ { "money": 3.2 } ] },
                  { "id": "no_items_at_all", "cost": [ { "money": 1.0 } ],
                    "result": [ { "money": 2.0 } ] }
                ] }
                """);

        // 产出里有物品 → 取产出的（这是商店真正在卖的东西）
        assertEquals("minecraft:bread",
                CommercialOfferModel.suggestStockItem(model.offerById("has_result_item")));
        // 产出只有钱 → 退而取成本里的物品（商店在收的东西）
        assertEquals("minecraft:wheat",
                CommercialOfferModel.suggestStockItem(model.offerById("money_result_item_cost")));
        // 两侧都没有物品 → 空串，由界面决定兜底值
        assertEquals("",
                CommercialOfferModel.suggestStockItem(model.offerById("no_items_at_all")));

        // 用建议值建一条库存规则，不该报孤儿库存
        JsonObject offer = model.offerById("has_result_item");
        JsonObject stock = model.stock(offer, true);
        stock.addProperty(CommercialOfferModel.ITEM,
                CommercialOfferModel.suggestStockItem(offer));
        stock.addProperty(CommercialOfferModel.MAX, 64);
        assertFalse(codes(model.validate()).contains("stock_orphan_item"));
    }

    @Test
    @DisplayName("阶段 4：库存规则可加可删；删掉之后不再报与库存相关的任何问题")
    void stockRuleCanBeAddedAndRemoved() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "x", "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:bread", "count": 1 } ]
                } ] }
                """);
        JsonObject offer = model.offers().get(0);

        // 没有库存规则：产出物品不受库存限制（警告），但这不是错误
        Set<String> before = codes(model.validate());
        assertTrue(before.contains("result_without_stock"));
        assertFalse(before.contains("stock_orphan_item"));

        // 加上规则（上限 0 → 仍然不跟踪）
        JsonObject stock = model.stock(offer, true);
        stock.addProperty(CommercialOfferModel.ITEM, "minecraft:bread");
        stock.addProperty(CommercialOfferModel.MAX, 0);
        assertEquals(CommercialOfferModel.StockMode.NONE, CommercialOfferModel.stockMode(stock));
        assertTrue(codes(model.validate()).contains("stock_no_tracking"));

        // 给一个有意义的上下限 → 变成 SQLite 库存型，警告消失
        stock.addProperty(CommercialOfferModel.MAX, 64);
        assertEquals(CommercialOfferModel.StockMode.SQLITE_BACKED, CommercialOfferModel.stockMode(stock));
        assertFalse(codes(model.validate()).contains("result_without_stock"));

        // 删掉规则 → 又回到「不受库存限制」
        assertTrue(model.removeStock(offer));
        assertTrue(codes(model.validate()).contains("result_without_stock"));
    }

    @Test
    @DisplayName("物品枚举：四处（cost / result / stock.item / materials）的物品 id 与位置都要列出来，供界面查注册表")
    void itemIdsCoversAllFourLocations() {
        CommercialOfferModel model = wrap(BREAD_SHOP);
        List<CommercialOfferModel.ItemRef> refs = model.itemIds();

        // 面包店：物品出现在产出、库存物品、库存材料、其它交易的成本四处。
        assertTrue(refs.stream().anyMatch(r ->
                r.itemId().equals("minecraft:bread")
                        && r.location().equals("offers[0].result[0].item")));
        assertTrue(refs.stream().anyMatch(r ->
                r.itemId().equals("minecraft:bread")
                        && r.location().equals("offers[0].stock.item")));
        assertTrue(refs.stream().anyMatch(r ->
                r.itemId().equals("minecraft:wheat")
                        && r.location().equals("offers[0].stock.materials[0].item")));
        assertTrue(refs.stream().anyMatch(r ->
                r.itemId().equals("minecraft:wheat")
                        && r.location().equals("offers[1].cost[0].item")));

        // 每个位置都必须是 "offers[...]" 开头，界面才能据此定位
        assertTrue(refs.stream().allMatch(r -> r.location().startsWith("offers[")));
        // 资金不会被当成物品列出来
        assertTrue(refs.stream().noneMatch(r -> r.itemId().isBlank()));
    }

    @Test
    @DisplayName("物品枚举：只有资金、没有物品时返回空列表；库存物品为空也不列")
    void itemIdsSkipsMoneyOnlyAndBlankStock() {
        CommercialOfferModel moneyOnly = wrap("""
                { "offers": [ { "id": "x", "cost": [ { "money": 1.0 } ],
                                "result": [ { "money": 2.0 } ] } ] }
                """);
        assertEquals(List.of(), moneyOnly.itemIds());

        CommercialOfferModel blankStock = wrap("""
                { "offers": [ { "id": "x", "cost": [ { "money": 1.0 } ],
                                "result": [ { "item": "minecraft:stone", "count": 1 } ],
                                "stock": { "max": 8 } } ] }
                """);
        List<CommercialOfferModel.ItemRef> refs = blankStock.itemIds();
        assertEquals(1, refs.size());
        assertEquals("minecraft:stone", refs.get(0).itemId());
        assertEquals("offers[0].result[0].item", refs.get(0).location());
    }

    @Test
    @DisplayName("资金角标格式照抄 NSUK：去尾随 0 与小数点（角标很窄，\"3.20\" 会顶到相邻图标）")
    void shortMoneyMatchesNsukMoneyShort() {
        assertEquals("3.2", CommercialOfferModel.shortMoney(3.2D));
        assertEquals("3", CommercialOfferModel.shortMoney(3.0D));
        assertEquals("0", CommercialOfferModel.shortMoney(0.0D));
        assertEquals("1.05", CommercialOfferModel.shortMoney(1.05D));
        assertEquals("12.5", CommercialOfferModel.shortMoney(12.5D));
        assertEquals("100", CommercialOfferModel.shortMoney(100.0D));
        // 四舍五入到两位；负数也照常（校验会把负数报出来，但格式化不该崩）
        assertEquals("0.33", CommercialOfferModel.shortMoney(0.333D));
        assertEquals("0.01", CommercialOfferModel.shortMoney(0.005D));
        assertEquals("-2.5", CommercialOfferModel.shortMoney(-2.5D));
    }

    @Test
    @DisplayName("库存候选物品会避开已被别的报价跟踪的物品（键是控制盒+物品，同建筑内重复=死规则）")
    void suggestedStockItemAvoidsAlreadyTrackedItems() {
        CommercialOfferModel model = wrap("""
                { "offers": [
                  { "id": "first", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:stone", "count": 1 } ],
                    "stock": { "item": "minecraft:stone", "max": 64 } },
                  { "id": "second", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:stone", "count": 1 },
                                { "item": "minecraft:bread", "count": 1 } ] }
                ] }
                """);
        JsonObject second = model.offerById("second");

        // 已被 first 跟踪：stone 是原本的默认候选，但应让位给没被跟踪的 bread
        assertEquals(List.of("minecraft:stone"), model.trackedStockItems(null));
        assertEquals("minecraft:bread",
                CommercialOfferModel.suggestStockItem(second, model.trackedStockItems(null)));

        // 排除自己那条：first 自己再建规则时，不该把自己的 stone 当成冲突。
        // 注意结果是空 —— 排除 stone 之后就没有别的被跟踪物品了。
        assertEquals(List.of(), model.trackedStockItems("minecraft:stone"));
        // 于是它仍然会（也应该）选 stone：自己已有的规则不算冲突。
        assertEquals("minecraft:stone",
                CommercialOfferModel.suggestStockItem(model.offerById("first"),
                        model.trackedStockItems("minecraft:stone")));

        // 所有候选都被跟踪时，退回第一个候选而不是给空（那会有明确的重复警告）
        CommercialOfferModel allTracked = wrap("""
                { "offers": [
                  { "id": "a", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:stone", "count": 1 } ],
                    "stock": { "item": "minecraft:stone", "max": 64 } },
                  { "id": "b", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:stone", "count": 1 } ] }
                ] }
                """);
        assertEquals("minecraft:stone", CommercialOfferModel.suggestStockItem(
                allTracked.offerById("b"), allTracked.trackedStockItems(null)));
    }

    @Test
    @DisplayName("供给方式：三态与键对应；卖完即止与自动补货是同一个模式（看补货字段填没填）")
    void supplyModeRoundTripsAndKeepsOnlyRelevantKeys() {
        // 反推：从键回到下拉框选项
        assertEquals(CommercialOfferModel.SupplyMode.UNLIMITED,
                CommercialOfferModel.SupplyMode.of(null));
        assertEquals(CommercialOfferModel.SupplyMode.MATERIALS,
                CommercialOfferModel.SupplyMode.of(stockJson("""
                        { "item": "minecraft:bread", "max": 64,
                          "materials": [ { "item": "minecraft:wheat", "count": 3 } ] }""")));
        // max<=0 等于规则不生效 → 如实报「不受限」，而不是谎称有库存
        assertEquals(CommercialOfferModel.SupplyMode.UNLIMITED,
                CommercialOfferModel.SupplyMode.of(stockJson("""
                        { "item": "minecraft:bread", "max": 0 }""")));

        // **合并的关键**：补货字段空着与填着，反推出来是同一个 STOCK 模式，
        // 区别只体现在 restockEnabled —— 用户不必在两个选项之间做选择。
        JsonObject limited = stockJson("""
                { "item": "minecraft:bread", "max": 16 }""");
        JsonObject restocking = stockJson("""
                { "item": "minecraft:bread", "max": 16, "initial": 8,
                  "restockAmount": 4, "restockInterval": 12000 }""");
        assertEquals(CommercialOfferModel.SupplyMode.STOCK,
                CommercialOfferModel.SupplyMode.of(limited));
        assertEquals(CommercialOfferModel.SupplyMode.STOCK,
                CommercialOfferModel.SupplyMode.of(restocking));
        assertFalse(CommercialOfferModel.restockEnabled(limited));
        assertTrue(CommercialOfferModel.restockEnabled(restocking));

        // 材料非空优先（NSUK：materialBacked 一成立就不是 sqliteBacked）
        assertEquals(CommercialOfferModel.SupplyMode.MATERIALS,
                CommercialOfferModel.SupplyMode.of(stockJson("""
                        { "item": "minecraft:bread", "max": 64, "restockAmount": 4,
                          "restockInterval": 12000,
                          "materials": [ { "item": "minecraft:wheat", "count": 3 } ] }""")));

        // STOCK → MATERIALS：材料长出至少一行，initial 与补货键必须清掉
        //（留着会被校验报 materials_disable_restock —— 编辑器不该指责自己写出的状态）
        assertFalse(CommercialOfferModel.applySupplyMode(restocking,
                CommercialOfferModel.SupplyMode.MATERIALS));
        assertEquals(CommercialOfferModel.SupplyMode.MATERIALS,
                CommercialOfferModel.SupplyMode.of(restocking));
        assertFalse(restocking.has("initial"));
        assertFalse(restocking.has("restockAmount"));
        assertFalse(restocking.has("restockInterval"));
        assertEquals(1, CommercialOfferModel.materialsArray(restocking, false).size());
        // max 在材料模式下仍然有效（NSUK 用 min(max, 可用材料数)），所以不能被删
        assertEquals(16, restocking.get("max").getAsInt());

        // MATERIALS → STOCK：补货字段**不会**被填上默认值 —— 空着就是卖完即止，
        // 这是合并之后刻意保留的语义（想开补货得自己去填）。
        assertFalse(CommercialOfferModel.applySupplyMode(restocking,
                CommercialOfferModel.SupplyMode.STOCK));
        assertEquals(CommercialOfferModel.SupplyMode.STOCK,
                CommercialOfferModel.SupplyMode.of(restocking));
        assertFalse(CommercialOfferModel.restockEnabled(restocking));
        assertFalse(restocking.has("materials"));

        // → UNLIMITED 表示「整个 stock 键都该删掉」
        assertTrue(CommercialOfferModel.applySupplyMode(restocking,
                CommercialOfferModel.SupplyMode.UNLIMITED));
    }

    /** 把一个 stock 对象字面量解析成 JsonObject，便于上面那个测试写清楚输入。 */
    private static JsonObject stockJson(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ── Tab 归类（非互斥） ─────────────────────────────────────────────

    @Test
    @DisplayName("Tab 归类照抄 NSUK：SELL/BUY 互相独立、可同时命中；两者都不命中才算未归类")
    void tabClassificationIsNotExclusive() {
        CommercialOfferModel model = wrap("""
                { "offers": [
                  { "id": "sell", "cost": [ { "money": 0.25 } ],
                    "result": [ { "item": "minecraft:bread", "count": 1 } ] },
                  { "id": "buy", "cost": [ { "item": "minecraft:wheat", "count": 16 } ],
                    "result": [ { "money": 3.2 } ] },
                  { "id": "barter", "cost": [ { "item": "minecraft:wheat", "count": 3 } ],
                    "result": [ { "item": "minecraft:bread", "count": 1 } ] },
                  { "id": "both", "cost": [ { "money": 1.0 }, { "item": "minecraft:wheat", "count": 1 } ],
                    "result": [ { "money": 1.0 }, { "item": "minecraft:bread", "count": 1 } ] },
                  { "id": "money_only", "cost": [ { "money": 1.0 } ],
                    "result": [ { "money": 2.0 } ] }
                ] }
                """);

        assertEquals(Set.of(CommercialOfferModel.TradeTab.SELL), tabs(model, "sell"));
        assertEquals(Set.of(CommercialOfferModel.TradeTab.BUY), tabs(model, "buy"));
        assertEquals(Set.of(CommercialOfferModel.TradeTab.BARTER), tabs(model, "barter"));
        // 两侧都同时有钱和物品时，SELL 与 BUY 都会被命中（NSUK 的判据不互斥）。
        assertEquals(Set.of(CommercialOfferModel.TradeTab.SELL, CommercialOfferModel.TradeTab.BUY),
                tabs(model, "both"));
        // 钱换钱：一个 Tab 都进不去 → 玩家永远看不到这条报价。
        assertEquals(Set.of(), tabs(model, "money_only"));
        assertTrue(CommercialOfferModel.isUnclassified(model.offerById("money_only")));
    }

    @Test
    @DisplayName("未归类报价本身是「有效」的，但玩家看不到它 —— 所以必须是警告")
    void unclassifiedOfferIsAWarning() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "money_only",
                  "cost": [ { "money": 1.0 } ],
                  "result": [ { "money": 2.0 } ]
                } ] }
                """);
        List<DefinitionIssue> issues = model.validate();
        assertTrue(codes(issues).contains("unclassified_offer"));
        assertFalse(codes(issues).contains("offers_legacy_fallback"),
                "它仍是有效报价，不该触发回退");
        assertEquals(DefinitionIssue.Severity.WARNING, issues.stream()
                .filter(i -> i.code().equals("unclassified_offer")).findFirst().orElseThrow().severity());
    }

    private static Set<CommercialOfferModel.TradeTab> tabs(CommercialOfferModel model, String id) {
        return CommercialOfferModel.tabsOf(model.offerById(id));
    }

    // ── 别名与可见性 ───────────────────────────────────────────────────

    @Test
    @DisplayName("读取接受别名（costs/results/visible_to/amount/requiredMaterials），写入统一成规范键")
    void aliasesAreAcceptedOnReadAndNormalizedOnWrite() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "x",
                  "visible_to": "both",
                  "costs": [ { "money": 1.0 } ],
                  "results": [ { "item": "minecraft:stone", "amount": 2 } ],
                  "stock": { "item": "minecraft:stone", "max": 8,
                             "requiredMaterials": [ { "item": "minecraft:cobblestone", "amount": 1 } ] }
                } ] }
                """);
        JsonObject offer = model.offers().get(0);

        // 读：别名全都认得，且 both 读作 mixed。
        assertEquals("mixed", model.visibleTo(offer));
        assertEquals(1, CommercialOfferModel.costArray(offer, false).size());
        assertEquals(2, CommercialOfferModel.countOf(
                CommercialOfferModel.resultArray(offer, false).get(0).getAsJsonObject()));
        assertEquals(1, CommercialOfferModel.materialsArray(model.stock(offer, false), false).size());

        // 写：留下规范键、清掉别名。
        model.setVisibleTo(offer, "npc");
        assertTrue(offer.has("visibleTo"));
        assertFalse(offer.has("visible_to"));
        assertEquals("npc", model.visibleTo(offer));

        JsonObject resultResource = CommercialOfferModel.resultArray(offer, false)
                .get(0).getAsJsonObject();
        CommercialOfferModel.writeItem(resultResource, "minecraft:stone", 4);
        assertTrue(resultResource.has("count"));
        assertFalse(resultResource.has("amount"), "写入时应清掉数量别名");

        // 上面只改了「资源」；数组本身的别名（results）需要显式规范化才消失。
        assertTrue(offer.has("results"), "读取不得擅自改名");
        model.canonicalizeOfferKeys(offer);
        assertTrue(offer.has("result"));
        assertFalse(offer.has("results"));
        assertTrue(offer.has("cost"));
        assertFalse(offer.has("costs"));
        JsonObject stock = model.stock(offer, false);
        assertTrue(stock.has("materials"));
        assertFalse(stock.has("requiredMaterials"));
        // 规范化之后仍然读得出同样的值（数量已在上面写成 4）。
        assertEquals(4, CommercialOfferModel.countOf(
                CommercialOfferModel.resultArray(offer, false).get(0).getAsJsonObject()));
        assertEquals(1, CommercialOfferModel.materialsArray(stock, false).size());
    }

    @Test
    @DisplayName("可见性：非法值静默回落 player（要警告），规范值不报")
    void unknownVisibilityFallsBackToPlayer() {
        CommercialOfferModel model = wrap("""
                { "offers": [
                  { "id": "bad", "visibleTo": "everyone", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:stone", "count": 1 } ] },
                  { "id": "good", "visibleTo": "npc", "cost": [ { "money": 1.0 } ],
                    "result": [ { "item": "minecraft:stone", "count": 1 } ] }
                ] }
                """);
        assertEquals("player", model.visibleTo(model.offerById("bad")));
        assertTrue(codes(model.validate()).contains("visible_unknown"));
        assertFalse(CommercialOfferModel.isCanonicalVisibility("everyone"));
        assertTrue(CommercialOfferModel.isCanonicalVisibility("mixed"));
    }

    @Test
    @DisplayName("count 小于 1 不是致命错，但 NSUK 会静默改成 1 —— 要提示")
    void countBelowOneIsClampedAndReported() {
        CommercialOfferModel model = wrap("""
                { "offers": [ {
                  "id": "x", "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:stone", "count": 0 } ]
                } ] }
                """);
        assertTrue(codes(model.validate()).contains("count_clamped"));
        // 读出来已经是夹过的值，与 NSUK 一致。
        assertEquals(1, CommercialOfferModel.countOf(
                CommercialOfferModel.resultArray(model.offers().get(0), false).get(0).getAsJsonObject()));
    }

    // ── 顶层遮蔽与容器 ─────────────────────────────────────────────────

    @Test
    @DisplayName("job 对象存在时顶层 jobType/jobName/heldItem 被完全忽略，要提示")
    void jobObjectShadowsTopLevelJobKeys() {
        CommercialOfferModel model = wrap("""
                { "job": { "id": "baker" },
                  "jobType": "ignored_worker",
                  "heldItem": "minecraft:bread",
                  "offers": [ { "id": "x", "cost": [ { "money": 1.0 } ],
                                "result": [ { "item": "minecraft:stone", "count": 1 } ] } ] }
                """);
        Set<String> issues = codes(model.validate());
        assertTrue(issues.contains("job_shadows_toplevel"));
    }

    @Test
    @DisplayName("声明了容器但坐标为空 → 整座建筑 invalid_definition（错误级）")
    void containerWithoutPositionsIsFatal() {
        CommercialOfferModel model = wrap("""
                { "containers": { "input": { "type": "structure_pos" } },
                  "offers": [ { "id": "x", "cost": [ { "money": 1.0 } ],
                                "result": [ { "item": "minecraft:stone", "count": 1 } ] } ] }
                """);
        List<DefinitionIssue> issues = model.validate();
        DefinitionIssue issue = issues.stream()
                .filter(i -> i.code().equals("container_missing_positions")).findFirst().orElse(null);
        assertNotNull(issue);
        assertEquals(DefinitionIssue.Severity.ERROR, issue.severity());
    }

    @Test
    @DisplayName("containers 整个缺失是合法的（NSUK 不报错）")
    void missingContainersEntirelyIsFine() {
        CommercialOfferModel model = wrap("""
                { "offers": [ { "id": "x", "cost": [ { "money": 1.0 } ],
                                "result": [ { "item": "minecraft:stone", "count": 1 } ],
                                "stock": { "item": "minecraft:stone", "max": 8 } } ] }
                """);
        assertFalse(codes(model.validate()).contains("container_missing_positions"));
    }

    // ── 增删改 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("新建的报价是空白的：不预填配方，也不预填库存")
    void addedOfferIsBlank() {
        CommercialOfferModel model = wrap("{ \"offers\": [] }");
        JsonObject created = model.addOffer("new_offer");
        assertEquals("new_offer", model.offerId(created));
        assertEquals("player", model.visibleTo(created));
        // 配方与库存都不许预填 —— 示例值会被当成作者写的内容一起进到作品里。
        assertFalse(created.has(CommercialOfferModel.COST));
        assertFalse(created.has(CommercialOfferModel.RESULT));
        assertFalse(created.has(CommercialOfferModel.STOCK));
        assertEquals(1, model.offers().size());

        // 代价照实报出来：填完之前这条对 NSUK 无效，而且是唯一一条时整体会回退旧格式。
        Set<String> issues = codes(model.validate());
        assertTrue(issues.contains("empty_cost"));
        assertTrue(issues.contains("empty_result"));
        assertTrue(issues.contains("offers_legacy_fallback"));
    }

    @Test
    @DisplayName("空白报价只报「还没填完」，不报库存与形态那些由预填引起的噪声")
    void blankOfferReportsNoPrefillNoise() {
        CommercialOfferModel model = wrap("{ \"offers\": [] }");
        model.addOffer("trade");
        model.addOffer("trade");
        Set<String> issues = codes(model.validate());
        // 两条新建报价都是圆石 + 库存 64 时，这里会出现 duplicate_stock_item 和 restock_disabled。
        assertFalse(issues.contains("duplicate_stock_item"));
        assertFalse(issues.contains("intake_without_stock"));
        assertFalse(issues.contains("result_without_stock"));
    }

    @Test
    @DisplayName("新建/复制时 id 自动去重，不会产出重复 id")
    void idsAreDeduplicated() {
        CommercialOfferModel model = wrap("{ \"offers\": [] }");
        model.addOffer("shop");
        model.addOffer("shop");
        assertEquals(List.of("shop", "shop_2"), model.offers().stream().map(model::offerId).toList());

        model.duplicateOffer(0);
        assertEquals(List.of("shop", "shop_3", "shop_2"),
                model.offers().stream().map(model::offerId).toList());
    }

    @Test
    @DisplayName("没有 id 的报价按下标回落到 offer_<i>，两条同内容的不会被判成同一条")
    void missingIdFallsBackToIndex() {
        CommercialOfferModel model = wrap("""
                { "offers": [
                  { "cost": [ { "money": 1.0 } ], "result": [ { "item": "minecraft:stone", "count": 1 } ] },
                  { "cost": [ { "money": 1.0 } ], "result": [ { "item": "minecraft:stone", "count": 1 } ] }
                ] }
                """);
        assertEquals(List.of("offer_0", "offer_1"),
                model.offers().stream().map(model::offerId).toList());
        // 缺 id 是提示级，不是错误：NSUK 自己的兜底就是这个。
        assertTrue(codes(model.validate()).contains("empty_offer_id"));
    }

    @Test
    @DisplayName("删除与复制：复制是深拷贝，改副本不影响原件")
    void duplicateIsDeepCopy() {
        CommercialOfferModel model = wrap(BREAD_SHOP);
        JsonObject copy = model.duplicateOffer(0);
        assertNotNull(copy);
        CommercialOfferModel.writeMoney(
                CommercialOfferModel.costArray(copy, false).get(0).getAsJsonObject(), 99.0D);
        assertEquals(0.25D, CommercialOfferModel.moneyOf(
                CommercialOfferModel.costArray(model.offerById("shop_sells_bread"), false)
                        .get(0).getAsJsonObject()), 1e-9);

        assertTrue(model.removeOffer(3));
        assertFalse(model.removeOffer(99));
        assertEquals(3, model.offers().size());
    }

    @Test
    @DisplayName("库存是可选键：没有 stock 合法，删掉之后也合法")
    void stockIsOptionalAndRemovable() {
        CommercialOfferModel model = wrap("""
                { "offers": [ { "id": "x", "cost": [ { "money": 1.0 } ],
                                "result": [ { "item": "minecraft:stone", "count": 1 } ],
                                "stock": { "item": "minecraft:stone", "max": 8 } } ] }
                """);
        JsonObject offer = model.offers().get(0);
        assertTrue(model.removeStock(offer));
        assertNull(model.stock(offer, false));
        assertFalse(codes(model.validate()).contains("stock_orphan_item"));
    }

    @Test
    @DisplayName("根对象为 null 直接拒绝，避免把 NPE 拖到后面")
    void wrapRejectsNull() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> CommercialOfferModel.wrap(null));
    }

    // ── 夹具构造 ───────────────────────────────────────────────────────

    private static String offersJson(int count) {
        StringBuilder builder = new StringBuilder("{ \"offers\": [");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("""
                    { "id": "o%d", "cost": [ { "money": 1.0 } ],
                      "result": [ { "item": "minecraft:stone", "count": 1 } ],
                      "stock": { "item": "minecraft:stone", "max": 8 } }""".formatted(i));
        }
        return builder.append("] }").toString();
    }

    private static String costResourcesJson(int count) {
        StringBuilder builder = new StringBuilder("""
                { "offers": [ { "id": "x", "result": [ { "item": "minecraft:stone", "count": 1 } ],
                  "cost": [""");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{ \"money\": ").append(i + 1).append(".0 }");
        }
        return builder.append("] } ] }").toString();
    }

    private static String materialsJson(int count) {
        StringBuilder builder = new StringBuilder("""
                { "offers": [ { "id": "x", "cost": [ { "money": 1.0 } ],
                  "result": [ { "item": "minecraft:stone", "count": 1 } ],
                  "stock": { "item": "minecraft:stone", "max": 8, "materials": [""");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{ \"item\": \"minecraft:stone\", \"count\": 1 }");
        }
        return builder.append("] } } ] }").toString();
    }
}
