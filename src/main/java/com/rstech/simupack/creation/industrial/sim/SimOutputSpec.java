package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonObject;

/**
 * 一条产出定义，对应 NSUK 的 {@code IndustrialDefinition.ProductOutput}。
 *
 * <p>旧版仿真器把 {@code probability()} 写成恒返回 1、{@code randomRange()} 写成恒返回 0，
 * 于是「有 30% 概率产出」在预览里永远变成「必定产出」，而 {@code Random} 还照抽不误 ——
 * 预览和游戏里跑出来的东西对不上，这是那个模块最根本的不诚实。
 */
public record SimOutputSpec(SimItemSpec spec, int baseAmount, int randomRange,
                            double probability, boolean ignoreMultiplier) {

    public SimOutputSpec {
        baseAmount = Math.max(1, baseAmount);
        randomRange = Math.max(0, randomRange);
        probability = Math.max(0.0, Math.min(1.0, probability));
    }

    /** 照抄 {@code IndustrialDefinitionLoader.parseOutputs} 的默认值与别名。 */
    public static SimOutputSpec parse(JsonObject output) {
        if (output == null) {
            return null;
        }
        SimItemSpec spec = SimItemSpec.parse(output);
        if (spec.isEmpty()) {
            return null;
        }
        int baseAmount = Math.max(1, SimJson.integerAny(output, 1, "baseAmount", "count"));
        int randomRange = Math.max(0, SimJson.integer(output, "randomRange", 0));
        double probability = Math.max(0.0, Math.min(1.0, SimJson.decimal(output, "probability", 1.0)));
        boolean ignoreMultiplier = SimJson.bool(output, "ignoreMultiplier", false);
        return new SimOutputSpec(spec, baseAmount, randomRange, probability, ignoreMultiplier);
    }

    /** 最坏情况数量：{@code baseAmount + randomRange}，用于 {@code require_output_space} 的空间检查。 */
    public int worstCaseAmount() {
        return baseAmount + randomRange;
    }
}
