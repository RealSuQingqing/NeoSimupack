package com.rstech.simupack.creation.industrial.sim;

/**
 * 移动方式，与 NSUK 的 {@code MovementMode} 一一对应。
 *
 * <p>它不是装饰信息：客户端要靠它决定播哪段动画（走 / 跑 / 起跳 / 下落 / 爬梯 / 游泳），
 * 以及要不要在两点之间补一条抛物线。
 */
public enum SimMotion {
    WALK,
    RUN,
    JUMP,
    FALL,
    CLIMB,
    SWIM,
    SWIM_EXIT;

    /** 竖直方向的动作（不摆腿，改播攀爬 / 下落姿势）。 */
    public boolean vertical() {
        return this == JUMP || this == FALL || this == CLIMB || this == SWIM || this == SWIM_EXIT;
    }

    /** 是否需要在这段位移里画抛物线。 */
    public boolean arc() {
        return this == JUMP || this == FALL;
    }
}
