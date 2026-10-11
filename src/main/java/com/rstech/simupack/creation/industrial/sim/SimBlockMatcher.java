package com.rstech.simupack.creation.industrial.sim;

/**
 * 成簇收获需要知道的「世界」：某个坐标上是什么方块、工作区里哪些坐标有方块。
 *
 * <p>内核不依赖 Minecraft 类型（单测要能跑），所以按坐标问客户端 —— 后者手里有预览世界。
 * 和 {@link SimLootProvider} 是同一套理由、同一套做法。
 *
 * <p>为什么要有 {@link #candidates}：成簇收获要在工作区里找「匹配某个标签的方块」，
 * 而工作区可能有 200 多格半径（几十万格），不可能逐格问。
 * 预览世界的方块本来就存在一张表里，让客户端直接把「有方块的坐标」交出来最省事。
 */
public interface SimBlockMatcher {

    /**
     * 这个坐标上的方块匹不匹配选择器。
     *
     * @param selector 方块 id（{@code minecraft:oak_log}）或标签（{@code #minecraft:logs}）
     * @return {@code null} = **查不到**（没有预览世界 / 认不出这个选择器）；
     *         {@code true}/{@code false} = 明确答案
     */
    Boolean matches(String selector, SimPos pos);

    /** 工作区里所有**有方块**的坐标（空气不在内）。 */
    java.util.List<SimPos> candidates(SimWorkArea area);

    /** 什么都不给的实现：查不到、也没有候选。内核据此把这一步标注成「需要真实世界」。 */
    SimBlockMatcher NONE = new SimBlockMatcher() {
        @Override
        public Boolean matches(String selector, SimPos pos) {
            return null;
        }

        @Override
        public java.util.List<SimPos> candidates(SimWorkArea area) {
            return java.util.List.of();
        }
    };
}
