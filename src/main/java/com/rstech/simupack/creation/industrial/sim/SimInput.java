package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方输入要求，支持 NSUK 的嵌套 {@code and}/{@code or} 组。
 *
 * <p>对应 NSUK 的 {@code IndustrialDefinitionLoader.parseInputRequirement}：
 * 先看 {@code or/anyOf/any/或}，再看 {@code and/allOf/all/与}，都没有才当作一件物品；
 * 物品的 {@code count} 别名是 {@code amount}（下限 1），{@code consume} 默认 true
 * （{@code consume:false} 表示「只检查、不消耗」）。
 */
public record SimInput(Logic logic, SimItemSpec spec, int count, boolean consume, List<SimInput> children) {

    public enum Logic {
        /** 一件物品。 */
        ITEM,
        /** 全部子项都要满足。 */
        ALL,
        /** 任一子项满足即可（取第一个可行的分支）。 */
        ANY
    }

    public SimInput {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static SimInput item(SimItemSpec spec, int count, boolean consume) {
        return new SimInput(Logic.ITEM, spec, Math.max(1, count), consume, List.of());
    }

    public static List<SimInput> parseList(JsonArray array) {
        List<SimInput> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            SimInput input = parse(element);
            if (input != null) {
                result.add(input);
            }
        }
        return List.copyOf(result);
    }

    public static SimInput parse(JsonElement element) {
        JsonObject object = SimJson.asObject(element);
        if (object == null) {
            return null;
        }
        JsonArray any = SimJson.arrayAny(object, "or", "anyOf", "any", "或");
        if (any != null) {
            return group(Logic.ANY, any);
        }
        JsonArray all = SimJson.arrayAny(object, "and", "allOf", "all", "与");
        if (all != null) {
            return group(Logic.ALL, all);
        }
        SimItemSpec spec = SimItemSpec.parse(object);
        if (spec.isEmpty()) {
            return null;
        }
        int count = Math.max(1, SimJson.integerAny(object, 1, "count", "amount"));
        boolean consume = SimJson.bool(object, "consume", true);
        return item(spec, count, consume);
    }

    private static SimInput group(Logic logic, JsonArray array) {
        List<SimInput> children = parseList(array);
        return children.isEmpty() ? null : new SimInput(logic, SimItemSpec.EMPTY, 1, true, children);
    }
}
