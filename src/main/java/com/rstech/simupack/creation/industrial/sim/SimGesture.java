package com.rstech.simupack.creation.industrial.sim;

/**
 * 某一步里市民在做什么，供客户端挑动画。
 *
 * <p>放在内核而不是客户端，是因为它<b>本来就是步骤语义的一部分</b>：
 * NSUK 的 {@code use_item} 会 {@code triggerWorkSwing}、{@code inspect_container} 会把
 * 容器开关一次、{@code craft_recipe} 是持续作业。客户端只负责把这些翻译成手臂角度，
 * 不需要再抄一遍「哪个 type 算挥手」这张表。
 */
public enum SimGesture {
    /** 什么都不做（站着、设状态、设手持物）。 */
    IDLE,
    /** 走路。 */
    WALK,
    /** 挥手使用物品（{@code use_item}）。 */
    SWING,
    /** 打开 / 查看容器（{@code inspect_container}）。 */
    OPEN_CONTAINER,
    /** 生成物品放进容器（{@code insert_item}）。 */
    INSERT,
    /** 把随身物品放进容器（{@code deposit_carried_items}）。 */
    DEPOSIT,
    /** 合成 / 连续合成：持续作业。 */
    CRAFT,
    /** 放置方块或流体。 */
    PLACE,
    /** 破坏方块 / 收割。 */
    BREAK,
    /** 与实体交互（繁殖 / 屠宰 / 剪毛 / 拾取）。 */
    ENTITY,
    /** 看向某个点位 / 容器。 */
    LOOK,
    /** 真实机器加工：持续作业。 */
    MACHINE;

    /** 需要持续摆动主手的动作（作业类）。 */
    public boolean swinging() {
        return this == SWING || this == CRAFT || this == BREAK || this == MACHINE || this == PLACE;
    }

    /** 需要伸手到前方（容器交互类）。 */
    public boolean reaching() {
        return this == OPEN_CONTAINER || this == INSERT || this == DEPOSIT;
    }
}
