package com.rstech.simupack.client.ui.editor;

import com.rstech.simupack.creation.commercial.CommercialOfferModel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 物品注册表检查 —— 数据层刻意不做的那一件事。
 *
 * <p>NSUK 的 {@code CommercialResource.valid()} 要求物品已注册
 * （{@code item() != Items.AIR}），不满足时那条资源无效，
 * 进而**整条交易被静默丢弃**。但注册表是 Minecraft 的东西，不能进数据层
 * （否则 {@code CommercialOfferModel} 就不再能脱离客户端跑单测）。
 *
 * <p>所以分工是这样：数据层用 {@link CommercialOfferModel#itemIds()} 列出
 * 「哪些物品 id 出现在哪个位置」，界面层在这里查注册表。这样既补上了唯一那个检查缺口，
 * 又保住了可离线单测。
 */
@OnlyIn(Dist.CLIENT)
public final class ItemRegistry {

    private ItemRegistry() {
    }

    /** 物品 id 是否已注册（拼错到无法解析也算未注册）。 */
    public static boolean isRegistered(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId))
                    .filter(item -> item != Items.AIR)
                    .isPresent();
        } catch (Exception exception) {
            // ResourceLocation.parse 对 "foo bar" / "minecraft:" 这类拼写会抛异常，
            // 那当然也是「没有这个物品」。
            return false;
        }
    }

    /** 从一堆引用里挑出未注册的那些。 */
    public static List<CommercialOfferModel.ItemRef> unknown(
            List<CommercialOfferModel.ItemRef> refs) {
        return refs.stream().filter(ref -> !isRegistered(ref.itemId())).toList();
    }

    /**
     * 该 id 对应的物品堆；未注册（或拼到无法解析）时返回 {@link ItemStack#EMPTY}。
     *
     * <p>返回空堆而不是 null：调用方大多是直接丢给控件渲染图标，
     * 空堆在 LDLib2 的 {@code ItemSlot} 里就是不画东西。
     */
    public static ItemStack stackOf(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId))
                    .filter(item -> item != Items.AIR)
                    .map(ItemStack::new)
                    .orElse(ItemStack.EMPTY);
        } catch (Exception exception) {
            return ItemStack.EMPTY;
        }
    }

    // ── 标签（item tag）────────────────────────────────────────────────
    //
    // 【工业收标签，商业不收 —— 这个不对称是查过 NSUK 字节码的，不要"统一"它们】
    //
    // 工业：IndustrialItemStackSpec 有独立的 itemTag 字段，IndustrialItemSpecJsonParser
    //       的常量池里有 "#"，所以 "item": "#minecraft:planks" 会解析成标签。
    // 商业：62 个商业类里没有一个用到 TagKey / Ingredient / getTag。唯一的物品解析路径是
    //       CommercialResource.stack() → BuiltInRegistries.ITEM.getOptional(
    //           ResourceLocation.parse(itemId))，
    //       既没有标签分支，ResourceLocation.parse 本身也过不了 "#"。
    //       所以商业报价里写标签 = 那笔交易永远不成交（静默失效）。
    //
    // 结论：选择栏给工业开标签、给商业关掉（见 EditorHost.openItemPicker 的 allowTags）。

    /** 标签引用的前缀。NSUK 与原生 Ingredient 都用这个约定。 */
    public static final String TAG_PREFIX = "#";

    /** tag 引用 → 成员物品 id。注册表读一次就够，缓存住。 */
    private static final java.util.Map<String, List<String>> TAG_MEMBERS = new java.util.HashMap<>();

    /** 全部 item tag 的引用串（带 {@code #} 前缀），按命名空间+路径排序，只算一次。 */
    private static List<String> allTagRefs;

    /**
     * 让标签相关的缓存失效。
     *
     * <p><b>为什么必须有这个</b>：{@link #allTagRefs()} 与 {@link #TAG_MEMBERS} 都是静态缓存，
     * 而标签集合是<b>会变的</b> —— 数据包重载（{@code /reload}）、中途启用/禁用带标签的数据包、
     * 切换存档，都会换掉整套 item tag。不失效的话界面会一直用启动那一次的旧表：
     * 列出已经不存在的标签（写进去就是没人认的 {@code #xxx}），或者看不到新加的。
     *
     * <p>调用时机选在<b>打开选择栏时</b>（见 {@code ItemPicker} 的构造），而不是挂事件：
     * 一是标签只在打开时被读，这时刷新正好够用；二是不必依赖某个具体的事件类名与触发时机
     * （那需要另行查证，凭记忆写事件名是这类 bug 的常见来源）。
     * 代价是选择栏<b>开着的时候</b>如果标签变了不会实时反映 —— 可以忽略。
     */
    public static void invalidateTagCache() {
        allTagRefs = null;
        TAG_MEMBERS.clear();
    }

    /** 这个字符串是不是标签引用（形如 {@code #minecraft:planks}）。 */
    public static boolean isTagRef(String value) {
        return value != null && value.startsWith(TAG_PREFIX) && value.length() > 1;
    }

    private static TagKey<Item> tagKey(String tagRef) {
        if (!isTagRef(tagRef)) {
            return null;
        }
        try {
            return TagKey.create(Registries.ITEM,
                    ResourceLocation.parse(tagRef.substring(TAG_PREFIX.length())));
        } catch (Exception exception) {
            return null;
        }
    }

    /** 该标签是否真实存在（不存在时界面应报"未注册"，而不是画一个空集合）。 */
    public static boolean isKnownTag(String tagRef) {
        TagKey<Item> key = tagKey(tagRef);
        return key != null && BuiltInRegistries.ITEM.getTag(key).isPresent();
    }

    /**
     * 标签下的成员物品 id（保持注册顺序，去重）。
     *
     * <p>返回 {@code List<String>} 而不是 {@code List<ItemStack>}：轮播每秒都要取一次，
     * 缓存 {@code ItemStack} 会把可变对象共享出去，而 id 是不可变的。
     */
    public static List<String> tagMembers(String tagRef) {
        if (!isTagRef(tagRef)) {
            return List.of();
        }
        List<String> cached = TAG_MEMBERS.get(tagRef);
        if (cached != null) {
            return cached;
        }
        List<String> ids = new java.util.ArrayList<>();
        TagKey<Item> key = tagKey(tagRef);
        if (key != null) {
            BuiltInRegistries.ITEM.getTag(key).ifPresent(set -> {
                for (Holder<Item> holder : set) {
                    Item item = holder.value();
                    if (item != Items.AIR) {
                        // 同一个物品在一个标签里不会出现两次，但跨标签合并时会，故去重。
                        String id = BuiltInRegistries.ITEM.getKey(item).toString();
                        if (!ids.contains(id)) {
                            ids.add(id);
                        }
                    }
                }
            });
        }
        List<String> result = List.copyOf(ids);
        TAG_MEMBERS.put(tagRef, result);
        return result;
    }

    /**
     * 全部 item tag 引用，物品之后按命名空间+路径排序。
     *
     * <p>数量是<b>几百</b>（原版就约两百个，再加模组与 {@code c:}/{@code forge:} 通用标签），
     * 所以选择栏必须靠虚拟化网格 + 搜索承载，不能一次铺开。
     */
    public static List<String> allTagRefs() {
        if (allTagRefs == null) {
            List<String> refs = new java.util.ArrayList<>();
            BuiltInRegistries.ITEM.getTagNames().forEach(key -> {
                // 只收非空标签：空标签轮播无可显示，选它没有意义。
                if (BuiltInRegistries.ITEM.getTag(key).map(set -> set.size() > 0).orElse(false)) {
                    refs.add(TAG_PREFIX + key.location());
                }
            });
            refs.sort(java.util.Comparator.naturalOrder());
            allTagRefs = List.copyOf(refs);
        }
        return allTagRefs;
    }
}
