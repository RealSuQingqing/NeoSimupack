package com.rstech.simupack.creation.industrial.sim;

import java.util.List;
import java.util.Random;

/**
 * 动物产出的来源 —— 就是**真实的战利品表**（{@code IntegratedServerLootProvider}）。
 *
 * <p>为什么这里是个接口而不是直接调：{@code SimulationRunner} 的 91 条单测在普通 JUnit 里跑，
 * 没有 Minecraft 运行时，直接调 {@code Minecraft.getInstance()} 会 NPE。
 * 所以留**一个方法缝**，让客户端把真数据塞进来 —— 只有这一个理由，没有别的设计意图。
 *
 * <p><b>不提供任何内置掉落数据</b>：以前这里挂过一张手写的近似表（羊/牛/猪/鸡/兔），
 * 那是过度防御 —— 手写表覆盖不到模组生物，而模组生物恰恰是最需要真数据的地方。
 * 现在查不到就是查不到，调用方标注「需要真实世界」，不编数字。
 */
public interface SimLootProvider {

    /** 一种产物：物品 id + 数量。 */
    record Product(String item, int count) {
    }

    /**
     * 屠宰 {@code count} 只这种动物的产物（每只单独掷一次区间，返回的是**总数**）。
     *
     * @return 空表 = 查不到（实体没注册、没有战利品表、拿不到服务器）
     */
    List<Product> drops(String entityType, int count, Random random);

    /**
     * 剪毛产物。
     *
     * <p>剪毛**不是战利品表**：{@code Sheep.shear()} 里是硬编码的
     * {@code 1 + random.nextInt(3)} 个羊毛，颜色取自实体数据，所以只能按实体规则来。
     */
    List<Product> shear(String entityType, int count, Random random);

    /**
     * 繁殖时这种动物认哪些食物。
     *
     * <p>这一项读的是**真实物品标签**（{@code ItemTags.COW_FOOD} 之类），
     * 标签是同步到客户端的，所以不需要服务器，而且模组添加的食物也算在内。
     *
     * @return 空表 = 不认识这种动物
     */
    List<String> food(String entityType);

    /**
     * 破坏某个坐标上的方块之后掉什么（**真实方块战利品表**）。
     *
     * <p>为什么按坐标问而不是按方块 id：预览世界里的方块状态只有客户端知道
     * （而且它在播放过程中会变 —— 前面刚放下的方块后面又被敲掉）。
     * 原版的算法是 {@code BlockStateBase.getDrops}：{@code BLOCK_STATE} + {@code ORIGIN}
     * + {@code TOOL} 建 {@code LootContextParamSets.BLOCK}，再去查方块自己的战利品表。
     *
     * @return {@code null} = **算不出来**（没有预览世界 / 没有服务器）；
     *         空表 = 这个方块**真的不掉东西**（例如玻璃、或者没有战利品表）——
     *         两者含义不同，调用方要分开处理
     */
    List<Product> blockDrops(int x, int y, int z, Random random);

    /**
     * 按**方块 id** 求「挖掉它掉什么」。
     *
     * <p>为什么需要按 id 问：配方经常先 {@code place_block} 把方块放下去、过几步再挖掉
     * （砖厂：放泥土 → 泥巴 → 黏土 → 铁锹挖），而那些位置在**结构文件里本来就是空气**。
     * 按坐标去问预览世界只会得到「空气，不掉东西」，于是掉落凭空消失、
     * 后面的 {@code require_drops} 永远判 MISSING_DROPS。
     *
     * @param blockId      仿真自己放下去的方块 id（如 {@code minecraft:clay}）
     * @param toolSelector 工具物品 id；空串 = 空手
     * @param enchantments 工具的附魔（{@code minecraft:fortune=3}）；空串 = 无
     * @return 掉落表；{@code null} = 算不出来（客户端不认识这个方块）
     */
    default List<Product> blockDropsForBlock(String blockId, String toolSelector, String enchantments,
                                             Random random) {
        return null;
    }

    /**
     * 同上，但**拿着工具**破坏。
     *
     * <p>为什么要单独一个方法：精准采集、时运这类附魔是在战利品表求值时靠
     * {@code LootContextParams.TOOL} 生效的 —— 空手和拿着时运 III 的镐，
     * 同一块矿石的掉落完全不同。
     *
     * @param toolSelector 工具的物品 id（空串 = 空手）。真实实现里「工人主手优先，
     *                     否则用步骤的 {@code item}」，和这里的取值一致
     */
    default List<Product> blockDrops(int x, int y, int z, String toolSelector, Random random) {
        return blockDrops(x, y, z, random);
    }

    /**
     * 同上，但工具**带附魔**。
     *
     * <p>NSUK 是把附魔真的附到 ItemStack 上再当 {@code LootContextParams.TOOL} 用的
     * （{@code IndustrialItemStackSpec:132-160}），所以时运 III 和空手的掉落不同。
     * 只传物品 id 时附魔就丢了 —— 这一层就是为了把它带到客户端。
     *
     * @param enchantments 归一化的 {@code minecraft:fortune=3,minecraft:silk_touch=1}；空串 = 无附魔
     */
    default List<Product> blockDrops(int x, int y, int z, String toolSelector, String enchantments,
                                     Random random) {
        return blockDrops(x, y, z, toolSelector, random);
    }

    /**
     * **虚拟收获**用：按选择器（方块 id 或 {@code #标签}）求「一个这种方块掉什么」。
     *
     * <p>为什么单独一个方法：虚拟收获发生在「工作区里一个匹配的方块都没有」的时候，
     * 那时**没有坐标可用**。而选择器往往是标签（{@code #minecraft:logs}），
     * 内核认不出标签里有哪几种方块 —— 客户端认得，所以由它挑一个成员方块再查它的战利品表。
     *
     * <p>这样掉落的**种类**仍然是真实的（砍橡木得橡木、收小麦得小麦和种子），
     * 只有**数量**是估算的（见内核里的虚拟收获注释）。
     *
     * @return {@code null} = 算不出来（没有客户端数据 / 认不出这个选择器）
     */
    default List<Product> virtualBlockDrops(String selector, Random random) {
        return null;
    }

    /**
     * 这个物品每格最多堆几个。
     *
     * <p>容器容量是「格数」，占用几格要按**物品自己的堆叠上限**折算（原版
     * {@code GenericContainerAccess.simulateInsert} 走的就是真实槽位 + {@code getMaxStackSize}）：
     * 64 根原木占 1 格，而 64 把不可堆叠的镐占 64 格。内核拿不到物品元数据，
     * 所以由客户端注入；默认 64（原版多数物品）。
     */
    default int maxStackSize(String itemId) {
        return 64;
    }

    /**
     * 这个物品匹不匹配选择器（物品 id 或 {@code #标签}）。
     *
     * <p>给「补种」用：{@code plantItemTag} 是个**物品标签**，要和背包/容器里的具体物品对上号。
     * 物品标签是同步到客户端的，所以这一项也只问客户端、不用服务器。
     *
     * @return {@code null} = 查不到（没有客户端数据）
     */
    default Boolean itemMatches(String selector, String itemId) {
        return null;
    }

    /**
     * 这个物品放下去会变成哪个方块 id（例如 {@code minecraft:oak_sapling} → {@code minecraft:oak_sapling}）。
     *
     * @return {@code null} = 不是方块物品，或查不到
     */
    default String placedBlock(String itemId) {
        return null;
    }

    /**
     * 什么都不给的实现：不注入产出源时用（单测、或还没接上数据）。
     *
     * <p>注意这里返回的是 {@code null}（= **算不出来**），不是空表（= 真的不掉东西）：
     * 两者的处理完全不同 —— 前者标「需要真实世界」，后者照常往下走。
     */
    SimLootProvider NONE = new SimLootProvider() {
        @Override
        public List<Product> drops(String entityType, int count, Random random) {
            return null;
        }

        @Override
        public List<Product> shear(String entityType, int count, Random random) {
            return List.of();
        }

        @Override
        public List<String> food(String entityType) {
            return List.of();
        }

        @Override
        public List<Product> blockDrops(int x, int y, int z, Random random) {
            // null = 算不出来（不是「不掉东西」）。内核据此标注「需要真实世界」。
            return null;
        }
    };
}
