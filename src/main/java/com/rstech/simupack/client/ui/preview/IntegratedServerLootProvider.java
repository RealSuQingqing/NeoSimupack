package com.rstech.simupack.client.ui.preview;

import com.rstech.simupack.creation.industrial.sim.SimLootProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 动物产出 —— 直接用**客户端这一侧的注册表与战利品表**。
 *
 * <p>做法照抄原版 {@code LivingEntity.dropFromLootTable}（1.21.1）：
 *
 * <pre>
 *   LootTable table = server.reloadableRegistries().getLootTable(living.getLootTable());
 *   LootParams params = new LootParams.Builder(serverLevel)
 *       .withParameter(THIS_ENTITY, living).withParameter(ORIGIN, living.position())
 *       .withParameter(DAMAGE_SOURCE, serverLevel.damageSources().generic())
 *       .create(LootContextParamSets.ENTITY);
 *   table.getRandomItems(params, seed, output);
 * </pre>
 *
 * <p>三个关键点：
 * <ol>
 *   <li><b>按注册表查，不按名字猜</b>：{@code BuiltInRegistries.ENTITY_TYPE} + 实体实例的
 *       {@code getLootTable()}。所以**模组生物自动就有** —— 这是手写表永远做不到的事。
 *       顺带一提 {@code getLootTable()} 是实体实例的方法（不是「按类型查表」），
 *       因为 {@code Sheep} 重写了它、按毛色返回 {@code entities/sheep/white} 这类表。</li>
 *   <li><b>战利品表不同步到客户端</b>（{@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES}
 *       里没有 {@code Registries.LOOT_TABLE}），所以要向**集成服务器**要。创作空间没有多人，
 *       集成服务器总在同一进程里。</li>
 *   <li><b>攻击者是可选参数</b>（{@code LootContextParamSets.ENTITY} 里
 *       {@code ATTACKING_ENTITY} / {@code DIRECT_ATTACKING_ENTITY} 都是 optional），
 *       不给就是「无抢夺、无火焰附加」的自然掉落 —— 正是仿真要的。</li>
 * </ol>
 *
 * <p>查不到就返回空表（调用方标注「需要真实世界」），不编数字：
 * 实体没注册、没有战利品表、集成服务器不在，都属于这一类。
 */
@OnlyIn(Dist.CLIENT)
public final class IntegratedServerLootProvider implements SimLootProvider {

    /**
     * 预览世界 —— 破坏方块求掉落时要按坐标读「现在是什么方块」。
     *
     * <p>用 {@code Supplier} 而不是直接存一个引用：场景可能在运行中被换掉
     * （切换建筑 / 重新加载），拿的时候再去问它要最新的那个。
     */
    private final Supplier<Level> previewWorld;

    /** 动物 → 它认的食物标签。真实标签是同步的，所以这一项连服务器都不用问。 */
    private static final Map<String, TagKey<Item>> FOOD_TAGS = Map.ofEntries(
            Map.entry("minecraft:cow", ItemTags.COW_FOOD),
            Map.entry("minecraft:mooshroom", ItemTags.COW_FOOD),
            Map.entry("minecraft:sheep", ItemTags.SHEEP_FOOD),
            Map.entry("minecraft:pig", ItemTags.PIG_FOOD),
            Map.entry("minecraft:chicken", ItemTags.CHICKEN_FOOD),
            Map.entry("minecraft:rabbit", ItemTags.RABBIT_FOOD));

    public IntegratedServerLootProvider(Supplier<Level> previewWorld) {
        this.previewWorld = previewWorld;
    }

    @Override
    public List<Product> drops(String entityType, int count, Random random) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            // 算不出来（没进单人世界）：**null 表示「不知道」**，不是「不掉东西」。
            // 这个区分很要紧：屠宰那步以前把「空表」当「掉落表未知」，
            // 于是整步按「假定成功」继续，后面 require_drops/collect_drops 全空跑。
            return null;
        }
        Entity sample = sampleEntity(server, entityType);
        if (!(sample instanceof LivingEntity living)) {
            return null;
        }
        LootTable table;
        try {
            table = server.reloadableRegistries().getLootTable(living.getLootTable());
        } catch (RuntimeException exception) {
            return null;
        }
        if (table == LootTable.EMPTY) {
            // 战利品表就是空的 = 这种实体**真的**不掉东西。
            return List.of();
        }
        ServerLevel level = server.overworld();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, living)
                .withParameter(LootContextParams.ORIGIN, living.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
                .create(LootContextParamSets.ENTITY);

        int animals = Math.max(1, count);
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (int index = 0; index < animals; index++) {
            // 每只单独掷一次：一次 getRandomItems 只对应一只动物的掉落。
            // 种子取自内核的随机源（不是 level.random）—— 重播同一轮仿真掉落一致。
            table.getRandomItems(params, random.nextLong(), stack -> {
                if (!stack.isEmpty()) {
                    totals.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
                }
            });
        }
        return toProducts(totals);
    }

    /**
     * 剪毛。
     *
     * <p>这一项**没有战利品表可用**（{@code BuiltInLootTables} 里只有 {@code BOGGED_SHEAR}）：
     * {@code Sheep.shear()} 里是硬编码的 {@code 1 + random.nextInt(3)} 个羊毛，颜色取自实体的
     * {@code DATA_WOOL_ID}。所以照那条规则来 —— 区别只是把私有的 {@code ITEM_BY_DYE}
     * 换成按 {@link DyeColor} 名字拼出来的 {@code <颜色>_wool}（16 种羊毛物品恰好一一对应）。
     */
    @Override
    public List<Product> shear(String entityType, int count, Random random) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        Entity sample = server == null ? null : sampleEntity(server, entityType);
        if (!(sample instanceof Sheep sheep)) {
            return List.of();
        }
        Item wool = woolOf(sheep.getColor());
        if (wool == null) {
            return List.of();
        }
        int animals = Math.max(1, count);
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (int index = 0; index < animals; index++) {
            totals.merge(itemId(wool), 1 + random.nextInt(3), Integer::sum);
        }
        return toProducts(totals);
    }

    @Override
    public List<String> food(String entityType) {
        TagKey<Item> tag = FOOD_TAGS.get(normalize(entityType));
        if (tag == null) {
            return List.of();
        }
        List<String> foods = new ArrayList<>();
        BuiltInRegistries.ITEM.getTag(tag).ifPresent(holders ->
                holders.forEach(holder -> foods.add(itemId(holder.value()))));
        return List.copyOf(foods);
    }

    /**
     * 破坏方块掉什么 —— 照抄原版 {@code BlockStateBase.getDrops} 的做法：
     * {@code BLOCK_STATE} + {@code ORIGIN} + {@code TOOL} 建 {@code LootContextParamSets.BLOCK}，
     * 再查**方块自己的**战利品表。
     *
     * <p>两个「世界」在这里各司其职：**方块状态**来自预览世界（那个坐标上现在是什么），
     * 而 {@code LootParams} 必须用真正的 {@code ServerLevel}（只有它才拿得到战利品表注册表）。
     * 掉落只读方块状态与工具，不读所在世界，所以这样拼是成立的 —— 原版也是这么切的。
     */
    @Override
    public List<Product> blockDrops(int x, int y, int z, Random random) {
        return blockDrops(x, y, z, "", random);
    }

    /**
     * 带工具的破坏掉落 —— 精准采集、时运就是靠 {@code TOOL} 参数在战利品表里生效的。
     *
     * @param toolSelector 工具物品 id；空串 = 空手。写成 {@code #标签} 时无法确定具体工具，
     *                     按空手算（宁可保守，也不要拿错工具算出一个假的时运加成）
     */
    @Override
    public List<Product> blockDrops(int x, int y, int z, String toolSelector, String enchantments,
                                    Random random) {
        BlockPos pos = new BlockPos(x, y, z);
        return dropsOf(pos, null, toolSelector, enchantments, random);
    }

    /**
     * 按**方块 id** 求掉落 —— 用于「配方自己放下去、结构文件里却是空气」的方块。
     *
     * <p>砖厂就是活例子：第 13 步 {@code place_block} 把黏土放到 {@code clay_bed}（那片在结构里是空气），
     * 第 19 步才挖。按坐标问预览世界只会得到「空气不掉东西」，黏土球凭空消失、
     * 后面的 {@code require_drops} 永远失败。这里按 id 造一个默认方块状态来求战利品表。
     *
     * @param blockId 方块 id；认不出返回 {@code null}（= 算不出来，不是「不掉东西」）
     */
    @Override
    public List<Product> blockDropsForBlock(String blockId, String toolSelector, String enchantments,
                                            Random random) {
        ResourceLocation id = ResourceLocation.tryParse(blockId == null ? "" : blockId.trim());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        // 位置用原点：战利品表里跟坐标有关的只有极少数（精准采集/时运只看 TOOL），
        // 而这里的坐标本来就不在结构里。
        return dropsOf(BlockPos.ZERO, BuiltInRegistries.BLOCK.get(id).defaultBlockState(),
                toolSelector, enchantments, random);
    }

    /**
     * 求一个方块状态的掉落。
     *
     * @param explicit 非空则直接用这个状态（按 id 查时用）；为空则按坐标读世界
     */
    private List<Product> dropsOf(BlockPos pos, BlockState explicit, String toolSelector,
                                  String enchantments, Random random) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        Level world = this.previewWorld == null ? null : this.previewWorld.get();
        if (server == null || world == null) {
            // null = 算不出来，和「不掉东西」不是一回事。
            return null;
        }
        BlockState state = explicit != null ? explicit : world.getBlockState(pos);
        ResourceKey<LootTable> key = state.getBlock().getLootTable();
        if (key == BuiltInLootTables.EMPTY) {
            // 没有战利品表 = 原版语义下就是不掉东西（玻璃、屏障这类）。
            return List.of();
        }
        ServerLevel level = server.overworld();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, toolStack(toolSelector, enchantments))
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .create(LootContextParamSets.BLOCK);
        LootTable table = server.reloadableRegistries().getLootTable(key);
        Map<String, Integer> totals = new LinkedHashMap<>();
        table.getRandomItems(params, random.nextLong(), stack -> {
            if (!stack.isEmpty()) {
                totals.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
            }
        });
        return toProducts(totals);
    }

    /** 造一个只用来问「你掉什么 / 你认什么食物」的样本实体；不进世界、不 tick。 */
    private static Entity sampleEntity(MinecraftServer server, String entityType) {
        ResourceLocation id = ResourceLocation.tryParse(normalize(entityType));
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        // 1.21.1 只有 create(Level)；特性开关关着时会返回 null。
        return type.create(server.overworld());
    }

    private static Item woolOf(DyeColor color) {
        ResourceLocation id = ResourceLocation.withDefaultNamespace(color.getName() + "_wool");
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : null;
    }

    /** 工具选择器 → 物品堆。空串或标签都按空手算（拿不准就不假装有附魔）。 */
    private static ItemStack toolStack(String toolSelector) {
        return toolStack(toolSelector, "");
    }

    /**
     * 工具选择器 + 附魔 → 物品堆。
     *
     * <p>附魔必须**真的附上去**：时运/精准采集是在战利品表求值时读
     * {@code LootContextParams.TOOL} 上的附魔生效的（NSUK 也是这么做的，
     * `IndustrialItemStackSpec:132-160`）。只给裸 id 的话时运永远不作用。
     *
     * @param enchantments {@code minecraft:fortune=3,minecraft:silk_touch=1}；空串 = 无附魔
     */
    private static ItemStack toolStack(String toolSelector, String enchantments) {
        if (toolSelector == null || toolSelector.isBlank() || toolSelector.startsWith("#")) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(toolSelector);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        applyEnchantments(stack, enchantments);
        return stack;
    }

    /** 把 {@code id=level,id=level} 附到堆上；认不出的附魔跳过（一个拼错的名字不该毁掉整轮仿真）。 */
    private static void applyEnchantments(ItemStack stack, String enchantments) {
        if (enchantments == null || enchantments.isBlank()) {
            return;
        }
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return;
        }
        var registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (String entry : enchantments.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            ResourceLocation enchantId = ResourceLocation.tryParse(entry.substring(0, separator).trim());
            int level;
            try {
                level = Integer.parseInt(entry.substring(separator + 1).trim());
            } catch (NumberFormatException exception) {
                continue;
            }
            if (enchantId == null || level <= 0) {
                continue;
            }
            registry.get(ResourceKey.create(Registries.ENCHANTMENT, enchantId))
                    .ifPresent(holder -> stack.enchant(holder, level));
        }
    }

    /**
     * 虚拟收获：按选择器求「一个这种方块掉什么」。
     *
     * <p>选择器是标签时（{@code #minecraft:logs}），从**真实标签内容**里挑一个成员方块 ——
     * 内核认不出标签，客户端认得。挑到的方块查它自己的战利品表，所以掉落的**种类**是真的，
     * 只有数量由内核估算（虚拟收获那里没有坐标、也没有真实方块）。
     *
     * <p>坐标参数只能给 {@code ORIGIN = 0}：战利品表里真正依赖坐标的只有极少数函数
     * （爆炸、精准采集的方块实体之类），而且这里根本不知道方块在哪。
     */
    @Override
    public List<Product> virtualBlockDrops(String selector, Random random) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || selector == null || selector.isBlank()) {
            return null;
        }
        BlockState state = null;
        if (selector.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(selector.substring(1));
            if (tagId == null) {
                return null;
            }
            var holders = BuiltInRegistries.BLOCK.getTag(
                    TagKey.create(Registries.BLOCK, tagId)).orElse(null);
            if (holders == null) {
                return null;
            }
            for (net.minecraft.core.Holder<Block> holder : holders) {
                state = holder.value().defaultBlockState();
                break;
            }
        } else {
            ResourceLocation id = ResourceLocation.tryParse(selector);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
            }
        }
        if (state == null) {
            return null;
        }
        ResourceKey<LootTable> key = state.getBlock().getLootTable();
        if (key == BuiltInLootTables.EMPTY) {
            // 没有战利品表 = 原版语义下不掉东西。虚拟收获也不能凭空变出来。
            return List.of();
        }
        ServerLevel level = server.overworld();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .create(LootContextParamSets.BLOCK);
        LootTable table = server.reloadableRegistries().getLootTable(key);
        Map<String, Integer> totals = new LinkedHashMap<>();
        table.getRandomItems(params, random.nextLong(), stack -> {
            if (!stack.isEmpty()) {
                totals.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
            }
        });
        return toProducts(totals);
    }

    /**
     * 这个物品每格最多堆几个 —— 容器容量按**格**算，占用几格取决于它。
     *
     * <p>不可堆叠物（工具、盔甲）是 1：一个 27 格的箱子只能放 27 把，而不是 27×64 把。
     */
    @Override
    public int maxStackSize(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            // 认不出就退回默认堆叠上限，别假装它是 1（那会把容量判得过于悲观）。
            return 64;
        }
        // NeoForge 1.21 的 getMaxStackSize 要带 stack 参数，所以包一层 ItemStack 问它。
        return Math.max(1, new ItemStack(BuiltInRegistries.ITEM.get(id)).getMaxStackSize());
    }
    /**
     * 物品匹不匹配选择器（物品 id 或 {@code #标签}）—— 补种要用它把 {@code plantItemTag}
     * 和背包/容器里的具体物品对上号。物品标签是同步到客户端的，所以不用问服务器。
     */
    @Override
    public Boolean itemMatches(String selector, String itemId) {
        if (selector == null || selector.isBlank() || itemId == null || itemId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (selector.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(selector.substring(1));
            if (tagId == null) {
                return null;
            }
            // 标签不存在时原版语义是「永不匹配」。
            return item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tagId));
        }
        return itemId.equals(selector);
    }

    /**
     * 这个物品放下去会变成哪个方块（补种要知道树苗对应哪个方块）。
     *
     * @return {@code null} = 不是方块物品
     */
    @Override
    public String placedBlock(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item instanceof BlockItem blockItem) {
            return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
        }
        return null;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static List<Product> toProducts(Map<String, Integer> totals) {
        if (totals.isEmpty()) {
            return List.of();
        }
        List<Product> products = new ArrayList<>(totals.size());
        totals.forEach((item, amount) -> {
            if (amount > 0) {
                products.add(new Product(item, amount));
            }
        });
        return List.copyOf(products);
    }

    private static String normalize(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return "";
        }
        String value = entityType.trim();
        return value.indexOf(':') < 0 ? "minecraft:" + value : value;
    }
}
