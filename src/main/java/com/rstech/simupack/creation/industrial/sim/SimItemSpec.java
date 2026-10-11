package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份物品描述，字段与 NSUK 的 {@code IndustrialItemStackSpec} 对齐。
 *
 * <p>仿真不需要真的造出 {@code ItemStack}：容器库存按 {@link #key()} 记账。
 * 代价是<b>标签无法展开</b> —— {@code #c:ingots} 只能匹配用同一个标签串写进库存的条目，
 * 不会在预览里被展开成成员物品（展开需要物品注册表，那会把内核拖进 Minecraft）。
 * 这一点在界面上有明确提示，不会静默给出错误结论。
 */
public record SimItemSpec(String item, String tag, String potion, String itemStack, String customData) {

    public static final SimItemSpec EMPTY = new SimItemSpec("", "", "", "", "");

    public SimItemSpec {
        item = item == null ? "" : item.trim();
        tag = tag == null ? "" : tag.trim();
        potion = potion == null ? "" : potion.trim();
        itemStack = itemStack == null ? "" : itemStack.trim();
        customData = customData == null ? "" : customData.trim();
    }

    public boolean isEmpty() {
        return key().isBlank();
    }

    /**
     * 库存键。
     *
     * <p>顺序照抄 NSUK 的 {@code displayItemId()}（`IndustrialItemStackSpec:218-231`）：
     * **具体物品优先**，其次 {@code #标签}，再是 itemStack、potion。
     * （早先我把标签放在最前面，同时写 {@code item} 和 {@code tag} 时键就和 NSUK 不一样了。）
     */
    public String key() {
        if (!item.isBlank()) {
            return item;
        }
        if (!tag.isBlank()) {
            return "#" + tag;
        }
        if (!itemStack.isBlank()) {
            int componentStart = itemStack.indexOf('[');
            return componentStart >= 0 ? itemStack.substring(0, componentStart) : itemStack;
        }
        if (!potion.isBlank()) {
            return "potion:" + potion;
        }
        return "";
    }

    /**
     * 只写了标签、没写具体物品。
     *
     * <p>NSUK 里这种描述**造不出 ItemStack**（`IndustrialItemStackSpec:148-150` 返回 EMPTY）：
     * {@code insert_item} 会判 {@code invalid_step}，合成产出被静默丢弃，
     * {@code require_output_space} 也把这种产出 filter 掉。仿真必须照此，不然会凭空记账。
     */
    public boolean tagOnly() {
        return item.isBlank() && !tag.isBlank();
    }

    /** 界面展示用：不带 {@code [components]} 的裸 id。 */
    public String display() {
        String key = key();
        return key.isBlank() ? "" : key;
    }

    public boolean hasComplexConstraints() {
        return !customData.isBlank() || !itemStack.isBlank();
    }

    public boolean matchesKey(String inventoryKey) {
        return !key().isBlank() && key().equals(inventoryKey);
    }

    /**
     * 与 NSUK 的 {@code IndustrialItemSpecJsonParser.parseList} 对应。
     *
     * <p>数组元素既可以是对象，也可以是一段序列化文本（{@code fromSerialized}）。
     * 这里只处理对象形式与纯物品 id 文本 —— 完整序列化文本需要组件解析，属于世界侧能力。
     */
    public static List<SimItemSpec> parseList(JsonArray array) {
        List<SimItemSpec> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive()) {
                SimItemSpec text = new SimItemSpec(element.getAsString(), "", "", "", "");
                if (!text.isEmpty()) {
                    result.add(text);
                }
                continue;
            }
            SimItemSpec parsed = parse(SimJson.asObject(element));
            if (!parsed.isEmpty()) {
                result.add(parsed);
            }
        }
        return List.copyOf(result);
    }

    /** 与 NSUK 的 {@code IndustrialItemSpecJsonParser.parse} 一一对应。 */
    public static SimItemSpec parse(JsonObject object) {
        if (object == null) {
            return EMPTY;
        }
        return new SimItemSpec(
                SimJson.string(object, "item", ""),
                SimJson.stringAny(object, "", "tag", "itemTag", "item_tag"),
                SimJson.string(object, "potion", ""),
                SimJson.stringAny(object, "", "itemStack", "itemString", "stack"),
                SimJson.stringAny(object, "", "customData", "nbt"));
    }
}
