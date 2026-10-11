package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonObject;
import com.rstech.simupack.creation.industrial.sim.SimLootProvider;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 干跑执行器的语义测试。
 *
 * <p>每一条都对应旧版 {@code IndustrialActionSimulator} 的一个具体错误，
 * 所以断言里会写明「为什么必须是这样」—— 依据是 NSUK 2.2.0 的反编译实现。
 */
class SimulationRunnerTest {

    private static final SimPos START = new SimPos(0, 0, 0);

    @Test
    void 循环位置照NSUK注入子步骤() {
        // NSUK 的注入条件**只看 pos/positions**（`IndustrialDefinitionLoader:346`），
        // point/container 不参与判断 —— 写了 container 的步骤照样被注入循环坐标。
        // 「move_to_container 被奶池坐标带偏」的修法在**目标解析**（客户端按类型取目标），
        // 不在注入条件；这条守的就是这个容易改错的边界。
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"repeat","positions":[[8,1,4],[9,1,4]],
                    "steps":[{"type":"move_to_container","container":"output"},
                             {"type":"place_fluid","fluid":"minecraft:water"}]}]}]}
                """);
        List<SimulationPlan.Entry> entries = SimulationPlan.of(recipe);
        assertEquals(4, entries.size(), "两轮 × 两步");

        for (int index : new int[] {0, 1}) {
            assertEquals(1, entries.get(index).step().positions().size(),
                    "第 " + (index + 1) + " 步也要被注入循环坐标（NSUK 无条件注入）");
            assertEquals(8, entries.get(index).step().positions().get(0).x());
        }
        assertEquals(9, entries.get(3).step().positions().get(0).x(), "第二轮用第二个位置");
    }

    private static JsonObject recipe(String json) {
        return BuildingJsonDocument.parse(json).recipes().get(0);
    }

    private static SimulationRunner.Result run(String recipeJson, SimInventory inventory, long seed) {
        return run(recipeJson, SimulationRunner.Focus.NONE, SimNavigation.direct(), inventory, seed);
    }

    private static SimulationRunner.Result run(String recipeJson, SimulationRunner.Focus focus,
                                               SimNavigation navigation, SimInventory inventory, long seed) {
        JsonObject recipe = recipe(recipeJson);
        List<SimulationPlan.Entry> plan = SimulationPlan.of(recipe);
        return SimulationRunner.run(recipe, plan, focus, inventory, START, navigation, seed);
    }

    /** 指定产出源跑一轮（真实战利品表由客户端注入，内核只消费它的结果）。 */
    private static SimulationRunner.Result runWithLoot(String recipeJson, SimInventory inventory,
                                                       SimLootProvider loot) {
        JsonObject recipe = recipe(recipeJson);
        List<SimulationPlan.Entry> plan = SimulationPlan.of(recipe);
        return SimulationRunner.run(recipe, plan, SimulationRunner.Focus.NONE, inventory, START,
                SimNavigation.direct(), 0L, loot);
    }

    /** 一个写死的产出源，用来测内核的「放进容器 / 受阻」逻辑本身。 */
    private static SimLootProvider fakeLoot(List<SimLootProvider.Product> drops,
                                            List<SimLootProvider.Product> shear,
                                            List<String> food) {
        return new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return drops;
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return shear;
            }

            @Override
            public List<String> food(String entityType) {
                return food;
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                // null = 算不出来（「不掉东西」是空表）。默认就当作拿不到数据。
                return null;
            }
        };
    }

    /** 一个能给出方块掉落的产出源（测破坏方块用）。 */
    private static SimLootProvider blockLoot(Map<String, Integer> byPosition, List<SimLootProvider.Product> products) {
        return new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                return byPosition.containsKey(x + "," + y + "," + z) ? products : null;
            }
        };
    }

    /** 不管哪个坐标都给同一份掉落（测成簇收获用）。 */
    private static SimLootProvider anyBlockLoot(SimLootProvider.Product product) {
        return new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                return List.of(product);
            }
        };
    }

    /**
     * 一个「认工具、也能匹配补种物品」的产出源。
     *
     * @param drop        掉落
     * @param requiredTool 只有拿着这个工具才掉；null = 什么工具都掉
     * @param plantTag    补种选择器（{@code #标签} 或物品 id）
     * @param plantItem   匹配該选择器的具体物品
     * @param plantBlock  该物品放下去变成的方块
     */
    private static SimLootProvider toolLoot(SimLootProvider.Product drop, String requiredTool,
                                            String plantTag, String plantItem, String plantBlock) {
        return new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                return requiredTool == null ? List.of(drop) : List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, String toolSelector, Random random) {
                // 模拟「精准采集 / 时运」这类差异：工具不对就不掉。
                return requiredTool == null || requiredTool.equals(toolSelector) ? List.of(drop) : List.of();
            }

            @Override
            public Boolean itemMatches(String selector, String itemId) {
                return selector != null && selector.equals(plantTag) && itemId != null
                        && itemId.equals(plantItem);
            }

            @Override
            public String placedBlock(String itemId) {
                return plantItem != null && plantItem.equals(itemId) ? plantBlock : null;
            }
        };
    }

    /**
     * 一个只认「虚拟收获」的产出源：按选择器给固定产出。
     *
     * <p>虚拟收获发生在工作区里一个匹配方块都没有的时候 —— 那时没有坐标可用，
     * 只有选择器（方块 id 或标签）。
     */
    private static SimLootProvider virtualLoot(SimLootProvider.Product product) {
        return new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                return null;
            }

            @Override
            public List<SimLootProvider.Product> virtualBlockDrops(String selector, Random random) {
                return List.of(product);
            }
        };
    }

    /**
     * 用一张「坐标 → 方块 id」的表当预览世界。
     *
     * <p>{@link #matches} 只认完全相等的 id（测试里用不上真标签）；
     * {@link #candidates} 从表里过滤，和真实实现「遍历结构方块表」是同一个形状。
     */
    private static final class FakeWorld implements SimBlockMatcher {        private final Map<String, String> blocks = new java.util.LinkedHashMap<>();

        void put(int x, int y, int z, String id) {
            this.blocks.put(x + "," + y + "," + z, id);
        }

        @Override
        public Boolean matches(String selector, SimPos pos) {
            String id = this.blocks.get(pos.x() + "," + pos.y() + "," + pos.z());
            return id != null && id.equals(selector);
        }

        @Override
        public List<SimPos> candidates(SimWorkArea area) {
            List<SimPos> found = new java.util.ArrayList<>();
            this.blocks.forEach((key, id) -> {
                String[] parts = key.split(",");
                SimPos pos = new SimPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
                if (area.contains(pos)) {
                    found.add(pos);
                }
            });
            return found;
        }
    }

    /**
     * 一个把「这一步引用的所有点位」都解析到同一个坐标的 {@code Focus}。
     *
     * <p>用来测需要坐标的步骤（放方块、等待方块等）——真实实现在客户端，
     * 会把点位名解析成结构里的实际坐标；内核只要求「能拿到坐标」。
     */
    private static SimulationRunner.Focus withPoint(SimPos pos) {
        return new SimulationRunner.Focus() {
            @Override
            public List<SimPos> positionsOf(SimStepSpec step) {
                return List.of(pos);
            }
        };
    }

    private static SimulationRunner.StepOutcome step(SimulationRunner.Result result, int index) {
        SimulationRunner.StepOutcome outcome = result.outcome(index);
        assertTrue(outcome != null, "步骤 " + index + " 应当存在");
        return outcome;
    }

    // ── 合成 ───────────────────────────────────────────────────────────

    @Test
    void 合成按配方级输入扣料并产出() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 4);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals(2, result.inventory().count("in", "minecraft:iron_ingot"));
        assertEquals(1, result.inventory().count("out", "minecraft:gear"));
        assertTrue(result.completed());
    }

    @Test
    void 同一格方块的两个容器名共用一本账() {
        // 最常见的情况：input 和 output 其实是同一个箱子。
        // 不连起来的话，「往 input 放、从 output 看」会看到两个互不相干的箱子，
        // 产物就永远不出现 —— 这是实机上真的踩到的。
        SimInventory inventory = new SimInventory();
        inventory.link("output", "input");

        inventory.add("input", "minecraft:dirt", 32);
        assertEquals(32, inventory.count("output", "minecraft:dirt"), "换个名字应当看到同一本账");
        assertTrue(inventory.take("output", "minecraft:dirt", 2), "从另一个名字取用也要生效");
        assertEquals(30, inventory.count("input", "minecraft:dirt"));

        // 快照要按两个名字都能查到（界面就是按悬浮到的那个名字去查的）。
        assertEquals(30, inventory.snapshot().get("output").get("minecraft:dirt"));
        assertEquals(30, inventory.snapshot().get("input").get("minecraft:dirt"));
    }

    @Test
    void 容器名连起来之后合成产出能在同一个箱子里看到() {
        SimInventory inventory = new SimInventory();
        inventory.link("out", "in");
        inventory.add("in", "minecraft:iron_ingot", 4);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        // 同一个账本：扣掉 2 个铁锭、加上 1 个齿轮，两个名字查到的都一样。
        Map<String, Map<String, Integer>> after = result.inventoryAt(0);
        assertEquals(2, after.get("in").get("minecraft:iron_ingot"));
        assertEquals(1, after.get("out").get("minecraft:gear"));
        assertEquals(after.get("in"), after.get("out"), "两个名字应当指向同一份内容");
    }

    // ── 成簇收获 ───────────────────────────────────────────────────────

    /** 一棵 3 格高的树（下面垫泥土）放在原点。 */
    private static FakeWorld tree() {
        FakeWorld world = new FakeWorld();
        for (int y = 0; y < 3; y++) {
            world.put(0, y, 0, "minecraft:oak_log");
        }
        world.put(0, -1, 0, "minecraft:dirt");
        return world;
    }

    private static SimWorkArea areaAround(int radius) {
        return new SimWorkArea(new SimPos(-radius, -radius, -radius),
                new SimPos(radius, radius + 8, radius), null, null);
    }

    private static SimulationRunner.Result harvest(String stepJson, FakeWorld world, SimWorkArea area) {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[%s]}]}
                """.formatted(stepJson));
        return SimulationRunner.run(recipe, SimulationPlan.of(recipe), SimulationRunner.Focus.NONE,
                new SimInventory(), START, SimNavigation.direct(), 0L,
                new SimEnvironment(anyBlockLoot(new SimLootProvider.Product("minecraft:oak_log", 1)),
                        world, area));
    }

    @Test
    void 成簇收获把整棵树砍掉产物进背包再由入库步骤搬进容器() {
        SimulationRunner.Result result = harvest("""
                {"type":"harvest_block_clusters","targetBlockTag":"minecraft:oak_log",
                 "maxClusterBlocks":64},
                {"type":"deposit_carried_items","container":"out"}
                """, tree(), areaAround(8));

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertEquals(SimulationRunner.Status.DONE, step(result, harvest).status());
        assertEquals(3, result.blockEdits().get(harvest).size(), "3 根原木都要被砍掉");
        // 真实实现里收获物先进工人背包，入库是另一步 —— 所以这一步之后容器里还没有东西。
        assertEquals(3, result.inventory().count("out", "minecraft:oak_log"), "入库步骤把它搬进了容器");
    }

    @Test
    void 收获后按补种标签把树苗种回根部并从容器扣料() {
        // 真实实现：砍完把树苗种回 roots（一格一个），先吃背包、再吃输入容器的材料。
        FakeWorld world = tree();
        SimWorkArea area = areaAround(8);
        SimLootProvider loot = toolLoot(new SimLootProvider.Product("minecraft:oak_log", 1), null,
                "#minecraft:saplings", "minecraft:oak_sapling", "minecraft:oak_sapling");
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"harvest_block_clusters","targetBlockTag":"minecraft:oak_log",
                            "maxClusterBlocks":64,"plantItemTag":"#minecraft:saplings",
                            "input":"in"}]}]}
                """);
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:oak_sapling", 2);

        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, inventory, START, SimNavigation.direct(), 0L,
                new SimEnvironment(loot, world, area));

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertEquals(SimulationRunner.Status.DONE, step(result, harvest).status());
        // 3 根原木变成空气 + 1 棵树苗种回根部（roots 只有最低那一格）
        List<SimulationRunner.BlockEdit> edits = result.blockEdits().get(harvest);
        assertEquals(4, edits.size(), "3 个挖空 + 1 个补种");
        assertTrue(edits.stream().anyMatch(edit -> "minecraft:oak_sapling".equals(edit.blockId())
                        && edit.y() == 0),
                "树苗要种回最低那格（roots）");
        assertEquals(1, result.inventory().count("in", "minecraft:oak_sapling"), "补一棵扣一棵");
        assertTrue(step(result, harvest).message().contains("补种 1"), "消息里要说清补了几棵");
    }

    @Test
    void 没有补种材料时照砍但消息里说明缺料() {
        FakeWorld world = tree();
        SimLootProvider loot = toolLoot(new SimLootProvider.Product("minecraft:oak_log", 1), null,
                "#minecraft:saplings", "minecraft:oak_sapling", "minecraft:oak_sapling");
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"harvest_block_clusters","targetBlockTag":"minecraft:oak_log",
                            "maxClusterBlocks":64,"plantItemTag":"#minecraft:saplings","input":"in"}]}]}
                """);

        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, new SimInventory(), START, SimNavigation.direct(), 0L,
                new SimEnvironment(loot, world, areaAround(8)));

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertEquals(SimulationRunner.Status.DONE, step(result, harvest).status(), "缺树苗不该让整步失败");
        assertEquals(3, result.blockEdits().get(harvest).size(), "只砍不种");
        assertTrue(step(result, harvest).message().contains("缺补种材料"));
    }

    @Test
    void 破坏方块会把步骤里的工具传给掉落计算() {
        // 时运 / 精准采集就是靠 TOOL 参数生效的：工具不对，掉落完全不同。
        SimLootProvider needsTool = toolLoot(
                new SimLootProvider.Product("minecraft:diamond", 1), "minecraft:diamond_pickaxe",
                null, null, null);

        SimulationRunner.Result withTool = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"destroy_block","positions":[[1,0,0]],"dropItems":true,
                            "container":"out","item":"minecraft:diamond_pickaxe"}]}]}
                """, new SimInventory(), needsTool);
        assertTrue(step(withTool, 0).message().contains("diamond"),
                "拿着正确工具才有掉落（掉在地上）：" + step(withTool, 0).message());

        SimulationRunner.Result bareHanded = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"destroy_block","positions":[[1,0,0]],"dropItems":true,
                            "container":"out"}]}]}
                """, new SimInventory(), needsTool);
        assertFalse(step(bareHanded, 0).message().contains("diamond"), "空手就没有这份掉落");
    }

    @Test
    void 整簇大于maxClusterBlocks时整簇跳过而不是部分收() {
        // 一簇都收不成时**改走虚拟收获**（预览看不到建筑外的世界，不能就地下结论说「收不到」），
        // 但消息里必须写清跳过原因 —— 只说「跳过 N 簇」等于没说。
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"harvest_block_clusters","targetBlockTag":"minecraft:oak_log",
                            "maxClusterBlocks":2}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, new SimInventory(), START, SimNavigation.direct(), 0L,
                new SimEnvironment(virtualLoot(new SimLootProvider.Product("minecraft:oak_log", 1)),
                        tree(), areaAround(8)));

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertFalse(result.blockEdits().containsKey(harvest), "3 根 > 上限 2 ⇒ 一根都不砍");
        String message = step(result, harvest).message();
        // 只说你收到了什么，不解释框架为什么没收 —— 那种话对作者没有行动价值。
        assertTrue(message.contains("AREA_EMPTY"), "收不到就是 NSUK 的 AREA_EMPTY，没有产出：" + message);
        assertFalse(message.contains("跳过"), "不该出现「跳过 N 簇」这种冗余说明：" + message);
    }

    @Test
    void 树落在建筑外框内时整簇跳过() {
        // 建筑外框把整棵树罩住 ⇒ 真实实现会整簇否决（那是为了不去砍建筑本身）。
        SimWorkArea area = new SimWorkArea(new SimPos(-8, -8, -8), new SimPos(8, 16, 8),
                new SimPos(-1, -1, -1), new SimPos(1, 3, 1));
        SimulationRunner.Result result = harvest("""
                {"type":"harvest_block_clusters","targetBlockTag":"minecraft:oak_log",
                 "maxClusterBlocks":64}
                """, tree(), area);

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertFalse(result.blockEdits().containsKey(harvest), "建筑内的簇不该被砍");
    }

    @Test
    void 附着物标签为空又要至少一个附着物时永远不收这是配置陷阱() {
        // 真实实现：attachedBlockTag 留空 ⇒ 附着集合恒为空 ⇒ minAttachedBlocks >= 1 时全拒。
        SimulationRunner.Result result = harvest("""
                {"type":"harvest_block_clusters","targetBlockTag":"minecraft:oak_log",
                 "minAttachedBlocks":1,"maxClusterBlocks":64}
                """, tree(), areaAround(8));

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertFalse(result.blockEdits().containsKey(harvest), "这个配置在真实游戏里也什么都收不到");
    }

    @Test
    void 工作区里没有可收获的方块时走虚拟收获() {
        // 真实的成簇收获是在建筑外的世界里砍东西；预览里的结构没有那种方块时，
        // 与其按方块种类现造地形（小麦/花/甘蔗各有各的长法，还要管支撑方块），
        // 不如直接给一份估算产出：**种类是真的**（问客户端的战利品表），**数量是估的**。
        FakeWorld empty = new FakeWorld();
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"harvest_block_clusters","targetBlockTag":"#minecraft:logs",
                            "maxClusterBlocks":64},
                           {"type":"deposit_carried_items","container":"out"}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, new SimInventory(), START, SimNavigation.direct(), 0L,
                new SimEnvironment(virtualLoot(new SimLootProvider.Product("minecraft:oak_log", 1)),
                        empty, areaAround(8)));

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertEquals(SimulationRunner.Status.DONE, step(result, harvest).status());
        assertTrue(step(result, harvest).message().contains("AREA_EMPTY"),
                "消息里要写明这是 NSUK 的 AREA_EMPTY：" + step(result, harvest).message());
        assertFalse(result.blockEdits().containsKey(harvest), "没收成就不该假装动过世界");
        // **不能凭空产出**：NSUK 扫不到可收的方块就是不掉一根木头。
        // （这里曾经断言容器里有 8 个 oak_log —— 那是凭空产出，已撤。）
        assertEquals(0, result.inventory().count("out", "minecraft:oak_log"));
    }

    @Test
    void 工作区为空时不管认不认得出掉落都只报_AREA_EMPTY() {
        // 曾经的「虚拟收获」会问客户端要战利品表、答不出来就标 NEEDS_WORLD；
        // 现在这条路根本不产出，也就不需要掉落表了。
        FakeWorld empty = new FakeWorld();
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"harvest_block_clusters","targetBlockTag":"modded:mystery",
                            "maxClusterBlocks":64}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, new SimInventory(), START, SimNavigation.direct(), 0L,
                new SimEnvironment(SimLootProvider.NONE, empty, areaAround(8)));

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertEquals(SimulationRunner.Status.DONE, step(result, harvest).status());
        assertTrue(step(result, harvest).message().contains("AREA_EMPTY"));
    }

    @Test
    void 到达判定按NSUK的到达半径而不是必须踩在那一格() {
        // NSUK：move_to 的到达半径是 max(0.2, range)（IndustrialWorkService:560-565）。
        // 默认 range=1.5 ⇒ 站在旁边一格（距离 1.0）就算到了，1 tick 结束；
        // 把 range 调到下限 ⇒ 必须真的走过去。早先只判「踩在同一格」，两种都白走一段。
        JsonObject near = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to","point":"p"}]}]}
                """);
        SimulationRunner.Result arrived = SimulationRunner.run(near, SimulationPlan.of(near),
                pointFocus(new SimPos(1, 0, 0)), new SimInventory(), START, SimNavigation.direct(), 0L,
                SimEnvironment.NONE);
        assertEquals(SimulationRunner.Status.DONE, step(arrived, 0).status());
        assertTrue(step(arrived, 0).message().contains("已经在目标点上"),
                "1 格之差在默认 range 内就该算到达：" + step(arrived, 0).message());

        JsonObject tight = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to","point":"p","range":0.1}]}]}
                """);
        SimulationRunner.Result walked = SimulationRunner.run(tight, SimulationPlan.of(tight),
                pointFocus(new SimPos(1, 0, 0)), new SimInventory(), START, SimNavigation.direct(), 0L,
                SimEnvironment.NONE);
        assertTrue(step(walked, 0).message().contains("移动到"),
                "range 收紧到 0.2 以内就不再算到达：" + step(walked, 0).message());
    }

    /** 一个把任何步骤都解析成固定坐标的 Focus —— 测走位时用它替掉客户端注入。 */
    private static SimulationRunner.Focus pointFocus(SimPos target) {
        return new SimulationRunner.Focus() {
            @Override
            public List<SimPos> positionsOf(SimStepSpec step) {
                return List.of(target);
            }

            @Override
            public boolean knowsContainer(String name) {
                return false;
            }
        };
    }

    @Test
    void 作业区扫的是环且贴着建筑的前几圈不扫() {
        // NSUK：环集合是 [max(1, startOffset), radius]
        // （IndustrialBlockClusterHarvestService:952/:960-965/:987）。
        SimPos min = new SimPos(0, 0, 0);
        SimPos max = new SimPos(4, 2, 4);
        // 作业盒 = 建筑外框 ± radius（客户端就是这么给的），环从**建筑外框**量起。
        SimWorkArea area = new SimWorkArea(new SimPos(-6, 0, -6), new SimPos(10, 2, 10),
                min, max, null, null, 3, 6);
        assertFalse(area.contains(new SimPos(5, 1, 0)), "第 1 圈不扫");
        assertFalse(area.contains(new SimPos(6, 1, 0)), "第 2 圈不扫");
        assertTrue(area.contains(new SimPos(7, 1, 0)), "第 3 圈要扫");
        assertTrue(area.contains(new SimPos(10, 1, 0)), "第 6 圈要扫");
        assertFalse(area.contains(new SimPos(11, 1, 0)), "超出 radius 不扫");

        // radius = 0 时环集合为空 ⇒ 首次执行就是 AREA_EMPTY，连建筑自己也不扫。
        SimWorkArea none = new SimWorkArea(new SimPos(0, 0, 0), new SimPos(4, 2, 4),
                min, max, null, null, 0, 0);
        assertFalse(none.contains(new SimPos(0, 1, 0)), "radius=0 什么都不扫");

        // excludeBuilding：x/z 落在排除矩形内的**整列**跳过（只看 XZ，不看 Y）。
        SimWorkArea excluded = new SimWorkArea(new SimPos(-6, 0, -6), new SimPos(10, 4, 10),
                min, max, min, max, 0, 6);
        assertFalse(excluded.contains(new SimPos(2, 3, 2)), "落在排除列里就不算作业区");
        assertTrue(excluded.contains(new SimPos(7, 3, 7)), "排除列之外照扫");
    }

    @Test
    void 输出空间按整张最坏情况表一起判定() {
        // NSUK 把整张表一次性 simulateInsert（IndustrialInventoryService:35-42）：
        // 容量 100 格、两条各 60 件（堆叠上限 1）⇒ 共 120 > 100，必须判失败；
        // 逐条比会以为每条 60 都放得下而误判成功。
        SimInventory inventory = new SimInventory();
        inventory.setCapacity("out", 100);
        inventory.setStackLimits(item -> 1);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],
                  "outputs":[{"item":"minecraft:gear","baseAmount":60},
                             {"item":"minecraft:stick","baseAmount":60}],
                  "steps":[{"type":"require_output_space","container":"out"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status(),
                "两条加起来放不下就该失败：" + step(result, 0).message());
    }

    @Test
    void 连续合成的次数按NSUK的规则数() {
        SimInventory inventory = new SimInventory();
        // 空输入表 ⇒ 0 次（NSUK IndustrialInputPlanner:48-50；以前我们返回 1，等于凭空合成一次）。
        assertEquals(0, SimulationRunner.availableCraftCount(inventory, "in", List.of()));

        // 全是 consume:false ⇒ 最多 1 次（:51-53）。
        inventory.add("in", "minecraft:stone", 500);
        SimItemSpec stone = new SimItemSpec("minecraft:stone", "", "", "", "");
        List<SimInput> checkOnly = List.of(SimInput.item(stone, 1, false));
        assertEquals(1, SimulationRunner.availableCraftCount(inventory, "in", checkOnly));

        // 可消耗的：按材料够几次算，但**上限 4096**（:28 的 MAX_AVAILABLE_CRAFTS）。
        List<SimInput> consumable = List.of(SimInput.item(stone, 1, true));
        assertEquals(500, SimulationRunner.availableCraftCount(inventory, "in", consumable));
        inventory.add("in", "minecraft:stone", 5000);
        assertEquals(SimulationRunner.MAX_AVAILABLE_CRAFTS,
                SimulationRunner.availableCraftCount(inventory, "in", consumable), "上限是 4096");
    }

    @Test
    void 剪过的羊不能再剪一次() {
        // NSUK 的 shear 只挑「非幼年 + 可剪毛」（IndustrialEntityActionService:109-112），
        // 剪过之后 isShearable 为假 —— 账本必须跟着扣，否则「剪 2 只、再剪 2 只」会假成功
        // （画面上羊已经秃了，账却说过得去）。
        SimLootProvider loot = fakeLoot(List.of(),
                List.of(new SimLootProvider.Product("minecraft:white_wool", 1)), List.of());
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"shear_entities","entityType":"minecraft:sheep","count":2},
                           {"type":"shear_entities","entityType":"minecraft:sheep","count":2}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                withEntities(3), new SimInventory(), START, SimNavigation.direct(), 0L,
                SimEnvironment.of(loot));

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status(), "3 只够剪 2 只");
        assertEquals(1, result.blockedAt(), "剪过的不能再算，第二步只剩 1 只");
    }

    @Test
    void 工具的附魔会传到掉落计算() {
        // NSUK 把附魔**真的附到 ItemStack 上**再当 LootContextParams.TOOL 用
        // （IndustrialItemStackSpec:132-160），所以时运 III 的镐和空手砍同一块矿掉落不同。
        // 我们用产出源把「收到的工具 + 附魔」记下来，验证它确实透传到了客户端那一层。
        List<String> seen = new java.util.ArrayList<>();
        SimLootProvider recording = new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, String tool,
                                                            String enchantments, Random random) {
                seen.add(tool + "|" + enchantments);
                return List.of();
            }
        };
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"destroy_block","positions":[[1,0,0]],"dropItems":true,
                            "item":"minecraft:diamond_pickaxe",
                            "enchantments":{"fortune":3,"minecraft:silk_touch":1}}]}]}
                """, new SimInventory(), recording);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals("minecraft:diamond_pickaxe|minecraft:fortune=3,minecraft:silk_touch=1",
                seen.get(0), "工具与附魔都要传到产出源");
    }

    @Test
    void 容器每格的上限参与容量判定() {
        // 一格能装多少 = min(该格上限, 物品自己的堆叠上限)。
        // 模组容器经常限制特定格位，只按「格数 × 64」算会把容量算大。
        SimInventory inventory = new SimInventory();
        inventory.setCapacity("out", 3);
        inventory.setSlotLimits("out", new int[]{16, 16, 16});

        // 3 格 × 16 = 48 件刚好放得下。
        SimulationRunner.Result ok = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"insert_item","container":"out","item":"minecraft:snowball","count":48}]}]}
                """, inventory, 0L);
        assertEquals(SimulationRunner.Status.DONE, step(ok, 0).status(),
                "3 格 × 16 应当刚好放下 48 件：" + step(ok, 0).message());

        // 49 件就需要第 4 格。
        SimulationRunner.Result blocked = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"insert_item","container":"out","item":"minecraft:snowball","count":49}]}]}
                """, inventory, 0L);
        assertEquals(SimulationRunner.Status.BLOCKED, step(blocked, 0).status(), "49 件要第 4 格");
    }

    @Test
    void 先放后挖的方块按方块id求掉落() {
        // 砖厂的真实形态：place_block 把黏土放到 clay_bed（那片坐标在**结构文件里是空气**），
        // 几步之后才 destroy_block 挖掉。按坐标问预览世界只会得到「空气不掉东西」，
        // 黏土球凭空消失、第 21 步 require_drops 永远判 MISSING_DROPS。
        List<String> askedByPosition = new java.util.ArrayList<>();
        List<String> askedById = new java.util.ArrayList<>();
        SimLootProvider loot = new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                askedByPosition.add(x + "," + y + "," + z);
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, String tool,
                                                            String enchantments, Random random) {
                askedByPosition.add(x + "," + y + "," + z);
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDropsForBlock(String blockId, String tool,
                                                                    String enchantments, Random random) {
                askedById.add(blockId);
                return List.of(new SimLootProvider.Product("minecraft:clay_ball", 4));
            }

            @Override
            public Boolean itemMatches(String selector, String itemId) {
                return "minecraft:clay_ball".equals(itemId);
            }
        };
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"place_block","positions":[[1,0,0]],"block":"minecraft:clay"},
                           {"type":"destroy_block","positions":[[1,0,0]],"dropItems":true,
                            "item":"minecraft:iron_shovel"},
                           {"type":"require_drops","item":"minecraft:clay_ball","positions":[[1,0,0]]}]}]}
                """, new SimInventory(), loot);

        assertEquals(SimulationRunner.Status.DONE, step(result, 1).status(),
                "挖自己放下去的方块要有掉落：" + step(result, 1).message());
        assertEquals(List.of("minecraft:clay"), askedById, "应当按方块 id 问掉落");
        assertTrue(askedByPosition.isEmpty(), "不该再去问那个坐标（那里是空气）");
        assertEquals(SimulationRunner.Status.DONE, step(result, 2).status(),
                "地上的黏土球要能被 require_drops 找到：" + step(result, 2).message());
    }

    @Test
    void 容器内容按逐格摊开而不是一格一个总数() {
        // 界面上把 1728 个水瓶画成一格「1728」是错的：真实容器里那是 27 格 × 64。
        SimInventory inventory = new SimInventory();
        inventory.setCapacity("in", 27);
        inventory.add("in", "minecraft:potion", 1728);

        List<SimInventory.Slot> layout = inventory.slotLayout("in");
        assertEquals(27, layout.size(), "1728 = 27 格 × 64");
        assertTrue(layout.stream().allMatch(slot -> slot.count() == 64), "每格 64");

        // 不满一格、以及跨格的情况。
        SimInventory small = new SimInventory();
        small.setCapacity("in", 3);
        small.add("in", "minecraft:snowball", 130);
        assertEquals(List.of(64, 64, 2),
                small.slotLayout("in").stream().map(SimInventory.Slot::count).toList());

        // 每格上限更小时按格上限摊（16 × 3 = 48，剩下 22 是「装不下的部分」，单列一格）。
        small.setSlotLimits("in", new int[]{16, 16, 16});
        small.set("in", "minecraft:snowball", 70);
        assertEquals(List.of(16, 16, 16, 22),
                small.slotLayout("in").stream().map(SimInventory.Slot::count).toList(),
                "超出格数的部分要看得见，别假装它不在");
    }

    @Test
    void 拾取掉落物捡不完也算完成() {
        // NSUK 只把「地上一件都没有」当 MISSING_DROPS；捡到多少算多少，不因为没凑够数量就失败。
        // 真实例子：砖厂 40 个黏土球，旧代码把「背包 14 个空格」当「只能装 14 件」，
        // 于是只捡了 14 个（40 个本该进 1 格）还判受阻。
        SimLootProvider loot = new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                return List.of(new SimLootProvider.Product("minecraft:clay_ball", 40));
            }

            @Override
            public Boolean itemMatches(String selector, String itemId) {
                return "minecraft:clay_ball".equals(itemId);
            }
        };
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"destroy_block","positions":[[1,0,0]],"dropItems":true},
                           {"type":"collect_drops","item":"minecraft:clay_ball","positions":[[1,0,0]],
                            "count":40}]}]}
                """, new SimInventory(), loot);

        assertEquals(SimulationRunner.Status.DONE, step(result, 1).status(),
                "捡到 40 个应当完成：" + step(result, 1).message());
        // 捡了多少、还剩多少**不写进消息**：背包与地上的掉落物在预览里都画得出来，
        // 写进消息是重复信息（作者要求）。
        assertEquals("捡起 minecraft:clay_ball", step(result, 1).message(),
                "消息里不该出现数量：" + step(result, 1).message());

        // 地上不够时也照常完成。
        SimLootProvider fewer = new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                return List.of(new SimLootProvider.Product("minecraft:clay_ball", 3));
            }

            @Override
            public Boolean itemMatches(String selector, String itemId) {
                return "minecraft:clay_ball".equals(itemId);
            }
        };
        SimulationRunner.Result partial = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"destroy_block","positions":[[1,0,0]],"dropItems":true},
                           {"type":"collect_drops","item":"minecraft:clay_ball","positions":[[1,0,0]],
                            "count":40}]}]}
                """, new SimInventory(), fewer);
        assertEquals(SimulationRunner.Status.DONE, step(partial, 1).status(),
                "地上只有 3 个也要照常往下走：" + step(partial, 1).message());
    }

    @Test
    void 不写item的require和collect也真的去查和捡() {
        // 官方农牧配方（鸡肉/牛肉农场）的 require_drops / collect_drops **都不写 item** ——
        // 留空表示「不按物品筛选」（NSUK 的 matchingDrops 就是「空则全匹配」）。
        // 以前这里判「需要世界」跳过，于是掉落物永远留在地上、背包空、输出箱空。
        SimLootProvider loot = new SimLootProvider() {
            @Override
            public List<SimLootProvider.Product> drops(String entityType, int count, Random random) {
                return List.of(new SimLootProvider.Product("minecraft:chicken", count),
                        new SimLootProvider.Product("minecraft:feather", count));
            }

            @Override
            public List<SimLootProvider.Product> shear(String entityType, int count, Random random) {
                return List.of();
            }

            @Override
            public List<String> food(String entityType) {
                return List.of();
            }

            @Override
            public List<SimLootProvider.Product> blockDrops(int x, int y, int z, Random random) {
                return List.of();
            }
        };
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"slaughter_entities","entityType":"minecraft:chicken","count":1},
                           {"type":"require_drops"},
                           {"type":"collect_drops"}]}]}
                """, new SimInventory(), loot);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status(), "屠宰应当完成");
        assertEquals(SimulationRunner.Status.DONE, step(result, 1).status(),
                "不写 item 的 require_drops 要按「地上有没有掉落物」判，而不是「需要世界」："
                        + step(result, 1).message());
        assertEquals(SimulationRunner.Status.DONE, step(result, 2).status(),
                "不写 item 的 collect_drops 要把地上的掉落物捡进背包："
                        + step(result, 2).message());
    }

    @Test
    void 所有落脚点都到不了时仍然报最近的那个() {
        // 诊断要按最近的候选给：那个坐标才是作者在建筑里找得到的「本该站的地方」。
        SimPos sealed = new SimPos(1, 0, 0);
        SimNavigation navigation = new SimNavigation() {
            @Override
            public List<SimWaypoint> findPath(SimPos from, SimPos to) {
                return List.of();
            }

            @Override
            public List<SimPos> standTargets(SimPos container) {
                return List.of(sealed);
            }

            @Override
            public String explainPathFailure(SimPos from, SimPos to) {
                return "展柜封死了";
            }
        };
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to_container","container":"c","positions":[[1,0,1]]}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, new SimInventory(), START, navigation, 0L,
                SimEnvironment.NONE);

        SimulationRunner.StepOutcome outcome = step(result, indexOfStep(result, "move_to_container"));
        assertEquals(SimulationRunner.Status.BLOCKED, outcome.status());
        assertTrue(outcome.message().contains("展柜封死了"), "诊断要带上原因：" + outcome.message());
    }

    @Test
    void 拿不到作业范围时标注需要世界() {
        SimulationRunner.Result result = harvest("""
                {"type":"harvest_block_clusters","targetBlockTag":"minecraft:oak_log"}
                """, tree(), SimWorkArea.UNKNOWN);

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertEquals(SimulationRunner.Status.NEEDS_WORLD, step(result, harvest).status());
    }

    @Test
    void 目标标签留空时这一步无效() {
        SimulationRunner.Result result = harvest("""
                {"type":"harvest_block_clusters","maxClusterBlocks":64}
                """, tree(), areaAround(8));

        int harvest = indexOfStep(result, "harvest_block_clusters");
        assertEquals(SimulationRunner.Status.INVALID, step(result, harvest).status());
    }

    @Test
    void 库存时间轴记录每步之后的账且首项是初始库存() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 4);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        // 下标 = 步骤下标 + 1；-1 是初始库存。界面靠它做「悬浮容器看当前时刻的库存」。
        assertEquals(result.outcomes().size() + 1, result.inventoryTimeline().size());
        assertEquals(4, result.inventoryAt(-1).get("in").get("minecraft:iron_ingot"));
        assertEquals(0, result.inventoryAt(-1).getOrDefault("out", Map.of()).getOrDefault("minecraft:gear", 0));
        assertEquals(2, result.inventoryAt(0).get("in").get("minecraft:iron_ingot"));
        assertEquals(1, result.inventoryAt(0).get("out").get("minecraft:gear"));
    }

    @Test
    void 破坏方块把坐标记成空气并可选捡掉落() {
        // 掉落按坐标问产出源（真实实现在客户端读预览世界的方块状态）。
        SimLootProvider loot = blockLoot(Map.of("1,0,0", 1),
                List.of(new SimLootProvider.Product("minecraft:cobblestone", 1)));
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"destroy_block","positions":[[1,0,0]],
                            "dropItems":true,"container":"out"}]}]}
                """, new SimInventory(), loot);

        int index = indexOfStep(result, "destroy_block");
        assertEquals(SimulationRunner.Status.DONE, step(result, index).status());
        // 世界改动：那个坐标变成空气。
        List<SimulationRunner.BlockEdit> edits = result.blockEdits().get(index);
        assertEquals(1, edits.size());
        assertEquals("minecraft:air", edits.get(0).blockId());
        // NSUK: destroyBlock(target, dropItems) 的掉落**生成在地上**（IndustrialBlockActionService:149），
        // 要 collect_drops 才捡进背包、deposit_carried_items 才进容器。
        assertEquals(0, result.inventory().count("out", "minecraft:cobblestone"), "掉落先落在地上");
        assertTrue(step(result, index).message().contains("掉落在地上"),
                "消息里要写明落在地上：" + step(result, index).message());
    }

    @Test
    void 破坏方块没开掉落就只清方块不捡东西() {
        SimLootProvider loot = blockLoot(Map.of("1,0,0", 1),
                List.of(new SimLootProvider.Product("minecraft:cobblestone", 1)));
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"destroy_block","positions":[[1,0,0]],"container":"out"}]}]}
                """, new SimInventory(), loot);

        int index = indexOfStep(result, "destroy_block");
        assertEquals(SimulationRunner.Status.DONE, step(result, index).status());
        assertTrue(result.inventory().contents("out").isEmpty(), "没开 dropItems 就不该有掉落");
    }

    @Test
    void 方块掉落算不出来时仍然破坏但标注需要世界() {
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"destroy_block","positions":[[1,0,0]],
                            "dropItems":true,"container":"out"}]}]}
                """, new SimInventory(), SimLootProvider.NONE);

        int index = indexOfStep(result, "destroy_block");
        assertEquals(SimulationRunner.Status.NEEDS_WORLD, step(result, index).status());
        // 方块照样会被破坏（世界改动还在），只是掉落不假装知道。
        assertEquals("minecraft:air", result.blockEdits().get(index).get(0).blockId());
        assertTrue(result.inventory().contents("out").isEmpty());
    }

    @Test
    void 库存时间轴查询越界时夹到两端而不是抛异常() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 4);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        // 播放到一半时界面会问到还没走到的步骤，越界必须给出可用的快照。
        assertEquals(result.inventoryAt(999), result.inventoryAt(result.outcomes().size() - 1));
        assertEquals(result.inventoryAt(-99), result.inventoryAt(-1));
    }

    @Test
    void 受阻之后的步骤不再占用库存快照() {
        SimInventory inventory = new SimInventory();
        // 只有 1 个铁锭，配方要 2 个：第一步合成必然受阻，后面的步骤根本不会被执行。
        inventory.add("in", "minecraft:iron_ingot", 1);
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"},
                           {"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        assertEquals(1, result.outcomes().size());
        assertEquals(2, result.inventoryTimeline().size(), "初始 + 受阻那一步，下标 = 步骤下标 + 1 仍然成立");
        assertEquals(1, result.inventoryAt(-1).getOrDefault("in", Map.of()).getOrDefault("minecraft:iron_ingot", 0));
        assertEquals(1, result.inventoryAt(0).getOrDefault("in", Map.of()).getOrDefault("minecraft:iron_ingot", 0),
                "受阻的步骤不扣料");
    }

    @Test
    void 等待方块等一秒后在世界里生成该方块() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "points":{"p":[[1,2,3]]},
                  "steps":[{"type":"require_block","point":"p","block":"minecraft:water"}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                withPoint(new SimPos(1, 2, 3)), new SimInventory(), START, SimNavigation.direct(), 0L);

        SimulationRunner.StepOutcome wait = step(result, 0);
        assertEquals(SimulationRunner.Status.DONE, wait.status(), "等待方块不再卡住整轮");
        assertEquals(20, wait.ticks(), "等 1 秒 = 20 tick");
        assertEquals("minecraft:water", result.blockEdits().get(0).get(0).blockId());
        assertEquals(new SimPos(1, 2, 3), result.blockEdits().get(0).get(0).pos());
    }

    @Test
    void 没写方块id的等待方块退化成一个纯等待步骤() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "points":{"p":[[1,2,3]]},
                  "steps":[{"type":"require_block","point":"p"}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                withPoint(new SimPos(1, 2, 3)), new SimInventory(), START, SimNavigation.direct(), 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals(20, step(result, 0).ticks());
        assertFalse(result.blockEdits().containsKey(0), "没写方块 id 就不该改世界");
    }

    // ── 实体类动作 ─────────────────────────────────────────────────────

    /** 一个「场景里有 N 只这种实体」的 Focus（其余行为同 NONE）。 */
    private static SimulationRunner.Focus withEntities(int count) {
        return new SimulationRunner.Focus() {
            @Override
            public List<SimPos> positionsOf(SimStepSpec step) {
                return List.of();
            }

            @Override
            public int entityCount(String entityType) {
                return count;
            }
        };
    }

    @Test
    void 场景里动物不够时剪毛受阻而不是假装成功() {
        // 真实实现是在半径内找实体、找不到就重试 —— 没刷怪就不该成功。
        SimLootProvider loot = fakeLoot(List.of(),
                List.of(new SimLootProvider.Product("minecraft:white_wool", 1)), List.of());
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"shear_entities","entityType":"minecraft:sheep",
                            "count":3,"container":"out"}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                withEntities(2), new SimInventory(), START, SimNavigation.direct(), 0L,
                SimEnvironment.of(loot));

        int index = indexOfStep(result, "shear_entities");
        assertEquals(SimulationRunner.Status.BLOCKED, step(result, index).status());
        assertTrue(step(result, index).message().contains("只找到 2 只"), "消息要说清找到几只");
        assertTrue(result.inventory().contents("out").isEmpty(), "不够就不该产出");
    }

    @Test
    void 前面已经用掉的动物后面不能再算一次() {
        // 两步各要 2 只、场景里只有 3 只 ⇒ 第一步成功、第二步受阻（真实世界里牛不够）。
        SimLootProvider loot = fakeLoot(List.of(new SimLootProvider.Product("minecraft:beef", 1)),
                List.of(), List.of());
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"slaughter_entities","entityType":"minecraft:cow",
                            "count":2,"container":"out"},
                           {"type":"slaughter_entities","entityType":"minecraft:cow",
                            "count":2,"container":"out"}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                withEntities(3), new SimInventory(), START, SimNavigation.direct(), 0L,
                SimEnvironment.of(loot));

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status(), "第一步够用");
        assertEquals(1, result.blockedAt(), "第二步只剩 1 只，应当受阻");
        // 产出落在地上（不进容器），所以这里用**消息**确认「只有第一步产出了」：
        // 这个 fake 产出源忽略数量、固定给 1 份。
        assertTrue(step(result, 0).message().contains("beef x1"),
                "第一步应当产出 1 份：" + step(result, 0).message());
        assertTrue(result.inventory().contents("out").isEmpty(), "产物在地上，容器里还什么都没有");
    }

    @Test
    void 剪羊毛的产物落在地上而不是直接进容器() {
        // 产出源（真实战利品表）由客户端注入；NSUK 把羊毛 popResource 到地上
        // （IndustrialEntityActionService:124-129），要 collect_drops 才进背包、
        // deposit_carried_items 才进容器 —— 所以这一步容器里**不该**有东西。
        SimLootProvider loot = fakeLoot(List.of(),
                List.of(new SimLootProvider.Product("minecraft:white_wool", 5)), List.of());
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"shear_entities","entityType":"minecraft:sheep",
                            "count":3,"container":"out"}]}]}
                """, new SimInventory(), loot);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status(), "剪毛应当能算出产物");
        // 5 就是 5：products 已经是「3 只羊」的总数，内核不能再乘一次动物数（踩过这个 bug）。
        assertTrue(step(result, 0).message().contains("white_wool x5"),
                "消息里要写明掉了什么：" + step(result, 0).message());
        assertEquals(0, result.inventory().count("out", "minecraft:white_wool"),
                "产物先落在地上，不该直接进容器");
    }

    @Test
    void 屠宰的掉落物落在地上再由收集与入库步骤搬进容器() {
        // NSUK：屠宰让动物**自然掉落在地上**，collect_drops 把地上的捡进工人背包，
        // deposit_carried_items 才把背包倒进容器 —— 三步缺一不可。
        // 早先我们直接写容器，deposit 就成了空操作，作者会漏配那一步而看不出来。
        SimLootProvider loot = fakeLoot(List.of(new SimLootProvider.Product("minecraft:beef", 4),
                new SimLootProvider.Product("minecraft:leather", 2)), List.of(), List.of());
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"slaughter_entities","entityType":"minecraft:cow",
                            "count":2,"container":"out"},
                           {"type":"collect_drops","item":"minecraft:beef","count":4},
                           {"type":"deposit_carried_items","container":"out"}]}]}
                """, new SimInventory(), loot);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertTrue(step(result, 0).message().contains("掉落在地上"),
                "屠宰自身不进容器，产物先落地：" + step(result, 0).message());
        // 只有被 collect 捡起来的那一样进了容器；皮革还躺在地上（没人捡）。
        assertEquals(4, result.inventory().count("out", "minecraft:beef"));
        assertEquals(0, result.inventory().count("out", "minecraft:leather"),
                "没被收集的掉落物不该自己跑进容器");
    }

    @Test
    void 地上没有掉落物时收集受阻而不是假装捡到() {
        // NSUK：collect_drops 找不到地上的掉落物是 missing_drops（重试），不是成功。
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"collect_drops","item":"minecraft:white_wool","count":2}]}]}
                """, new SimInventory(), fakeLoot(List.of(), List.of(), List.of()));

        int index = indexOfStep(result, "collect_drops");
        assertEquals(SimulationRunner.Status.BLOCKED, step(result, index).status());
        assertTrue(step(result, index).message().contains("地上没有"),
                "消息要说清是地上没有：" + step(result, index).message());
    }

    /** 按步骤类型找它的下标（计划里可能有自动补的步骤，不能假定就是 0/1/2）。 */
    private static int indexOfStep(SimulationRunner.Result result, String typeId) {
        for (SimulationRunner.StepOutcome outcome : result.outcomes()) {
            if (typeId.equals(outcome.typeId())) {
                return outcome.index();
            }
        }
        throw new AssertionError("计划里没有 " + typeId + " 这一步");
    }

    @Test
    void 剪毛屠宰繁殖各自记下动物改动() {
        // 库存算对了但羊还全站着，画面就没对上。这三条改动就是给客户端改画面用的。
        SimLootProvider loot = fakeLoot(
                List.of(new SimLootProvider.Product("minecraft:beef", 1)),
                List.of(new SimLootProvider.Product("minecraft:white_wool", 2)),
                List.of("minecraft:wheat"));
        SimInventory inventory = new SimInventory();
        // 一对要**两份**食物（NSUK: consumeBreedFood(..., first, 2)）。
        inventory.add("in", "minecraft:wheat", 2);

        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"shear_entities","entityType":"minecraft:sheep",
                            "count":2,"container":"out"},
                           {"type":"slaughter_entities","entityType":"minecraft:cow",
                            "count":2,"container":"out"},
                           {"type":"breed_entities","entityType":"minecraft:cow",
                            "container":"in"}]}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, inventory, START, SimNavigation.direct(), 0L, loot);

        List<SimulationRunner.EntityEdit> shears =
                result.entityEdits().get(indexOfStep(result, "shear_entities"));
        assertEquals(2, shears.size(), "2 只羊 = 2 条剪毛改动");
        assertEquals(SimulationRunner.EntityEdit.Kind.SHEAR, shears.get(0).kind());
        assertEquals("minecraft:sheep", shears.get(0).entityType());

        List<SimulationRunner.EntityEdit> kills =
                result.entityEdits().get(indexOfStep(result, "slaughter_entities"));
        assertEquals(2, kills.size(), "2 头牛 = 2 条屠宰改动");
        assertEquals(SimulationRunner.EntityEdit.Kind.KILL, kills.get(0).kind());

        List<SimulationRunner.EntityEdit> spawns =
                result.entityEdits().get(indexOfStep(result, "breed_entities"));
        // NSUK：count 是**对数**，一对生**两只**幼崽（原版繁殖是双胞胎）。
        assertEquals(2, spawns.size(), "繁殖一对 = 2 条生成改动");
        assertEquals(SimulationRunner.EntityEdit.Kind.SPAWN, spawns.get(0).kind());
    }

    @Test
    void 繁殖受阻时不留动物改动() {
        // 要喂食但容器里没有它认的食物 → 这一步受阻 ⇒ 不该凭空多出一只动物
        // （画面和账要对得上：账没成功，羊就不该变多）。
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"breed_entities","entityType":"minecraft:cow",
                            "container":"in"}]}]}
                """, new SimInventory(), fakeLoot(List.of(), List.of(), List.of("minecraft:wheat")));

        int index = indexOfStep(result, "breed_entities");
        assertEquals(SimulationRunner.Status.BLOCKED, step(result, index).status());
        assertFalse(result.entityEdits().containsKey(index), "没成功就不该动动物");
    }

    @Test
    void 拿不到产出源时标注需要世界而不是编一个产物() {
        // 这就是「模组生物但战利品表查不到」的情况：不猜，老实说需要真实世界。
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"slaughter_entities","entityType":"some_mod:critter",
                            "container":"out"}]}]}
                """, new SimInventory(), SimLootProvider.NONE);

        assertEquals(SimulationRunner.Status.NEEDS_WORLD, step(result, 0).status());
        assertTrue(result.inventory().contents("out").isEmpty(), "不能凭空编产物出来");
    }

    @Test
    void 繁殖要喂食时从容器扣食物() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:wheat", 2);

        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"breed_entities","entityType":"minecraft:cow",
                            "container":"in"}]}]}
                """, inventory, fakeLoot(List.of(), List.of(), List.of("minecraft:wheat")));

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals(0, result.inventory().count("in", "minecraft:wheat"), "一对扣两份（NSUK 是 2）");
    }

    @Test
    void 繁殖没食物时受阻并说明认哪些食物() {
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"breed_entities","entityType":"minecraft:cow","container":"in"}]}]}
                """, new SimInventory(), fakeLoot(List.of(), List.of(), List.of("minecraft:wheat")));

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        assertTrue(step(result, 0).message().contains("wheat"), "提示里要说清认哪些食物");
    }

    @Test
    void 繁殖写了不喂食就不扣食物() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"breed_entities","entityType":"minecraft:cow",
                            "container":"in","requireFood":false}]}]}
                """, SimulationRunner.Focus.NONE, SimNavigation.direct(), new SimInventory(), 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
    }

    @Test
    void 收集掉落物把地上的东西捡进背包再由入库步骤进容器() {
        // 完整链条：剪毛（落在地上）→ 收集（进背包）→ 入库（进容器）。
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"shear_entities","entityType":"minecraft:sheep","count":2},
                           {"type":"collect_drops","item":"minecraft:white_wool","count":2},
                           {"type":"deposit_carried_items","container":"out"}]}]}
                """, new SimInventory(),
                fakeLoot(List.of(), List.of(new SimLootProvider.Product("minecraft:white_wool", 2)),
                        List.of()));

        assertEquals(2, result.inventory().count("out", "minecraft:white_wool"),
                "剪毛落在地上 → 收集进背包 → 入库才进容器");
    }

    @Test
    void 内核用注入的产出源而不是自己猜() {
        // 客户端注入的是走真实战利品表的实现（IntegratedServerLootProvider）；
        // 内核只负责用它的结果，不关心数据从哪来 —— 这条测试守住这个边界。
        SimLootProvider fake = fakeLoot(
                List.of(new SimLootProvider.Product("minecraft:diamond", 10)), List.of(), List.of());
        SimulationRunner.Result result = runWithLoot("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"slaughter_entities","entityType":"minecraft:cow",
                            "count":2,"container":"out"}]}]}
                """, new SimInventory(), fake);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertTrue(step(result, 0).message().contains("diamond x10"),
                "用的是注入源的产出（掉落在地上）：" + step(result, 0).message());
        assertEquals(0, result.inventory().count("out", "minecraft:diamond"), "产物先落在地上");
        assertEquals(0, result.inventory().count("out", "minecraft:beef"), "内核自己不该有掉落数据");
    }

    @Test
    void 材料不足时合成受阻且不会只扣掉一半() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 1);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":1},
                            {"item":"minecraft:coal","count":1}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        // 旧版在这里已经把铁锭扣掉了：它是边遍历边 consume 的。
        assertEquals(1, result.inventory().count("in", "minecraft:iron_ingot"));
        assertEquals(0, result.inventory().count("out", "minecraft:gear"));
        assertEquals(0, result.blockedAt());
        assertFalse(result.completed());
    }

    @Test
    void 概率为0的产出永远不出现() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 8);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":1}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1,"probability":0.0}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals(0, result.inventory().count("out", "minecraft:gear"));
    }

    @Test
    void 同一种子重跑得到完全一致的结果() {
        String recipeJson = """
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":1}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1,"randomRange":3,"probability":0.7}],
                  "steps":[{"type":"repeat","count":12,"steps":[
                     {"type":"craft_recipe","input":"in","output":"out"}]}]}]}
                """;
        SimInventory first = new SimInventory();
        first.add("in", "minecraft:iron_ingot", 64);
        SimInventory second = new SimInventory();
        second.add("in", "minecraft:iron_ingot", 64);

        SimulationRunner.Result a = run(recipeJson, first, 20261008L);
        SimulationRunner.Result b = run(recipeJson, second, 20261008L);

        assertEquals(a.inventory().snapshot(), b.inventory().snapshot());
        assertEquals(a.totalTicks(), b.totalTicks());
    }

    @Test
    void 随机产出范围生效且与概率各自消耗一次随机数() {
        String recipeJson = """
                {"recipes":[{"id":"r",
                  "inputs":[],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1,"randomRange":10,"probability":1.0}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """;
        int total = 0;
        for (long seed = 0; seed < 64; seed++) {
            total += run(recipeJson, new SimInventory(), seed).inventory().count("out", "minecraft:gear");
        }
        // baseAmount=1 且 randomRange=10 ⇒ 每次产出落在 [1,10]。
        assertTrue(total > 64, "随机增量应当让总产出超过每次都取 1 的结果，实际 " + total);
        assertTrue(total <= 64 * 11, "总产出不该超过 baseAmount+randomRange 的上界，实际 " + total);
    }

    @Test
    void 连续合成会把材料一次做到尽() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 7);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_available_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        // 7 个铁锭、每次 2 个 ⇒ 做 3 次，剩 1 个。
        assertEquals(3, result.inventory().count("out", "minecraft:gear"));
        assertEquals(1, result.inventory().count("in", "minecraft:iron_ingot"));
    }

    @Test
    void 输出容器容量不足时合成受阻并回滚() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 2);
        inventory.setCapacity("out", 0);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        assertEquals(2, result.inventory().count("in", "minecraft:iron_ingot"));
    }

    // ── 材料/空间检查 ──────────────────────────────────────────────────

    @Test
    void require_inputs读的是配方级输入而不是步骤上的inputs() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 2);

        // 步骤上写了一份「只要有 1 个石头就够」的 inputs，配方级要的是 3 个铁锭。
        // NSUK 只会看配方级的，所以这里必须受阻。
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":3}],
                  "outputs":[],
                  "steps":[{"type":"require_inputs","container":"in",
                            "inputs":[{"item":"minecraft:stone","count":1}]}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        assertTrue(step(result, 0).message().contains("minecraft:iron_ingot"));
    }

    @Test
    void require_output_space按最坏情况占用检查() {
        // capacity 的单位是**格**：这里把堆叠上限设成 1（不可堆叠），
        // 于是最坏情况 6 件就占 6 格 —— 3 格放不下、6 格刚好。
        SimInventory inventory = new SimInventory();
        inventory.setCapacity("out", 3);
        inventory.setStackLimits(item -> 1);

        SimulationRunner.Result blocked = run("""
                {"recipes":[{"id":"r","inputs":[],
                  "outputs":[{"item":"minecraft:gear","baseAmount":2,"randomRange":4}],
                  "steps":[{"type":"require_output_space","container":"out"}]}]}
                """, inventory, 0L);
        assertEquals(SimulationRunner.Status.BLOCKED, step(blocked, 0).status());

        SimInventory roomy = new SimInventory();
        roomy.setCapacity("out", 6);
        roomy.setStackLimits(item -> 1);
        SimulationRunner.Result ok = run("""
                {"recipes":[{"id":"r","inputs":[],
                  "outputs":[{"item":"minecraft:gear","baseAmount":2,"randomRange":4}],
                  "steps":[{"type":"require_output_space","container":"out"}]}]}
                """, roomy, 0L);
        assertEquals(SimulationRunner.Status.DONE, step(ok, 0).status());
    }

    @Test
    void 步骤自带inputs时覆盖配方级输入() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:stone", 1);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":3}],
                  "outputs":[{"item":"minecraft:gear","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out",
                            "inputs":[{"item":"minecraft:stone","count":1}]}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals(0, result.inventory().count("in", "minecraft:stone"));
        assertEquals(1, result.inventory().count("out", "minecraft:gear"));
    }

    @Test
    void any组取第一个可行分支() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:gold_ingot", 1);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"or":[{"item":"minecraft:iron_ingot","count":1},
                                   {"item":"minecraft:gold_ingot","count":1}]}],
                  "outputs":[],
                  "steps":[{"type":"require_inputs","container":"in"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
    }

    @Test
    void consume为false的材料只检查不消耗() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:bucket", 1);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:bucket","count":1,"consume":false}],
                  "outputs":[{"item":"minecraft:water_bucket","baseAmount":1}],
                  "steps":[{"type":"craft_recipe","input":"in","output":"out"}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals(1, result.inventory().count("in", "minecraft:bucket"));
        assertEquals(1, result.inventory().count("out", "minecraft:water_bucket"));
    }

    // ── 容器动作 ───────────────────────────────────────────────────────

    @Test
    void insert_item把步骤自带的物品放进输出容器且按12tick计() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"insert_item","container":"out","item":"minecraft:stone","count":5}]}]}
                """, new SimInventory(), 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals(5, result.inventory().count("out", "minecraft:stone"));
        assertEquals(12, step(result, 0).ticks());
    }

    @Test
    void insert_item在容器满时受阻() {
        // capacity 的单位是**格**：1 格只放得下 64 个（同种物品堆叠），65 个就需要 2 格。
        SimInventory inventory = new SimInventory();
        inventory.setCapacity("out", 1);

        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"insert_item","container":"out","item":"minecraft:stone","count":65}]}]}
                """, inventory, 0L);

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        assertEquals(0, result.inventory().count("out", "minecraft:stone"));
    }

    @Test
    void 没有type的步骤被判为无效() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"statusKey":"x"},{"type":"set_status"}]}]}
                """, new SimInventory(), 0L);

        assertEquals(SimulationRunner.Status.INVALID, step(result, 0).status());
        // 无效步骤不阻断后续：它和 needs-world 一样只是「没法判定」。
        assertEquals(SimulationRunner.Status.DONE, step(result, 1).status());
    }

    @Test
    void set_held_item改变手上物品但不改库存() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","heldItem":"minecraft:iron_pickaxe","inputs":[],"outputs":[],
                  "steps":[{"type":"set_held_item","item":"minecraft:diamond_pickaxe"},
                           {"type":"use_item","ticks":20}]}]}
                """, new SimInventory(), 0L);

        assertEquals("minecraft:diamond_pickaxe", step(result, 0).heldItem());
        assertEquals("minecraft:diamond_pickaxe", result.heldItem());
        assertEquals(20, step(result, 1).ticks());
        assertTrue(result.inventory().snapshot().isEmpty());
    }

    // ── 世界步骤与受阻传播 ─────────────────────────────────────────────

    @Test
    void 放方块现在会真的改世界而不是只标注需要世界() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"place_block","block":"minecraft:furnace","positions":[[1,0,0]]},
                           {"type":"set_status"}]}]}
                """, new SimInventory(), 0L);

        // 以前这里是 NEEDS_WORLD（只标注「按假定成功继续」），仿真里看不出放了什么。
        // 现在改成 DONE + 一条世界改动，客户端按播放进度真的把方块应用上去。
        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals("minecraft:furnace", result.blockEdits().get(0).get(0).blockId());
        assertEquals(new SimPos(1, 0, 0), result.blockEdits().get(0).get(0).pos());
        assertEquals(SimulationRunner.Status.DONE, step(result, 1).status());
        assertTrue(result.completed());
    }

    @Test
    void 世界步骤标记为需要世界并假定成功继续() {
        // harvest_block_clusters 是最后一个仍然只能标注「需要世界并假定成功」的步骤：
        // 它要真实方块标签、预览世界的方块状态、以及 workArea 的扫描几何。
        // （destroy_block / real_machine_recipe 现在都能算了，见上面的测试。）
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"harvest_block_clusters","targetBlockTag":"#minecraft:logs"},
                           {"type":"set_status"}]}]}
                """, new SimInventory(), 0L);

        assertEquals(SimulationRunner.Status.NEEDS_WORLD, step(result, 0).status());
        assertTrue(step(result, 0).assumed());
        assertEquals(SimulationRunner.Status.DONE, step(result, 1).status());
        assertTrue(result.completed());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void 真实机器配方扣料并在一次poll之后出产物() {
        // 真实实现（NSUK IndustrialMachineOperationService）也不问机器：
        // 扣 inputs → 数机器槽里比 baseline 多出来的 outputs。见 SimulationRunner.executeRealMachine。
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:iron_block","count":1}],
                  "steps":[{"type":"real_machine_recipe","point":"p",
                            "input":"in","output":"out","pollTicks":7}]}]}
                """);
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 5);

        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, inventory, START, SimNavigation.direct(), 0L);

        int index = indexOfStep(result, "real_machine_recipe");
        assertEquals(SimulationRunner.Status.DONE, step(result, index).status());
        assertEquals(3, result.inventory().count("in", "minecraft:iron_ingot"), "扣掉 2 个铁锭");
        assertEquals(1, result.inventory().count("out", "minecraft:iron_block"), "产物进了输出箱");
        assertEquals(7, step(result, index).ticks(), "耗时按 pollTicks（最早只可能在第一次 poll 后被观测到）");
    }

    @Test
    void 机器产物留在机器里时不进输出箱() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:iron_block","count":1}],
                  "steps":[{"type":"real_machine_recipe","point":"p","input":"in","output":"out",
                            "outputPolicy":"keep_in_machine"}]}]}
                """);
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 5);

        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, inventory, START, SimNavigation.direct(), 0L);

        assertEquals(SimulationRunner.Status.DONE,
                step(result, indexOfStep(result, "real_machine_recipe")).status());
        assertEquals(3, result.inventory().count("in", "minecraft:iron_ingot"), "料照样扣");
        assertTrue(result.inventory().contents("out").isEmpty(), "keep_in_machine 就不该出现在输出箱");
    }

    @Test
    void 机器缺料时受阻不产出() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":2}],
                  "outputs":[{"item":"minecraft:iron_block","count":1}],
                  "steps":[{"type":"real_machine_recipe","point":"p","input":"in","output":"out"}]}]}
                """);

        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                SimulationRunner.Focus.NONE, new SimInventory(), START, SimNavigation.direct(), 0L);

        assertEquals(SimulationRunner.Status.BLOCKED,
                step(result, indexOfStep(result, "real_machine_recipe")).status());
        assertTrue(result.inventory().contents("out").isEmpty(), "没料就不该凭空出产物");
    }

    @Test
    void 受阻之后立刻停止不再为剩余步骤生成记录() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":9}],
                  "outputs":[],
                  "steps":[{"type":"require_inputs","container":"in"},
                           {"type":"set_status"}]}]}
                """, new SimInventory(), 0L);

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        assertEquals(0, result.blockedAt());
        // 受阻就停在那里：不再补齐「前一步受阻，未执行」的占位步骤。
        // 界面上那会变成十几行同义噪音，而播放也会「继续跑」过它们。
        assertEquals(1, result.outcomes().size(), "受阻的那一步之后不该再有任何步骤记录");
        assertEquals(2, result.inventoryTimeline().size());
    }

    @Test
    void 开启skipOnTimeout的受阻步骤按超时跳过() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":9}],
                  "outputs":[],
                  "steps":[{"type":"require_inputs","container":"in",
                            "skipOnTimeout":true,"timeoutTicks":100},
                           {"type":"set_status"}]}]}
                """, new SimInventory(), 0L);

        assertEquals(SimulationRunner.Status.SKIPPED_TIMEOUT, step(result, 0).status());
        assertEquals(100, step(result, 0).ticks());
        assertEquals(SimulationRunner.Status.DONE, step(result, 1).status());
        assertEquals(-1, result.blockedAt());
    }

    // ── 寻路 ───────────────────────────────────────────────────────────

    @Test
    void 移动步骤按寻路结果给出路线与耗时() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to","point":"p"},{"type":"use_item","ticks":3}]}]}
                """);
        List<SimulationPlan.Entry> plan = SimulationPlan.of(recipe);
        SimPos target = new SimPos(3, 0, 4);

        SimulationRunner.Result result = SimulationRunner.run(recipe, plan,
                step -> "move_to".equals(step.typeId()) ? List.of(target) : List.of(),
                new SimInventory(), START, SimNavigation.direct(), 0L);

        SimulationRunner.StepOutcome move = step(result, 0);
        assertEquals(SimulationRunner.Status.DONE, move.status());
        // (0,0,0) → (3,0,4)：直线兜底沿轴走 7 格、含起点 8 个途径点；
        // 走路速度 0.2 格/tick ⇒ ceil(7 / 0.2) = 35 tick。
        assertEquals(8, move.route().size());
        assertEquals(35, move.ticks());
        assertEquals(target, move.route().get(move.route().size() - 1).cell());
        assertEquals(1, move.focus().size());
        assertTrue(move.focus().contains(target));
        assertEquals(SimGesture.WALK, move.gesture());
    }

    @Test
    void 多点位移动取离当前位置最近的一个() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to","point":"p"}]}]}
                """);
        List<SimulationPlan.Entry> plan = SimulationPlan.of(recipe);
        SimPos far = new SimPos(50, 0, 50);
        SimPos near = new SimPos(2, 0, 0);

        SimulationRunner.Result result = SimulationRunner.run(recipe, plan,
                step -> List.of(far, near),
                new SimInventory(), START, SimNavigation.direct(), 0L);

        SimulationRunner.StepOutcome move = step(result, 0);
        assertEquals(SimulationRunner.Status.DONE, move.status());
        assertEquals(near, move.route().get(move.route().size() - 1).cell());
        // 2 格 ÷ 0.2 格/tick = 10 tick。
        assertEquals(10, move.ticks());
        // 两点都要被高亮，不然作者看不出这一步有哪些候选站位。
        assertEquals(2, move.focus().size());
    }

    @Test
    void 移动到实体且没有点位时按需要世界处理() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to_entity","entityType":"minecraft:sheep","radius":6}]}]}
                """, new SimInventory(), 0L);

        assertEquals(SimulationRunner.Status.NEEDS_WORLD, step(result, 0).status());
    }

    @Test
    void 移动步骤没有目标点位时受阻() {
        SimulationRunner.Result result = run("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to","point":"不存在"}]}]}
                """, new SimInventory(), 0L);

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        assertTrue(step(result, 0).route().isEmpty());
    }

    @Test
    void 移动步骤只走一个坐标轴且不穿楼板() {
        // 兜底实现是「X → Z → 最后 Y」，不会先横穿再原地升天。
        List<SimWaypoint> path = SimNavigation.direct().findPath(new SimPos(0, 0, 0), new SimPos(2, 3, 1));
        // 曼哈顿距离 2+3+1 = 6，含起点共 7 个点。
        assertEquals(7, path.size());
        // 先把 XZ 平面走完（前 4 个点都在 y=0），最后才动 Y。
        assertEquals(0.0D, path.get(0).y());
        assertEquals(0.0D, path.get(3).y());
        assertEquals(3.0D, path.get(path.size() - 1).y());
        // 落点是格心，不是格角。
        assertEquals(2.5D, path.get(path.size() - 1).x());
        assertEquals(1.5D, path.get(path.size() - 1).z());
    }

    @Test
    void 单次寻路点数有上限() {
        List<SimWaypoint> path = SimNavigation.direct().findPath(new SimPos(0, 0, 0), new SimPos(100000, 0, 0));
        assertTrue(path.size() <= SimNavigation.MAX_POINTS_PER_LEG + 1,
                "寻路点数必须被限制住，实际 " + path.size());
    }

    @Test
    void 走路耗时按真实步速换算而不是一格一tick() {
        List<SimWaypoint> path = SimNavigation.direct().findPath(new SimPos(0, 0, 0), new SimPos(10, 0, 0));
        // 10 格 ÷ 0.2 格/tick = 50 tick（旧版是 10 tick，快五倍，看起来像瞬移）。
        assertEquals(50, SimNavigation.travelTicks(path));
    }

    @Test
    void 攀爬与下落各按自己的速度计时() {
        SimWaypoint ground = SimWaypoint.of(new SimPos(0, 0, 0), 0.0D, SimMotion.WALK);
        SimWaypoint ladder = SimWaypoint.of(new SimPos(0, 1, 0), 1.0D, SimMotion.CLIMB);
        SimWaypoint top = SimWaypoint.of(new SimPos(0, 2, 0), 2.0D, SimMotion.CLIMB);
        // 两格梯子：每格 1 / 0.16 = 6.25 ⇒ 合计 12.5 ⇒ ceil 13 tick，
        // 比按走路速度算（2 / 0.2 = 10）慢，爬梯子才不像坐电梯。
        assertEquals(13, SimNavigation.travelTicks(List.of(ground, ladder, top)));

        SimWaypoint drop = SimWaypoint.of(new SimPos(0, -3, 0), -3.0D, SimMotion.FALL);
        assertTrue(SimNavigation.travelTicks(List.of(ground, drop)) >= 3);
    }

    @Test
    void 库存取用是原子的() {
        SimInventory inventory = new SimInventory();
        inventory.add("in", "minecraft:iron_ingot", 2);
        assertFalse(inventory.take("in", "minecraft:iron_ingot", 3));
        assertEquals(2, inventory.count("in", "minecraft:iron_ingot"));
        assertTrue(inventory.take("in", "minecraft:iron_ingot", 2));
        assertEquals(0, inventory.count("in", "minecraft:iron_ingot"));
    }

    @Test
    void 物品描述键把标签与具体物品区分开() {
        assertEquals("#c:ingots", new SimItemSpec("", "c:ingots", "", "", "").key());
        assertEquals("minecraft:iron_ingot", new SimItemSpec("minecraft:iron_ingot", "", "", "", "").key());
        assertEquals("minecraft:iron_ingot",
                new SimItemSpec("", "", "", "minecraft:iron_ingot[damage=1]", "").key());
        assertTrue(SimItemSpec.EMPTY.isEmpty());
    }

    @Test
    void 容器没有定义时步骤受阻而不是被忽略() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r",
                  "inputs":[{"item":"minecraft:iron_ingot","count":1}],
                  "outputs":[],
                  "steps":[{"type":"require_inputs","container":"并不存在的箱子"}]}]}
                """);
        SimulationRunner.Focus focus = new SimulationRunner.Focus() {
            @Override
            public List<SimPos> positionsOf(SimStepSpec step) {
                return List.of();
            }

            @Override
            public boolean knowsContainer(String name) {
                return "exists".equals(name);
            }
        };
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe), focus,
                new SimInventory(), START, SimNavigation.direct(), 0L);

        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        assertTrue(step(result, 0).message().contains("并不存在的箱子"));
    }

    @Test
    void 走到容器是站到容器旁边而不是站进容器里() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to_container","container":"箱"}]}]}
                """);
        SimPos container = new SimPos(3, 0, 0);
        SimPos stand = new SimPos(2, 0, 0);
        SimulationRunner.Focus focus = new SimulationRunner.Focus() {
            @Override
            public List<SimPos> positionsOf(SimStepSpec step) {
                return List.of(container);
            }

            @Override
            public boolean knowsContainer(String name) {
                return "箱".equals(name);
            }
        };
        SimNavigation navigation = new SimNavigation() {
            @Override
            public List<SimWaypoint> findPath(SimPos from, SimPos to) {
                return SimNavigation.direct().findPath(from, to);
            }

            @Override
            public List<SimPos> standTargets(SimPos target) {
                return List.of(stand);
            }
        };
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe), focus,
                new SimInventory(), new SimPos(0, 0, 0), navigation, 0L);

        SimulationRunner.StepOutcome move = step(result, 0);
        assertEquals(SimulationRunner.Status.DONE, move.status());
        assertEquals(stand, move.route().get(move.route().size() - 1).cell(),
                "必须站到容器旁边，而不是容器那一格");
        // 容器本身仍然要高亮，否则用户看不出这一步在操作哪个箱子。
        assertTrue(move.focus().contains(container));
    }

    @Test
    void 容器周围没有落脚点时退回容器自身() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to_container","container":"箱"}]}]}
                """);
        SimPos container = new SimPos(3, 0, 0);
        SimulationRunner.Focus focus = new SimulationRunner.Focus() {
            @Override
            public List<SimPos> positionsOf(SimStepSpec step) {
                return List.of(container);
            }

            @Override
            public boolean knowsContainer(String name) {
                return "箱".equals(name);
            }
        };
        // standTargets 默认返回空 ⇒ 代表「周围没有落脚点」，此时退化成容器自身（NSUK 的 hasStandTarget=false）。
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe), focus,
                new SimInventory(), new SimPos(0, 0, 0), SimNavigation.direct(), 0L);

        assertEquals(SimulationRunner.Status.DONE, step(result, 0).status());
        assertEquals(container, step(result, 0).route().get(step(result, 0).route().size() - 1).cell());
    }

    @Test
    void 容器没定义时走到容器会受阻而不是静默走错() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],
                  "steps":[{"type":"move_to_container","container":"不存在的箱子"}]}]}
                """);
        // 客户端的 Focus 会用「建筑里到底有没有这个容器定义」来回答 knowsContainer，
        // 这里模拟「确实没有」。
        SimulationRunner.Focus focus = new SimulationRunner.Focus() {
            @Override
            public List<SimPos> positionsOf(SimStepSpec step) {
                return List.of();
            }

            @Override
            public boolean knowsContainer(String name) {
                return false;
            }
        };
        SimulationRunner.Result result = SimulationRunner.run(recipe, SimulationPlan.of(recipe),
                focus, new SimInventory(), START, SimNavigation.direct(), 0L);
        assertEquals(SimulationRunner.Status.BLOCKED, step(result, 0).status());
        assertTrue(step(result, 0).message().contains("不存在的箱子"),
                "提示里要写清是哪个容器没定义，实际：" + step(result, 0).message());
    }

    @Test
    void 空配方跑出空结果() {
        JsonObject recipe = recipe("""
                {"recipes":[{"id":"r"}]}
                """);
        SimulationRunner.Result result = SimulationRunner.run(recipe, List.of(),
                SimulationRunner.Focus.NONE, new SimInventory(), START, SimNavigation.direct(), 0L);
        assertTrue(result.outcomes().isEmpty());
        assertFalse(result.completed());
        assertNull(result.outcome(3));
    }
}
