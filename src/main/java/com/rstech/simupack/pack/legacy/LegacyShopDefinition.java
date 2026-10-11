package com.rstech.simupack.pack.legacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 旧版商业定义的字段升级：把 {@code trades}/{@code buyTrades}/{@code shopMode} 风格的商店 JSON
 * 转成新版 {@code job} + {@code offers} + {@code stock} 结构。
 *
 * <p>为什么要转：NSUK 2.2.0 只在「{@code offers} 解析结果为空」时才回退读旧字段
 * （{@code CommercialDefinitionLoader:116}）。旧包一旦带上别的新版键、或旧字段拼写稍有出入，
 * 就会静默变成一个没有任何交易的商店；显式转成新结构可以把这个不确定性消掉。
 *
 * <p>铁律（沿用旧版转换脚本的设计）：<b>绝不静默丢数据</b>。被搬走的旧键之外，
 * 所有未知键一律原样带进输出；无法确定的单位/语义只记录提示，不猜。
 *
 * <p>本类不依赖 Minecraft，可脱机测试。
 */
public final class LegacyShopDefinition {

    /** 表明这是商店定义的旧字段。 */
    private static final List<String> LEGACY_MARKERS = List.of("trades", "buyTrades", "shopMode");

    /** 表明这已经是新版定义。 */
    private static final List<String> MODERN_MARKERS = List.of("offers", "containers");

    /**
     * 旧版默认值与新版运行时旧格式解析器的默认值不一致时，以<b>旧版</b>为准补齐。
     *
     * <p>例：旧版 {@code maxStock} 缺省 64、{@code restockAmount} 缺省 32，而新版旧格式
     * 解析器把缺省当成 0。显式写出旧版默认值，转换后的商店才和转换前一个行为。
     */
    private static final int LEGACY_DEFAULT_MAX_STOCK = 64;
    private static final int LEGACY_DEFAULT_RESTOCK_AMOUNT = 32;

    /** 被搬走（重命名/合并）的旧顶层键：不再原样保留，但内容会映射到新结构里。 */
    private static final Set<String> MOVED_KEYS = Set.of(
            "buildingId", "buildingName", "jobType", "jobName", "heldItem",
            "shopMode", "trades", "buyTrades", "requireMaterialsForSale");

    private LegacyShopDefinition() {
    }

    /** 这个 JSON 文本是不是需要升级的旧商店定义。 */
    public static boolean looksLikeLegacyShop(String raw) {
        JsonObject object = parse(raw);
        if (object == null) {
            return false;
        }
        boolean legacy = LEGACY_MARKERS.stream().anyMatch(object::has);
        boolean modern = MODERN_MARKERS.stream().anyMatch(object::has);
        return legacy && !modern;
    }

    /**
     * 升级一个功能定义 JSON。
     *
     * <p>非商店定义（工业 / 医疗 / 伐木等）字节级原样返回，不做任何猜测性改写；
     * 已经是新版结构的也原样返回。
     *
     * @param raw       原始 JSON 文本
     * @param source    源文件名（用于提示）
     * @param id        建筑 id（新版以文件名为主键，这里显式写进 id/buildingId 保持一致）
     * @param notices   提示收集器
     */
    public static JsonObject upgrade(String raw, String source, String id,
                                     List<LegacyPackConverter.Notice> notices) {
        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(stripBom(raw));
            if (!parsed.isJsonObject()) {
                notices.add(new LegacyPackConverter.Notice(
                        LegacyPackConverter.Notice.SKIPPED_DUPLICATE,
                        source + " 的 JSON 顶层不是对象，已原样保留"));
                return rawObject(raw);
            }
            object = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            notices.add(new LegacyPackConverter.Notice(
                    LegacyPackConverter.Notice.SKIPPED_DUPLICATE,
                    source + " 的 JSON 解析失败（" + exception.getMessage() + "），已原样保留"));
            return rawObject(raw);
        }

        boolean legacy = LEGACY_MARKERS.stream().anyMatch(object::has);
        boolean modern = MODERN_MARKERS.stream().anyMatch(object::has);
        if (!legacy) {
            if (!modern) {
                notices.add(new LegacyPackConverter.Notice(
                        LegacyPackConverter.Notice.SKIPPED_DUPLICATE,
                        source + " 不是可识别的商店/功能定义，已原样保留"));
            }
            return object;
        }
        if (modern) {
            // 同时有两套字段：以新版为准（加载器也是这个优先级），旧字段原样留着不删。
            notices.add(new LegacyPackConverter.Notice(
                    LegacyPackConverter.Notice.SHOP_UPGRADED,
                    source + " 同时含旧字段与新 offers，已保留原样（加载器优先用 offers）"));
            return object;
        }

        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("buildingId", id);

        String displayName = firstText(object, "buildingName", "name");
        if (displayName != null) {
            result.addProperty("name", displayName);
        }

        JsonObject job = new JsonObject();
        String jobType = firstText(object, "jobType", "job_type");
        String jobName = firstText(object, "jobName", "job_name");
        String heldItem = firstText(object, "heldItem", "held_item");
        if (jobType != null) {
            job.addProperty("id", jobType);
        }
        if (jobName != null) {
            job.addProperty("name", jobName);
        }
        if (heldItem != null) {
            job.addProperty("heldItem", heldItem);
        }
        if (job.size() > 0) {
            result.add("job", job);
        }

        // 未搬走的键一律原样保留，并按需提示。
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            if (MOVED_KEYS.contains(key) || result.has(key)) {
                continue;
            }
            result.add(key, entry.getValue());
            if ("workTime".equals(key) || "messages".equals(key)) {
                notices.add(new LegacyPackConverter.Notice(
                        LegacyPackConverter.Notice.SHOP_UPGRADED,
                        source + " 的 " + key + " 已原样保留，请进游戏核对"));
            } else if ("restockTime".equals(key)) {
                notices.add(new LegacyPackConverter.Notice(
                        LegacyPackConverter.Notice.SHOP_UPGRADED,
                        source + " 的 restockTime 已原样保留；新版补货周期取自报价的 stock.restockInterval"
                                + "（旧格式解析器用固定 12000 tick），请在游戏里核对"));
            } else if (!"name".equals(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            notices.add(new LegacyPackConverter.Notice(
                    LegacyPackConverter.Notice.SHOP_UPGRADED,
                    source + " 保留了未识别字段：" + String.join(", ", unknown)));
        }
        if (asBoolean(object.get("requireMaterialsForSale"))
                && asArray(object.get("materials")).isEmpty()) {
            notices.add(new LegacyPackConverter.Notice(
                    LegacyPackConverter.Notice.SHOP_UPGRADED,
                    source + " 的 requireMaterialsForSale=true 但没有任何材料明细，需要手动补 stock.materials"));
        }

        String visibility = visibility(object.get("shopMode"));
        JsonArray offers = new JsonArray();

        JsonArray trades = asArray(object.get("trades"));
        for (int index = 0; index < trades.size(); index++) {
            JsonElement trade = trades.get(index);
            if (!trade.isJsonObject()) {
                notices.add(new LegacyPackConverter.Notice(
                        LegacyPackConverter.Notice.SHOP_UPGRADED,
                        source + " 第 " + (index + 1) + " 条 trades 不是对象，已跳过"));
                continue;
            }
            JsonObject tradeObject = trade.getAsJsonObject();
            if (tradeObject.has("cost") && tradeObject.has("result")) {
                offers.add(tradeObject.deepCopy()); // 已经是新版报价条目
                continue;
            }
            String item = firstText(tradeObject, "item", "id");
            if (item == null) {
                notices.add(new LegacyPackConverter.Notice(
                        LegacyPackConverter.Notice.SHOP_UPGRADED,
                        source + " 第 " + (index + 1) + " 条 trades 缺少 item，已跳过"));
                continue;
            }
            JsonObject offer = new JsonObject();
            offer.addProperty("id", "shop_sells_" + slug(item));
            offer.addProperty("visibleTo", visibility);
            offer.add("cost", moneyList(number(tradeObject.get("sellPrice"), 0.0D)));
            // retail=true 表示"零售"：运行时的旧格式解析器把结果数量取 1，否则取 64
            // （CommercialLegacyDefinitionParser.legacySellOffer）。这里必须照抄，
            // 否则攒批卖（retail 缺省 false）会被写成 1 个，等于价格瞬间翻了 64 倍。
            boolean retail = asBoolean(tradeObject.get("retail"));
            offer.add("result", itemList(item, retail ? 1 : 64));
            JsonObject stockRule = stock(tradeObject, item,
                    materialsFor(object, tradeObject, item));
            if (stockRule != null) {
                offer.add("stock", stockRule);
            }
            offers.add(offer);
        }

        JsonArray buyTrades = asArray(object.get("buyTrades"));
        for (int index = 0; index < buyTrades.size(); index++) {
            JsonElement trade = buyTrades.get(index);
            if (!trade.isJsonObject()) {
                notices.add(new LegacyPackConverter.Notice(
                        LegacyPackConverter.Notice.SHOP_UPGRADED,
                        source + " 第 " + (index + 1) + " 条 buyTrades 不是对象，已跳过"));
                continue;
            }
            JsonObject tradeObject = trade.getAsJsonObject();
            String item = firstText(tradeObject, "item", "id");
            if (item == null) {
                notices.add(new LegacyPackConverter.Notice(
                        LegacyPackConverter.Notice.SHOP_UPGRADED,
                        source + " 第 " + (index + 1) + " 条 buyTrades 缺少 item，已跳过"));
                continue;
            }
            double price = tradeObject.has("buyPrice")
                    ? number(tradeObject.get("buyPrice"), 0.0D)
                    : number(tradeObject.get("sellPrice"), 0.0D);
            JsonObject offer = new JsonObject();
            offer.addProperty("id", "shop_buys_" + slug(item));
            offer.addProperty("visibleTo", "player");
            offer.add("cost", itemList(item, 64));
            offer.add("result", moneyList(price));
            JsonObject buyStockRule = buyStock(tradeObject, item);
            if (buyStockRule != null) {
                offer.add("stock", buyStockRule);
            }
            offers.add(offer);
        }

        if (offers.size() > 0) {
            result.add("offers", offers);
        } else {
            notices.add(new LegacyPackConverter.Notice(
                    LegacyPackConverter.Notice.SHOP_UPGRADED,
                    source + " 没有任何交易条目，已转成纯职业定义（无 offers）"));
        }
        if (nonRetailNotices(notices, source, object)) {
            notices.add(new LegacyPackConverter.Notice(
                    LegacyPackConverter.Notice.SHOP_UPGRADED,
                    source + " 的 retail 已按旧版语义换算成结果数量（retail=true → 1，否则 → 64）"));
        }
        notices.add(new LegacyPackConverter.Notice(
                LegacyPackConverter.Notice.SHOP_UPGRADED,
                source + " 旧格式没有容器坐标，新版 containers 已省略（交易供料有邻近扫描兜底，"
                        + "要接物流端口再手动补 containers）"));
        return result;
    }

    /** 是否出现过 retail 字段：出现过就提示"已换算成数量"，避免用户以为它被丢了。 */
    private static boolean nonRetailNotices(List<LegacyPackConverter.Notice> notices, String source,
                                            JsonObject object) {
        for (JsonElement trade : asArray(object.get("trades"))) {
            if (trade.isJsonObject() && trade.getAsJsonObject().has("retail")) {
                return true;
            }
        }
        return false;
    }

    // ── 内部构造 ───────────────────────────────────────────────────────

    /**
     * 卖出报价的库存规则。
     *
     * <p>照抄 {@code CommercialLegacyDefinitionParser.legacySellOffer}：max 取
     * {@code maxStock}/{@code max_stock}/{@code max}（缺省 0，再与 0 取大），
     * restockAmount 取 {@code restockAmount}/{@code restock_amount}；
     * 只有 {@code max > 0} 或带材料时才建库存规则。
     */
    private static JsonObject stock(JsonObject trade, String item, JsonArray materials) {
        // 旧版把 maxStock 缺省当 64、restockAmount 缺省当 32；新版旧格式解析器当 0。
        // 为了"转换前后行为一致"，这里显式写出旧版的默认值。
        int max = Math.max(0, (int) numberAny(trade, LEGACY_DEFAULT_MAX_STOCK,
                "maxStock", "max_stock", "max"));
        if (max <= 0 && (materials == null || materials.isEmpty())) {
            return null;
        }
        JsonObject stock = new JsonObject();
        stock.addProperty("item", item);
        stock.addProperty("max", max);
        stock.addProperty("restockAmount", Math.max(0, (int) numberAny(trade,
                LEGACY_DEFAULT_RESTOCK_AMOUNT, "restockAmount", "restock_amount")));
        if (materials != null && !materials.isEmpty()) {
            stock.add("materials", materials);
        }
        return stock;
    }

    /**
     * 买入报价（商店收购）的库存规则。
     *
     * <p>照抄 {@code legacyBuyOffer}：{@code maxBuyAmount}/{@code max_buy_amount}/{@code max}
     * 才是"每次最多收购多少"（旧版缺省 64），再乘 64 当库存上限（老的解析器就是这么算的，
     * 连它会整型溢出这点都一样——这里多一层 {@code min(MAX_VALUE, …)} 兜住）。
     */
    private static JsonObject buyStock(JsonObject trade, String item) {
        int perTrade = Math.max(0, (int) numberAny(trade, 64.0D,
                "maxBuyAmount", "max_buy_amount", "max"));
        int max = (int) Math.min(Integer.MAX_VALUE, (long) perTrade * 64L);
        if (max <= 0) {
            return null;
        }
        JsonObject stock = new JsonObject();
        stock.addProperty("item", item);
        stock.addProperty("max", max);
        stock.addProperty("restockAmount", 0);
        return stock;
    }

    /**
     * 旧格式的材料明细 → 新版 {@code stock.materials}。
     *
     * <p>优先级照抄 {@code legacyMaterials}：先看这笔交易自己的
     * {@code requiredMaterials}/{@code required_material}，都没有时才回退到顶层
     * {@code requireMaterialsForSale: true} + {@code materials}。
     */
    private static JsonArray materialsFor(JsonObject root, JsonObject trade, String item) {
        JsonArray materials = convertMaterials(asArrayAny(trade, "requiredMaterials", "required_materials"));
        if (!materials.isEmpty()) {
            return materials;
        }
        String single = firstText(trade, "requiredMaterial", "required_material");
        int singleCount = Math.max(0, (int) numberAny(trade, 0.0D,
                "requiredMaterialCount", "required_material_count"));
        if (single != null && singleCount > 0) {
            JsonArray array = new JsonArray();
            array.add(materialEntry(single, singleCount));
            return array;
        }
        if (!asBoolean(root.get("requireMaterialsForSale"))) {
            return null;
        }
        return convertMaterials(asArray(root.get("materials")));
    }

    private static JsonArray convertMaterials(JsonArray source) {
        JsonArray result = new JsonArray();
        if (source == null) {
            return result;
        }
        for (JsonElement element : source) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String item = firstText(entry, "item");
            double count = Math.max(0.0D, number(entry.get("count"), 1.0D));
            count = Math.max(count, number(entry.get("amount"), 1.0D));
            if (item != null && count > 0) {
                result.add(materialEntry(item, (int) count));
            }
        }
        return result;
    }

    /** 材料条目同时写 count 与 amount：新解析器两个键都读、取两者较大值。 */
    private static JsonObject materialEntry(String item, int count) {
        JsonObject entry = new JsonObject();
        entry.addProperty("item", item);
        entry.addProperty("count", count);
        entry.addProperty("amount", count);
        return entry;
    }

    private static JsonArray asArrayAny(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonArray value = asArray(object.get(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return new JsonArray();
    }

    private static double numberAny(JsonObject object, double fallback, String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                return number(object.get(key), fallback);
            }
        }
        return fallback;
    }

    private static JsonArray moneyList(double money) {
        JsonObject entry = new JsonObject();
        entry.addProperty("money", money);
        JsonArray array = new JsonArray();
        array.add(entry);
        return array;
    }

    private static JsonArray itemList(String item, int count) {
        JsonObject entry = new JsonObject();
        entry.addProperty("item", item);
        entry.addProperty("count", Math.max(1, count));
        JsonArray array = new JsonArray();
        array.add(entry);
        return array;
    }

    /** 旧 {@code shopMode} → 新可见性。取值与加载器一致：其它一律 player。 */
    static String visibility(JsonElement mode) {
        if (mode == null || !mode.isJsonPrimitive()) {
            return "mixed";
        }
        String value = mode.getAsString().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "mixed", "both", "all" -> "mixed";
            case "npc", "npc_sell", "npcsell", "npc_buy" -> "npc";
            default -> "player";
        };
    }

    static String slug(String item) {
        String cleaned = item.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "item" : cleaned;
    }

    private static String firstText(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private static double number(JsonElement value, double fallback) {
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static boolean asBoolean(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static JsonArray asArray(JsonElement value) {
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject parse(String raw) {
        try {
            JsonElement parsed = JsonParser.parseString(stripBom(raw));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * JSON 解析失败时的兜底容器。
     *
     * <p>直接把原始文本原样带走（放进 {@code _raw}），绝不因为解析失败就把用户的内容丢掉。
     */
    private static JsonObject rawObject(String raw) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("_raw", stripBom(raw));
        return wrapper;
    }

    private static String stripBom(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
    }
}
