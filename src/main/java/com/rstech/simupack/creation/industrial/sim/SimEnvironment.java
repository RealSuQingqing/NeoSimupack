package com.rstech.simupack.creation.industrial.sim;

/**
 * 一轮仿真要用到的「客户端世界」—— 三样东西打成一包，免得 {@code run(...)} 的参数越加越长。
 *
 * @param loot     动物 / 剪毛 / 繁殖食物 / 方块破坏的产出（真实战利品表）
 * @param blocks   按坐标查方块（成簇收获用）
 * @param workArea 成簇收获的作业范围
 */
public record SimEnvironment(SimLootProvider loot, SimBlockMatcher blocks, SimWorkArea workArea) {

    /** 什么都不给：单测、或数据还没接上时用。 */
    public static final SimEnvironment NONE =
            new SimEnvironment(SimLootProvider.NONE, SimBlockMatcher.NONE, SimWorkArea.UNKNOWN);

    public SimEnvironment {
        loot = loot == null ? SimLootProvider.NONE : loot;
        blocks = blocks == null ? SimBlockMatcher.NONE : blocks;
        workArea = workArea == null ? SimWorkArea.UNKNOWN : workArea;
    }

    /** 只要产出、不要方块查询的简写（现有的老调用点用这个）。 */
    public static SimEnvironment of(SimLootProvider loot) {
        return new SimEnvironment(loot, SimBlockMatcher.NONE, SimWorkArea.UNKNOWN);
    }
}
