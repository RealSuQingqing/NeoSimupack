package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonObject;
import com.rstech.simupack.creation.industrial.IndustrialStepType;
import com.rstech.simupack.creation.json.BuildingJsonDocument;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 一次「干跑」：按扁平化的动作列表逐条推演容器库存、耗时和市民走位。
 *
 * <p>与旧版 {@code IndustrialActionSimulator} 的差别，全部是为了对齐 NSUK 的真实语义
 * （依据：NSUK 2.2.0 {@code IndustrialWorkService} / {@code IndustrialInventoryService} /
 * {@code IndustrialDefinitionLoader} 的反编译）：
 *
 * <table border="1">
 *   <caption>关键语义对照</caption>
 *   <tr><th>点</th><th>NSUK 实际行为</th><th>旧版仿真器</th></tr>
 *   <tr><td>{@code require_inputs} 的材料来源</td>
 *       <td><b>配方级</b> {@code recipe.inputs}</td>
 *       <td>读步骤上的 {@code inputs}（该字段在类型定义里根本不存在）→ 永远判定通过</td></tr>
 *   <tr><td>{@code require_output_space}</td>
 *       <td>按 {@code baseAmount + randomRange} 检查输出容器空间</td>
 *       <td>直接返回「假设有空间」</td></tr>
 *   <tr><td>{@code craft_recipe} 概率与随机量</td>
 *       <td>{@code nextDouble() > probability} 跳过，再 {@code nextInt(randomRange)}</td>
 *       <td>函数签名收下参数却恒返回 1 / 0</td></tr>
 *   <tr><td>{@code craft_recipe} 失败时</td>
 *       <td>先计划、再消耗，任一步失败就<b>回滚</b>已消耗的材料</td>
 *       <td>边循环边扣，缺第二样时第一样已经被扣掉，状态从此错到底</td></tr>
 *   <tr><td>{@code insert_item} 的物品来源</td>
 *       <td>直接用步骤自带的 item 规格凭空放入容器</td>
 *       <td>「从 carried 里扣」，而 carried 只会被写成 0 → 永远失败</td></tr>
 *   <tr><td>{@code set_held_item}</td>
 *       <td>只改市民手上的<b>显示</b>物品</td>
 *       <td>往 carried 里塞一个数量 0 的条目</td></tr>
 *   <tr><td>受阻步骤</td>
 *       <td>一直重试；{@code skipOnTimeout} 且超时才跳过</td>
 *       <td>记一条 success=false 就继续往下走，界面上完全看不出来</td></tr>
 * </table>
 */
public final class SimulationRunner {

    /** 步骤在干跑里的结局。 */
    public enum Status {
        /** 完成。 */
        DONE,
        /** 材料/空间不足：NSUK 会一直重试，这里停下并说明原因。 */
        BLOCKED,
        /** 因为 {@code skipOnTimeout} 超时被跳过。 */
        SKIPPED_TIMEOUT,
        /** 需要世界状态（方块/实体/机器），干跑无法判定，按「假定成功」继续。 */
        NEEDS_WORLD,
        /** 步骤类型缺失或未知。 */
        INVALID,
        /** 前面的步骤卡住了，没轮到这一步。 */
        NOT_REACHED
    }

    /**
     * 一条步骤的推演结果。
     *
     * @param route    这一步里市民实际走过的途径点（含起点，带精确落点与移动方式）；不需要移动时为空
     * @param focus    这一步要强调的方块（点位 / 容器的定义坐标）
     * @param heldItem 这一步结束后市民手上的物品 id；空串表示配方默认
     * @param gesture  这一步该播什么动画（挥手 / 开箱 / 作业……），由步骤类型决定
     */
    public record StepOutcome(int index, BuildingJsonDocument.StepPath sourcePath, String typeId, String labelKey,
                              int startTick, int ticks, Status status, String message,
                              List<SimWaypoint> route, Set<SimPos> focus, String heldItem, SimGesture gesture) {

        public int endTick() {
            return startTick + ticks;
        }

        public boolean assumed() {
            return status == Status.NEEDS_WORLD;
        }
    }

    /** 把步骤映射到「这件事发生在结构里的哪些格子」。由客户端按点位/容器定义实现。 */
    @FunctionalInterface
    public interface Focus {
        List<SimPos> positionsOf(SimStepSpec step);

        Focus NONE = step -> List.of();

        /**
         * 这个容器名在建筑里是否真的定义了。
         *
         * <p>NSUK 的 {@code resolveContainerPositions} 找不到容器就返回空表，步骤随即
         * 进入 WAITING_RETRY —— 也就是说「写错容器名」在游戏里就是永远卡住，
         * 而不是「忽略这个字段」。预览必须复现这一点，否则作者会以为配置没问题。
         */
        default boolean knowsContainer(String name) {
            return true;
        }

        /**
         * 现在的场景里有多少只这种实体。
         *
         * <p>真实实现（NSUK {@code IndustrialEntityActionService}）是在半径内**找实体**，
         * 找不到就重试 —— 所以「没刷怪却要剪羊毛」在游戏里是卡住，不是成功。
         * 仿真必须复现这一点，否则作者会以为配置没问题。
         *
         * @return {@code -1} = 不知道（不做这项检查，例如没有客户端场景的单测）
         */
        default int entityCount(String entityType) {
            return -1;
        }
    }

    /**
     * 场景里现存的实体账本：开局问一次，之后随屠宰递减、随繁殖递增。
     *
     * <p>为什么需要它：一步「宰 3 只牛」成功之后，下一步「再宰 3 只」在真实世界里
     * 会因为牛不够而卡住 —— 只看初始数量是复现不出来的。
     */
    private static final class EntityPool {
        private final Focus focus;
        private final Map<String, Integer> consumed = new LinkedHashMap<>();

        EntityPool(Focus focus) {
            this.focus = focus;
        }

        /** 还能动几只；{@code -1} = 不知道（不检查）。 */
        int remaining(String entityType) {
            int total = this.focus.entityCount(entityType);
            if (total < 0) {
                return -1;
            }
            return Math.max(0, total - this.consumed.getOrDefault(normalizeType(entityType), 0));
        }

        /** 把这一步产生的动物改动记进账本。 */
        void record(List<EntityEdit> edits) {
            if (edits == null) {
                return;
            }
            for (EntityEdit edit : edits) {
                String type = normalizeType(edit.entityType());
                if (edit.kind() == EntityEdit.Kind.KILL) {
                    this.consumed.merge(type, 1, Integer::sum);
                } else if (edit.kind() == EntityEdit.Kind.SHEAR) {
                    // **剪过毛的就不算「能剪的」了**：NSUK 的 shear 只挑
                    // `!isBaby && isShearable(...)`（IndustrialEntityActionService:109-112），
                    // 而剪过之后 isShearable 为假。以前唯一没记的就是这一种，
                    // 于是「剪了 2 只再剪 2 只」在账本上仍然成功（画面上羊已经秃了）。
                    this.consumed.merge(type, 1, Integer::sum);
                } else if (edit.kind() == EntityEdit.Kind.SPAWN) {
                    // 繁殖出来的幼崽也能被后面的步骤用上。
                    this.consumed.merge(type, -1, Integer::sum);
                }
            }
        }

        private static String normalizeType(String entityType) {
            if (entityType == null || entityType.isBlank()) {
                return "";
            }
            String value = entityType.trim();
            return value.indexOf(':') < 0 ? "minecraft:" + value : value;
        }
    }

    /**
     * 某一步对预览世界做的一次方块改动。
     *
     * <p>内核不碰 Minecraft 类型，所以只描述「哪个坐标应该变成哪个方块 id」，
     * 由客户端把它应用到预览世界上 —— 这样「放方块 / 等待方块」在仿真里是**看得见**的，
     * 而不是只标注一句「按假定成功继续」。
     *
     * @param blockId 方块 id（例如 {@code minecraft:stone}）。流体步骤放的是对应的流体方块
     *                （原版里水/岩浆本身就是方块）
     * @param replace 是否允许替换已有方块（对应步骤的 {@code replace} 字段）
     */
    public record BlockEdit(int x, int y, int z, String blockId, boolean replace) {
        public SimPos pos() {
            return new SimPos(x, y, z);
        }
    }

    /**
     * 某一步对**刷出来的动物**做的一次改动。
     *
     * <p>没有它的话，「宰了 3 只羊」之后羊还全站在那儿 —— 库存算对了，画面没对上。
     *
     * @param kind {@link Kind#SHEAR} 剪毛（羊变秃）、{@link Kind#KILL} 屠宰（消失）、
     *             {@link Kind#SPAWN} 繁殖（多一只幼崽）
     */
    public record EntityEdit(String entityType, Kind kind) {
        public enum Kind {
            SHEAR,
            KILL,
            SPAWN
        }
    }

    public record Result(List<StepOutcome> outcomes, SimInventory inventory, String heldItem,
                         int totalTicks, int completedSteps, int blockedAt, Set<String> containersUsed,
                         List<String> warnings, Map<String, Map<String, Integer>> initialInventory,
                         List<Map<String, Map<String, Integer>>> inventoryTimeline,
        List<List<SimBackpack.Slot>> backpackTimeline,
                         Map<Integer, List<BlockEdit>> blockEdits,
                         Map<Integer, List<EntityEdit>> entityEdits) {

        /**
         * 第 {@code index} 步**结束后**各容器的物品数量；{@code index = -1} 是初始库存。
         *
         * <p>界面靠它实现「悬浮容器实时看库存」：播放到哪一步，就取哪一步的快照。
         * 越界时夹到两端，所以播放到一半时查询未来的步骤不会炸。
         */
        public Map<String, Map<String, Integer>> inventoryAt(int index) {
            if (inventoryTimeline.isEmpty()) {
                return Map.of();
            }
            int slot = Math.max(0, Math.min(inventoryTimeline.size() - 1, index + 1));
            return inventoryTimeline.get(slot);
        }

        /**
         * 工人**背包**在这一步之后的样子（逐格）。用来在悬停市民时把背包画出来。
         *
         * <p>下标规则和库存时间轴一样：第 0 项是出发前（空背包），之后每跑完一步追加一项。
         */
        public List<SimBackpack.Slot> backpackAt(int index) {
            if (backpackTimeline.isEmpty()) {
                return List.of();
            }
            int slot = Math.max(0, Math.min(backpackTimeline.size() - 1, index + 1));
            return backpackTimeline.get(slot);
        }

        public boolean completed() {
            return blockedAt < 0 && !outcomes.isEmpty();
        }

        public StepOutcome outcome(int index) {
            return index >= 0 && index < outcomes.size() ? outcomes.get(index) : null;
        }
    }

    private SimulationRunner() {
    }

    public static Result run(JsonObject recipe, List<SimulationPlan.Entry> plan, Focus focus,
                             SimInventory initial, SimPos start, SimNavigation navigation, long seed) {
        return run(recipe, plan, focus, initial, start, navigation, seed, SimEnvironment.NONE);
    }

    /**
     * 跑一轮仿真，并指定**产出源**（动物掉落 / 剪毛 / 繁殖食物 / 方块破坏掉落）。
     *
     * <p>客户端会传一个走真实战利品表的实现（{@code IntegratedServerLootProvider}）。
     */
    public static Result run(JsonObject recipe, List<SimulationPlan.Entry> plan, Focus focus,
                             SimInventory initial, SimPos start, SimNavigation navigation, long seed,
                             SimLootProvider loot) {
        return run(recipe, plan, focus, initial, start, navigation, seed, SimEnvironment.of(loot));
    }

    /**
     * 跑一轮仿真，并指定完整的**客户端世界**（产出源 + 方块查询 + 作业范围）。
     *
     * <p>成簇收获要按坐标查方块、还要知道作业范围，所以它需要的不止产出源。
     * 三样东西打成 {@link SimEnvironment} 一包，免得参数越加越长。
     */
    public static Result run(JsonObject recipe, List<SimulationPlan.Entry> plan, Focus focus,
                             SimInventory initial, SimPos start, SimNavigation navigation, long seed,
                             SimEnvironment environment) {
        SimEnvironment env = environment == null ? SimEnvironment.NONE : environment;
        SimLootProvider lootProvider = env.loot();
        SimInventory inventory = initial == null ? new SimInventory() : initial.copy();
        Focus resolvedFocus = focus == null ? Focus.NONE : focus;
        SimNavigation nav = navigation == null ? SimNavigation.direct() : navigation;
        Random random = new Random(seed);
        // 工人随身背包：跨步骤存活（成簇收获的产物先进这里，deposit_carried_items 再搬进容器）。
        SimBackpack backpack = new SimBackpack();
        SimGroundItems ground = new SimGroundItems();
        // 场景里的实体账本：剪毛 / 屠宰要按「还剩几只」判成败（没刷怪就不该成功）。
        SimBlocks blocks = new SimBlocks();
        EntityPool entities = new EntityPool(resolvedFocus);

        List<StepOutcome> outcomes = new ArrayList<>(plan.size());
        List<String> warnings = new ArrayList<>();
        Set<String> containersUsed = new LinkedHashSet<>();
        // 步骤下标 → 这一步对世界做的方块改动。客户端按播放进度累积应用，就能看见方块出现。
        Map<Integer, List<BlockEdit>> blockEdits = new LinkedHashMap<>();
        // 步骤下标 → 这一步对刷出来的动物做的改动（剪毛 / 屠宰 / 繁殖）。
        Map<Integer, List<EntityEdit>> entityEdits = new LinkedHashMap<>();
        // 库存时间轴：第 0 项是初始库存，之后每跑完一步追加一项，
        // 所以「下标 = 步骤下标 + 1」永远成立，界面查表不需要判断状态。
        List<Map<String, Map<String, Integer>>> inventoryTimeline = new ArrayList<>(plan.size() + 1);
        Map<String, Map<String, Integer>> initialInventory = inventory.snapshot();
        inventoryTimeline.add(initialInventory);
        // 背包时间轴：出发前是空的，之后每步末快照一次（悬停市民时按当前时刻画背包）。
        List<List<SimBackpack.Slot>> backpackTimeline = new ArrayList<>(plan.size() + 1);
        backpackTimeline.add(List.of());

        String heldItem = SimJson.string(recipe, "heldItem", "");
        SimPos citizen = start;
        int tick = 0;
        int blockedAt = -1;

        for (int index = 0; index < plan.size(); index++) {
            SimulationPlan.Entry entry = plan.get(index);
            SimStepSpec step = entry.step();
            int startTick = tick;

            StepExecution execution = execute(recipe, step, inventory, random, citizen, nav, resolvedFocus, heldItem, env, backpack, ground, entities, blocks);
            heldItem = execution.heldItem;
            citizen = execution.citizen;
            // 记下这一步用掉/生出来的动物，供后面的步骤判「还剩几只」。
            entities.record(execution.entityEdits);
            tick += execution.ticks;

            if (execution.status == Status.BLOCKED && step.skipOnTimeout() && step.timeoutTicks() > 0) {
                // NSUK：skipOnTimeout 的步骤在超时后会被跳过，耗时按 timeoutTicks 计。
                tick = startTick + step.timeoutTicks();
                outcomes.add(new StepOutcome(index, entry.sourcePath(), step.typeId(), step.typeLabelKey(),
                        startTick, step.timeoutTicks(), Status.SKIPPED_TIMEOUT,
                        execution.message + "（已配置超时跳过，按 " + step.timeoutTicks() + " tick 计）",
                        execution.route, execution.focus, execution.heldItem, execution.gesture));
                inventoryTimeline.add(inventory.snapshot());
            backpackTimeline.add(backpack.slots());
                continue;
            }

            outcomes.add(new StepOutcome(index, entry.sourcePath(), step.typeId(), step.typeLabelKey(),
                    startTick, execution.ticks, execution.status, execution.message,
                    execution.route, execution.focus, execution.heldItem, execution.gesture));
            containersUsed.addAll(execution.containersUsed);
            inventoryTimeline.add(inventory.snapshot());
            backpackTimeline.add(backpack.slots());
            if (!execution.edits.isEmpty()) {
                blockEdits.put(index, execution.edits);
            }
            if (!execution.entityEdits.isEmpty()) {
                entityEdits.put(index, execution.entityEdits);
            }

            if (execution.status == Status.BLOCKED) {
                blockedAt = index;
                warnings.add("第 " + (index + 1) + " 步受阻：" + execution.message);
                // 受阻**就停在这里**，后面不再生成步骤记录。
                // 之前会为剩下的每一步各补一条 NOT_REACHED，界面上就是十几行「前一步受阻，未执行」——
                // 那既不是「还在跑」，也不提供任何新信息。
                break;
            }
            if (execution.status == Status.NEEDS_WORLD) {
                warnings.add("第 " + (index + 1) + " 步需要世界状态，已按假定成功继续：" + execution.message);
            } else if (execution.status == Status.INVALID) {
                warnings.add("第 " + (index + 1) + " 步无效：" + execution.message);
            }
        }

        int completed = 0;
        for (StepOutcome outcome : outcomes) {
            if (outcome.status() == Status.DONE || outcome.status() == Status.NEEDS_WORLD) {
                completed++;
            }
        }
        if (plan.size() >= SimulationPlan.MAX_STEPS) {
            warnings.add("动作数量达到上限 " + SimulationPlan.MAX_STEPS + "，超出的部分没有展开");
        }
        return new Result(List.copyOf(outcomes), inventory, heldItem, tick, completed, blockedAt,
                Set.copyOf(containersUsed), List.copyOf(warnings), initialInventory,
                List.copyOf(inventoryTimeline), List.copyOf(backpackTimeline),
                Map.copyOf(blockEdits), Map.copyOf(entityEdits));
    }

    // ── 单步执行 ───────────────────────────────────────────────────────

    private static final class StepExecution {
        StepExecution() {
        }

        Status status = Status.DONE;
        String message = "";
        int ticks = 1;
        String heldItem = "";
        SimPos citizen;
        SimGesture gesture = SimGesture.IDLE;
        List<SimWaypoint> route = List.of();
        Set<SimPos> focus = Set.of();
        Set<String> containersUsed = Set.of();
        /** 这一步对预览世界做的方块改动；空表示不动世界。 */
        List<BlockEdit> edits = List.of();
        /** 这一步对刷出来的动物做的改动；空表示不动动物。 */
        List<EntityEdit> entityEdits = List.of();

        StepExecution done(String message, int ticks) {
            this.status = Status.DONE;
            this.message = message;
            this.ticks = Math.max(1, ticks);
            return this;
        }

        StepExecution blocked(String message, int ticks) {
            this.status = Status.BLOCKED;
            this.message = message;
            this.ticks = Math.max(1, ticks);
            return this;
        }
    }

    private static StepExecution execute(JsonObject recipe, SimStepSpec step, SimInventory inventory, Random random,
                                         SimPos citizen, SimNavigation navigation, Focus focus,
                                         String heldItem, SimEnvironment env, SimBackpack backpack, SimGroundItems ground,
                                         EntityPool entities, SimBlocks blocks) {
        // 产出源从环境里取出来，下面原有的 loot.xxx 调用点就不用改。
        SimLootProvider loot = env.loot();
        StepExecution result = new StepExecution();
        result.citizen = citizen;
        result.heldItem = heldItem;
        result.focus = new LinkedHashSet<>(focus.positionsOf(step));
        result.ticks = step.actionTicks();

        IndustrialStepType type = step.type();
        if (type == null) {
            result.status = Status.INVALID;
            result.message = step.rawType().isBlank() ? "缺少 type 字段" : "未知的动作类型 " + step.rawType();
            result.ticks = 1;
            return result;
        }
        // changed 要看「这一步到底改没改世界」，所以放到执行完之后再定（见下方 setGesture）。
        result.gesture = gestureFor(step, false);

        switch (type) {
            case REPEAT -> {
                // 正常情况下 repeat 已经在 SimulationPlan 里被展开，走不到这里。
                result.status = Status.INVALID;
                result.message = "repeat 没有被展开（缺少 steps 或 count 为 0）";
                result.ticks = 1;
            }
            case SET_STATUS -> {
                // NSUK 显示的是 statusText（IndustrialWorkService:337-340），statusKey 只是 i18n 键。
                String statusText = step.statusText();
                result.done("状态设为 " + (statusText.isBlank()
                        ? (step.statusKey().isBlank() ? "work" : step.statusKey()) : statusText), 1);
            }
            case SET_HELD_ITEM -> {
                SimItemSpec spec = step.itemSpec().isEmpty()
                        ? new SimItemSpec(heldItem, "", "", "", "")
                        : step.itemSpec();
                result.heldItem = spec.display();
                result.done("手持 " + (result.heldItem.isBlank() ? "（配方默认）" : result.heldItem), 1);
            }
            case INSPECT_CONTAINER, LOOK_AT_CONTAINER -> {
                // NSUK：这两个步骤都要**解析容器**，解析不到就是 missing_container
                // （IndustrialWorkService:694-704、:740-745）—— 以前我们直接 done()，
                // 于是「容器名写错」在仿真里看不出来。
                String name = step.container("container", "output", SimInventory.DEFAULT_OUTPUT);
                if (!requireContainer(focus, name, result)) {
                    return result;
                }
                result.containersUsed = Set.of(name);
                type = null;
                result.done("查看容器 " + name, step.actionTicks());
            }
            case LOOK_AT -> {
                // NSUK：point 解析不到就是 missing_point（:684-691）。客户端已经把 point
                // 解析进 focus（分类型解析），这里只判「到底有没有目标」。
                if (result.focus.isEmpty() && step.positions().isEmpty() && step.point().isBlank()) {
                    result.blocked("没有可用的目标点位（point 未定义）", step.actionTicks());
                    return result;
                }
                result.done("", step.actionTicks());
            }
            case USE_ITEM -> result.done("", step.actionTicks());
            case FILL_ITEM -> {
                result.status = Status.NEEDS_WORLD;
                result.message = "从容器向目标点位补货，需要真实容器方块实体";
                result.ticks = step.actionTicks();
                return result;
            }
            case MOVE_TO, MOVE_TO_ENTITY -> executeMove(step, citizen, navigation, result);
            case MOVE_TO_CONTAINER -> {
                String container = step.targetContainer(SimInventory.DEFAULT_INPUT);
                if (requireContainer(focus, container, result)) {
                    executeMove(step, citizen, navigation, result);
                }
            }
            case REQUIRE_INPUTS -> {
                String container = step.container("container", "input", SimInventory.DEFAULT_INPUT);
                if (requireContainer(focus, container, result)) {
                    executeRequireInputs(recipe, container, inventory, result);
                }
            }
            case REQUIRE_OUTPUT_SPACE -> {
                String container = step.container("container", "output", SimInventory.DEFAULT_OUTPUT);
                if (requireContainer(focus, container, result)) {
                    executeRequireOutputSpace(recipe, container, inventory, result);
                }
            }
            case CRAFT_RECIPE, CRAFT_AVAILABLE_RECIPE -> {
                String inputContainer = step.container("input", "container", SimInventory.DEFAULT_INPUT);
                String outputContainer = step.container("output", "container", SimInventory.DEFAULT_OUTPUT);
                if (requireContainer(focus, inputContainer, result)
                        && requireContainer(focus, outputContainer, result)) {
                    executeCraft(recipe, step, inventory, random,
                            type == IndustrialStepType.CRAFT_AVAILABLE_RECIPE,
                            inputContainer, outputContainer, result);
                }
            }
            case INSERT_ITEM -> {
                String container = step.container("container", "output", SimInventory.DEFAULT_OUTPUT);
                if (requireContainer(focus, container, result)) {
                    executeInsert(step, container, inventory, result);
                }
            }
            case DEPOSIT_CARRIED_ITEMS -> executeDepositCarried(step, focus, inventory, backpack, result);
            case PLACE_BLOCK, PLACE_FLUID -> executePlaceBlock(step, focus, inventory, blocks, result);
            case REQUIRE_BLOCK -> executeRequireBlock(step, result);
            case SHEAR_ENTITIES -> executeShear(step, random, loot, entities, ground, result);
            case SLAUGHTER_ENTITIES -> executeSlaughter(step, random, loot, entities, ground, result);
            case BREED_ENTITIES -> executeBreed(step, focus, inventory, loot, entities, result);
            case COLLECT_DROPS -> executeCollectDrops(step, backpack, ground, result);
            case REQUIRE_DROPS -> executeRequireDrops(step, ground, result);
            case DESTROY_BLOCK -> executeDestroyBlock(step, random, loot, heldItem, ground, blocks, result);
            case REAL_MACHINE_RECIPE -> executeRealMachine(recipe, step, focus, inventory, random, result);
            case HARVEST_BLOCK_CLUSTERS -> executeHarvestClusters(step, focus, inventory, random, env, backpack, ground,
                    heldItem, result);
            default -> {
                result.status = Status.NEEDS_WORLD;
                result.message = "尚未建模的动作";
                result.ticks = step.actionTicks();
            }
        }
        return result;
    }

    private static String typeId(IndustrialStepType type) {
        return type != null ? type.canonical() : "";
    }

    /** 等待方块固定等 1 秒（20 tick）—— 等不到别的机器来放，仿真里就自己让它出现。 */
    private static final int REQUIRE_BLOCK_WAIT_TICKS = 20;

    // ── 实体类动作 ─────────────────────────────────────────────────────
    //
    // 这些步骤以前全落在 NEEDS_WORLD 组（只标注「需要世界方块 / 实体状态」），
    // 于是仿真里看不出任何效果。动物的产出是稳定的常识（见 SimAnimalProducts），
    // 所以能算的部分就算出来、真的放进容器；认不出的实体类型才退回 NEEDS_WORLD。

    /**
     * 场景里还有没有足够的实体。
     *
     * <p>真实实现是「在半径内找实体，找不到就重试」—— 所以「没刷怪却要剪羊毛」
     * 在游戏里是卡住，不是成功。仿真必须复现，否则作者会以为配置没问题。
     *
     * @return false 表示数量不够（已经写好 blocked 结果）；{@code entityCount} 回 -1（不知道）时一律放行
     */
    private static boolean enoughEntities(EntityPool entities, String entityType, int needed,
                                          SimStepSpec step, StepExecution result) {
        int available = entities.remaining(entityType);
        if (available < 0 || available >= needed) {
            return true;
        }
        result.blocked("只找到 " + available + " 只 " + shortName(entityType) + "，这一步要 " + needed
                + " 只（检查建筑的「刷怪」设置，或者前面是不是已经用掉了）", step.actionTicks());
        return false;
    }

    /** 剪毛：每只动物按表产出，**产物落在地上**（不是进容器）。 */
    private static void executeShear(SimStepSpec step, Random random,
                                     SimLootProvider loot, EntityPool entities, SimGroundItems ground,
                                     StepExecution result) {
        String type = step.entityType().isBlank() ? "minecraft:sheep" : step.entityType();
        int animals = Math.max(1, step.count());
        if (!enoughEntities(entities, type, animals, step, result)) {
            return;
        }
        List<SimLootProvider.Product> products = loot.shear(type, animals, random);
        if (products.isEmpty()) {
            result.status = Status.NEEDS_WORLD;
            result.message = "认不出 " + type + " 的剪毛产物，需要真实世界";
            result.ticks = step.actionTicks();
            return;
        }
        // 产物**掉在地上**（NSUK: IShearable.onSheared → Block.popResource，IndustrialEntityActionService:124-129），
        // 不是直接进容器 —— 由 collect_drops 捡进工人背包、deposit_carried_items 才入箱。
        products.forEach(product -> ground.drop(product.item(), product.count()));
        result.entityEdits = entityEdits(type, EntityEdit.Kind.SHEAR, animals);
        result.done("剪了 " + animals + " 只 " + shortName(type) + "，掉落在地上 "
                + describeProducts(products), step.actionTicks());
    }

    /** 屠宰：每只动物按表掉落，**产物落在地上**（不是进容器）。 */
    private static void executeSlaughter(SimStepSpec step, Random random,
                                         SimLootProvider loot, EntityPool entities, SimGroundItems ground,
                                         StepExecution result) {
        String type = step.entityType();
        int animals = Math.max(1, step.count());
        if (!enoughEntities(entities, type, animals, step, result)) {
            return;
        }
        List<SimLootProvider.Product> products = loot.drops(type, animals, random);
        if (products == null) {
            result.status = Status.NEEDS_WORLD;
            result.message = "拿不到掉落表（" + (type.isBlank() ? "未写 entityType" : shortName(type))
                    + "），掉什么需要真实世界";
            result.ticks = step.actionTicks();
            return;
        }
        if (products.isEmpty()) {
            // **真的不掉东西**（和「算不出来」是两回事，以前被混成一个分支）。
            result.entityEdits = entityEdits(type, EntityEdit.Kind.KILL, animals);
            result.done("宰了 " + animals + " 只 " + shortName(type) + "（没有掉落）", step.actionTicks());
            return;
        }
        // 同上：动物自然掉落 → 落在地上（NSUK: IndustrialEntityActionService:95-98）。
        products.forEach(product -> ground.drop(product.item(), product.count()));
        result.entityEdits = entityEdits(type, EntityEdit.Kind.KILL, animals);
        result.done("宰了 " + animals + " 只 " + shortName(type) + "，掉落在地上 "
                + describeProducts(products), step.actionTicks());
    }

    /**
     * 繁殖：按 {@code requireFood} 决定要不要消耗食物，然后产出「多一只」。
     *
     * <p>{@code requireFood} 这个字段 schema 里一直有，但内核以前一处都没读 ——
     * 「繁殖要喂东西」在仿真里完全没体现。现在真的从容器里扣一份食物。
     */
    /**
     * 繁殖。**{@code count} 是「对数」不是「只数」**，而且一对要**两只**可交配的成年同类。
     *
     * <p>照 NSUK {@code IndustrialEntityActionService.breed}：
     * {@code pairs = max(1, step.count())}，每对 {@code consumeBreedFood(..., first, 2)}（两份食物）
     * 且 {@code spawnChildFromBreeding} 一次（**一次生两只**：原版繁殖是双胞胎），
     * 前置要求候选里至少有两只非幼年、能恋爱的同类，否则 {@code MISSING_ENTITIES}。
     * 早先我们按「只数」算，食物和幼崽都只有一半。
     */
    private static void executeBreed(SimStepSpec step, Focus focus, SimInventory inventory, SimLootProvider loot,
                                     EntityPool entities, StepExecution result) {
        String type = step.entityType();
        List<String> foods = loot.food(type);
        int pairs = Math.max(1, step.count());
        // 一对要两只（NSUK 先查 candidates.size() < 2 直接 MISSING_ENTITIES），
        // 再查「每对两只」是否够。
        if (!enoughEntities(entities, type, 2, step, result)) {
            return;
        }
        if (!enoughEntities(entities, type, 2 * pairs, step, result)) {
            return;
        }
        String container = step.container("container", "input", SimInventory.DEFAULT_INPUT);
        if (!requireContainer(focus, container, result)) {
            return;
        }
        result.containersUsed = Set.of(container);
        if (!step.requireFood()) {
            result.done("繁殖 " + shortName(type) + " " + pairs + " 对（未要求喂食）", step.actionTicks());
            return;
        }
        if (foods.isEmpty()) {
            result.status = Status.NEEDS_WORLD;
            result.message = "认不出 " + (type.isBlank() ? "未写 entityType" : type) + " 吃什么，需要真实世界";
            result.ticks = step.actionTicks();
            return;
        }
        // 一对消耗**两份**食物（NSUK 是 consumeBreedFood(..., first, 2)）；容器里没有它认的任何一种就受阻。
        int foodNeeded = 2 * pairs;
        for (String food : foods) {
            if (inventory.take(container, food, foodNeeded)) {
                result.entityEdits = entityEdits(type, EntityEdit.Kind.SPAWN, 2 * pairs);
                result.done("繁殖 " + shortName(type) + " " + pairs + " 对（得 " + (2 * pairs)
                        + " 只幼崽），消耗 " + shortName(food) + " x" + foodNeeded, step.actionTicks());
                return;
            }
        }
        result.blocked("容器 " + container + " 里没有可喂的食物（认 " + describeFoods(foods) + "）",
                step.actionTicks());
    }

    /** 造 {@code count} 条同类改动（剪毛 / 屠宰 / 繁殖都是「一只一条」）。 */
    private static List<EntityEdit> entityEdits(String entityType, EntityEdit.Kind kind, int count) {
        int times = Math.max(1, count);
        List<EntityEdit> edits = new ArrayList<>(times);
        for (int index = 0; index < times; index++) {
            edits.add(new EntityEdit(entityType, kind));
        }
        return List.copyOf(edits);
    }

    /**
     * 收集掉落物：把**地上**匹配的物品捡进**工人背包**。
     *
     * <p>照 NSUK {@code IndustrialEntityActionService.collectReachableDrops}（`:146-173`）：
     * 捡的是地上的 ItemEntity、进的是工人背包（容量按 `maxCarryStacks`），
     * 容器要等后面的 `deposit_carried_items` 才拿到东西。
     *
     * <p>地上没有匹配的掉落物时是 `MISSING_DROPS`（`IndustrialWorkService:640-644` 记一条状态继续），
     * 不是「成功捡到」，也不是「容器满了」。
     */
    private static void executeCollectDrops(SimStepSpec step, SimBackpack backpack, SimGroundItems ground,
                                            StepExecution result) {
        SimItemSpec spec = step.itemSpec();
        // **`item` 留空 = 不按物品筛选**（NSUK 的 matchingDrops 同样是「空则全匹配」）。
        // 官方农牧配方基本都不写 item —— 以前这里直接判「需要世界」跳过，
        // 于是掉落物永远留在地上、背包永远空、输出箱也永远空。
        if (spec.isEmpty()) {
            if (ground.isEmpty()) {
                result.blocked("地上没有任何掉落物（NSUK 判 MISSING_DROPS 并重试）", step.actionTicks());
                return;
            }
            // 0 = 不限量（NSUK 的数量是「每一批」的个数）。
            int perKind = step.count() > 0 ? step.count() : Integer.MAX_VALUE;
            int picked = 0;
            for (Map.Entry<String, Integer> entry : ground.contents().entrySet()) {
                int take = (int) Math.min((long) perKind, entry.getValue());
                if (take <= 0) {
                    continue;
                }
                int leftover = backpack.add(entry.getKey(), take);
                int accepted = take - leftover;
                if (accepted > 0) {
                    ground.take(entry.getKey(), accepted);
                    picked += accepted;
                }
            }
            if (picked <= 0) {
                result.blocked("工人背包已满，捡不了地上的掉落物", step.actionTicks());
                return;
            }
            result.done("捡起地上的掉落物", step.actionTicks());
            return;
        }
        // 0 = 不限量（NSUK 的数量是「每一批」的个数，不是整任务总量）。
        int wanted = step.count() > 0 ? step.count() : Integer.MAX_VALUE;
        int available = ground.count(spec.key());
        if (available <= 0) {
            // NSUK：地上**一个都没有** → missing_drops（重试），不是容器问题。
            result.blocked("地上没有 " + spec.display() + " 可捡（NSUK 判 MISSSING_DROPS 并重试）",
                    step.actionTicks());
            return;
        }
        int picked = (int) Math.min((long) wanted, available);
        // 放不放得下由**背包自己**按堆叠上限算（一格 64 个，不是一格一件）——
        // 以前这里拿「空格数」当件数，40 个黏土球只捡了 14 个还判失败。
        int leftover = backpack.add(spec.key(), picked);
        int accepted = picked - leftover;
        if (accepted <= 0) {
            result.blocked("工人背包已满，捡不了 " + spec.display(), step.actionTicks());
            return;
        }
        ground.take(spec.key(), accepted);
        // **捡不完不算失败**：NSUK 只把「地上一件都没有」当 missing_drops，
        // 捡到多少算多少（背包满或地上不够都照常往下走）。
        // 捡了多少、还剩多少**不写进消息** —— 背包和地上的掉落物在预览里都看得见，
        // 写出来是重复信息（作者要求）。
        result.done("捡起 " + spec.display(), step.actionTicks());
    }

    /**
     * 检查有没有掉落物：查的是**地上真实存在**的掉落（NSUK `IndustrialEntityActionService:104-106`）。
     *
     * <p>以前这里「写了 item 就算找到」，等于这一步永远是绿的 —— 掉落物是前面步骤真的产生的
     * （剪毛/屠宰/破坏），这一步就该按那本账来判。
     */
    private static void executeRequireDrops(SimStepSpec step, SimGroundItems ground, StepExecution result) {
        SimItemSpec spec = step.itemSpec();
        // **`item` 留空 = 不按物品筛选**（NSUK 的 matchingDrops 就是 `itemSpec.isEmpty() || matches(...)`），
        // 也就是「地上只要有掉落物就算过」。以前这里把空 item 当「没法判定 ⇒ 需要世界」跳过，
        // 于是鸡肉/牛肉农场这种不写 item 的配方整条链都在假装成功：不检查、不捡、箱子空。
        if (spec.isEmpty()) {
            if (ground.isEmpty()) {
                result.blocked("地上没有任何掉落物（NSUK 判 MISSSING_DROPS 并重试）", step.actionTicks());
                return;
            }
            result.done("地上有掉落物（未指定物品，算全部符合）", step.actionTicks());
            return;
        }
        int available = ground.count(spec.key());
        if (available <= 0) {
            // NSUK: MISSSING_DROPS → WAITING_RETRY（这一步会一直等，直到有掉落物出现）。
            result.blocked("地上没有 " + spec.display() + "（NSUK 判 MISSSING_DROPS 并重试）",
                    step.actionTicks());
            return;
        }
        result.done("地上有 " + spec.display(), step.actionTicks());
    }

    /**
     * 把一批产物放进容器。
     *
     * <p>照 {@code insert_item} 的模板：**先整体检查容量，再一次性写入**，
     * 否则会出现「第一种放进去了、第二种放不下」的半成品状态。
     *
     * @return false 表示装不下（已经写好 blocked 结果）
     */
    private static boolean depositProducts(String container, List<SimLootProvider.Product> products,
                                           SimInventory inventory, StepExecution result, int ticks) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (SimLootProvider.Product product : products) {
            // 注意：products 已经按「多少只动物」算好了总数（掷区间是产出源的事），
            // 这里**不能**再乘一次动物数 —— 那会把 3 只羊算成 15 份羊毛。
            totals.merge(product.item(), product.count(), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            if (!inventory.canAccept(container, entry.getKey(), entry.getValue())) {
                result.blocked("容器 " + container + " 已满，放不下 " + shortName(entry.getKey())
                        + " x" + entry.getValue(), ticks);
                return false;
            }
        }
        totals.forEach((item, count) -> inventory.add(container, item, count));
        return true;
    }

    /**
     * 真实机器配方。
     *
     * <p>查过真实实现（NSUK {@code IndustrialMachineOperationService}）之后，这条反而很直接 ——
     * **因为真实实现自己也不问机器**：
     * <ul>
     *   <li>不查配方管理器、不读机器的进度字段，**唯一的真相源就是脚本里的 {@code inputs} / {@code outputs}**；</li>
     *   <li>把 inputs 塞进机器，然后每 {@code pollTicks} 数一次机器槽里「比 baseline 多出来多少」，
     *       够了就算完成（{@code hasReadyOutput}）；</li>
     *   <li>{@code timeoutTicks} 只是失败重试，**不是**完成条件；</li>
     *   <li>{@code extract_to_output} 把产物抽到输出箱，{@code keep_in_machine} 留在机器里。</li>
     * </ul>
     *
     * <p>所以仿真照同一套语义走：扣料 → 等一次 poll → 产物出现。耗时用 {@code pollTicks}，
     * 因为「机器做好了」最早也只可能在第一次 poll 之后被观测到。
     *
     * <p><b>读不到的部分</b>：真实实现靠 NeoForge 运行时的物品能力
     * （{@code Capabilities.ItemHandler.BLOCK}）读写机器槽位，那是方块实体在运行时构造的，
     * 预览世界（假世界、没有能力系统）里没有。不过完成判定比的是**比 baseline 多出来的量**，
     * baseline 自己会抵消 —— 所以机器里原本有什么并不影响算账。
     * 唯一被假设掉的是「机器塞不塞得下」，这里按「机器是空的」算。
     */
    private static void executeRealMachine(JsonObject recipe, SimStepSpec step, Focus focus,
                                           SimInventory inventory, Random random, StepExecution result) {
        // NSUK 先要 point 能解析（IndustrialMachineOperationService:66-70）——
        // 没写机器点位就是 missing_point。这里只能查到「有没有写」（能不能解析要问客户端），
        // 但这就挡住了最常见的配置错误。
        if (step.point().isBlank()) {
            result.blocked("机器步骤没写 point（NSUK 判 missing_point）", step.actionTicks());
            return;
        }
        List<SimInput> inputs = effectiveInputs(recipe, step);
        List<SimOutputSpec> outputs = effectiveOutputs(recipe, step);
        // 输出表为空 ⇒ NSUK 判 **invalid_step**（IndustrialMachineOperationService:106-109），
        // 不是「扣了料、什么也没产出、算成功」。以前我们照扣照成功。
        if (outputs.isEmpty()) {
            result.status = Status.INVALID;
            result.message = "机器步骤没有 outputs（NSUK 判 invalid_step）";
            result.ticks = step.actionTicks();
            return;
        }
        String inputContainer = step.container("input", "container", SimInventory.DEFAULT_INPUT);
        String outputContainer = step.container("output", "container", SimInventory.DEFAULT_OUTPUT);
        boolean keepInMachine = "keep_in_machine".equalsIgnoreCase(step.outputPolicy());
        // 容器要求照 NSUK（:110-117）：**有机器输入才要 input 容器**；
        // 只有 extract_to_output（即不是 keep_in_machine）才要 output 容器。
        boolean needInput = !inputs.isEmpty();
        if ((needInput && !requireContainer(focus, inputContainer, result))
                || (!keepInMachine && !requireContainer(focus, outputContainer, result))) {
            return;
        }
        result.containersUsed = keepInMachine
                ? Set.of(inputContainer)
                : Set.of(inputContainer, outputContainer);
        int ticks = Math.max(1, step.pollTicks());

        Map<String, Integer> consumption = planConsumption(inventory, inputContainer, inputs, 1);
        if (consumption == null) {
            // 缺料时 NSUK 不是干等：它会把步骤指针**回退**到同一 point 上的补料步骤
            // （IndustrialMachineOperationService:195-199 → IndustrialStepRewindService:28-37）。
            // 我们只做线性干跑、不建模回退，所以至少把这件事说清楚。
            result.blocked("容器 " + inputContainer + " 缺少机器要的料：" + describeInputs(inputs)
                    + "（真实实现会回退到补料步骤，见 IndustrialStepRewindService:28-37）", ticks);
            return;
        }
        List<SimItemSpec> produced = machineOutputs(outputs);
        Map<String, Integer> producedCounts = new LinkedHashMap<>();
        for (SimItemSpec output : produced) {
            producedCounts.merge(output.key(), 1, Integer::sum);
        }
        // 超时：NSUK 是「从开始到现在的 tick 数 > timeoutTicks ⇒ machine_timeout」
        // （IndustrialMachineOperationService:183-186），而且它**不清机器状态、不退料**，
        // 之后每次 tick 都还是超时 —— 只有 skipOnTimeout 能往前走。
        // 我们的模型里机器一定会在第一次 poll 时出产物，所以这条只在
        // 「pollTicks 比 timeoutTicks 还长」（配置写反了）时才会触发。
        int timeoutTicks = step.timeoutTicks();
        if (timeoutTicks > 0 && ticks > timeoutTicks) {
            if (step.skipOnTimeout()) {
                result.status = Status.SKIPPED_TIMEOUT;
                result.message = "机器在 " + timeoutTicks + " tick 内没出产物（pollTicks=" + ticks
                        + "），按配置跳过这一步";
                result.ticks = timeoutTicks;
                return;
            }
            result.blocked("机器超时：timeoutTicks=" + timeoutTicks + " 比 pollTicks=" + ticks
                    + " 还短，真实实现会一直重试（machine_timeout）", timeoutTicks);
            return;
        }
        if (!keepInMachine) {
            // 先整体检查容量再提交，避免「料扣了、产物放不进去」。
            for (Map.Entry<String, Integer> entry : producedCounts.entrySet()) {
                if (!inventory.canAccept(outputContainer, entry.getKey(), entry.getValue())) {
                    result.blocked("容器 " + outputContainer + " 已满，机器产物放不进去", ticks);
                    return;
                }
            }
        }
        consumption.forEach((item, count) -> inventory.take(inputContainer, item, count));
        if (keepInMachine) {
            // outputPolicy=keep_in_machine：产物留在机器里，不进任何一本账。
            result.done("机器加工完成，产物留在机器里（" + produced.size() + " 组）", ticks);
            return;
        }
        producedCounts.forEach((item, count) -> inventory.add(outputContainer, item, count));
        result.done("机器加工完成，取出 " + produced.size() + " 组", ticks);
    }

    /**
     * 成簇收获：在工作区里找「匹配 {@code targetBlockTag} 的连通块（26 邻接）」，按真实规则筛选后砍掉。
     *
     * <p>规则照 NSUK 的 {@code IndustrialBlockClusterHarvestService} 移植：
     * <ol>
     *   <li>洪水填充 26 邻接、只走匹配 target 的方块，上限 {@code maxClusterBlocks}；</li>
     *   <li>大小 {@code >= maxClusterBlocks} ⇒ **整簇跳过**（不是截断）；</li>
     *   <li>簇里任一方块落在建筑外框内 ⇒ 整簇跳过（无条件，和 {@code excludeBuilding} 开关无关）；</li>
     *   <li>{@code roots} = 最低一层里「下方匹配 {@code supportBlockTag}」的方块，为空 ⇒ 整簇跳过；</li>
     *   <li>半径 2 内的附着物数量 {@code < minAttachedBlocks} ⇒ 整簇跳过
     *       （所以 {@code attachedBlockTag} 留空 + {@code minAttachedBlocks >= 1} 会永远不收）；</li>
     *   <li>收获列表 = 主体方块 + 半径 3 内的附着物（截断到 {@code maxClusterBlocks - logs}），按 Y 降序。</li>
     * </ol>
     *
     * <p>产物进**工人背包**，不是容器 —— 真实实现就是这样，靠后面的 {@code deposit_carried_items} 搬出。
     * 配了 {@code plantItemTag} 的话，砍完会把树苗种回 {@code roots}（先吃背包、再吃输入容器）。
     *
     * <p>**没有建模**：扫描节奏（每 tick 扫几列、游标走到哪一环）是「什么时候做」而不是「做什么」，
     * 仿真按步算，一次性把这一片收完。
     */
    private static void executeHarvestClusters(SimStepSpec step, Focus focus, SimInventory inventory,
                                               Random random, SimEnvironment env, SimBackpack backpack,
                                               SimGroundItems ground, String heldItem, StepExecution result) {
        int ticks = step.actionTicks();
        String target = step.targetBlockTag();
        if (target.isBlank()) {
            result.status = Status.INVALID;
            result.message = "harvest_block_clusters 缺少 targetBlockTag";
            result.ticks = ticks;
            return;
        }
        SimWorkArea area = env.workArea();
        if (!area.known()) {
            result.status = Status.NEEDS_WORLD;
            result.message = "拿不到作业范围（workArea / 建筑外框），需要真实世界";
            result.ticks = ticks;
            return;
        }
        List<SimPos> candidates = env.blocks().candidates(area);
        Set<SimPos> present = new LinkedHashSet<>(candidates == null ? List.of() : candidates);
        Set<SimPos> targets = new LinkedHashSet<>();
        for (SimPos pos : present) {
            Boolean match = env.blocks().matches(target, pos);
            if (match == null) {
                result.status = Status.NEEDS_WORLD;
                result.message = "认不出 targetBlockTag：" + target + "，需要真实世界";
                result.ticks = ticks;
                return;
            }
            if (match) {
                targets.add(pos);
            }
        }
        if (targets.isEmpty()) {
            emptyHarvest(target, result, ticks);
            return;
        }

        int maxCluster = step.maxClusterBlocks();
        // 真实实现的背包只有 14 格，所以 maxCarryStacks 写 18 是达不到的 —— 这里按真实上限夹一次。
        int carryLimit = Math.min(step.maxCarryStacks(), SimBackpack.SLOTS);
        List<BlockEdit> edits = new ArrayList<>();
        Map<String, Integer> harvested = new LinkedHashMap<>();
        Set<SimPos> visited = new LinkedHashSet<>();
        // 补种的累计（逐簇补，见下面的 plantRoots）与簇计数。
        int planted = 0;
        boolean missingPlant = false;
        int clusters = 0;
        boolean carryFull = false;
        boolean unknownDrops = false;

        for (SimPos seed : sortedByYDesc(targets)) {
            if (visited.contains(seed)) {
                continue;
            }
            // 每次**独立**洪泛（和真实实现一样）：用一个全局 visited 的话，
            // 「过大被拒」的那棵树在换个种子重来时看不到已访问的同伴，
            // 就会被当成一棵小树砍掉一部分 —— 而规则要求「过大就整簇跳过」。
            Set<SimPos> logs = floodTargets(seed, targets, area, maxCluster);
            visited.addAll(logs);
            // 下面四个 continue 都是规则本身：整簇太大就整簇跳过（不部分收）、
            // 簇里有方块落在建筑外框内就整簇否决（不砍建筑本身）、根下无支撑不收、附着物不够不收。
            // **都不写进消息**：它们解释的是「框架为什么没收」，对作者没有行动价值，而且每次跑都会出现。
            if (logs.size() >= maxCluster) {
                continue;
            }
            if (logs.stream().anyMatch(area::insideBuilding)) {
                continue;
            }
            if (rootsOf(logs, env, area, step.supportBlockTag(), present).isEmpty()) {
                continue;
            }
            if (step.minAttachedBlocks() > 0
                    // 额度用 maxClusterBlocks 截断，不是「数不完」：NSUK 在半径 2 内数附着物时
                    // 也受同一个上限约束（IndustrialBlockClusterHarvestService:618/:696-709），
                    // 所以 minAttachedBlocks > maxClusterBlocks 时两边结论会相反。
                    && attachedNear(logs, env, area, step.attachedBlockTag(), 2, maxCluster).size()
                    < step.minAttachedBlocks()) {
                continue;
            }
            Set<SimPos> harvest = new LinkedHashSet<>(logs);
            harvest.addAll(attachedNear(logs, env, area, step.attachedBlockTag(), 3,
                    Math.max(0, maxCluster - logs.size())));
            clusters++;
            Set<SimPos> clusterRoots = new LinkedHashSet<>(
                    rootsOf(logs, env, area, step.supportBlockTag(), present));
            for (SimPos pos : harvestOrder(harvest, logs, clusterRoots)) {
                if (area.insideBuilding(pos)) {
                    continue;
                }
                edits.add(new BlockEdit(pos.x(), pos.y(), pos.z(), "minecraft:air", true));
                // 真实实现只给**主体方块**工具（附着物用空手），所以时运只作用在树上。
                String tool = logs.contains(pos) ? toolFor(step, heldItem) : "";
                List<SimLootProvider.Product> products =
                        env.loot().blockDrops(pos.x(), pos.y(), pos.z(), tool, random);
                if (products == null) {
                    // 掉落算不出来：方块照砍（世界改动照发），账不假装知道。
                    unknownDrops = true;
                    continue;
                }
                for (SimLootProvider.Product product : products) {
                    // 背包按**真格子**算（14 格 × 64）：放不下的就是没进背包。
                    // NSUK 这时会把放不下的 popResource 到工人脚下（IndustrialCarriedItemService:148-155）
                    // 并记 CARRY_FULL —— 所以这里落到地上的掉落池里，之后还能被 collect_drops 捡。
                    int notStored = backpack.add(product.item(), product.count());
                    int stored = product.count() - notStored;
                    if (stored > 0) {
                        harvested.merge(product.item(), stored, Integer::sum);
                    }
                    if (notStored > 0) {
                        ground.drop(product.item(), notStored);
                        carryFull = true;
                    }
                }
            }
            // 砍完**这一簇**就立刻补它的根（NSUK 的顺序：IndustrialBlockClusterHarvestService:129-131/:372-375）。
            PlantResult plant = plantRoots(step, focus, inventory, env, backpack, edits, clusterRoots, result);
            planted += plant.planted();
            if (plant.missing()) {
                missingPlant = true;
            }
            if (backpack.occupiedSlots() >= carryLimit) {
                carryFull = true;
                break;
            }
        }

        // 砍完补种：真实实现是**砍一簇就立刻补这一簇的根**（IndustrialBlockClusterHarvestService:129-131
        // + :372-375，plantPositions = 该簇的 roots），不是全部砍完再统一补 ——
        // 材料不够时「哪几棵树没补上」会不一样，所以补种放进了簇循环里。
        result.edits = List.copyOf(edits);
        // 强调范围：把砍掉的坐标给时间轴/描边用（太多了就不给，免得卡）。
        if (!edits.isEmpty() && edits.size() <= 256) {
            Set<SimPos> cut = new LinkedHashSet<>();
            edits.forEach(edit -> cut.add(edit.pos()));
            result.focus = cut;
        }
        if (edits.isEmpty()) {
            // 一簇都没收成 —— 预览看不到建筑外的世界，所以改按虚拟收获估算一份产出。
            emptyHarvest(target, result, ticks);
            return;
        }
        StringBuilder message = new StringBuilder("收获 ").append(clusters).append(" 簇 / ")
                .append(edits.size()).append(" 个方块");
        if (!harvested.isEmpty()) {
            message.append("，得到 ").append(describeTotals(harvested));
        }
        if (carryFull) {
            message.append("，背包满（").append(carryLimit).append(" 格）");
        }
        if (!step.plantItemTag().isBlank()) {
            if (planted > 0) {
                message.append("，补种 ").append(planted).append(" 棵");
            }
            if (missingPlant) {
                message.append(planted > 0 ? "，之后缺补种材料" : "，缺补种材料");
            }
        }
        if (unknownDrops) {
            // 方块砍了，但掉落没算出来 —— 与破坏方块同一种处理：保留世界改动，标注需要真实世界。
            result.status = Status.NEEDS_WORLD;
            result.message = message.append("；部分掉落拿不到战利品表").toString();
            result.ticks = ticks;
            return;
        }
        result.done(message.toString(), ticks);
    }


    /**
     * 工作区里**一个匹配的方块都没有**时的结局：**没有任何产出**。
     *
     * <p>这是 NSUK 的 AREA_EMPTY 语义（{@code IndustrialBlockClusterHarvestService:156-169} +
     * {@code IndustrialWorkService:374-377}；整簇被规则拒时它返回 null，走同一条路）——
     * 扫遍作业区没有可收的方块，这一步就此结束，不掉一根木头。
     *
     * <p>预览世界里的方块全部来自结构文件，所以「建筑外面有树」这件事它看不见，
     * 这里只把原因说清楚。**曾经**它会「按一簇约 8 个方块估算产出并写进背包」——
     * 那是凭空产出，会把后面的合成/机器步骤喂成假的可行性，已撤掉（对齐优先）。
     */
    private static void emptyHarvest(String selector, StepExecution result, int ticks) {
        result.done("工作区里没有可收获的 " + shortName(selector) + "（NSUK 判 AREA_EMPTY，无产出）", ticks);
    }

    /**
     * 收获这一步的耗时。
     *
     * <p>NSUK 的 {@code stumpDigTicks}：**{@code step.ticks() > 1} 就直接用它**当「每个树桩挖几 tick」，
     * 否则按方块硬度公式夹在 1..60（{@code IndustrialBlockClusterHarvestService:436-453}）。
     * 硬度算不出来（预览里没有真实方块），所以只对齐「配了 ticks」这一段；
     * 没配时退回原来的 {@code actionTicks()}。扫描与挥手的开销未计入，属已知近似。
     */
    private static int harvestTicks(SimStepSpec step, int stumps) {
        int perStump = step.ticks();
        return perStump > 1 && stumps > 0 ? perStump * stumps : step.actionTicks();
    }

    /** 补种结果：种了几棵 + 是不是中途缺材料（或配错了物品）。 */
    private record PlantResult(int planted, boolean missing) {
    }

    /**
     * 把树苗种回**这一簇**的树桩：先吃工人背包、再吃输入容器，一格一个。
     *
     * <p>NSUK 是砍一簇就立刻补这一簇的根（{@code IndustrialBlockClusterHarvestService:129-131}
     * + {@code :372-375}，{@code plantPositions} = 该簇的 roots），材料不够就 {@code MISSING_INPUTS}。
     * 我们以前是全部砍完再统一补，于是「哪几棵树没补上」和真机不一致。
     */
    private static PlantResult plantRoots(SimStepSpec step, Focus focus, SimInventory inventory,
                                          SimEnvironment env, SimBackpack backpack,
                                          List<BlockEdit> edits, Collection<SimPos> roots,
                                          StepExecution result) {
        if (step.plantItemTag().isBlank() || roots.isEmpty()) {
            return new PlantResult(0, false);
        }
        String container = step.container("input", "container", SimInventory.DEFAULT_INPUT);
        boolean containerOk = requireContainer(focus, container, result);
        int planted = 0;
        for (SimPos root : roots) {
            String item = backpack.findItem(env.loot(), step.plantItemTag());
            boolean fromBackpack = item != null;
            if (item == null && containerOk) {
                item = findInContainer(inventory, container, env.loot(), step.plantItemTag());
            }
            if (item == null) {
                return new PlantResult(planted, true);
            }
            String blockId = env.loot().placedBlock(item);
            if (blockId == null) {
                // 不是方块物品（配错了）—— 别假装种上了。
                return new PlantResult(planted, true);
            }
            if (fromBackpack) {
                backpack.take(item, 1);
            } else {
                inventory.take(container, item, 1);
            }
            edits.add(new BlockEdit(root.x(), root.y(), root.z(), blockId, true));
            planted++;
        }
        if (containerOk && planted > 0) {
            result.containersUsed = Set.of(container);
        }
        return new PlantResult(planted, false);
    }

    /** 26 邻接洪水填充，只走匹配主体方块的坐标，最多 {@code limit} 个。
     *
     * <p>每次调用都是**独立的**（自己一份 visited），这一点很要紧：
     * 共用一份的话，「过大被拒」的那棵树换个种子重来时看不到已访问的同伴，
     * 会被当成小树砍掉一部分 —— 而规则要求「过大就整簇跳过」。
     */
    private static Set<SimPos> floodTargets(SimPos seed, Set<SimPos> targets, SimWorkArea area, int limit) {
        Set<SimPos> found = new LinkedHashSet<>();
        Set<SimPos> seen = new LinkedHashSet<>();
        ArrayDeque<SimPos> queue = new ArrayDeque<>();
        found.add(seed);
        seen.add(seed);
        queue.add(seed);
        while (!queue.isEmpty() && found.size() < limit) {
            SimPos current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        SimPos next = new SimPos(current.x() + dx, current.y() + dy, current.z() + dz);
                        if (!area.contains(next) || seen.contains(next) || !targets.contains(next)) {
                            continue;
                        }
                        seen.add(next);
                        found.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return found;
    }

    /**
     * 树根：主体方块里 Y 最低那一层中「下方匹配支撑物」的位置。
     *
     * <p>{@code supportBlockTag} 留空时退化成「下方非空气」—— 用 {@code present}
     * （工作区里有方块的坐标集合）判断，不必再问一次方块查询。
     */
    private static List<SimPos> rootsOf(Set<SimPos> logs, SimEnvironment env, SimWorkArea area,
                                        String supportSelector, Set<SimPos> present) {
        int rootY = Integer.MAX_VALUE;
        for (SimPos pos : logs) {
            rootY = Math.min(rootY, pos.y());
        }
        List<SimPos> roots = new ArrayList<>();
        for (SimPos pos : logs) {
            if (pos.y() != rootY) {
                continue;
            }
            SimPos below = new SimPos(pos.x(), pos.y() - 1, pos.z());
            if (supportSelector.isBlank()) {
                if (present.contains(below)) {
                    roots.add(pos);
                }
                continue;
            }
            if (Boolean.TRUE.equals(env.blocks().matches(supportSelector, below))) {
                roots.add(pos);
            }
        }
        return roots;
    }

    /** 主体方块周围 {@code radius} 格内的附着物（去重，最多 {@code limit} 个）。 */
    private static Set<SimPos> attachedNear(Set<SimPos> logs, SimEnvironment env, SimWorkArea area,
                                            String selector, int radius, int limit) {
        Set<SimPos> attached = new LinkedHashSet<>();
        if (selector == null || selector.isBlank() || limit <= 0) {
            return attached;
        }
        for (SimPos log : logs) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (attached.size() >= limit) {
                            return attached;
                        }
                        SimPos pos = new SimPos(log.x() + dx, log.y() + dy, log.z() + dz);
                        if (!area.contains(pos) || attached.contains(pos)) {
                            continue;
                        }
                        if (Boolean.TRUE.equals(env.blocks().matches(selector, pos))) {
                            attached.add(pos);
                        }
                    }
                }
            }
        }
        return attached;
    }

    /** 种子的扫描顺序：Y 降序（先从高的地方找起）、同层按 X、Z —— 固定顺序，保证重播一致。 */
    private static List<SimPos> sortedByYDesc(Collection<SimPos> positions) {
        List<SimPos> sorted = new ArrayList<>(positions);
        sorted.sort((left, right) -> {
            if (left.y() != right.y()) {
                return Integer.compare(right.y(), left.y());
            }
            if (left.x() != right.x()) {
                return Integer.compare(left.x(), right.x());
            }
            return Integer.compare(left.z(), right.z());
        });
        return sorted;
    }

    /**
     * 破坏顺序照 NSUK 的 {@code harvestOrder}（{@code IndustrialBlockClusterHarvestService:417-427}）：
     * **树桩 → 主体按 Y 升序 → 附着物**。
     *
     * <p>早先用的是「Y 降序」，注释还写着「真实实现是 Y 降序」—— 那句是错的：
     * NSUK 里被 {@code reversed()} 的是**存进 state 的列表**，不是破坏顺序。
     * 顺序固定（同层再按 X、Z）是为了同一轮重播结果一致。
     */
    private static List<SimPos> harvestOrder(Collection<SimPos> harvest, Collection<SimPos> logs,
                                             Collection<SimPos> roots) {
        List<SimPos> sorted = new ArrayList<>(harvest);
        sorted.sort((left, right) -> {
            int leftGroup = groupOf(left, logs, roots);
            int rightGroup = groupOf(right, logs, roots);
            if (leftGroup != rightGroup) {
                return Integer.compare(leftGroup, rightGroup);
            }
            if (left.y() != right.y()) {
                return Integer.compare(left.y(), right.y());   // 主体按 Y **升序**
            }
            if (left.x() != right.x()) {
                return Integer.compare(left.x(), right.x());
            }
            return Integer.compare(left.z(), right.z());
        });
        return sorted;
    }

    /** 0 = 树桩（先砍）、1 = 主体、2 = 附着物。 */
    private static int groupOf(SimPos pos, Collection<SimPos> logs, Collection<SimPos> roots) {
        if (roots.contains(pos)) {
            return 0;
        }
        return logs.contains(pos) ? 1 : 2;
    }

    /**
     * 这一步手上拿的工具选择器。
     *
     * <p>照真实实现的 {@code effectiveTool}：**工人主手优先**（也就是前面 {@code set_held_item}
     * 设的那个），主手空着才用步骤自己的 {@code item}。工具会影响掉落（精准采集、时运）。
     */
    /**
     * 这一步手上那件工具的附魔串。
     *
     * <p>工序照 NSUK：**工人主手优先**（{@code heldItem} 是前面 {@code set_held_item} 设的），
     * 否则用步骤自己的 {@code item}；附魔跟着同一件物品走。
     */
    private static String toolEnchantmentsFor(SimStepSpec step, String heldItem) {
        return step.enchantments();
    }

    private static String toolFor(SimStepSpec step, String heldItem) {
        if (heldItem != null && !heldItem.isBlank()) {
            return heldItem;
        }
        SimItemSpec spec = step.itemSpec();
        return spec == null || spec.isEmpty() ? "" : spec.key();
    }

    /** 在容器里按选择器找一个匹配的物品 id（补种取料用）。 */
    private static String findInContainer(SimInventory inventory, String container, SimLootProvider loot,
                                          String selector) {
        for (String item : inventory.contents(container).keySet()) {
            if (Boolean.TRUE.equals(loot.itemMatches(selector, item))) {
                return item;
            }
        }
        return null;
    }

    /**
     * 把背包里的东西倒进容器。
     *
     * <p>真实实现里「收获」只把东西塞进工人背包，入库是**另一步**（{@code deposit_carried_items}）。
     * 先整体检查容量再提交，避免倒进去一半。
     */
    private static void executeDepositCarried(SimStepSpec step, Focus focus, SimInventory inventory,
                                              SimBackpack backpack, StepExecution result) {
        String container = step.container("container", "output", SimInventory.DEFAULT_OUTPUT);
        if (!requireContainer(focus, container, result)) {
            return;
        }
        result.containersUsed = Set.of(container);
        if (backpack.isEmpty()) {
            result.done("背包为空，无需放入", step.actionTicks());
            return;
        }
        // 照 NSUK `IndustrialCarriedItemService:111-127`：**逐格**倒，按 `items` 过滤，
        // 能倒多少倒多少，倒不下的**留在背包**（下次这个步骤继续倒），已倒进去的不回退。
        // 早先是「整包预检、塞不下就一件不倒」——那是全有或全无，和真实行为不一样。
        List<SimItemSpec> filter = step.itemSpecs();
        Map<String, Integer> moved = new LinkedHashMap<>();
        Map<String, Integer> left = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : backpack.contents().entrySet()) {
            if (!filter.isEmpty()
                    && filter.stream().noneMatch(spec -> spec.key().equals(entry.getKey()))) {
                // 不在过滤清单里的物品原样留在背包，**不算失败**（NSUK 同此）。
                left.put(entry.getKey(), entry.getValue());
                continue;
            }
            int fits = fitsInto(inventory, container, entry.getKey(), entry.getValue());
            if (fits > 0) {
                inventory.add(container, entry.getKey(), fits);
                backpack.take(entry.getKey(), fits);
                moved.merge(entry.getKey(), fits, Integer::sum);
            }
            int rest = entry.getValue() - fits;
            if (rest > 0) {
                left.put(entry.getKey(), rest);
            }
        }
        if (moved.isEmpty()) {
            result.blocked("容器 " + container + " 放不下背包里的东西（" + describeTotals(left)
                    + "），NSUK 判 OUTPUT_FULL 并重试", step.actionTicks());
            return;
        }
        if (!left.isEmpty()) {
            // 部分倒入：已进去的留着，剩下的在背包等下一次（NSUK 的 OUTPUT_FULL 也是这么继续的）。
            result.blocked("放入 " + describeTotals(moved) + "，背包还剩 " + describeTotals(left)
                    + "（容器放不下，NSUK 判 OUTPUT_FULL 并重试）", step.actionTicks());
            return;
        }
        result.done("放入 " + describeTotals(moved), step.actionTicks());
    }

    /** 容器最多还能放下这个物品几个（二分，别为了一个数把 64 件循环一遍）。 */
    private static int fitsInto(SimInventory inventory, String container, String item, int want) {
        int low = 0;
        int high = Math.max(0, want);
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (inventory.canAccept(container, item, middle)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /**
     * 破坏方块：把坐标记成「变成空气」的世界改动；配了 {@code dropItems} 时产物**落在地上**。
     *
     * <p>NSUK 这一步根本不碰容器：`level.destroyBlock(target, step.dropItems())`
     * （`IndustrialBlockActionService:149`）走的是原版 destroyBlock —— 掉落生成成
     * **地上的 ItemEntity**，之后要 `collect_drops` 捡、`deposit_carried_items` 才进箱子。
     * 早先我们按步骤的 `container` 直接入账，那是错的（官方配方里那两个字段本来只是给
     * 老版本兼容写的）。
     *
     * <p>掉落为什么按坐标问产出源：只有客户端知道那个坐标上**现在**是什么方块
     * （播放过程中会变 —— 前面刚放下的方块后面可能就被敲掉），
     * 而且玻璃这类方块是真的不掉东西，所以「拿不到表」和「不掉东西」必须分开。
     */
    private static void executeDestroyBlock(SimStepSpec step, Random random, SimLootProvider loot,
                                            String heldItem, SimGroundItems ground, SimBlocks blocks,
                                            StepExecution result) {
        List<SimPos> targets = SimPos.sortedList(result.focus.isEmpty() ? step.positions() : result.focus);
        if (targets.isEmpty()) {
            result.blocked("没有可用的目标坐标（positions / point 未定义）", step.actionTicks());
            return;
        }
        result.focus = new LinkedHashSet<>(targets);
        List<BlockEdit> edits = new ArrayList<>(targets.size());
        for (SimPos pos : targets) {
            edits.add(new BlockEdit(pos.x(), pos.y(), pos.z(), "minecraft:air", true));
        }
        result.edits = List.copyOf(edits);

        if (!step.dropItems()) {
            // 原版语义：dropItems=false 就是纯粹清掉，不捡东西。
            targets.forEach(blocks::remove);
            result.done("破坏 x" + edits.size() + "（不掉落）", step.actionTicks());
            return;
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (SimPos pos : targets) {
            // **先查台账**：若这个方块是配方自己 place_block 放上去的，结构文件里该坐标是空气，
            // 按坐标问只会得到「空气不掉东西」—— 掉落就这么凭空消失（砖厂卡第 21 步的真凶）。
            String placed = blocks.blockAt(pos);
            List<SimLootProvider.Product> products = placed == null
                    ? loot.blockDrops(pos.x(), pos.y(), pos.z(), toolFor(step, heldItem),
                            toolEnchantmentsFor(step, heldItem), random)
                    : loot.blockDropsForBlock(placed, toolFor(step, heldItem),
                            toolEnchantmentsFor(step, heldItem), random);
            if (products == null && placed != null) {
                // 按 id 也算不出来（客户端不认识这个方块）—— 退回按坐标问一次，
                // 别因为台账里有个捡不出来的 id 就把整步判成「需要真实世界」。
                products = loot.blockDrops(pos.x(), pos.y(), pos.z(), toolFor(step, heldItem),
                        toolEnchantmentsFor(step, heldItem), random);
            }
            if (products == null) {
                // 算不出来（没有预览世界）：方块还是会被破坏，但别假装知道掉什么。
                result.status = Status.NEEDS_WORLD;
                result.message = "拿不到方块战利品表（" + edits.size() + " 个方块），掉多少需要真实世界";
                result.ticks = step.actionTicks();
                return;
            }
            for (SimLootProvider.Product product : products) {
                totals.merge(product.item(), product.count(), Integer::sum);
            }
        }
        targets.forEach(blocks::remove);
        if (totals.isEmpty()) {
            result.done("破坏 x" + edits.size() + "（没有掉落）", step.actionTicks());
            return;
        }
        totals.forEach(ground::drop);
        result.done("破坏 x" + edits.size() + "，掉落在地上 " + describeTotals(totals), step.actionTicks());
    }

    /** 把「物品 → 数量」写成一行给人看（去掉命名空间）。 */
    private static String describeTotals(Map<String, Integer> totals) {
        StringBuilder text = new StringBuilder();
        totals.forEach((item, count) -> {
            if (text.length() > 0) {
                text.append("、");
            }
            text.append(shortName(item)).append(" x").append(count);
        });
        return text.toString();
    }

    private static String describeProducts(List<SimLootProvider.Product> products) {
        StringBuilder text = new StringBuilder();
        for (SimLootProvider.Product product : products) {
            if (text.length() > 0) {
                text.append("、");
            }
            text.append(shortName(product.item())).append(" x").append(product.count());
        }
        return text.toString();
    }

    private static String describeFoods(List<String> foods) {
        StringBuilder text = new StringBuilder();
        for (String food : foods) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(shortName(food));
        }
        return text.toString();
    }

    /** 界面里显示物品名时去掉命名空间。 */
    private static String shortName(String itemId) {
        int colon = itemId.indexOf(':');
        return colon < 0 ? itemId : itemId.substring(colon + 1);
    }

    /**
     * 放置类步骤：把「目标坐标 + 方块 id」记成一组世界改动。
     *
     * <p>以前这里是 {@code NEEDS_WORLD}（只标注「按假定成功继续」），
     * 于是仿真里完全看不出放了什么。现在客户端会按播放进度把这些改动真的应用到预览世界，
     * 方块会随着步骤出现。
     *
     * <p>注意：这些改动**不参与寻路**（不重建网格），所以放置的方块不改变后续走位。
     */
    private static void executePlaceBlock(SimStepSpec step, Focus focus, SimInventory inventory,
                                          SimBlocks blocks, StepExecution result) {
        boolean fluid = step.type() == IndustrialStepType.PLACE_FLUID;
        String blockId = fluid ? step.fluid() : step.block();
        if (blockId.isBlank()) {
            // 允许写反：place_block 里写 fluid、place_fluid 里写 block 都认。
            blockId = fluid ? step.block() : step.fluid();
        }
        if (blockId.isBlank()) {
            result.status = Status.INVALID;
            result.message = fluid ? "place_fluid 缺少 fluid" : "place_block 缺少 block";
            result.ticks = step.actionTicks();
            return;
        }
        List<SimPos> targets = SimPos.sortedList(result.focus.isEmpty() ? step.positions() : result.focus);
        if (targets.isEmpty()) {
            result.blocked("没有可用的目标坐标（positions / point 未定义）", step.actionTicks());
            return;
        }
        List<BlockEdit> edits = new ArrayList<>(targets.size());
        for (SimPos pos : targets) {
            edits.add(new BlockEdit(pos.x(), pos.y(), pos.z(), blockId, step.replace()));
            // 记进台账：后面 destroy_block 挖到这里时（结构文件里是空气）才能按 id 求出掉落。
            blocks.place(pos, blockId);
        }
        result.focus = new LinkedHashSet<>(targets);
        result.edits = List.copyOf(edits);
        // 材料消耗：NSUK 的 consumeMaterial（IndustrialBlockActionService:191-200）——
        // `consume:true` 时按**成功改动的格数**从 container/input（默认 input）扣料；
        // 没写 item 就用块自己的 id；料不够 ⇒ MISSING_INPUTS。
        if (step.consume() && !edits.isEmpty()) {
            String item = step.itemSpec().isEmpty() ? blockId : step.itemSpec().key();
            if (item.isBlank()) {
                result.status = Status.INVALID;
                result.message = "consume 为真但不知道消耗什么（没写 item，块 id 也认不出）";
                result.ticks = step.actionTicks();
                return;
            }
            String container = step.container("container", "input", SimInventory.DEFAULT_INPUT);
            if (!requireContainer(focus, container, result)) {
                return;
            }
            if (!inventory.take(container, item, edits.size())) {
                result.blocked("容器 " + container + " 里的 " + shortName(item) + " 不够（要 x"
                        + edits.size() + "）", step.actionTicks());
                return;
            }
            result.containersUsed = Set.of(container);
        }
        result.done((fluid ? "放置流体 " : "放置 ") + blockId + " x" + edits.size()
                + (step.consume() && !edits.isEmpty() ? "，消耗材料 x" + edits.size() : ""),
                step.actionTicks());
    }

    /**
     * 等待方块：等 1 秒后**在等待的位置直接生成该方块**，然后继续。
     *
     * <p>干跑里没有别的机器会来放这个方块，一直等下去就是死等（实机上就是卡在这里走不动）。
     * 既然用户要的是「看仿真能不能跑通」，就建模成「等一秒 → 方块出现 → 继续」，
     * 而这个「出现」是真写进预览世界的，看得见。
     *
     * <p>没写方块 id 时退化成纯等待（不产生世界改动）。
     */
    private static void executeRequireBlock(SimStepSpec step, StepExecution result) {
        List<SimPos> targets = SimPos.sortedList(result.focus.isEmpty() ? step.positions() : result.focus);
        if (targets.isEmpty()) {
            result.blocked("没有可用的目标坐标（positions / point 未定义）", step.actionTicks());
            return;
        }
        result.focus = new LinkedHashSet<>(targets);
        String blockId = step.block();
        if (blockId.isBlank()) {
            blockId = step.fluid();
        }
        if (blockId.isBlank()) {
            result.done("等待 1 秒", REQUIRE_BLOCK_WAIT_TICKS);
            return;
        }
        List<BlockEdit> edits = new ArrayList<>(targets.size());
        for (SimPos pos : targets) {
            edits.add(new BlockEdit(pos.x(), pos.y(), pos.z(), blockId, true));
        }
        result.edits = List.copyOf(edits);
        result.done("等待 1 秒后生成 " + blockId, REQUIRE_BLOCK_WAIT_TICKS);
    }

    /**
     * 步骤类型 → 客户端该播的动画。
     *
     * <p>映射依据是 NSUK {@code IndustrialWorkService.executeStep} 里各分支真正做的事：
     * {@code use_item} 会 {@code triggerWorkSwing}、{@code inspect_container} /
     * {@code insert_item} / {@code deposit_carried_items} 会 {@code setContainersOpen(..., true)}
     * 再关回去、{@code craft_recipe} 是持续作业、{@code place_block} / {@code destroy_block}
     * 会挥手。这张表放在内核里，客户端就不必再抄一遍。
     */
    static SimGesture gestureFor(SimStepSpec step, boolean changed) {
        IndustrialStepType type = step.type();
        return switch (type) {
            case MOVE_TO, MOVE_TO_CONTAINER, MOVE_TO_ENTITY -> SimGesture.WALK;
            case LOOK_AT, LOOK_AT_CONTAINER -> SimGesture.LOOK;
            // NSUK：use_item 只有 swing==true 才挥手（IndustrialWorkService:733-736）。
            case USE_ITEM -> step.swing() ? SimGesture.SWING : SimGesture.IDLE;
            case INSPECT_CONTAINER -> SimGesture.OPEN_CONTAINER;
            case INSERT_ITEM -> SimGesture.INSERT;
            case DEPOSIT_CARRIED_ITEMS -> SimGesture.DEPOSIT;
            case CRAFT_RECIPE, CRAFT_AVAILABLE_RECIPE -> SimGesture.CRAFT;
            // 放/破坏：只有**真的改动了方块且 swing** 才挥手（IndustrialBlockActionService:292-296）。
            case PLACE_BLOCK, PLACE_FLUID -> changed && step.swing() ? SimGesture.PLACE : SimGesture.IDLE;
            case DESTROY_BLOCK -> changed && step.swing() ? SimGesture.BREAK : SimGesture.IDLE;
            // 收获不受 swing 管：NSUK 挖树桩是每 4 tick 无条件挥手（:241-243）。
            case HARVEST_BLOCK_CLUSTERS -> SimGesture.BREAK;
            case BREED_ENTITIES, SLAUGHTER_ENTITIES, SHEAR_ENTITIES, REQUIRE_DROPS, COLLECT_DROPS ->
                    SimGesture.ENTITY;
            case REAL_MACHINE_RECIPE -> SimGesture.MACHINE;
            case SET_HELD_ITEM, SET_STATUS, REQUIRE_INPUTS, REQUIRE_OUTPUT_SPACE, REQUIRE_BLOCK, FILL_ITEM,
                    REPEAT -> SimGesture.IDLE;
        };
    }

    private static String worldReason(IndustrialStepType type) {
        return "需要世界方块 / 实体状态：" + typeId(type);
    }

    private static void executeMove(SimStepSpec step, SimPos citizen, SimNavigation navigation, StepExecution result) {
        result.gesture = SimGesture.WALK;
        List<SimPos> anchors = SimPos.sortedList(result.focus.isEmpty() ? step.positions() : result.focus);
        if (anchors.isEmpty()) {
            if (step.type() == IndustrialStepType.MOVE_TO_ENTITY) {
                result.status = Status.NEEDS_WORLD;
                result.message = "按半径搜索实体来决定站位，需要真实世界";
                result.ticks = step.actionTicks();
                return;
            }
            result.blocked("没有可用的目标点位（point 未定义，或点位没有坐标）", step.actionTicks());
            return;
        }

        // 「走到容器」不是走到容器方块里 —— 箱子是实心的。按 NSUK 的规则换成容器旁边的落脚点。
        boolean toContainer = step.type() == IndustrialStepType.MOVE_TO_CONTAINER;
        List<SimPos> targets = toContainer ? containerStandTargets(navigation, anchors) : anchors;
        result.focus = new LinkedHashSet<>(anchors);
        SimPos target = nearestTo(targets, citizen);
        if (citizen == null) {
            result.done("已在目标点 " + target.x() + "," + target.y() + "," + target.z(), 1);
            result.citizen = target;
            return;
        }
        // 到达判定用 NSUK 的**到达半径**，不是「必须站在那一格」：
        //   move_to            → max(0.2, range)（IndustrialWorkService:560-565）
        //   move_to_container  → 有落脚点时 min(range, 0.65)，否则 range（:580-583）
        //   move_to_entity / collect_drops → max(1.5, range)（:599-600、:645-646）
        // 早先只判 `citizen.equals(target)`：站在目标旁边一格时 NSUK 1 tick 就走完，
        // 我们却还要再走一段 —— 总 tick 偏长、时间轴偏慢。
        if (withinArrivalRange(citizen, target, step, toContainer)) {
            result.route = List.of(SimWaypoint.of(citizen, citizen.y(), SimMotion.WALK));
            result.done(toContainer ? "已经站在容器旁" : "已经在目标点上", 1);
            result.citizen = target;
            return;
        }
        // 落脚点只试**最近的那一个** —— 这是 NSUK 的行为（containerMoveTarget 只给一个目标），
        // 到不了就让工人重试/干站着。曾经这里会「依次换下一个候选」，那是我为了绕一个解析 bug
        // 加的宽容处理，会让仿真掩盖真实游戏里会卡住的位置 —— 已撤掉。对齐优先。
        List<SimWaypoint> path = navigation.findPath(citizen, target);
        if (path.isEmpty()) {
            // 「走不到」有好几种完全不同的原因（目标点没落脚点 / 被墙水分断 / 搜索超上限），
            // 说清是哪一种，作者才知道该改建筑还是改配置。
            String why = navigation.explainPathFailure(citizen, target);
            result.blocked("寻路失败：从当前位置走不到目标点"
                    + (why.isBlank() ? "（被挡住或没有落脚点）" : "——" + why), 1);
            return;
        }
        result.route = path;
        SimWaypoint last = path.get(path.size() - 1);
        result.citizen = last.cell();
        double length = SimNavigation.length(path);
        result.done((toContainer ? "移动到容器旁的站位 " : "移动到 ")
                + target.x() + "," + target.y() + "," + target.z()
                + "（" + (int) Math.ceil(length) + " 格）", SimNavigation.travelTicks(path));
    }

    /**
     * 容器旁的候选落脚点。
     *
     * <p>NSUK 的 {@code containerMoveTarget} 会在所有容器里挑「离市民最近的落脚点」；
     * 这里把所有容器的落脚点并起来交给 {@link #nearestTo} 挑，结果一致。
     * 一圈都找不到落脚点时退回容器自身 —— 那也是 NSUK 的行为（{@code hasStandTarget = false}），
     * 由寻路在附近就近吸附。
     */
    static List<SimPos> containerStandTargets(SimNavigation navigation, List<SimPos> containers) {
        Set<SimPos> stands = new LinkedHashSet<>();
        for (SimPos container : containers) {
            stands.addAll(navigation.standTargets(container));
        }
        return stands.isEmpty() ? containers : List.copyOf(stands);
    }

    /**
     * 当前位置算不算「已经到了」。
     *
     * <p>半径照 NSUK（见 {@code executeMove} 里的注释），比较的是**格心到格心的欧氏距离**
     * —— NSUK 用 {@code Vec3.atBottomCenterOf(...).distanceToSqr(worker.position())}，
     * 我们的市民位置就是格心（{@code SimPos}），所以等价。
     */
    static boolean withinArrivalRange(SimPos citizen, SimPos target, SimStepSpec step, boolean toContainer) {
        double range = step.range();
        double arrival = switch (step.type()) {
            case MOVE_TO_CONTAINER -> toContainer ? Math.min(range, 0.65D) : Math.max(0.2D, range);
            case MOVE_TO_ENTITY, COLLECT_DROPS -> Math.max(1.5D, range);
            default -> Math.max(0.2D, range);
        };
        double dx = citizen.x() - target.x();
        double dy = citizen.y() - target.y();
        double dz = citizen.z() - target.z();
        return dx * dx + dy * dy + dz * dz <= arrival * arrival;
    }

    /** 取离当前位置最近的一个；{@code citizen} 为空时取第一个（列表本身是有序的）。 */
    static SimPos nearestTo(List<SimPos> candidates, SimPos citizen) {
        SimPos best = candidates.get(0);
        if (citizen == null) {
            return best;
        }
        int distance = Integer.MAX_VALUE;
        for (SimPos candidate : candidates) {
            int candidateDistance = citizen.manhattan(candidate);
            if (candidateDistance < distance) {
                distance = candidateDistance;
                best = candidate;
            }
        }
        return best;
    }


    /** 容器没定义就按 NSUK 的行为算受阻（找不到容器 ⇒ WAITING_RETRY）。 */
    private static boolean requireContainer(Focus focus, String name, StepExecution result) {
        if (focus.knowsContainer(name)) {
            return true;
        }
        result.blocked("容器 " + name + " 没有在建筑定义的 containers 里，游戏里会一直卡在这一步", 1);
        return false;
    }

    private static void executeRequireInputs(JsonObject recipe, String container, SimInventory inventory,
                                             StepExecution result) {
        // NSUK 的 requireInputs 直接读 recipe.inputs()，**不**走 stepRecipe 的覆盖逻辑。
        // 用 effectiveInputs 会把「步骤上顺手写了 inputs」当成覆盖，判定就偏了。
        List<SimInput> inputs = recipeInputs(recipe);
        if (planConsumption(inventory, container, inputs, 1) == null) {
            result.blocked("容器 " + container + " 缺少 " + describeInputs(inputs), 1);
            return;
        }
        result.containersUsed = Set.of(container);
        result.done("材料齐备", 1);
    }

    private static void executeRequireOutputSpace(JsonObject recipe, String container, SimInventory inventory,
                                                  StepExecution result) {
        // 同 require_inputs：NSUK 用的是 recipe.outputs() 原表。
        // 而且它是把**整张最坏情况表一起** simulateInsert（IndustrialWorkService:715-723 +
        // IndustrialInventoryService:35-42）—— 逐条比会在「容量 100、两条各 60」时误判成功。
        List<SimOutputSpec> outputs = recipeOutputs(recipe);
        Map<String, Integer> worstCase = new LinkedHashMap<>();
        for (SimOutputSpec output : outputs) {
            if (output.spec().key().isBlank()) {
                // NSUK 会把「造不出 ItemStack」的产出 filter 掉（空栈不参与空间判定）。
                continue;
            }
            worstCase.merge(output.spec().key(), output.worstCaseAmount(), Integer::sum);
        }
        if (!inventory.canAcceptAll(container, worstCase)) {
            result.blocked("容器 " + container + " 剩余空间不足以放下最坏情况的产出（"
                    + describeTotals(worstCase) + "）", 1);
            return;
        }
        result.containersUsed = Set.of(container);
        result.done("输出空间充足（按最坏情况估算）", 1);
    }

    private static void executeCraft(JsonObject recipe, SimStepSpec step, SimInventory inventory, Random random,
                                     boolean craftAllAvailable, String inputContainer, String outputContainer,
                                     StepExecution result) {
        List<SimInput> inputs = effectiveInputs(recipe, step);
        List<SimOutputSpec> outputs = effectiveOutputs(recipe, step);
        result.containersUsed = Set.of(inputContainer, outputContainer);

        int crafts = craftAllAvailable ? availableCraftCount(inventory, inputContainer, inputs) : 1;
        if (crafts <= 0) {
            result.blocked("容器 " + inputContainer + " 缺少材料，一次也做不出来", 1);
            return;
        }

        // 事务：先算消耗，再算产出，最后一起提交。任何一步失败都整体放弃。
        SimInventory before = inventory.copy();
        Map<String, Integer> consumption = planConsumption(inventory, inputContainer, inputs, crafts);
        if (consumption == null) {
            result.blocked("容器 " + inputContainer + " 缺少材料：" + describeInputs(inputs), 1);
            return;
        }
        List<SimItemSpec> produced = buildOutputs(outputs, random, crafts);
        Map<String, Integer> producedCounts = new LinkedHashMap<>();
        for (SimItemSpec output : produced) {
            producedCounts.merge(output.key(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : producedCounts.entrySet()) {
            if (!inventory.canAccept(outputContainer, entry.getKey(), entry.getValue())) {
                inventory.restore(before);
                result.blocked("容器 " + outputContainer + " 已满，无法放入产出", 1);
                return;
            }
        }
        consumption.forEach((item, count) -> inventory.take(inputContainer, item, count));
        producedCounts.forEach((item, count) -> inventory.add(outputContainer, item, count));

        result.done(crafts > 1
                ? "连续合成 " + crafts + " 次，产出 " + produced.size() + " 组"
                : "合成完成，产出 " + produced.size() + " 组", 1);
    }

    private static void executeInsert(SimStepSpec step, String container, SimInventory inventory, StepExecution result) {
        SimItemSpec spec = step.itemSpec();
        if (spec.tagOnly()) {
            // NSUK：只有 tag 的描述造不出 ItemStack ⇒ insert_item 判 invalid_step
            // （IndustrialWorkService:500-503），不是「放进去了」。
            result.status = Status.INVALID;
            result.message = "只写了标签、没有具体物品（" + spec.key() + "）：NSUK 造不出物品，这一步无效";
            result.ticks = step.actionTicks();
            return;
        }
        if (spec.isEmpty()) {
            result.status = Status.INVALID;
            result.message = "insert_item 缺少 item";
            result.ticks = step.actionTicks();
            return;
        }
        int count = Math.max(1, step.count());
        result.containersUsed = Set.of(container);
        if (!inventory.canAccept(container, spec.key(), count)) {
            result.blocked("容器 " + container + " 已满，放不进 " + spec.display() + " x" + count, step.actionTicks());
            return;
        }
        inventory.add(container, spec.key(), count);
        result.done("放入 " + spec.display() + " x" + count, step.actionTicks());
    }

    // ── 材料与产出 ─────────────────────────────────────────────────────

    /** 配方级输入（不做步骤覆盖）。 */
    static List<SimInput> recipeInputs(JsonObject recipe) {
        return SimInput.parseList(recipe == null ? null : SimJson.arrayAny(recipe, "inputs"));
    }

    /** 配方级产出（不做步骤覆盖）。 */
    static List<SimOutputSpec> recipeOutputs(JsonObject recipe) {
        List<SimOutputSpec> outputs = new ArrayList<>();
        if (recipe == null) {
            return outputs;
        }
        for (JsonObject object : SimJson.objects(SimJson.arrayAny(recipe, "outputs"))) {
            SimOutputSpec spec = SimOutputSpec.parse(object);
            if (spec != null) {
                outputs.add(spec);
            }
        }
        return List.copyOf(outputs);
    }

    /**
     * 实际生效的输入：步骤写了 {@code inputs} 就覆盖配方级 —— 对应 NSUK 的
     * {@code step.inputsOverride() = object.has("inputs")} 与 {@code stepRecipe()}。
     * 注意只有 {@code craft_*} 走这条覆盖链，{@code require_inputs} 不走。
     */
    static List<SimInput> effectiveInputs(JsonObject recipe, SimStepSpec step) {
        return step.ownInputs() != null ? step.ownInputs() : recipeInputs(recipe);
    }

    /** 实际生效的产出：同一套覆盖规则。 */
    static List<SimOutputSpec> effectiveOutputs(JsonObject recipe, SimStepSpec step) {
        return step.ownOutputs() != null ? step.ownOutputs() : recipeOutputs(recipe);
    }

    /**
     * `real_machine_recipe` 的产物：**`Σ max(1, baseAmount)`**。
     *
     * <p>NSUK 的机器步骤只比「机器槽位比 baseline 多出来多少」（`IndustrialMachineOperationService:190-194`、
     * `:301-308`、`:318-324` 的 `expectedOutputCounts`），**`probability` / `randomRange` /
     * `ignoreMultiplier` 完全不参与** —— 它不做掉落随机，只看机器里真的多出了几个东西。
     * 早先我们照合成步骤那样掷概率、加随机量，判据就与 NSUK 不同了。
     */
    private static List<SimItemSpec> machineOutputs(List<SimOutputSpec> outputs) {
        List<SimItemSpec> produced = new ArrayList<>();
        for (SimOutputSpec output : outputs) {
            int amount = Math.max(1, output.baseAmount());
            for (int index = 0; index < amount; index++) {
                produced.add(output.spec());
            }
        }
        return produced;
    }

    /**
     * 照抄 {@code IndustrialInventoryService.buildOutputs} 的随机数消耗顺序。
     *
     * <p><b>顺序不能改</b>：先抽 {@code probability}，再抽 {@code randomRange}，
     * 每次合成各抽一轮。顺序换了，同一个 seed 就不再复现同一个结果，
     * 「重播」看到的东西会和第一次跑出来的不一样。
     */
    static List<SimItemSpec> buildOutputs(List<SimOutputSpec> outputs, Random random, int craftCount) {
        List<SimItemSpec> produced = new ArrayList<>();
        for (int craft = 0; craft < craftCount; craft++) {
            for (SimOutputSpec output : outputs) {
                // 纯标签的产出在 NSUK 里造不出 ItemStack，会被**静默丢弃**
                // （IndustrialInventoryService:94）—— 不能当真实物品记进账。
                if (output.spec().tagOnly()) {
                    continue;
                }
                if (random.nextDouble() > output.probability()) {
                    continue;
                }
                int bonus = output.randomRange() > 0 ? random.nextInt(output.randomRange()) : 0;
                int amount = output.baseAmount() + bonus;
                for (int i = 0; i < amount; i++) {
                    produced.add(output.spec());
                }
            }
        }
        return List.copyOf(produced);
    }

    /**
     * 求出「每个物品键需要消耗多少」。
     *
     * <p>{@code any} 组取第一个可行的分支 —— 与 NSUK 的 {@code IndustrialInputPlanner.planRequirement}
     * 一致（它是深度优先取第一个能排出来的子项，而不是取「最优」的那个）。
     * 返回 {@code null} 表示排不出来。
     */
    static Map<String, Integer> planConsumption(SimInventory inventory, String container,
                                                List<SimInput> inputs, int craftCount) {
        Map<String, Integer> reserved = reserveNonConsumables(inventory, container, inputs);
        if (reserved == null) {
            return null;
        }
        // 预留量要参与「可用量」的扣减，否则同一批料会被检查项和消耗项重复花掉
        // （NSUK 的 reserveInput 就是把它记进 state.reserved，消费项按 available - reserved 判，
        //  且只在第一次合成检查：IndustrialInputPlanner:97-123）。
        Map<String, Integer> planned = new LinkedHashMap<>(reserved);
        Map<String, Integer> total = new LinkedHashMap<>();
        for (int craft = 0; craft < Math.max(1, craftCount); craft++) {
            Map<String, Integer> one = planOnce(inventory, container, inputs, planned);
            if (one == null) {
                return null;
            }
            one.forEach((item, count) -> {
                total.merge(item, count, Integer::sum);
                planned.merge(item, count, Integer::sum);
            });
        }
        return total;
    }

    /**
     * 检查并**预留**所有 {@code consume:false} 的项。
     *
     * <p>只在合成计划开始时做一次（NSUK：{@code craft == 0} 时才 {@code checkNonConsumables}）。
     * 返回 null 表示材料不够。
     */
    private static Map<String, Integer> reserveNonConsumables(SimInventory inventory, String container,
                                                              List<SimInput> inputs) {
        Map<String, Integer> reserved = new LinkedHashMap<>();
        for (SimInput input : flatten(inputs)) {
            if (input.consume() || input.spec().key().isBlank()) {
                continue;
            }
            String key = input.spec().key();
            int available = inventory.count(container, key) - reserved.getOrDefault(key, 0);
            if (available < input.count()) {
                return null;
            }
            reserved.merge(key, input.count(), Integer::sum);
        }
        return reserved;
    }

    private static Map<String, Integer> planOnce(SimInventory inventory, String container,
                                                 List<SimInput> inputs, Map<String, Integer> alreadyPlanned) {
        Map<String, Integer> plan = new LinkedHashMap<>();
        for (SimInput input : inputs) {
            Map<String, Integer> part = planInput(inventory, container, input, alreadyPlanned, plan);
            if (part == null) {
                return null;
            }
            part.forEach((item, count) -> plan.merge(item, count, Integer::sum));
        }
        return plan;
    }

    private static Map<String, Integer> planInput(SimInventory inventory, String container, SimInput input,
                                                  Map<String, Integer> alreadyPlanned, Map<String, Integer> currentPlan) {
        return switch (input.logic()) {
            case ITEM -> planItem(inventory, container, input, alreadyPlanned, currentPlan);
            case ALL -> planOnce(inventory, container, input.children(), alreadyPlanned);
            case ANY -> {
                for (SimInput child : input.children()) {
                    Map<String, Integer> candidate = planInput(inventory, container, child,
                            alreadyPlanned, currentPlan);
                    if (candidate != null) {
                        yield candidate;
                    }
                }
                yield null;
            }
        };
    }

    private static Map<String, Integer> planItem(SimInventory inventory, String container, SimInput input,
                                                 Map<String, Integer> alreadyPlanned, Map<String, Integer> currentPlan) {
        String key = input.spec().key();
        if (key.isBlank()) {
            return Map.of();
        }
        if (!input.consume()) {
            // consume:false 的检查与预留已经在 reserveNonConsumables 里做过一次了
            // （NSUK 同样只在第一次合成检查：IndustrialInputPlanner:101-103），
            // 这里再减一次预留量就会**重复扣**，让本来够的材料判成不够。
            return Map.of();
        }
        int available = inventory.count(container, key)
                - alreadyPlanned.getOrDefault(key, 0)
                - currentPlan.getOrDefault(key, 0);
        return available >= input.count() ? Map.of(key, input.count()) : null;
    }

    /**
     * 最多能连续合成几次。
     *
     * <p>照 NSUK {@code IndustrialInputPlanner.availableCraftCount}（`:47-65`）：
     * <ul>
     *   <li>输入表为空 ⇒ **0**（这一步永远做不出来；以前我们返回 1，等于凭空合成一次）；</li>
     *   <li>一个可消耗项都没有（全是 {@code consume:false}）⇒ 最多 **1** 次；</li>
     *   <li>否则二分找最大次数，上限 {@value #MAX_AVAILABLE_CRAFTS}（NSUK 的
     *       {@code MAX_AVAILABLE_CRAFTS}；以前我们硬编码 64）。</li>
     * </ul>
     */
    static int availableCraftCount(SimInventory inventory, String container, List<SimInput> inputs) {
        List<SimInput> flat = flatten(inputs);
        if (flat.isEmpty()) {
            return 0;
        }
        if (flat.stream().noneMatch(SimInput::consume)) {
            return planConsumption(inventory, container, inputs, 1) == null ? 0 : 1;
        }
        int high = Math.min(MAX_AVAILABLE_CRAFTS, craftCeiling(inventory, container, flat));
        int low = 0;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (planConsumption(inventory, container, inputs, middle) != null) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** NSUK 的 {@code MAX_AVAILABLE_CRAFTS}。 */
    static final int MAX_AVAILABLE_CRAFTS = 4096;

    /** 二分上界：最稀缺的那种可消耗材料够几次（至少 1，免得二分区间是空的）。 */
    private static int craftCeiling(SimInventory inventory, String container, List<SimInput> flat) {
        int ceiling = MAX_AVAILABLE_CRAFTS;
        for (SimInput input : flat) {
            if (!input.consume() || input.spec().key().isBlank() || input.count() <= 0) {
                continue;
            }
            int available = inventory.count(container, input.spec().key());
            ceiling = Math.min(ceiling, available / input.count());
        }
        return Math.max(1, ceiling);
    }

    private static List<SimInput> flatten(List<SimInput> inputs) {
        List<SimInput> result = new ArrayList<>();
        for (SimInput input : inputs) {
            if (input.logic() == SimInput.Logic.ITEM) {
                result.add(input);
            } else {
                result.addAll(flatten(input.children()));
            }
        }
        return result;
    }

    private static String describeInputs(List<SimInput> inputs) {
        List<String> parts = new ArrayList<>();
        for (SimInput input : flatten(inputs)) {
            parts.add(input.spec().display() + " x" + input.count());
        }
        return parts.isEmpty() ? "（配方没有定义输入）" : String.join(" + ", parts);
    }
}
