package com.rstech.simupack.creation.industrial;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工业动作组数据层的单元测试。
 *
 * <p>这些测试完全脱离客户端与 Minecraft 运行时——数据层刻意只用 Gson，
 * 因为「往返无损」和「展开步数」是整个功能最容易出错、返工代价最大的两处。
 */
class BuildingJsonDocumentTest {

    /** 取自《编写方案》2.2 的官方示例，并额外塞入一个编辑器不认识的键。 */
    private static final String WOOL_FARM = """
            {
              "id": "simukraft:wool_farm",
              "name": "羊毛农场",
              "jobType": "wool_farmer",
              "jobName": "羊毛工",
              "heldItem": "minecraft:shears",
              "points": { "stand": { "type": "structure_pos", "positions": [[4,1,4]], "select": "nearest" } },
              "containers": { "output": { "type": "structure_pos", "positions": [[7,1,3]] } },
              "workArea": { "type": "building_outer_rect" },
              "spawnEntity": { "enabled": true, "type": "minecraft:sheep", "count": 5 },
              "someFutureModKey": { "nested": [1, 2, 3], "flag": true },
              "recipes": [
                {
                  "id": "shear_wool",
                  "name": "剪羊毛",
                  "inputs": [],
                  "outputs": [ { "item": "minecraft:white_wool", "baseAmount": 1, "randomRange": 2 } ],
                  "steps": [ { "type": "require_output_space", "container": "output" } ]
                }
              ]
            }
            """;

    // ── 阶段 1 可验证点：往返无损 ───────────────────────────────────────

    @Test
    @DisplayName("往返无损：不认识的顶层键原样保留，且二次序列化幂等")
    void roundTripIsLossless() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(WOOL_FARM);
        String text = model.toJsonString();

        JsonObject reparsed = JsonParser.parseString(text).getAsJsonObject();
        assertEquals("simukraft:wool_farm", reparsed.get("id").getAsString());
        assertEquals("羊毛农场", reparsed.get("name").getAsString());
        // 编辑器不认识的键必须还在——否则就是「打开再保存就损坏配置」。
        assertTrue(reparsed.has("someFutureModKey"));
        assertEquals(3, reparsed.getAsJsonObject("someFutureModKey").getAsJsonArray("nested").size());
        assertTrue(reparsed.getAsJsonObject("someFutureModKey").get("flag").getAsBoolean());
        assertEquals(5, reparsed.getAsJsonObject("spawnEntity").get("count").getAsInt());
        assertEquals("nearest", reparsed.getAsJsonObject("points").getAsJsonObject("stand")
                .get("select").getAsString());

        // 幂等：解析再打印不会漂移，否则每次保存都会产生无意义的 diff。
        BuildingJsonDocument again = BuildingJsonDocument.parse(text);
        assertEquals(text, again.toJsonString());
        assertEquals(1, again.steps("shear_wool").size());
    }

    @Test
    @DisplayName("空文本得到可编辑骨架；非法 JSON 抛异常而不是静默吞掉")
    void parsingEdgeCases() {
        BuildingJsonDocument empty = BuildingJsonDocument.parse("");
        assertTrue(empty.recipes().isEmpty());
        assertEquals(0, empty.steps("").size());

        assertThrows(IllegalArgumentException.class, () -> BuildingJsonDocument.parse("{ 坏掉的"));
        assertThrows(IllegalArgumentException.class, () -> BuildingJsonDocument.parse("[1,2,3]"));
    }

    @Test
    @DisplayName("新建建筑的骨架里没有分类专属内容：不凭空出现 containers / offers / points")
    void blankSkeletonInventsNoCategoryContent() {
        // 新建建筑时 jsonConfig 是空串，第一次写盘写出去的就是这份骨架（EditorPanel.persist）——
        // 所以「新建」这个动作能塞进用户作品的键，只可能来自这里。
        JsonObject root = BuildingJsonDocument.parse("").root();
        assertEquals(Set.of("recipes"), root.keySet(),
                "骨架只该有最小内容，多出来的都是替用户编的");
        assertFalse(root.has("containers"), "骨架里不许有容器条目");
        assertFalse(root.has("offers"), "骨架里不许有商业报价");
        assertFalse(root.has("points"), "骨架里不许有点位");
    }

    // ── 别名归一化 ────────────────────────────────────────────────────

    @Test
    @DisplayName("读取接受别名，写入归一到规范键")
    void aliasesAreReadAndNormalizedOnWrite() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"destroy_block","drop":true,"timeout":50,"positions":[[1,2,3]]},
                  {"type":"place_liquid","liquid":"minecraft:water","point":"p"},
                  {"type":"set_held_item","itemStack":"minecraft:diamond_pickaxe"}
                ]}]}
                """);
        IndustrialStepType destroy = IndustrialStepType.byRawType("destroy_block");
        IndustrialStepType fluid = IndustrialStepType.byRawType("place_fluid");
        IndustrialStepType held = IndustrialStepType.byRawType("set_held_item");

        JsonObject destroyStep = model.steps("r").get(0);
        assertEquals("destroy_block", destroyStep.get("type").getAsString());
        assertTrue(BuildingJsonDocument.readFlag(destroyStep, destroy.field("dropItems")));
        assertEquals(50D, BuildingJsonDocument.readNumber(destroyStep, destroy.field("timeoutTicks")), 1e-9);

        // 写入会删掉别名拼写、只留规范键。
        BuildingJsonDocument.writeNumber(destroyStep, destroy.field("timeoutTicks"), 80);
        assertFalse(destroyStep.has("timeout"));
        assertEquals(80, destroyStep.get("timeoutTicks").getAsInt());

        BuildingJsonDocument.writeFlag(destroyStep, destroy.field("dropItems"), false);
        assertFalse(destroyStep.has("drop"));

        JsonObject fluidStep = model.steps("r").get(1);
        assertEquals("minecraft:water",
                BuildingJsonDocument.readString(fluidStep, fluid.field("fluid")));
        assertEquals("p", BuildingJsonDocument.readString(fluidStep, fluid.field("point")));

        JsonObject heldStep = model.steps("r").get(2);
        assertEquals("minecraft:diamond_pickaxe", BuildingJsonDocument.readItemId(heldStep));
        assertTrue(BuildingJsonDocument.hasItemSpec(heldStep));
    }

    @Test
    @DisplayName("没写的字段返回加载器默认值，清空写空串等于删除该键")
    void defaultsAndClearing() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[{"type":"breed_entities"}]}]}
                """);
        JsonObject step = model.steps("r").get(0);
        IndustrialStepType breed = IndustrialStepType.byRawType("breed_entities");
        // NSUK 里 requireFood 默认 true（别名 requiresFood），radius 默认 6.0。
        assertTrue(BuildingJsonDocument.readFlag(step, breed.field("requireFood")));
        assertEquals(6.0D, BuildingJsonDocument.readNumber(step, breed.field("radius")), 1e-9);
        assertFalse(BuildingJsonDocument.hasField(step, breed.field("count")));

        BuildingJsonDocument.writeString(step, breed.field("entityType"), "minecraft:sheep");
        assertTrue(step.has("entityType"));
        BuildingJsonDocument.writeString(step, breed.field("entityType"), "");
        assertFalse(step.has("entityType"), "写空串应等于删除，避免留下空字符串垃圾");
    }

    @Test
    @DisplayName("type 查找接受别名且大小写不敏感")
    void typeLookupAcceptsAliases() {
        assertEquals(IndustrialStepType.CRAFT_RECIPE, IndustrialStepType.byRawType("CRAFT_RECIPE"));
        assertEquals(IndustrialStepType.CRAFT_AVAILABLE_RECIPE,
                IndustrialStepType.byRawType("craft_all_recipe"));
        assertEquals(IndustrialStepType.MOVE_TO_CONTAINER, IndustrialStepType.byRawType("move_to_chest"));
        assertEquals(IndustrialStepType.REPEAT, IndustrialStepType.byRawType("LOOP"));
        assertNull(IndustrialStepType.byRawType("nonsense"));
        assertNull(IndustrialStepType.byRawType(""));
    }

    // ── 展开步数（最容易踩的硬上限） ────────────────────────────────────

    @Test
    @DisplayName("repeat 按加载期语义展开，嵌套会相乘")
    void expandedStepCountFollowsLoaderSemantics() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");

        // 顶层：move_to，然后 repeat(count=3) { move_to, repeat(count=2){ look_at } }
        model.insertStep("r", null, 0, IndustrialStepType.MOVE_TO);
        BuildingJsonDocument.StepPath outer = model.insertStep("r", null, 1, IndustrialStepType.REPEAT);
        JsonObject outerRepeat = model.step("r", outer);
        assertNotNull(outerRepeat);
        outerRepeat.addProperty("count", 3);

        model.insertStep("r", outer, 0, IndustrialStepType.MOVE_TO);
        BuildingJsonDocument.StepPath inner = model.insertStep("r", outer, 1, IndustrialStepType.REPEAT);
        model.step("r", inner).addProperty("count", 2);
        model.insertStep("r", inner, 0, IndustrialStepType.LOOK_AT);

        // 1 + 3 * (1 + 2 * 1) = 10
        assertEquals(10, model.expandedStepCount("r"));

        // positions 优先于 count：3 个坐标 → 3 次重复。
        outerRepeat.remove("count");
        JsonArray positions = new JsonArray();
        for (int i = 0; i < 3; i++) {
            JsonArray point = new JsonArray();
            point.add(i);
            point.add(1);
            point.add(1);
            positions.add(point);
        }
        outerRepeat.add("positions", positions);
        assertEquals(10, model.expandedStepCount("r"));
    }

    @Test
    @DisplayName("展开后超过 256 步会被夹住并报错")
    void expandedStepCountIsCapped() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");
        BuildingJsonDocument.StepPath repeat = model.insertStep("r", null, 0, IndustrialStepType.REPEAT);
        model.step("r", repeat).addProperty("count", 300);
        model.insertStep("r", repeat, 0, IndustrialStepType.MOVE_TO);

        assertEquals(BuildingJsonDocument.MAX_STEPS, model.expandedStepCount("r"));
        assertTrue(codes(model).contains("steps_exceeded"));
    }

    // ── 校验 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("未知 type、未知点位/容器引用都会被报出来")
    void validateUnknownTypeAndReferences() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {
                  "points": { "stand": { "positions": [[0,0,0]] } },
                  "containers": { "output": { "positions": [[1,1,1]] } },
                  "recipes": [ { "id": "r", "steps": [
                    { "type": "not_a_real_step" },
                    { "type": "move_to", "point": "typo_point" },
                    { "type": "require_output_space", "container": "typo_container" }
                  ] } ]
                }
                """);
        List<DefinitionIssue> issues = model.validate(null);
        Set<String> codes = issues.stream().map(DefinitionIssue::code).collect(Collectors.toSet());
        assertTrue(codes.contains("unknown_type"), codes.toString());
        assertTrue(codes.contains("unknown_point"), codes.toString());
        assertTrue(codes.contains("unknown_container"), codes.toString());
        // 未知 type 是 ERROR：NSUK 会落在 switch 的 default，无限 WAITING_RETRY。
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("unknown_type")
                && issue.severity() == DefinitionIssue.Severity.ERROR));
    }

    @Test
    @DisplayName("repeat 缺 steps / 缺 count 分别对应加载器的两条错误")
    void validateRepeatRequirements() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"repeat","count":2},
                  {"type":"repeat","steps":[{"type":"move_to","point":"p"}]},
                  {"type":"repeat","count":0,"steps":[{"type":"move_to","point":"p"}]}
                ]}]}
                """);
        Set<String> codes = codes(model);
        assertTrue(codes.contains("missing_repeat_steps"), codes.toString());
        assertTrue(codes.contains("missing_repeat_count"), codes.toString());
    }

    @Test
    @DisplayName("数值越界、坐标过多、白名单外的键都会被报出来")
    void validateRangesAndWhitelist() {
        StringBuilder manyPositions = new StringBuilder();
        for (int i = 0; i < BuildingJsonDocument.MAX_POSITIONS + 1; i++) {
            if (i > 0) {
                manyPositions.append(',');
            }
            manyPositions.append("[0,0,0]");
        }
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"use_item","ticks":0},
                  {"type":"destroy_block","positions":[%s]},
                  {"type":"set_held_item","items":[{"item":"minecraft:shears"}]},
                  {"type":"harvest_block_clusters","maxBlocksPerTick":4}
                ]}]}
                """.formatted(manyPositions));
        List<DefinitionIssue> issues = model.validate(null);
        Set<String> codes = issues.stream().map(DefinitionIssue::code).collect(Collectors.toSet());
        assertTrue(codes.contains("out_of_range"), codes.toString());
        assertTrue(codes.contains("too_many_positions"), codes.toString());
        // items 是「物品规格列表」，set_held_item 只读单值规格 → 白填。
        assertTrue(codes.contains("unexpected_key"), codes.toString());
        // maxBlocksPerTick 被加载器解析却在全代码库没有运行时读取者：死键。
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("unexpected_key")
                && issue.detail().equals("maxBlocksPerTick")));
    }

    @Test
    @DisplayName("坐标越界只在知道结构尺寸时才检查")
    void validatePositionsAgainstStructureBounds() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"place_block","block":"minecraft:stone","positions":[[1,1,1],[99,1,1]]}
                ]}]}
                """);
        assertFalse(codes(model).contains("position_out_of_bounds"),
                "尺寸未知时不应报越界");
        assertTrue(codes(model, new BuildingJsonDocument.StructureBounds(8, 8, 8))
                .contains("position_out_of_bounds"));
    }

    @Test
    @DisplayName("空的 inputs/outputs 覆盖是陷阱，要给出警告")
    void validateEmptyOverrides() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","inputs":[],"outputs":[],"steps":[
                  {"type":"craft_recipe","input":"in","output":"out","inputs":[],"outputs":[]}
                ]}]}
                """);
        Set<String> codes = codes(model);
        assertTrue(codes.contains("empty_inputs_override"), codes.toString());
        assertTrue(codes.contains("empty_outputs_override"), codes.toString());
    }

    @Test
    @DisplayName("skipOnTimeout 在没写 timeoutTicks 时要提醒默认值长达 12000 tick")
    void validateSkipTimeoutDefaults() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[{"type":"move_to","point":"p","skipOnTimeout":true}]}]}
                """);
        assertTrue(codes(model).contains("skip_timeout_default"));
    }

    @Test
    @DisplayName("没有配方时直接报 missing_recipes")
    void validateMissingRecipes() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("{\"id\":\"x\"}");
        assertEquals(List.of("missing_recipes"), List.copyOf(codes(model)));
    }

    // ── 步骤增删改 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("插入 / 删除 / 排序 / 复制都落在正确的链上")
    void stepEditingKeepsStructureIntact() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");

        BuildingJsonDocument.StepPath a = model.insertStep("r", null, 0, IndustrialStepType.MOVE_TO);
        BuildingJsonDocument.StepPath b = model.insertStep("r", null, 1, IndustrialStepType.USE_ITEM);
        assertEquals(2, model.steps("r").size());
        assertEquals("move_to", model.step("r", a).get("type").getAsString());
        assertEquals("use_item", model.step("r", b).get("type").getAsString());

        // 在 a 后面插一个新步骤：原来的 b 被挤到下标 2。
        BuildingJsonDocument.StepPath inserted =
                model.insertStepAfter("r", a, IndustrialStepType.SET_HELD_ITEM);
        assertEquals("set_held_item", model.step("r", inserted).get("type").getAsString());
        assertEquals(3, model.steps("r").size());
        assertEquals("use_item", model.step("r", b.withLeaf(2)).get("type").getAsString());

        // 上移 / 下移。
        model.moveStep("r", inserted, -1);
        assertEquals("set_held_item", model.step("r", inserted.withLeaf(0)).get("type").getAsString());
        assertEquals("move_to", model.step("r", a.withLeaf(1)).get("type").getAsString());

        // 复制与删除。
        BuildingJsonDocument.StepPath copy = model.duplicateStep("r", inserted.withLeaf(0));
        assertEquals("set_held_item", model.step("r", copy).get("type").getAsString());
        assertEquals(4, model.steps("r").size());
        assertTrue(model.removeStep("r", copy));
        assertEquals(3, model.steps("r").size());
    }

    @Test
    @DisplayName("嵌套 repeat 里的步骤可以增删，且不影响外层")
    void nestedStepEditing() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");
        BuildingJsonDocument.StepPath repeat = model.insertStep("r", null, 0, IndustrialStepType.REPEAT);
        BuildingJsonDocument.StepPath child = model.insertStep("r", repeat, 0, IndustrialStepType.PLACE_FLUID);
        BuildingJsonDocument.StepPath grandChild =
                model.insertStep("r", repeat.down(0), 0, IndustrialStepType.LOOK_AT);

        assertEquals(1, model.steps("r").size());
        assertEquals(1, model.steps("r", repeat).size());
        assertEquals("look_at", model.step("r", grandChild).get("type").getAsString());
        assertEquals("place_fluid", model.step("r", child).get("type").getAsString());

        assertTrue(model.removeStep("r", grandChild));
        assertEquals(0, model.steps("r", child).size());
        // 外层与 repeat 本身都还在。
        assertEquals(1, model.steps("r").size());
        assertEquals(1, model.steps("r", repeat).size());
    }

    @Test
    @DisplayName("不允许把 repeat 放进自己的子树")
    void cannotMoveContainerIntoItself() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");
        BuildingJsonDocument.StepPath repeat = model.insertStep("r", null, 0, IndustrialStepType.REPEAT);
        BuildingJsonDocument.StepPath child = model.insertStep("r", repeat, 0, IndustrialStepType.REPEAT);

        assertFalse(model.canMoveInto(repeat, repeat));
        assertFalse(model.canMoveInto(repeat, child));
        assertTrue(model.canMoveInto(child, repeat));
        assertTrue(model.canMoveInto(repeat, null));
    }

    // ── 跨层拖动（拖拽排序的核心逻辑） ─────────────────────────────────

    @Test
    @DisplayName("moveStepTo：能把步骤拖进 repeat 内部，也能拖回顶层")
    void moveStepToCrossesNesting() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");
        // 顶层：[move_to, repeat(count=2)[use_item], place_block]
        BuildingJsonDocument.StepPath moveTo = model.insertStep("r", null, 0, IndustrialStepType.MOVE_TO);
        BuildingJsonDocument.StepPath repeat = model.insertStep("r", null, 1, IndustrialStepType.REPEAT);
        BuildingJsonDocument.StepPath useItem = model.insertStep("r", repeat, 0, IndustrialStepType.USE_ITEM);
        BuildingJsonDocument.StepPath place = model.insertStep("r", null, 2, IndustrialStepType.PLACE_BLOCK);
        assertEquals(3, model.steps("r").size());
        assertEquals(1, model.steps("r", repeat).size());

        // 把 place_block（顶层下标 2）拖进 repeat 内部的下标 1
        BuildingJsonDocument.StepPath movedIn =
                model.moveStepTo("r", place, repeat, 1);
        assertEquals(BuildingJsonDocument.StepPath.of(1, 1), movedIn);
        assertEquals(2, model.steps("r").size());
        assertEquals(2, model.steps("r", repeat).size());
        assertEquals("use_item", model.steps("r", repeat).get(0).get("type").getAsString());
        assertEquals("place_block", model.steps("r", repeat).get(1).get("type").getAsString());
        assertTrue(model.canMoveInto(place, repeat));

        // 再把 use_item（现在在 repeat 内部下标 0）拖回顶层下标 0
        BuildingJsonDocument.StepPath movedOut = model.moveStepTo("r", useItem, null, 0);
        assertEquals(BuildingJsonDocument.StepPath.of(0), movedOut);
        assertEquals(3, model.steps("r").size());
        assertEquals("use_item", model.steps("r").get(0).get("type").getAsString());
        assertEquals("move_to", model.steps("r").get(1).get("type").getAsString());
        assertEquals("repeat", model.steps("r").get(2).get("type").getAsString());
        // 路径是按下标定位的，顶层顺序变了以后 repeat 自己从 1 挪到了 2，要按新路径取。
        BuildingJsonDocument.StepPath repeatNow = BuildingJsonDocument.StepPath.of(2);
        assertEquals(1, model.steps("r", repeatNow).size());
        assertEquals("place_block",
                model.steps("r", repeatNow).get(0).get("type").getAsString());
    }

    @Test
    @DisplayName("moveStepTo：同一条链内往后移要修正下标（元素先被摘掉）")
    void moveStepWithinSameChainAdjustsIndex() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");
        model.insertStep("r", null, 0, IndustrialStepType.MOVE_TO);
        model.insertStep("r", null, 1, IndustrialStepType.USE_ITEM);
        model.insertStep("r", null, 2, IndustrialStepType.PLACE_BLOCK);

        // 把第 0 个拖到「第 2 个之前」= 意图上的下标 2 → 实际落在下标 1
        BuildingJsonDocument.StepPath moved =
                model.moveStepTo("r", BuildingJsonDocument.StepPath.of(0), null, 2);
        assertEquals(BuildingJsonDocument.StepPath.of(1), moved);
        assertEquals("use_item", model.steps("r").get(0).get("type").getAsString());
        assertEquals("move_to", model.steps("r").get(1).get("type").getAsString());
        assertEquals("place_block", model.steps("r").get(2).get("type").getAsString());

        // 往后拖到末尾
        BuildingJsonDocument.StepPath toEnd =
                model.moveStepTo("r", BuildingJsonDocument.StepPath.of(1), null, 3);
        assertEquals(BuildingJsonDocument.StepPath.of(2), toEnd);
        assertEquals("move_to", model.steps("r").get(2).get("type").getAsString());
    }

    @Test
    @DisplayName("moveStepTo：把自己拖进自己的子树会被拒绝，结构不受损")
    void moveStepToRefusesOwnSubtree() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");
        BuildingJsonDocument.StepPath repeat = model.insertStep("r", null, 0, IndustrialStepType.REPEAT);
        model.insertStep("r", repeat, 0, IndustrialStepType.USE_ITEM);

        // repeat 想拖进自己内部：返回原路径，且什么都没变
        BuildingJsonDocument.StepPath result =
                model.moveStepTo("r", repeat, repeat, 0);
        assertEquals(repeat, result);
        assertEquals(1, model.steps("r").size());
        assertEquals(1, model.steps("r", repeat).size());
        assertEquals("repeat", model.steps("r").get(0).get("type").getAsString());
    }

    @Test
    @DisplayName("sameChain：只比较父链，忽略末端下标")
    void sameChainComparesParentsOnly() {
        assertTrue(BuildingJsonDocument.sameChain(
                BuildingJsonDocument.StepPath.of(0), BuildingJsonDocument.StepPath.of(1)));
        assertTrue(BuildingJsonDocument.sameChain(
                BuildingJsonDocument.StepPath.of(0), null));
        assertFalse(BuildingJsonDocument.sameChain(
                BuildingJsonDocument.StepPath.of(1, 0), BuildingJsonDocument.StepPath.of(0)));
        assertTrue(BuildingJsonDocument.sameChain(
                BuildingJsonDocument.StepPath.of(1, 0), BuildingJsonDocument.StepPath.of(1, 2)));
    }

    // ── 配方管理 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("新增 / 复制 / 删除配方，id 冲突自动加序号")
    void recipeManagement() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        JsonObject first = model.addRecipe("craft");
        assertNotNull(first);
        assertEquals("craft", model.selectedRecipeId());

        model.addRecipe("craft");
        assertEquals("craft_2", model.selectedRecipeId());
        assertEquals(2, model.recipes().size());

        model.setRecipeProperty("craft", "name", "合成");
        assertEquals("合成", model.recipeDisplayName(model.recipe("craft")));

        JsonObject copy = model.duplicateRecipe("craft");
        assertNotNull(copy);
        assertEquals("craft_copy", model.selectedRecipeId());

        assertTrue(model.removeRecipe("craft_copy"));
        assertEquals(2, model.recipes().size());
        // 删掉当前配方后要自动落到剩下的第一个。
        assertEquals("craft", model.selectedRecipeId());
        assertFalse(model.removeRecipe("不存在"));
    }

    // ── 输入条件树（ALL / ANY 递归） ───────────────────────────────────

    @Test
    @DisplayName("嵌套 any 往返正确，切换逻辑不丢子节点")
    void nestedInputGroupsRoundTrip() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","inputs":[
                  { "item": "minecraft:wheat", "count": 1, "consume": true },
                  { "any": [ { "item": "minecraft:oak_log" }, { "item": "minecraft:birch_log" } ] }
                ],"steps":[]}]}
                """);
        BuildingJsonDocument.InputOwner owner = BuildingJsonDocument.InputOwner.ofRecipe("r");
        JsonArray inputs = model.inputArray(model.ownerObject(owner),
                BuildingJsonDocument.GROUP_ROOT, false);
        assertEquals(2, inputs.size());

        BuildingJsonDocument.InputPath groupPath =
                new BuildingJsonDocument.InputPath(List.of(new BuildingJsonDocument.InputRef(
                        BuildingJsonDocument.GROUP_ROOT, 1)));
        JsonObject group = model.inputNode(owner, groupPath);
        assertNotNull(group);
        assertTrue(BuildingJsonDocument.isGroup(group));
        assertTrue(BuildingJsonDocument.isAnyGroup(group));

        // ALL <-> ANY 切换后，子节点必须原样保留。
        assertTrue(model.setInputGroupAny(owner, groupPath, false));
        JsonObject flipped = model.inputNode(owner, groupPath);
        assertFalse(BuildingJsonDocument.isAnyGroup(flipped));
        assertEquals(2, flipped.getAsJsonArray(BuildingJsonDocument.GROUP_ALL).size());

        // 写回并重新解析，结构依然正确。
        BuildingJsonDocument reparsed = BuildingJsonDocument.parse(model.toJsonString());
        JsonObject reparsedGroup = reparsed.inputNode(owner, groupPath);
        assertFalse(BuildingJsonDocument.isAnyGroup(reparsedGroup));
        assertEquals("minecraft:oak_log", reparsedGroup
                .getAsJsonArray(BuildingJsonDocument.GROUP_ALL).get(0)
                .getAsJsonObject().get("item").getAsString());
    }

    @Test
    @DisplayName("读取时接受 all/any 的别名拼写，写入统一成 all/any")
    void inputGroupAliases() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","inputs":[
                  { "or": [ { "item": "minecraft:oak_log" } ] }
                ],"steps":[]}]}
                """);
        BuildingJsonDocument.InputOwner owner = BuildingJsonDocument.InputOwner.ofRecipe("r");
        BuildingJsonDocument.InputPath path = new BuildingJsonDocument.InputPath(
                List.of(new BuildingJsonDocument.InputRef(BuildingJsonDocument.GROUP_ROOT, 0)));
        JsonObject group = model.inputNode(owner, path);
        assertTrue(BuildingJsonDocument.isAnyGroup(group), "or 是 any 的别名");

        assertTrue(model.setInputGroupAny(owner, path, true));
        assertFalse(group.has("or"));
        assertTrue(group.has(BuildingJsonDocument.GROUP_ANY));
    }

    @Test
    @DisplayName("能在根层与分组内追加输入条件，也能删除")
    void addAndRemoveInputs() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");
        BuildingJsonDocument.InputOwner owner = BuildingJsonDocument.InputOwner.ofRecipe("r");

        BuildingJsonDocument.InputPath first = model.addInputItem(owner, null);
        assertNotNull(first);
        assertEquals(1, model.inputArray(model.ownerObject(owner),
                BuildingJsonDocument.GROUP_ROOT, false).size());

        BuildingJsonDocument.InputPath group = model.addInputGroup(owner, null, true);
        assertNotNull(group);
        BuildingJsonDocument.InputPath nested = model.addInputItem(owner, group);
        assertNotNull(nested);
        assertEquals(1, model.inputNode(owner, group)
                .getAsJsonArray(BuildingJsonDocument.GROUP_ANY).size());

        assertTrue(model.removeInput(owner, nested));
        assertTrue(model.removeInput(owner, group));
        assertEquals(1, model.inputArray(model.ownerObject(owner),
                BuildingJsonDocument.GROUP_ROOT, false).size());
        assertTrue(model.removeInput(owner, first));
        assertEquals(0, model.inputArray(model.ownerObject(owner),
                BuildingJsonDocument.GROUP_ROOT, false).size());
    }

    @Test
    @DisplayName("产出列表挂在配方或步骤上，互不干扰")
    void outputsAreScopedToOwner() {
        BuildingJsonDocument model = BuildingJsonDocument.createDefault("t", "T");
        model.addRecipe("r");
        BuildingJsonDocument.StepPath step = model.insertStep("r", null, 0, IndustrialStepType.CRAFT_RECIPE);

        BuildingJsonDocument.InputOwner recipeOwner = BuildingJsonDocument.InputOwner.ofRecipe("r");
        BuildingJsonDocument.InputOwner stepOwner =
                BuildingJsonDocument.InputOwner.ofStep("r", step);

        assertEquals(0, model.addOutput(recipeOwner));
        assertEquals(0, model.addOutput(stepOwner));
        assertEquals(1, model.outputArray(recipeOwner, false).size());
        assertEquals(1, model.outputArray(stepOwner, false).size());

        assertTrue(model.removeOutput(stepOwner, 0));
        assertEquals(0, model.outputArray(stepOwner, false).size());
        assertEquals(1, model.outputArray(recipeOwner, false).size(),
                "步骤级产出不该动到配方级产出");
    }

    // ── 展示辅助 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("积木短标签就地可读")
    void summariesAreInformative() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"require_output_space","container":"output"},
                  {"type":"use_item","ticks":40,"swing":true},
                  {"type":"repeat","count":3,"steps":[{"type":"move_to","point":"p"}]},
                  {"type":"repeat","positions":[[1,1,1],[2,2,2]],"steps":[{"type":"move_to","point":"p"}]}
                ]}]}
                """);
        List<JsonObject> steps = model.steps("r");
        assertEquals("output", BuildingJsonDocument.summary(steps.get(0)));
        assertEquals("40t", BuildingJsonDocument.summary(steps.get(1)));
        assertEquals("count ×3", BuildingJsonDocument.summary(steps.get(2)));
        assertEquals("positions ×2", BuildingJsonDocument.summary(steps.get(3)));
    }

    @Test
    @DisplayName("白名单覆盖全部调色板 type，且每个 type 都至少有一个字段")
    void paletteIsComplete() {
        List<IndustrialStepType> palette = IndustrialStepType.palette();
        assertEquals(IndustrialStepType.values().length, palette.size());
        for (IndustrialStepType type : palette) {
            assertFalse(type.fields().isEmpty(), type.canonical() + " 没有字段");
            // 每个 type 都应当有通用超时字段。
            assertNotNull(type.field("timeoutTicks"), type.canonical());
            assertNotNull(type.field("skipOnTimeout"), type.canonical());
            // 白名单里认自己的 type 键与规范名。
            assertTrue(type.acceptedKeys().contains("type"), type.canonical());
            assertEquals(type, IndustrialStepType.byRawType(type.canonical()));
            for (String alias : type.aliases()) {
                assertEquals(type, IndustrialStepType.byRawType(alias), type.canonical() + "/" + alias);
            }
        }
        // 调色板里没有重名的规范 type。
        assertEquals(IndustrialStepType.values().length,
                palette.stream().map(IndustrialStepType::canonical).distinct().count());
    }

    // ── 点位 / 容器定义的管理 ───────────────────────────────────────────

    /**
     * 带嵌套 repeat 的样本：顶层与嵌套层各有一处引用。
     *
     * <p>级联最容易漏的就是 repeat 内部那一层 —— 只改顶层的话，引用会静默失效
     * （NSUK 只会在运行时返回 null，不报错）。
     */
    private static String nestedReferences() {
        return """
                {
                  "points": { "站位": { "type": "structure_pos", "positions": [[1,0,1],[2,0,2]] } },
                  "containers": {
                    "站位": { "type": "structure_pos", "positions": [[1,0,1],[2,0,2]] },
                    "成品箱": { "type": "structure_pos", "positions": [[7,1,3]] }
                  },
                  "recipes": [
                    {
                      "id": "r1",
                      "steps": [
                        { "type": "move_to", "point": "站位" },
                        { "type": "repeat", "count": 2, "steps": [
                            { "type": "look_at", "point": "站位" },
                            { "type": "look_at_container", "container": "成品箱" }
                        ] }
                      ]
                    }
                  ]
                }
                """;
    }

    @Test
    @DisplayName("新建点位：写 type=structure_pos；单个坐标不写 select（那时它与 ordered 完全等价）")
    void defineSinglePositionPoint() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("{ \"recipes\": [] }");
        assertTrue(model.definePoint("工作点", BuildingJsonDocument.DefinitionKind.POINT,
                List.of(new int[]{2, 1, 3}), "nearest"));

        JsonObject entry = model.definitionEntry("工作点", BuildingJsonDocument.DefinitionKind.POINT);
        assertNotNull(entry);
        // type 漏写会让 NSUK 静默返回空表，所以必须由编辑器保证
        assertEquals("structure_pos", entry.get("type").getAsString());
        assertEquals(1, entry.getAsJsonArray("positions").size());
        assertFalse(entry.has("select"));
    }

    @Test
    @DisplayName("多个坐标才写 select；容器条目**永远**不写 select（ContainerDefinition 没这个字段）")
    void selectOnlyForMultiPositionPoints() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("{ \"recipes\": [] }");
        assertTrue(model.definePoint("多点", BuildingJsonDocument.DefinitionKind.POINT,
                List.of(new int[]{1, 0, 1}, new int[]{2, 0, 2}), "ordered"));
        assertEquals("ordered",
                model.definitionEntry("多点", BuildingJsonDocument.DefinitionKind.POINT)
                        .get("select").getAsString());

        assertTrue(model.definePoint("箱子", BuildingJsonDocument.DefinitionKind.CONTAINER,
                List.of(new int[]{1, 0, 1}, new int[]{2, 0, 2}), "ordered"));
        assertFalse(model.definitionEntry("箱子", BuildingJsonDocument.DefinitionKind.CONTAINER)
                .has("select"));
    }

    @Test
    @DisplayName("同名定义直接拒绝，绝不覆盖已有条目")
    void duplicateDefinitionNameRejected() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(nestedReferences());
        assertFalse(model.definePoint("站位", BuildingJsonDocument.DefinitionKind.POINT,
                List.of(new int[]{9, 9, 9}), null));
        assertEquals(2, model.definitionEntry("站位", BuildingJsonDocument.DefinitionKind.POINT)
                .getAsJsonArray("positions").size());
    }

    @Test
    @DisplayName("改名：两个 map 一起改，且**repeat 嵌套层**的引用也被更新")
    void renameCascadesIntoNestedChains() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(nestedReferences());
        assertEquals(2, model.renameDefinition("站位", "集合点"));

        assertNotNull(model.definitionEntry("集合点", BuildingJsonDocument.DefinitionKind.POINT));
        assertNotNull(model.definitionEntry("集合点", BuildingJsonDocument.DefinitionKind.CONTAINER));
        assertNull(model.definitionEntry("站位", BuildingJsonDocument.DefinitionKind.POINT));
        assertNull(model.definitionEntry("站位", BuildingJsonDocument.DefinitionKind.CONTAINER));

        assertEquals(2, model.findReferences("集合点").size());
        assertTrue(model.findReferences("站位").isEmpty());
        // 一处是顶层路径（长度 1），一处是 repeat 内部（长度 2）—— 证明级联走到底了
        assertEquals(Set.of(1, 2), model.findReferences("集合点").stream()
                .map(reference -> reference.path().indices().size())
                .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("引用定位报出的是**有效键**（走兜底链时写的是哪个键就报哪个）")
    void findReferencesReportsEffectiveKey() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                { "containers": { "c1": { "positions": [[1,1,1]] } },
                  "recipes": [ { "id": "r", "steps": [
                      { "type": "move_to_container", "output": "c1" } ] } ] }
                """);
        List<BuildingJsonDocument.Reference> references = model.findReferences("c1");
        assertEquals(1, references.size());
        assertEquals("output", references.get(0).fieldKey());
    }

    @Test
    @DisplayName("删除：仍被引用时拒绝，并报出引用它的位置；条目与引用都不动")
    void deleteBlockedWhileReferenced() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(nestedReferences());
        BuildingJsonDocument.DeleteResult result = model.deleteDefinition("成品箱", false);
        assertFalse(result.removed());
        assertEquals(1, result.references().size());
        assertNotNull(model.definitionEntry("成品箱", BuildingJsonDocument.DefinitionKind.CONTAINER));
        assertEquals(1, model.findReferences("成品箱").size());
    }

    @Test
    @DisplayName("删除（强制）：条目删除，且所有引用字段被**移除**成空（不是留一个空名字）")
    void forceDeleteClearsReferences() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(nestedReferences());
        BuildingJsonDocument.DeleteResult result = model.deleteDefinition("成品箱", true);
        assertTrue(result.removed());
        assertNull(model.definitionEntry("成品箱", BuildingJsonDocument.DefinitionKind.CONTAINER));
        assertTrue(model.findReferences("成品箱").isEmpty());
        assertFalse(model.toJsonString().contains("成品箱"));
    }

    @Test
    @DisplayName("容器补点位条目：深拷贝，改一份不影响另一份")
    void ensurePointEntryCopiesDeeply() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(nestedReferences());
        assertTrue(model.ensurePointEntry("成品箱"));
        assertNotNull(model.definitionEntry("成品箱", BuildingJsonDocument.DefinitionKind.POINT));
        // 已经有了就不再补
        assertFalse(model.ensurePointEntry("成品箱"));

        model.definitionEntry("成品箱", BuildingJsonDocument.DefinitionKind.POINT)
                .addProperty("marker", "x");
        assertFalse(model.definitionEntry("成品箱", BuildingJsonDocument.DefinitionKind.CONTAINER)
                .has("marker"));
    }

    @Test
    @DisplayName("改坐标：两个 map 一起改；容器那一份不带 select")
    void setPositionsUpdatesBothMaps() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(nestedReferences());
        assertTrue(model.setDefinitionPositions("站位",
                List.of(new int[]{5, 0, 5}, new int[]{6, 0, 6}), "ordered"));

        JsonObject point = model.definitionEntry("站位", BuildingJsonDocument.DefinitionKind.POINT);
        JsonObject container = model.definitionEntry("站位", BuildingJsonDocument.DefinitionKind.CONTAINER);
        assertEquals(2, point.getAsJsonArray("positions").size());
        assertEquals(2, container.getAsJsonArray("positions").size());
        assertEquals("ordered", point.get("select").getAsString());
        assertFalse(container.has("select"));
    }

    // ── 工具 ───────────────────────────────────────────────────────────

    private static Set<String> codes(BuildingJsonDocument model) {
        return codes(model, null);
    }

    private static Set<String> codes(BuildingJsonDocument model,
                                     BuildingJsonDocument.StructureBounds bounds) {
        return model.validate(bounds).stream()
                .map(DefinitionIssue::code)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
