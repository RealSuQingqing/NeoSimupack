package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rstech.simupack.creation.industrial.IndustrialStepType;

import java.util.List;

/**
 * 一条已经归一化好的动作步骤（{@code repeat} 已经被展开成扁平列表里的一条）。
 *
 * <p><b>为什么要有这个类：</b>旧版仿真器直接抱着原始 {@code JsonObject} 现读现用，
 * 于是同一份 JSON 被三处代码用三套规则各解析一遍（仿真器、面板时间轴、面板高亮），
 * 三套规则互不一致：仿真器读 {@code ticks}，面板按 {@code result.steps()} 的下标去索引
 * <b>原始</b> {@code steps[]}，而 {@code result.steps()} 因为展开了 repeat 又丢掉了 repeat 节点，
 * 下标从一开始就对不上 —— 时间轴指到第 3 条，高亮的是第 5 条。
 *
 * <p>这里把「读一条步骤」收敛成一个类，取值规则与 NSUK 的
 * {@code IndustrialDefinitionLoader.parseStepDefinition} 一一对应。
 */
public final class SimStepSpec {

    private final JsonObject json;
    private final String rawType;
    private final IndustrialStepType type;
    private final List<SimPos> positions;
    private final List<SimInput> inputs;
    private final List<SimOutputSpec> outputs;
    private final SimItemSpec itemSpec;
    private final List<SimItemSpec> itemSpecs;

    SimStepSpec(JsonObject json) {
        this.json = json;
        this.rawType = SimJson.string(json, "type", "");
        this.type = IndustrialStepType.byRawType(this.rawType);
        this.positions = SimJson.positions(json);
        JsonArray inputArray = SimJson.arrayAny(json, "inputs");
        this.inputs = inputArray == null ? null : SimInput.parseList(inputArray);
        JsonArray outputArray = SimJson.arrayAny(json, "outputs");
        this.outputs = outputArray == null ? null : parseOutputs(outputArray);
        this.itemSpec = SimItemSpec.parse(json);
        this.itemSpecs = SimItemSpec.parseList(SimJson.arrayAny(json, "items", "itemSpecs"));
    }

    private static List<SimOutputSpec> parseOutputs(JsonArray array) {
        List<SimOutputSpec> result = new java.util.ArrayList<>();
        for (JsonObject object : SimJson.objects(array)) {
            SimOutputSpec spec = SimOutputSpec.parse(object);
            if (spec != null) {
                result.add(spec);
            }
        }
        return List.copyOf(result);
    }

    /** 原始的、可能被 repeat 展开器补过 {@code pos} 的步骤对象。 */
    public JsonObject json() {
        return json;
    }

    public String rawType() {
        return rawType;
    }

    /** 未识别的步骤类型返回 {@code null}。 */
    public IndustrialStepType type() {
        return type;
    }

    public boolean known() {
        return type != null;
    }

    public String typeId() {
        return type != null ? type.canonical() : rawType;
    }

    public String typeLabelKey() {
        return type != null ? type.translationKey() : "";
    }

    public List<SimPos> positions() {
        return positions;
    }

    public String point() {
        return SimJson.string(json, "point", "");
    }

    /**
     * 容器名。
     *
     * <p>{@code primary}/{@code secondary} 的顺序是 NSUK 的 {@code containerName(primary, secondary, fallback)}
     * —— 每种步骤的「一个容器、两个候选字段名」不一样，抄错就会把输出写进输入箱。
     */
    public String container(String primary, String secondary, String fallback) {
        String value = SimJson.string(json, primary, "");
        if (!value.isBlank()) {
            return value;
        }
        value = SimJson.string(json, secondary, "");
        return value.isBlank() ? fallback : value;
    }

    /**
     * 「这一步针对的是哪个容器」。
     *
     * <p>照抄 NSUK 的 {@code IndustrialWorkService.targetContainerName}：
     * {@code container} → {@code input} → {@code output} → 兜底。
     * {@code move_to_container} 只认这一个容器 —— 而不是「这一步引用到的所有容器」，
     * 后者会让市民跑到另一个箱子上站着。
     */
    public String targetContainer(String fallback) {
        for (String key : List.of("container", "input", "output")) {
            String value = SimJson.string(json, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    public String statusKey() {
        return SimJson.string(json, "statusKey", "");
    }

    public String statusText() {
        return SimJson.string(json, "statusText", "");
    }

    public String block() {
        return SimJson.string(json, "block", "");
    }

    public String fluid() {
        return SimJson.stringAny(json, "", "fluid", "liquid");
    }

    public String entityType() {
        return SimJson.stringAny(json, "", "entityType", "entity");
    }

    public String outputPolicy() {
        return SimJson.stringAny(json, "extract_to_output", "outputPolicy", "output_policy");
    }

    public int count() {
        return Math.max(0, SimJson.integer(json, "count", 0));
    }

    public double radius() {
        return Math.max(0.5, SimJson.decimal(json, "radius", 6.0));
    }

    public double range() {
        return Math.max(0.1, SimJson.decimal(json, "range", 1.5));
    }

    public boolean swing() {
        return SimJson.bool(json, "swing", false);
    }

    public boolean consume() {
        return SimJson.bool(json, "consume", false);
    }

    public boolean replace() {
        return SimJson.bool(json, "replace", false);
    }

    public boolean dropItems() {
        return SimJson.bool(json, "dropItems", SimJson.bool(json, "drop", false));
    }

    // ── 成簇收获（harvest_block_clusters）─────────────────────────────
    //
    // 这几个字段 schema 里一直存在，但内核以前一处都没解析（整个 sim 包里搜不到），
    // 所以这一步只能笼统标注「需要真实世界」。现在要真的算它，就得先把它们读出来。
    // 别名照 IndustrialStepType:40 的声明抄。

    /** 主体方块（原木）。**必填**：空或认不出的 id ⇒ 这一步无效。 */
    public String targetBlockTag() {
        return SimJson.stringAny(json, "", "targetBlockTag", "target_block_tag", "blockTag", "block_tag");
    }

    /** 附着物（栅栏、藤蔓…）：只参与「够不够 minAttachedBlocks」和「一起砍掉」。 */
    public String attachedBlockTag() {
        return SimJson.stringAny(json, "", "attachedBlockTag", "attached_block_tag");
    }

    /** 支撑物：决定哪一层算「树根」（补种落点）。留空时退化成「下方非空气」。 */
    public String supportBlockTag() {
        return SimJson.stringAny(json, "", "supportBlockTag", "support_block_tag");
    }

    /** 补种用什么（物品标签）。留空 = 不补种。 */
    public String plantItemTag() {
        return SimJson.stringAny(json, "", "plantItemTag", "plant_item_tag");
    }

    /** 附着物至少要有几个才收这一簇（注意：{@code attachedBlockTag} 留空时恒为 0 个 ⇒ 永远拒收）。 */
    public int minAttachedBlocks() {
        return Math.max(0, SimJson.integer(json, "minAttachedBlocks",
                SimJson.integer(json, "min_attached_blocks", 0)));
    }

    /**
     * 一簇最多几个方块。
     *
     * <p>对主体方块是**整簇跳过**（{@code size >= max} 就不收，不部分收），对附着物才是截断 ——
     * 和真实实现一致（NSUK {@code IndustrialBlockClusterHarvestService}）。
     */
    public int maxClusterBlocks() {
        return Math.max(1, SimJson.integer(json, "maxClusterBlocks",
                SimJson.integer(json, "max_cluster_blocks", 96)));
    }

    /** 背包最多占几格。真实实现比的是**占用格数**，而且背包只有 14 格，所以写 18 是达不到的。 */
    public int maxCarryStacks() {
        return Math.max(1, SimJson.integer(json, "maxCarryStacks",
                SimJson.integer(json, "max_carry_stacks", 18)));
    }

    /**
     * {@code breed_entities} 的「繁殖要不要消耗食物」（{@code requireFood}，别名 {@code requiresFood}）。
     *
     * <p>schema 里这个字段一直存在，但内核以前一处都没读它 —— 于是「繁殖要喂东西」这件事
     * 在仿真里完全没体现。默认 true，和字段定义一致。
     */
    public boolean requireFood() {
        return SimJson.bool(json, "requireFood", SimJson.bool(json, "requiresFood", true));
    }

    public int timeoutTicks() {
        return Math.max(1, SimJson.integer(json, "timeoutTicks", SimJson.integer(json, "timeout", 12000)));
    }

    /** {@code pollTicks}；别名 {@code poll}（NSUK IndustrialDefinitionLoader:355）。 */
    public int pollTicks() {
        return Math.max(1, SimJson.integer(json, "pollTicks", SimJson.integer(json, "poll", 20)));
    }

    /** {@code skipOnTimeout}；别名 {@code skip_on_timeout}（NSUK IndustrialDefinitionLoader:355）。 */
    public boolean skipOnTimeout() {
        return SimJson.bool(json, "skipOnTimeout", SimJson.bool(json, "skip_on_timeout", false));
    }

    public SimItemSpec itemSpec() {
        return itemSpec;
    }

    /**
     * 这一步「手上那件工具」的附魔，归一成 {@code minecraft:fortune=3} 这样的逗号串（没有就空串）。
     *
     * <p>为什么要它：NSUK 会把附魔**真的附到 ItemStack 上**再当
     * {@code LootContextParams.TOOL} 传给战利品表（{@code IndustrialItemStackSpec:117/:132-160}、
     * {@code IndustrialBlockClusterHarvestService:347}），所以时运 III 的镐和空手砍同一棵树，
     * 掉落完全不同。我们以前只把**裸物品 id** 传给客户端，附魔永远不生效。
     *
     * <p>键名照 NSUK：{@code enchantments} 与 {@code storedEnchantments}（附魔书）。
     */
    public String enchantments() {
        return enchantmentText(json, "enchantments", "storedEnchantments");
    }

    /** 把 {@code {enchantments: {"fortune": 3}}} 归一成 {@code minecraft:fortune=3}。 */
    static String enchantmentText(com.google.gson.JsonObject object, String... keys) {
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject map = object.getAsJsonObject(key);
            for (String name : map.keySet()) {
                int level;
                try {
                    level = map.get(name).getAsInt();
                } catch (RuntimeException exception) {
                    continue;
                }
                if (level <= 0) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(',');
                }
                String id = name.indexOf(':') < 0 ? "minecraft:" + name : name;
                text.append(id).append('=').append(level);
            }
        }
        return text.toString();
    }

    public List<SimItemSpec> itemSpecs() {
        return itemSpecs;
    }

    /** 步骤自带的 {@code inputs}；为 {@code null} 表示没写，用配方级的。 */
    public List<SimInput> ownInputs() {
        return inputs;
    }

    /** 步骤自带的 {@code outputs}；为 {@code null} 表示没写，用配方级的。 */
    public List<SimOutputSpec> ownOutputs() {
        return outputs;
    }

    /**
     * 步骤占用的 tick 数。
     *
     * <p>规则来自 NSUK 的实现，不是拍脑袋：
     * <ul>
     *   <li>{@code insert_item} 打开容器看 {@code ticks}，没写或 &le;1 时固定 12；
     *   <li>其余步骤 {@code max(1, ticks)}；
     *   <li>{@code move_to*} 的实际耗时由寻路决定，这里只给动作本身的 1 tick 下限。
     * </ul>
     */
    public int actionTicks() {
        int ticks = Math.max(1, SimJson.integer(json, "ticks", 1));
        if (type == IndustrialStepType.INSERT_ITEM) {
            return ticks > 1 ? ticks : 12;
        }
        return ticks;
    }

    /**
     * 步骤里**原样写的** {@code ticks}（没写就是 1，不做任何按类型的默认值替换）。
     *
     * <p>成簇收获要用它：NSUK 的 {@code stumpDigTicks} 先看 {@code step.ticks() > 1}
     * —— 配了就用它当「每个树桩挖几 tick」，没配才回去按方块硬度算
     * （{@code IndustrialBlockClusterHarvestService:436-453}）。用 {@link #actionTicks()}
     * 会把「没写」和「写了 1」混在一起，分不出该不该走硬度公式。
     */
    public int ticks() {
        return Math.max(1, SimJson.integer(json, "ticks", 1));
    }

    public boolean isMove() {
        return type == IndustrialStepType.MOVE_TO
                || type == IndustrialStepType.MOVE_TO_CONTAINER
                || type == IndustrialStepType.MOVE_TO_ENTITY;
    }

    /** 时间轴 / 日志里显示的一行字。 */
    public String label() {
        String canonical = typeId();
        return canonical.isBlank() ? "(missing type)" : canonical;
    }

    @Override
    public String toString() {
        return "SimStepSpec[" + typeId() + "]";
    }
}
