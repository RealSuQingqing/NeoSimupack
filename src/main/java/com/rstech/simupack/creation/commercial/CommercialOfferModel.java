package com.rstech.simupack.creation.commercial;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.rstech.simupack.creation.json.DefinitionIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 商业建筑「交易（报价，{@code offers}）」子树的数据模型。
 *
 * <p><b>与 {@code BuildingJsonDocument} 共用同一棵树</b>：本类不自己 {@code parse}，
 * 而是包在已有根对象上（{@link #wrap(JsonObject)}），只读写 {@code offers} 这一个子树。
 * 这是「分类设定」与「交易编辑」两个视图不会互相覆盖的前提。
 *
 * <p><b>本类不依赖任何 Minecraft 类</b>（只用 Gson + {@link DefinitionIssue}），
 * 所以能脱离客户端跑 {@code gradlew test}。
 *
 * <p>规则全部逐条追到 NSUK 源码（路径根 {@code common/cn/kafei/simukraft/commercial/}）：
 * <ul>
 *   <li>{@code CommercialDefinitionLoader.parseOffers:172-199}：报价上限 256
 *       （{@code MAX_OFFERS}），缺 {@code id} 回落 {@code offer_<下标>}；</li>
 *   <li>{@code parseResource:223-230}：<b>只要元素里有 {@code money} 键就走资金分支，
 *       同元素的 {@code item}/{@code count} 被完全忽略</b> —— 这是最坑的一条；</li>
 *   <li>{@code CommercialResource.valid():29-34}：资金 {@code > 0}，物品非空且数量 {@code > 0}
 *       且已注册（注册表检查属于界面层，见 {@link #validate()} 的说明）；</li>
 *   <li>{@code CommercialOffer.valid():34-39}：{@code cost} 与 {@code result} 都非空才有效，
 *       否则该条被丢弃并报 {@code invalid_offer}；</li>
 *   <li>{@code CommercialDefinitionLoader:115-117}：<b>{@code offers} 解析结果为空会静默回退
 *       旧格式解析器</b>，「全部写坏」与「空数组」等价 —— 所以 {@code offers_legacy_fallback}
 *       是本模型最重要的一条校验；</li>
 *   <li>{@code CommercialTradeOfferTab.matches:22-32}：Tab 归类并非互斥，
 *       一条报价可以同时属于多个 Tab。</li>
 * </ul>
 */
public final class CommercialOfferModel {

    /** 报价上限（{@code CommercialDefinitionLoader.MAX_OFFERS}），超出部分被静默丢弃。 */
    public static final int MAX_OFFERS = 256;
    /** 单侧资源上限（{@code MAX_RESOURCES}）：{@code cost} / {@code result} / {@code materials} 各 4。 */
    public static final int MAX_RESOURCES = 4;

    /** 顶层报价数组键。 */
    public static final String OFFERS = "offers";
    /** 成本数组键。 */
    public static final String COST = "cost";
    /** 产出数组键。 */
    public static final String RESULT = "result";
    /** 库存对象键。 */
    public static final String STOCK = "stock";
    /** 资金键。 */
    public static final String MONEY = "money";
    /** 物品键。 */
    public static final String ITEM = "item";
    /** 数量键。 */
    public static final String COUNT = "count";
    /** 库存材料数组键。 */
    public static final String MATERIALS = "materials";
    /** 可见性键。 */
    public static final String VISIBLE_TO = "visibleTo";
    /** 库存上限键。 */
    public static final String MAX = "max";
    /** 初始库存键。 */
    public static final String INITIAL = "initial";
    /** 每次补货数量键。 */
    public static final String RESTOCK_AMOUNT = "restockAmount";
    /** {@link #RESTOCK_AMOUNT} 的兼容拼写。 */
    public static final String RESTOCK_AMOUNT_ALIAS = "restock_amount";
    /** 补货间隔键（单位是服务器运行 tick）。 */
    public static final String RESTOCK_INTERVAL = "restockInterval";
    /** {@link #RESTOCK_INTERVAL} 的兼容拼写。 */
    public static final String RESTOCK_INTERVAL_ALIAS = "restock_interval";

    private static final String[] COST_ALIASES = {"costs"};
    private static final String[] RESULT_ALIASES = {"results"};
    private static final String[] VISIBLE_ALIASES = {"visible_to", "visibility"};
    private static final String[] COUNT_ALIASES = {"amount"};
    private static final String[] MATERIALS_ALIASES = {"requiredMaterials", "required_materials"};
    private static final String[] RESTOCK_AMOUNT_ALIASES = {RESTOCK_AMOUNT_ALIAS};
    private static final String[] RESTOCK_INTERVAL_ALIASES = {RESTOCK_INTERVAL_ALIAS};
    /** 旧格式顶层键：只在 {@code offers} 解析结果为空时才被读取。 */
    private static final String[] LEGACY_KEYS = {"shopMode", "trades", "buyTrades", "requireMaterialsForSale"};
    /** 顶层会遮蔽 {@code job} 子键的兜底键（{@code CommercialDefinitionLoader:126-137}）。 */
    private static final String[] JOB_FALLBACK_KEYS =
            {"jobType", "job_type", "jobName", "job_name", "heldItem", "held_item"};

    private final JsonObject root;

    private CommercialOfferModel(JsonObject root) {
        this.root = root;
    }

    /**
     * 包在已有的建筑根对象上。
     *
     * <p>刻意<b>不</b>提供 {@code parse(String)}：模型必须与
     * {@code BuildingJsonDocument} 操作同一棵树，自己解析会制造出第二份状态。
     */
    public static CommercialOfferModel wrap(JsonObject root) {
        return new CommercialOfferModel(Objects.requireNonNull(root, "root"));
    }

    /** 底层根对象（整个建筑 JSON）。 */
    public JsonObject root() {
        return root;
    }

    // ── 报价数组 ───────────────────────────────────────────────────────

    /** {@code offers} 数组；不存在且 {@code create} 为 false 时返回 null。 */
    public JsonArray offersArray(boolean create) {
        JsonElement existing = root.get(OFFERS);
        if (existing != null && existing.isJsonArray()) {
            return existing.getAsJsonArray();
        }
        if (!create) {
            return null;
        }
        JsonArray created = new JsonArray();
        root.add(OFFERS, created);
        return created;
    }

    /** 数组里的对象型元素（非对象元素被 NSUK 记为 {@code invalid_offer} 并跳过）。 */
    public List<JsonObject> offers() {
        JsonArray array = offersArray(false);
        if (array == null) {
            return List.of();
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                out.add(element.getAsJsonObject());
            }
        }
        return out;
    }

    /** 数组的原始长度（含非对象元素），用来判断是否超过 {@link #MAX_OFFERS}。 */
    public int rawOfferCount() {
        JsonArray array = offersArray(false);
        return array == null ? 0 : array.size();
    }

    /** 按 id 找报价；找不到返回 null。 */
    public JsonObject offerById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (JsonObject offer : offers()) {
            if (id.equals(offerId(offer))) {
                return offer;
            }
        }
        return null;
    }

    /**
     * 报价 id：缺省回落 {@code offer_<下标>}（与 NSUK 的 {@code string(object, "id", "offer_" + i)} 一致）。
     *
     * <p>下标按<b>引用</b>查找，不用 {@code indexOf}：后者比的是 JSON 内容，
     * 两条内容相同（都没写 id）的报价会得到同一个下标。
     */
    public String offerId(JsonObject offer) {
        String explicit = stringOr(offer, "id", "");
        if (!explicit.isBlank()) {
            return explicit;
        }
        JsonArray array = offersArray(false);
        if (array != null) {
            for (int index = 0; index < array.size(); index++) {
                if (array.get(index) == offer) {
                    return "offer_" + index;
                }
            }
        }
        return "offer";
    }

    /**
     * 追加一条**空白**报价（只写 {@code id} 与 {@code visibleTo}），返回它。
     *
     * <p>刻意<b>不</b>预填内容：不写 {@code cost}、不写 {@code result}、也不写 {@code stock}。
     * 以前这里放的是一条示例（1 块钱买 1 个圆石 + 库存上限 64），理由是
     * 「{@code cost}/{@code result} 都非空才不会被 NSUK 丢掉」—— 但示例会被当成作者写的内容
     * 一起进到作品里：新建两条就是两条 {@code minecraft:stone}，于是报
     * {@code duplicate_stock_item}，示例里的库存规则也成了真规则。要示例该由模板提供，
     * 不该由「新建」这个动作塞进用户的作品。
     *
     * <p>代价是明确的：这条报价在填完之前对 NSUK 无效（{@code valid()} 要求 cost/result 都非空），
     * 校验会报 {@code empty_cost} / {@code empty_result}（错误级，导出闸门会拦住），
     * 若它是唯一一条还会报 {@code offers_legacy_fallback}。这正是「还没填完」应得的提示 ——
     * 比一条悄悄生效的假交易好。
     *
     * <p>{@code visibleTo} 留着：它是这条报价自己的属性（面板上「可见对象」显示的就是它），
     * 不是配方或库存内容，而且缺省值与 NSUK 的兜底一致（{@code player}）。
     */
    public JsonObject addOffer(String baseId) {
        JsonArray array = offersArray(true);
        JsonObject offer = new JsonObject();
        offer.addProperty("id", uniqueId(array, baseId));
        offer.addProperty(VISIBLE_TO, "player");
        array.add(offer);
        return offer;
    }

    /** 深拷贝一条报价（含全部资源与库存），插到原条目之后并返回新条目。 */
    public JsonObject duplicateOffer(int index) {
        JsonArray array = offersArray(false);
        if (array == null || index < 0 || index >= array.size() || !array.get(index).isJsonObject()) {
            return null;
        }
        JsonObject original = array.get(index).getAsJsonObject();
        // 先取原条目的「有效 id」（可能来自缺省下标），再深拷贝，
        // 否则副本不在数组里、引用查找拿不到下标。
        String baseId = offerId(original);
        JsonObject copy = original.deepCopy();
        copy.addProperty("id", uniqueId(array, baseId));
        arrayInsert(array, index + 1, copy);
        return copy;
    }

    /**
     * 在数组中间插入一个元素。
     *
     * <p>Gson 2.10.1 的 {@code JsonArray} 只有 {@code add} 与 {@code set}，
     * 没有「按下标插入」。这里用与 {@code BuildingJsonDocument.arrayInsert} 相同的手法：
     * 摘出尾部、截断、追加、再补回尾部。
     */
    private static void arrayInsert(JsonArray array, int index, JsonElement element) {
        int at = Math.max(0, Math.min(index, array.size()));
        List<JsonElement> tail = new ArrayList<>();
        for (int i = at; i < array.size(); i++) {
            tail.add(array.get(i));
        }
        while (array.size() > at) {
            array.remove(array.size() - 1);
        }
        array.add(element);
        for (JsonElement item : tail) {
            array.add(item);
        }
    }

    /** 删除一条报价；越界返回 false。 */
    public boolean removeOffer(int index) {
        JsonArray array = offersArray(false);
        if (array == null || index < 0 || index >= array.size()) {
            return false;
        }
        array.remove(index);
        return true;
    }

    /** id 改名；同时避开数组里已存在的同名 id。 */
    public void setOfferId(JsonObject offer, String id) {
        putCanonical(offer, "id", new String[0], id);
    }

    /**
     * 把一条报价上的别名键名统一成规范键名。
     *
     * <p>读取一律接受别名（不能替用户改名），但**写盘时应当只有一种拼写**。
     * 这里只在用户真的编辑过这条报价时调用，用来消掉 {@code costs} / {@code results} /
     * {@code visible_to} / {@code amount} / {@code requiredMaterials} 这些历史拼写。
     *
     * <p>规范键已存在时不覆盖（NSUK 读的也是规范键优先）。
     */
    public void canonicalizeOfferKeys(JsonObject offer) {
        if (offer == null) {
            return;
        }
        canonicalize(offer, COST, COST_ALIASES);
        canonicalize(offer, RESULT, RESULT_ALIASES);
        canonicalize(offer, VISIBLE_TO, VISIBLE_ALIASES);
        for (JsonArray array : new JsonArray[]{costArray(offer, false), resultArray(offer, false)}) {
            canonicalizeResourceKeys(array);
        }
        JsonObject stock = stock(offer, false);
        if (stock != null) {
            canonicalize(stock, MATERIALS, MATERIALS_ALIASES);
            canonicalize(stock, "restockAmount", RESTOCK_AMOUNT_ALIASES);
            canonicalize(stock, "restockInterval", RESTOCK_INTERVAL_ALIASES);
            canonicalizeResourceKeys(materialsArray(stock, false));
        }
    }

    private static void canonicalizeResourceKeys(JsonArray array) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                canonicalize(element.getAsJsonObject(), COUNT, COUNT_ALIASES);
            }
        }
    }

    /** 把别名的值搬到规范键上并删掉别名；规范键已存在时只删别名。 */
    private static void canonicalize(JsonObject object, String canonical, String[] aliases) {
        for (String alias : aliases) {
            if (!object.has(alias)) {
                continue;
            }
            JsonElement value = object.get(alias);
            if (!object.has(canonical)) {
                object.add(canonical, value);
            }
            object.remove(alias);
        }
    }

    // ── 可见性 ─────────────────────────────────────────────────────────

    /** 读取可见性（接受 {@code visible_to}/{@code visibility} 别名），非法值回落 {@code player}。 */
    public String visibleTo(JsonObject offer) {
        JsonElement value = value(offer, VISIBLE_TO, VISIBLE_ALIASES);
        String raw = value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
        return canonicalVisibility(raw);
    }

    /** 写入可见性：统一写规范键与规范值，并清掉别名。 */
    public void setVisibleTo(JsonObject offer, String value) {
        putCanonical(offer, VISIBLE_TO, VISIBLE_ALIASES, canonicalVisibility(value));
    }

    /** NSUK 的 {@code CommercialVisibility.fromName}：{@code both}/{@code all} 读作 mixed，其它非法值读作 player。 */
    private static String canonicalVisibility(String raw) {
        if (raw == null || raw.isBlank()) {
            return "player";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "npc" -> "npc";
            case "mixed", "both", "all" -> "mixed";
            default -> "player";
        };
    }

    /** 该拼写是否是 NSUK 承认的规范值。 */
    public static boolean isCanonicalVisibility(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return "player".equals(normalized) || "npc".equals(normalized) || "mixed".equals(normalized);
    }

    // ── 资源（cost / result / materials 的叶子） ────────────────────────

    /** 资源的两种形态，互斥。 */
    public enum ResourceKind {
        /** 城市资金。 */
        MONEY,
        /** 物品。 */
        ITEM
    }

    /** 取某个数组（{@code cost} / {@code result} / {@code materials}），不存在时按 create 决定。 */
    public static JsonArray arrayOf(JsonObject owner, String key, String[] aliases, boolean create) {
        JsonElement existing = value(owner, key, aliases);
        if (existing != null && existing.isJsonArray()) {
            return existing.getAsJsonArray();
        }
        if (!create) {
            return null;
        }
        JsonArray created = new JsonArray();
        owner.add(key, created);
        return created;
    }

    /** 报价的 {@code cost} 数组（兼容别名 {@code costs}）。 */
    public static JsonArray costArray(JsonObject offer, boolean create) {
        return arrayOf(offer, COST, COST_ALIASES, create);
    }

    /** 报价的 {@code result} 数组（兼容别名 {@code results}）。 */
    public static JsonArray resultArray(JsonObject offer, boolean create) {
        return arrayOf(offer, RESULT, RESULT_ALIASES, create);
    }

    /** 库存的 {@code materials} 数组（兼容别名）。 */
    public static JsonArray materialsArray(JsonObject stock, boolean create) {
        return arrayOf(stock, MATERIALS, MATERIALS_ALIASES, create);
    }

    /**
     * 资源形态：<b>有 {@code money} 键就是资金</b>，与 NSUK 的存在性判断一致
     * （{@code CommercialDefinitionLoader:224}）。
     */
    public static ResourceKind kindOf(JsonObject resource) {
        return resource != null && resource.has(MONEY) ? ResourceKind.MONEY : ResourceKind.ITEM;
    }

    /** 读资金数额；不是资金形态时为 0。 */
    public static double moneyOf(JsonObject resource) {
        return kindOf(resource) == ResourceKind.MONEY ? doubleOr(resource, MONEY, 0.0D) : 0.0D;
    }

    /** 读物品 id；不是物品形态时为空串。 */
    public static String itemOf(JsonObject resource) {
        return kindOf(resource) == ResourceKind.ITEM ? stringOr(resource, ITEM, "") : "";
    }

    /** 读数量（兼容别名 {@code amount}，缺省 1）；不是物品形态时为 0。 */
    public static int countOf(JsonObject resource) {
        if (kindOf(resource) != ResourceKind.ITEM) {
            return 0;
        }
        double raw = number(resource, COUNT, COUNT_ALIASES, 1.0D);
        return (int) Math.max(1, raw);
    }

    /** 该资源按 NSUK 的规则是否有效（<b>不含</b>物品注册表检查）。 */
    public static boolean resourceValid(JsonObject resource) {
        if (resource == null) {
            return false;
        }
        return switch (kindOf(resource)) {
            case MONEY -> moneyOf(resource) > 0.0D;
            case ITEM -> !itemOf(resource).isBlank() && countOf(resource) > 0;
        };
    }

    /**
     * 把一条资源改写成资金形态。
     *
     * <p>刻意<b>先清掉</b> {@code item}/{@code count}：否则元素会同时带 {@code money} 与
     * {@code item}，而 NSUK 会静默忽略后者（正是 {@code money_shadows_item} 那条校验）。
     */
    public static void writeMoney(JsonObject resource, double amount) {
        putCanonical(resource, ITEM, new String[0], null);
        putCanonical(resource, COUNT, COUNT_ALIASES, null);
        resource.addProperty(MONEY, amount);
    }

    /** 把一条资源改写成物品形态；同样先清掉 {@code money}。 */
    public static void writeItem(JsonObject resource, String itemId, int count) {
        putCanonical(resource, MONEY, new String[0], null);
        putCanonical(resource, ITEM, new String[0], itemId);
        putCanonical(resource, COUNT, COUNT_ALIASES, Integer.toString(Math.max(1, count)));
    }

    /** 追加一条资金资源。 */
    public static JsonObject addMoneyResource(JsonArray array, double amount) {
        JsonObject resource = new JsonObject();
        resource.addProperty(MONEY, amount);
        array.add(resource);
        return resource;
    }

    /** 追加一条物品资源。 */
    public static JsonObject addItemResource(JsonArray array, String itemId, int count) {
        JsonObject resource = new JsonObject();
        resource.addProperty(ITEM, itemId);
        resource.addProperty(COUNT, Math.max(1, count));
        array.add(resource);
        return resource;
    }

    // ── 库存 ───────────────────────────────────────────────────────────

    /** 库存对象的三种模式（判据见 {@code CommercialOffer.StockRule:81-93}）。 */
    public enum StockMode {
        /** {@code materials} 非空：从控制盒附近容器实时扣材料，自动补货字段被忽略。 */
        MATERIAL_BACKED,
        /** 有 item 且 max &gt; 0：写入 SQLite 库存表，按间隔自动补货。 */
        SQLITE_BACKED,
        /** item 为空或 max &le; 0：该物品不受库存限制，也不写入库存表。 */
        NONE
    }

    /** 取库存对象；不存在时按 create 决定是否创建一个。 */
    public JsonObject stock(JsonObject offer, boolean create) {
        JsonElement existing = offer.get(STOCK);
        if (existing != null && existing.isJsonObject()) {
            return existing.getAsJsonObject();
        }
        if (!create) {
            return null;
        }
        JsonObject created = new JsonObject();
        offer.add(STOCK, created);
        return created;
    }

    /** 删除库存规则（库存是可选键，「没有」是合法状态）。 */
    public boolean removeStock(JsonObject offer) {
        return offer.remove(STOCK) != null;
    }

    /** 库存物品 id。 */
    public static String stockItem(JsonObject stock) {
        return stock == null ? "" : stringOr(stock, ITEM, "");
    }

    /** 库存上限；负数按 0 处理（NSUK 会 {@code Math.max(0, …)}）。 */
    public static int stockMax(JsonObject stock) {
        return stock == null ? 0 : Math.max(0, (int) doubleOr(stock, MAX, 0.0D));
    }

    /** 初始库存（NSUK 会夹到 {@code [0, max]}）。 */
    public static int stockInitial(JsonObject stock) {
        return stock == null ? 0 : Math.max(0, (int) doubleOr(stock, INITIAL, 0.0D));
    }

    /** 是否由材料实时供给库存。 */
    public static boolean isMaterialBacked(JsonObject stock) {
        return stock != null && materialsArray(stock, false) != null
                && !materialsArray(stock, false).isEmpty();
    }

    /** 是否由 SQLite 库存表维护。 */
    public static boolean isSqliteBacked(JsonObject stock) {
        return !isMaterialBacked(stock) && !stockItem(stock).isBlank() && stockMax(stock) > 0;
    }

    /** 是否会按间隔自动补货。 */
    public static boolean restockEnabled(JsonObject stock) {
        if (!isSqliteBacked(stock)) {
            return false;
        }
        int amount = (int) number(stock, RESTOCK_AMOUNT, RESTOCK_AMOUNT_ALIASES, 0.0D);
        long interval = (long) number(stock, RESTOCK_INTERVAL, RESTOCK_INTERVAL_ALIASES, 0.0D);
        return amount > 0 && interval > 0L;
    }

    /**
     * 把资金金额格式化成<b>槽位角标</b>用的短文本。
     *
     * <p>照抄 NSUK 的 {@code CommercialTradeUiSupport.moneyShort}
     * （它的注释就是「格式化槽位角标资金金额」）：先 {@code "%.2f"}，
     * 再去掉尾随的 0 和小数点。于是 {@code 3.2 → "3.2"}、{@code 3.0 → "3"}、
     * {@code 0.0 → "0"} —— 角标位置很窄，{@code "3.20"} 会顶到相邻图标上。
     *
     * <p>放在数据层是因为它是纯计算：不碰 Minecraft，可以离线单测
     * （尾随 0 的处理有好几个边界，值得锁住）。
     */
    public static String shortMoney(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (text.contains(".") && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** 库存模式。 */
    public static StockMode stockMode(JsonObject stock) {
        if (isMaterialBacked(stock)) {
            return StockMode.MATERIAL_BACKED;
        }
        return isSqliteBacked(stock) ? StockMode.SQLITE_BACKED : StockMode.NONE;
    }

    /**
     * 库存物品在一条报价里的<b>流向</b>。
     *
     * <p>存在的理由：同一组库存字段（{@code max} / {@code initial} / {@code restockAmount}）
     * 在两种流向下含义<b>相反</b>，而 NSUK 就是按物品在哪一侧来判断的：
     *
     * <pre>
     * itemLeavesStock() = stock.item 出现在 result   // 成交时从库存扣（出售）
     * itemEntersStock() = stock.item 出现在 cost     // 成交时往库存加（收购）
     * </pre>
     *
     * <p>净流向按 {@code CommercialTradeSupplyService.stockDeltas} 算
     * （{@code cost} 记 {@code +}、{@code result} 记 {@code −}），净值为 0 时才退回
     * {@code itemLeavesStock()} 的「在 result 即算出库」—— 两条判据都是 NSUK 自己的，不是本类的发明。
     */
    public enum StockFlow {
        /** 出库：成交时从库存扣。{@code restockAmount} 到点把货补回来。 */
        LEAVES,
        /**
         * 入库：成交时往库存加（收购）。
         *
         * <p>此时 {@code currentStock} 记的是<b>累计已收</b>：{@code max} 是「总共还会收多少」，
         * 而 {@code restockAmount} 只会把这个计数<b>往上推</b> —— 补得越勤，收购站越早满仓停收。
         * NSUK 的补货不可能为负（构造时 {@code Math.max(0, …)}，{@code add()} 忽略非正数），
         * 所以入库型库存<b>没有</b>「按间隔刷新」这种配置。
         */
        ENTERS,
        /** 两侧都没有该物品：这条规则永远不会被触发（校验报 {@code stock_orphan_item}）。 */
        NONE
    }

    /** 某个物品在一条报价里的净数量（{@code cost} 记 {@code +}、{@code result} 记 {@code −}）。 */
    public static int stockNetDelta(JsonObject offer, String itemId) {
        if (offer == null || itemId == null || itemId.isBlank()) {
            return 0;
        }
        return itemCount(costArray(offer, false), itemId) - itemCount(resultArray(offer, false), itemId);
    }

    /** 库存物品的流向（见 {@link StockFlow}）。 */
    public static StockFlow stockFlow(JsonObject offer, String itemId) {
        if (offer == null || itemId == null || itemId.isBlank()) {
            return StockFlow.NONE;
        }
        int net = stockNetDelta(offer, itemId);
        if (net > 0) {
            return StockFlow.ENTERS;
        }
        if (net < 0) {
            return StockFlow.LEAVES;
        }
        // 净值为 0（两侧同量）：按 itemLeavesStock() 的判据，出现在 result 即算出库。
        if (anyItemMatching(resultArray(offer, false), itemId)) {
            return StockFlow.LEAVES;
        }
        return anyItemMatching(costArray(offer, false), itemId) ? StockFlow.ENTERS : StockFlow.NONE;
    }

    /** 同上，物品 id 从 {@code stock} 里取。 */
    public static StockFlow stockFlow(JsonObject offer, JsonObject stock) {
        return stockFlow(offer, stockItem(stock));
    }

    /** 新建库存规则时的默认上限。 */
    public static final int DEFAULT_STOCK_MAX = 64;
    /** 切到「自动补货」时的默认每次补货量。 */
    public static final int DEFAULT_RESTOCK_AMOUNT = 4;
    /** 切到「自动补货」时的默认间隔（服务器运行 tick，12000 ≈ 10 分钟）。 */
    public static final int DEFAULT_RESTOCK_INTERVAL = 12000;

    /**
     * 库存的<b>供给方式</b> —— 界面上那个下拉框的四个选项。
     *
     * <p>为什么要单独定义它：NSUK 那边「库存」和「补货」不是并列关系，而是<b>两层</b>：
     *
     * <pre>
     * 供给方式（二选一）       材料供给  /  SQLite 库存
     * SQLite 库存下是否补货    卖完即止  /  自动补货
     * </pre>
     *
     * <p>判定依据是 {@code CommercialOffer.StockRule} 的三个方法：
     * {@code materialBacked() = !materials.isEmpty()}；
     * {@code sqliteBacked() = !materialBacked() && item 非空 && max > 0}；
     * {@code restockEnabled() = sqliteBacked() && restockAmount > 0 && restockInterval > 0}。
     * 所以<b>补货本来就依赖库存</b>，两者不是互斥的选项 —— 它们完全能共存。
     *
     * <p>把它们摊平成四个互斥选项，是因为「材料供给」一旦成立就会让
     * {@code initial/restockAmount/restockInterval} <b>全部失效</b>：
     * 与其让用户填一堆被忽略的字段，不如用一次选择决定「这一组字段该不该出现」。
     */
    public enum SupplyMode {
        /** 没有 {@code stock} 键：不受库存限制，也不写入库存表。 */
        UNLIMITED,
        /**
         * SQLite 库存。补货字段<b>可留空</b>：填了就是自动补货，空着就是卖完即止。
         *
         * <p>刻意不再把「卖完即止」与「自动补货」拆成两个选项 ——
         * 它们本来就是同一套键下「补货参数有没有填」的两种状态
         * （NSUK：{@code restockEnabled() = sqliteBacked() && restockAmount > 0 && restockInterval > 0}）。
         * 拆成两个选项反而要求切换时删键，而合并之后「清空输入框」就直接表达了「关掉补货」。
         */
        STOCK,
        /**
         * 材料供给：成交时从控制盒附近容器实时扣除原材料，绕开 SQLite。
         *
         * <p><b>只对出库型（{@link StockFlow#LEAVES}）成立</b>：NSUK 的
         * {@code usesMaterialSupply()} 要求 {@code itemLeavesStock()}。写在入库型（收购）上
         * 材料既不会被扣，{@code materialBacked()} 又让 {@code sqliteBacked()} 变 false ——
         * 上限会静默失效（校验报 {@code intake_materials_ignored}）。
         */
        MATERIALS;

        /** 从现有 {@code stock} 反推当前供给方式；{@code stock} 为 null 即「不受限」。 */
        public static SupplyMode of(JsonObject stock) {
            if (stock == null) {
                return UNLIMITED;
            }
            if (isMaterialBacked(stock)) {
                return MATERIALS;
            }
            // 有 stock 对象但 max<=0 或物品为空 —— 规则实际不生效，如实报「不受限」。
            return isSqliteBacked(stock) ? STOCK : UNLIMITED;
        }
    }

    /** 该供给方式下用户是否该看到「初始」字段（材料模式忽略它）。 */
    public static boolean showsInitial(SupplyMode mode) {
        return mode == SupplyMode.STOCK;
    }

    /** 该供给方式下用户是否该看到补货字段（可留空 = 卖完即止）。 */
    public static boolean showsRestock(SupplyMode mode) {
        return mode == SupplyMode.STOCK;
    }

    /** 该供给方式下用户是否该看到材料列表。{@code max} 在材料模式下<b>仍然有效</b>（也是上限）。 */
    public static boolean showsMaterials(SupplyMode mode) {
        return mode == SupplyMode.MATERIALS;
    }

    /**
     * 把一条 {@code stock} 改造成指定供给方式下的「干净初始状态」。
     *
     * <p>因为「卖完即止」与「自动补货」已合并成同一个 {@link SupplyMode#STOCK}，
     * 这里<b>不再碰补货字段</b> —— 它们空着就是卖完即止，由用户清空输入框来表达
     * （见 {@code CommercialTradePanel.writeRestock}：留空即删键）。
     *
     * <p>唯一必须删的是材料：材料非空会让整条规则变成材料供给型，
     * 那样 {@code STOCK} 模式根本立不住（NSUK：{@code sqliteBacked() = !materialBacked() && …}）。
     *
     * @return true 表示这个 {@code stock} 应当被整个移除（即选到了 {@link SupplyMode#UNLIMITED}）
     */
    public static boolean applySupplyMode(JsonObject stock, SupplyMode mode) {
        if (stock == null || mode == SupplyMode.UNLIMITED) {
            return true;
        }
        // 两种非材料模式之间来回切时保留 initial（两者都用它）。
        boolean keepInitial = showsInitial(mode) && stock.has(INITIAL);
        int previousInitial = keepInitial ? stock.get(INITIAL).getAsInt() : 0;

        stock.remove(MATERIALS);
        stock.remove("requiredMaterials");
        stock.remove("required_materials");

        int max = stock.has(MAX) && stock.get(MAX).getAsInt() > 0
                ? stock.get(MAX).getAsInt() : DEFAULT_STOCK_MAX;
        stock.addProperty(MAX, max);
        if (keepInitial) {
            stock.addProperty(INITIAL, Math.clamp(previousInitial, 0, max));
        } else {
            stock.remove(INITIAL);
        }
        if (mode == SupplyMode.MATERIALS) {
            // 材料非空才算「材料供给型」，所以必须至少给出一行，否则这个模式根本不成立。
            // 材料模式下 initial 与补货字段全部失效，一并清掉，免得留下被忽略的键
            // （否则校验会报 materials_disable_restock —— 编辑器不该指责自己写出的状态）。
            stock.remove(RESTOCK_AMOUNT);
            stock.remove(RESTOCK_AMOUNT_ALIAS);
            stock.remove(RESTOCK_INTERVAL);
            stock.remove(RESTOCK_INTERVAL_ALIAS);
            JsonArray materials = new JsonArray();
            JsonObject material = new JsonObject();
            String seed = stockItem(stock);
            material.addProperty(ITEM, seed.isBlank() ? "minecraft:stone" : seed);
            material.addProperty(COUNT, 1);
            materials.add(material);
            stock.add(MATERIALS, materials);
        }
        return false;
    }

    /**
     * 一处物品 id 引用。
     *
     * @param itemId   物品 id
     * @param location 位置，形如 {@code offers[0].cost[1].item}，便于界面报错时定位
     */
    public record ItemRef(String itemId, String location) {
    }

    /**
     * 列出这份配置里出现的**全部**物品 id 及其位置（不含空串）。
     *
     * <p>存在的理由是一条分层妥协：NSUK 的 {@code CommercialResource.valid()} 会查物品注册表
     * （{@code item() != Items.AIR}），**而注册表是 Minecraft 的东西，不能进数据层** ——
     * 否则这个类就不再能脱离客户端跑单测。所以数据层只负责「把 id 和它的位置列出来」，
     * 由界面层去查注册表。这样既补上了唯一那个检查缺口，又保住了可离线单测。
     *
     * <p>覆盖四处：{@code cost[].item}、{@code result[].item}、
     * {@code stock.item}、{@code stock.materials[].item}。
     */
    public List<ItemRef> itemIds() {
        JsonArray array = offersArray(false);
        if (array == null) {
            return List.of();
        }
        List<ItemRef> refs = new ArrayList<>();
        int limit = Math.min(array.size(), MAX_OFFERS);
        for (int index = 0; index < limit; index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject offer = element.getAsJsonObject();
            String where = OFFERS + "[" + index + "]";
            collectItemIds(refs, costArray(offer, false), where + "." + COST);
            collectItemIds(refs, resultArray(offer, false), where + "." + RESULT);
            JsonObject stock = stock(offer, false);
            if (stock == null) {
                continue;
            }
            String stockWhere = where + "." + STOCK;
            if (!stockItem(stock).isBlank()) {
                refs.add(new ItemRef(stockItem(stock), stockWhere + "." + ITEM));
            }
            collectItemIds(refs, materialsArray(stock, false), stockWhere + "." + MATERIALS);
        }
        return List.copyOf(refs);
    }

    /** 收集一个资源数组里的物品 id（材料数组的元素形状与资源相同）。 */
    private static void collectItemIds(List<ItemRef> refs, JsonArray array, String where) {
        if (array == null) {
            return;
        }
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject resource = element.getAsJsonObject();
            if (kindOf(resource) == ResourceKind.ITEM && !itemOf(resource).isBlank()) {
                refs.add(new ItemRef(itemOf(resource), where + "[" + index + "]." + ITEM));
            }
        }
    }

    /**
     * 列出已经被库存规则跟踪的物品 id（{@code stock.item} 非空且不等于 {@code except}）。
     *
     * <p>存在的理由：NSUK 的库存记录键是<b>两级的</b> ——
     * {@code CommercialStockManager} 里是
     * {@code ConcurrentMap<BlockPos, ConcurrentMap<String itemId, CommercialStockData>>}，
     * 也就是「<b>哪个商业控制盒</b>」+「<b>哪个物品</b>」。
     *
     * <p>于是：<b>同一个建筑内</b>，多条报价跟踪同一个物品会共用同一条库存记录，
     * 而初始化时 {@code putIfAbsent(rule.itemId(), rule)} 只保留<b>第一次遇到</b>的规则 ——
     * 后面那些规则里的 {@code initial/restockAmount/restockInterval} 全部不生效
     * （{@code max} 例外：{@code ensureStock} 每次都会把 maxStock 同步成规则里的 max）。
     * 不同建筑之间则各自独立。
     *
     * <p>而「复制交易」正是最容易制造同物品重复规则的操作：复制出来的副本连 {@code stock}
     * 一起复制，于是同一物品在一个建筑里被跟踪 N 次、其中 N-1 条是死的。
     * 界面在新建库存规则时用这个方法避开已被跟踪的物品，就不会一新建就是死规则。
     */
    public List<String> trackedStockItems(String except) {
        List<String> tracked = new ArrayList<>();
        for (JsonObject offer : offers()) {
            JsonObject stock = stock(offer, false);
            if (stock == null) {
                continue;
            }
            String item = stockItem(stock);
            // except 是**物品 id**（不是某条报价），所以这里排除的是「这个物品」，
            // 用于「同一条报价自己已有的规则不算冲突」。
            if (!item.isBlank() && !item.equals(except) && !tracked.contains(item)) {
                tracked.add(item);
            }
        }
        return List.copyOf(tracked);
    }

    /**
     * 给「新建库存规则」挑一个合理的库存物品：产出里的第一个物品，其次成本里的。
     *
     * <p>存在的意义是<b>从构造上避开两个坑</b>：
     * <ol>
     *   <li>{@code stock.item} 若既不在 {@code cost} 也不在 {@code result} 里，这条规则
     *       什么都不做（{@code itemLeavesStock} 与 {@code itemEntersStock} 都是假，
     *       校验报 {@code stock_orphan_item}）；</li>
     *   <li>库存记录键是「控制盒 + 物品」，所以<b>同一个建筑内</b>同一物品被多条报价
     *       跟踪时只有第一条的规则生效（校验报 {@code duplicate_stock_item}）。</li>
     * </ol>
     *
     * <p>所以优先挑「真实出现过<b>且还没被别的报价跟踪</b>」的物品；实在挑不到
     * （例如所有物品都已被跟踪）才退回「出现过但已被跟踪」的第一个 —— 那时会有明确的
     * 重复警告，而不是悄悄给出一条死规则。
     *
     * @param alreadyTracked 已被其它报价跟踪的物品 id（见 {@link #trackedStockItems}）
     * @return 候选物品 id；两侧都没有物品时返回空串（由调用方决定兜底值）
     */
    public static String suggestStockItem(JsonObject offer, List<String> alreadyTracked) {
        if (offer == null) {
            return "";
        }
        List<String> candidates = new ArrayList<>();
        for (JsonArray array : new JsonArray[]{resultArray(offer, false), costArray(offer, false)}) {
            if (array == null) {
                continue;
            }
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject resource = element.getAsJsonObject();
                if (kindOf(resource) == ResourceKind.ITEM && !itemOf(resource).isBlank()) {
                    String item = itemOf(resource);
                    if (!candidates.contains(item)) {
                        candidates.add(item);
                    }
                }
            }
        }
        for (String item : candidates) {
            if (alreadyTracked == null || !alreadyTracked.contains(item)) {
                return item;
            }
        }
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    /**
     * 同上，但不考虑重复（等价于 {@code alreadyTracked} 为空）。
     *
     * @return 候选物品 id；两侧都没有物品时返回空串（由调用方决定兜底值）
     */
    public static String suggestStockItem(JsonObject offer) {
        return suggestStockItem(offer, List.of());
    }

    // ── 交易形态（Tab 归类） ───────────────────────────────────────────

    /**
     * 交易界面左侧的三个 Tab。
     *
     * <p><b>归类并非互斥</b>：判据逐条照抄 {@code CommercialTradeOfferTab.matches:22-32}，
     * 其中 SELL 与 BUY 互相独立，一条报价可以同时命中多个 Tab。
     * 只有 {@code BARTER} 要求两侧都没有资金。
     */
    public enum TradeTab {
        /** 出售：成本含资金，产出含物品。 */
        SELL("gui.simupack.commercial.shape.sell"),
        /** 收购：成本含物品，产出含资金。 */
        BUY("gui.simupack.commercial.shape.buy"),
        /** 交换：两侧都是物品且都没有资金。 */
        BARTER("gui.simupack.commercial.shape.barter");

        private final String labelKey;

        TradeTab(String labelKey) {
            this.labelKey = labelKey;
        }

        /** 界面上显示这个 Tab 用的语言键。 */
        public String labelKey() {
            return labelKey;
        }
    }

    private static boolean anyMoney(JsonArray array) {
        if (array == null) {
            return false;
        }
        for (JsonElement element : array) {
            if (element.isJsonObject() && kindOf(element.getAsJsonObject()) == ResourceKind.MONEY) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyItem(JsonArray array) {
        if (array == null) {
            return false;
        }
        for (JsonElement element : array) {
            if (element.isJsonObject() && kindOf(element.getAsJsonObject()) == ResourceKind.ITEM) {
                return true;
            }
        }
        return false;
    }

    /** 该报价是否属于某个 Tab（判据与 NSUK 一致）。 */
    public static boolean matches(JsonObject offer, TradeTab tab) {
        if (offer == null) {
            return false;
        }
        boolean costMoney = anyMoney(costArray(offer, false));
        boolean resultMoney = anyMoney(resultArray(offer, false));
        boolean costItem = anyItem(costArray(offer, false));
        boolean resultItem = anyItem(resultArray(offer, false));
        return switch (tab) {
            case SELL -> costMoney && resultItem;
            case BUY -> costItem && resultMoney;
            case BARTER -> costItem && resultItem && !costMoney && !resultMoney;
        };
    }

    /** 该报价命中的全部 Tab（顺序固定为 SELL / BUY / BARTER）。 */
    public static Set<TradeTab> tabsOf(JsonObject offer) {
        Set<TradeTab> tabs = new LinkedHashSet<>();
        for (TradeTab tab : TradeTab.values()) {
            if (matches(offer, tab)) {
                tabs.add(tab);
            }
        }
        return tabs;
    }

    /**
     * 该报价在交易界面里一个 Tab 都进不去。
     *
     * <p>这是真实的坑：报价本身能通过 NSUK 的 {@code valid()}，但玩家永远看不到它。
     */
    public static boolean isUnclassified(JsonObject offer) {
        return tabsOf(offer).isEmpty();
    }

    // ── 校验 ───────────────────────────────────────────────────────────

    /**
     * 静态校验。
     *
     * <p><b>分层说明（不能破的边界）：</b>本方法只看 JSON 的形状、上限与一致性，
     * <b>不查物品是否已注册</b> —— 那需要 {@code BuiltInRegistries}，属界面层。
     * 因此「物品 id 拼错」在这里不会被报出来，界面层需要再跑一遍注册表检查。
     * 这条边界的代价是少报一类错，收益是数据层保持可离线单测。
     */
    public List<DefinitionIssue> validate() {
        List<DefinitionIssue> issues = new ArrayList<>();
        JsonArray array = offersArray(false);

        if (array == null || array.isEmpty()) {
            issues.add(DefinitionIssue.error("missing_offers", OFFERS, ""));
            return issues;
        }
        if (array.size() > MAX_OFFERS) {
            issues.add(DefinitionIssue.error("too_many_offers", OFFERS + ".size",
                    Integer.toString(array.size())));
        }

        int valid = 0;
        Set<String> seenIds = new LinkedHashSet<>();
        Map<String, String> firstStockItemLocation = new LinkedHashMap<>();
        Set<String> resultItemsWithStock = new LinkedHashSet<>();
        Set<String> costItemsWithStock = new LinkedHashSet<>();
        Map<String, Integer> stockItemCount = new LinkedHashMap<>();

        for (int index = 0; index < Math.min(array.size(), MAX_OFFERS); index++) {
            JsonElement element = array.get(index);
            String where = OFFERS + "[" + index + "]";
            if (!element.isJsonObject()) {
                issues.add(DefinitionIssue.error("invalid_offer", where, ""));
                continue;
            }
            JsonObject offer = element.getAsJsonObject();
            String id = stringOr(offer, "id", "");
            if (id.isBlank()) {
                issues.add(new DefinitionIssue(DefinitionIssue.Severity.INFO,
                        "empty_offer_id", where, ""));
            } else if (!seenIds.add(id)) {
                issues.add(DefinitionIssue.warning("duplicate_offer_id", where + ".id", id));
            }

            String rawVisibility = rawVisibility(offer);
            if (!rawVisibility.isBlank() && !isCanonicalVisibility(rawVisibility)) {
                String normalized = canonicalVisibility(rawVisibility);
                boolean knownAlias = normalized.equals(rawVisibility.trim().toLowerCase(Locale.ROOT));
                issues.add(knownAlias
                        ? new DefinitionIssue(DefinitionIssue.Severity.INFO, "visible_alias",
                                where + "." + VISIBLE_TO, rawVisibility)
                        : DefinitionIssue.warning("visible_unknown", where + "." + VISIBLE_TO, rawVisibility));
            }

            JsonArray cost = arrayAt(offer, COST, COST_ALIASES);
            JsonArray result = arrayAt(offer, RESULT, RESULT_ALIASES);
            boolean costOk = validateResources(issues, cost, where + "." + COST, "cost");
            boolean resultOk = validateResources(issues, result, where + "." + RESULT, "result");
            if (cost == null || cost.isEmpty()) {
                issues.add(DefinitionIssue.error("empty_cost", where + "." + COST, ""));
            }
            if (result == null || result.isEmpty()) {
                issues.add(DefinitionIssue.error("empty_result", where + "." + RESULT, ""));
            }
            if (costOk && resultOk && cost != null && !cost.isEmpty() && result != null && !result.isEmpty()) {
                valid++;
            }

            if (isUnclassified(offer) && costOk && resultOk) {
                issues.add(DefinitionIssue.warning("unclassified_offer", where, id));
            }

            JsonObject stock = stock(offer, false);
            validateStock(issues, offer, stock, where, stockItemCount);

            // 收集「产出物品有没有库存规则」，跨报价判断。
            if (result != null) {
                for (JsonElement resource : result) {
                    if (!resource.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = resource.getAsJsonObject();
                    if (kindOf(object) != ResourceKind.ITEM) {
                        continue;
                    }
                    String itemId = itemOf(object);
                    if (stock != null && itemId.equals(stockItem(stock)) && !itemId.isBlank()) {
                        resultItemsWithStock.add(itemId);
                    }
                }
            }

            // 收购侧对称的一半：库存物品同时也是成本里的那个物品（成交时进库存）。
            if (cost != null && stock != null && !stockItem(stock).isBlank()) {
                String trackedItem = stockItem(stock);
                for (JsonElement resource : cost) {
                    if (!resource.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = resource.getAsJsonObject();
                    if (kindOf(object) == ResourceKind.ITEM && trackedItem.equals(itemOf(object))) {
                        costItemsWithStock.add(trackedItem);
                    }
                }
            }
        }

        if (valid == 0) {
            // 最危险的一条：NSUK 见到解析结果为空会静默改用旧格式解析器，
            // 「全写坏了」和「offers: []」在那里完全等价。
            issues.add(DefinitionIssue.error("offers_legacy_fallback", OFFERS, ""));
        }

        // 同一物品在多个报价里带不同库存规则时，只有第一次遇到的生效。
        for (Map.Entry<String, Integer> entry : stockItemCount.entrySet()) {
            if (entry.getValue() > 1) {
                issues.add(DefinitionIssue.warning("duplicate_stock_item", OFFERS, entry.getKey()));
            }
        }

        // 产出的物品在全库都没有库存规则 → 不受库存限制（文档 :277）。
        for (JsonObject offer : offers()) {
            JsonArray result = resultArray(offer, false);
            if (result == null) {
                continue;
            }
            for (JsonElement resource : result) {
                if (!resource.isJsonObject()) {
                    continue;
                }
                JsonObject object = resource.getAsJsonObject();
                if (kindOf(object) != ResourceKind.ITEM) {
                    continue;
                }
                String itemId = itemOf(object);
                if (!itemId.isBlank() && !resultItemsWithStock.contains(itemId)) {
                    issues.add(DefinitionIssue.warning("result_without_stock", OFFERS, itemId));
                    break;
                }
            }
        }

        // 对称的另一半：收购的物品在全库都没有库存规则 → 不限量收货（同样是经济漏洞）。
        // 只看「收购」形态（成本含物品、产出含资金），免得把交换里那些本来就不跟踪的成本物品算进来。
        for (JsonObject offer : offers()) {
            if (!matches(offer, TradeTab.BUY)) {
                continue;
            }
            JsonArray cost = costArray(offer, false);
            if (cost == null) {
                continue;
            }
            for (JsonElement resource : cost) {
                if (!resource.isJsonObject()) {
                    continue;
                }
                JsonObject object = resource.getAsJsonObject();
                if (kindOf(object) != ResourceKind.ITEM) {
                    continue;
                }
                String itemId = itemOf(object);
                if (!itemId.isBlank() && !costItemsWithStock.contains(itemId)) {
                    issues.add(DefinitionIssue.warning("intake_without_stock", OFFERS, itemId));
                    break;
                }
            }
        }

        validateLegacyKeys(issues);
        validateJobShadowing(issues);
        validateContainers(issues);
        return issues;
    }

    /** 校验一侧资源数组；返回该侧是否「每条都有效」。 */
    private boolean validateResources(List<DefinitionIssue> issues, JsonArray array,
                                      String where, String label) {
        if (array == null) {
            return false;
        }
        if (array.size() > MAX_RESOURCES) {
            issues.add(DefinitionIssue.error("too_many_resources", where, Integer.toString(array.size())));
        }
        boolean ok = true;
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String at = where + "[" + index + "]";
            if (!element.isJsonObject()) {
                issues.add(DefinitionIssue.error("invalid_resource", at, ""));
                ok = false;
                continue;
            }
            JsonObject resource = element.getAsJsonObject();
            boolean hasMoney = resource.has(MONEY);
            boolean hasItem = resource.has(ITEM);
            if (hasMoney && hasItem) {
                // NSUK 用「存在性」判断，item 会被静默忽略 —— 用户写的物品不会生效。
                issues.add(DefinitionIssue.error("money_shadows_item", at, itemOf(resource)));
            }
            switch (kindOf(resource)) {
                case MONEY -> {
                    if (moneyOf(resource) <= 0.0D) {
                        issues.add(DefinitionIssue.error("invalid_money", at,
                                Double.toString(moneyOf(resource))));
                        ok = false;
                    }
                }
                case ITEM -> {
                    if (itemOf(resource).isBlank()) {
                        issues.add(DefinitionIssue.error("invalid_item", at, ""));
                        ok = false;
                    }
                    double rawCount = number(resource, COUNT, COUNT_ALIASES, 1.0D);
                    if (rawCount < 1.0D) {
                        // 不是致命错：NSUK 会 Math.max(1, …)，但用户写的值会变。
                        issues.add(DefinitionIssue.warning("count_clamped", at + "." + COUNT,
                                trim(rawCount)));
                    }
                }
            }
        }
        return ok;
    }

    /** 校验库存规则。 */
    private void validateStock(List<DefinitionIssue> issues, JsonObject offer, JsonObject stock,
                               String where, Map<String, Integer> stockItemCount) {
        if (stock == null) {
            return;
        }
        String at = where + "." + STOCK;
        String itemId = stockItem(stock);
        int max = stockMax(stock);

        if (itemId.isBlank()) {
            issues.add(new DefinitionIssue(DefinitionIssue.Severity.INFO, "stock_no_tracking", at, ""));
        } else {
            stockItemCount.merge(itemId, 1, Integer::sum);
            // 库存物品既不在 cost 也不在 result 里 → 这条规则什么都不做。
            boolean inCost = anyItemMatching(costArray(offer, false), itemId);
            boolean inResult = anyItemMatching(resultArray(offer, false), itemId);
            if (!inCost && !inResult) {
                issues.add(DefinitionIssue.warning("stock_orphan_item", at + "." + ITEM, itemId));
            }
            if (max <= 0) {
                issues.add(new DefinitionIssue(DefinitionIssue.Severity.INFO,
                        "stock_no_tracking", at + ".max", ""));
            }
        }

        double rawInitial = doubleOr(stock, "initial", 0.0D);
        if (rawInitial > max) {
            issues.add(DefinitionIssue.warning("initial_over_max", at + ".initial",
                    trim(rawInitial) + " > " + max));
        }

        // 入库型（收购）的库存是「累计已收」，同一组字段的含义与出库型相反，而且没有补救手段：
        // NSUK 的补货只能加（restockAmount 构造时 Math.max(0, …)，StockData.add() 又忽略非正数），
        // 所以「定时刷新库存」这件事在收购上根本无法用配置表达。
        if (stockFlow(offer, itemId) == StockFlow.ENTERS) {
            if (max > 0 && stockInitial(stock) >= max) {
                // current + 数量 > maxStock 就报 stock_full 并拒收，而 current 开局就等于上限。
                issues.add(DefinitionIssue.warning("intake_stock_full_at_start", at, itemId));
            }
            if (restockEnabled(stock)) {
                // 补货把「已收」往上推：补得越勤，越早满仓、越早停收。
                issues.add(DefinitionIssue.warning("intake_restock_raises_stock", at, itemId));
            }
        }

        JsonArray materials = materialsArray(stock, false);
        if (materials != null && !materials.isEmpty()) {
            // materials 非空 → materialBacked 覆盖 sqliteBacked：
            // initial / restockAmount / restockInterval 全被忽略。
            boolean wroteRestock = stock.has("initial") || stock.has("restockAmount")
                    || stock.has("restock_amount") || stock.has("restockInterval")
                    || stock.has("restock_interval");
            if (wroteRestock) {
                issues.add(DefinitionIssue.warning("materials_disable_restock", at, ""));
            }
            if (stockFlow(offer, itemId) == StockFlow.ENTERS) {
                // NSUK 的 usesMaterialSupply() 要求 itemLeavesStock()：入库型上材料既不会被扣除，
                // 又因为 materialBacked() 让 sqliteBacked() 变 false → 库存记录根本不建
                // → validateSqliteStock 遇到 data == null 直接跳过 → 上限静默失效（不限量收购）。
                issues.add(DefinitionIssue.warning("intake_materials_ignored", at, itemId));
            }
            if (materials.size() > MAX_RESOURCES) {
                issues.add(DefinitionIssue.error("too_many_materials", at + "." + MATERIALS,
                        Integer.toString(materials.size())));
            }
            for (int index = 0; index < materials.size(); index++) {
                JsonElement element = materials.get(index);
                String materialAt = at + "." + MATERIALS + "[" + index + "]";
                if (!element.isJsonObject()) {
                    issues.add(DefinitionIssue.error("invalid_resource", materialAt, ""));
                    continue;
                }
                JsonObject material = element.getAsJsonObject();
                if (stringOr(material, ITEM, "").isBlank()) {
                    issues.add(DefinitionIssue.error("invalid_item", materialAt, ""));
                }
                double rawCount = number(material, COUNT, COUNT_ALIASES, 1.0D);
                if (rawCount < 1.0D) {
                    issues.add(DefinitionIssue.warning("count_clamped", materialAt + "." + COUNT,
                            trim(rawCount)));
                }
            }
        } else if (isSqliteBacked(stock) && !restockEnabled(stock)) {
            issues.add(new DefinitionIssue(DefinitionIssue.Severity.INFO, "restock_disabled", at, ""));
        }
    }

    private void validateLegacyKeys(List<DefinitionIssue> issues) {
        for (String key : LEGACY_KEYS) {
            if (root.has(key)) {
                // 只要有一条有效报价，旧键就完全不被读取。
                issues.add(DefinitionIssue.warning("legacy_keys_present", key, ""));
            }
        }
    }

    private void validateJobShadowing(List<DefinitionIssue> issues) {
        if (root.has("job") && root.get("job").isJsonObject()) {
            for (String key : JOB_FALLBACK_KEYS) {
                if (root.has(key)) {
                    issues.add(DefinitionIssue.warning("job_shadows_toplevel", key, ""));
                }
            }
        }
    }

    private void validateContainers(List<DefinitionIssue> issues) {
        JsonElement containers = root.get("containers");
        if (containers == null || !containers.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : containers.getAsJsonObject().entrySet()) {
            String where = "containers." + entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                issues.add(DefinitionIssue.error("invalid_container", where, ""));
                continue;
            }
            JsonObject container = entry.getValue().getAsJsonObject();
            // 坐标为空会让整座建筑判定为 invalid_definition（missing_positions）。
            boolean hasPositions = container.has("positions") && container.get("positions").isJsonArray()
                    && !container.getAsJsonArray("positions").isEmpty();
            boolean hasPos = container.has("pos");
            if (!hasPositions && !hasPos) {
                // detail 带容器 id：文案要说清是哪一个容器，否则用户得靠上面的位置前缀自己认。
                issues.add(DefinitionIssue.error("container_missing_positions", where,
                        entry.getKey()));
            }
            String type = stringOr(container, "type", "structure_pos");
            if (!"structure_pos".equalsIgnoreCase(type)) {
                // 只有物流读它，商业交易路径不看 type。
                issues.add(new DefinitionIssue(DefinitionIssue.Severity.INFO,
                        "container_type_unused", where + ".type", type));
            }
        }
    }

    // ── 工具 ───────────────────────────────────────────────────────────

    private static boolean anyItemMatching(JsonArray array, String itemId) {
        if (array == null) {
            return false;
        }
        for (JsonElement element : array) {
            if (element.isJsonObject() && kindOf(element.getAsJsonObject()) == ResourceKind.ITEM
                    && itemId.equals(itemOf(element.getAsJsonObject()))) {
                return true;
            }
        }
        return false;
    }

    /** 数组里某个物品的数量合计；不是物品形态、id 不匹配的都算 0。 */
    private static int itemCount(JsonArray array, String itemId) {
        if (array == null) {
            return 0;
        }
        int total = 0;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (kindOf(object) == ResourceKind.ITEM && itemId.equals(itemOf(object))) {
                total += countOf(object);
            }
        }
        return total;
    }

    private static JsonArray arrayAt(JsonObject owner, String key, String[] aliases) {
        JsonElement element = value(owner, key, aliases);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String rawVisibility(JsonObject offer) {
        JsonElement element = value(offer, VISIBLE_TO, VISIBLE_ALIASES);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    /** 规范键优先、别名兜底的取值。 */
    private static JsonElement value(JsonObject owner, String canonical, String[] aliases) {
        if (owner == null) {
            return null;
        }
        if (owner.has(canonical)) {
            return owner.get(canonical);
        }
        for (String alias : aliases) {
            if (owner.has(alias)) {
                return owner.get(alias);
            }
        }
        return null;
    }

    /**
     * 写入规范键：先删掉全部别名拼写，再写规范键；值为 null/空串等同于删除。
     *
     * <p>这样「读取接受别名、写入统一规范名」这条约定才不会在文件里留下两种拼写。
     */
    private static void putCanonical(JsonObject object, String canonical, String[] aliases, String value) {
        for (String alias : aliases) {
            object.remove(alias);
        }
        if (value == null || value.isBlank()) {
            object.remove(canonical);
            return;
        }
        object.addProperty(canonical, value);
    }

    /** 生成不与现有条目冲突的 id。 */
    private static String uniqueId(JsonArray array, String baseId) {
        String base = baseId == null || baseId.isBlank() ? "offer" : baseId.trim();
        Set<String> used = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                String id = stringOr(element.getAsJsonObject(), "id", "");
                if (!id.isBlank()) {
                    used.add(id);
                }
            }
        }
        if (!used.contains(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "_" + suffix;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static double doubleOr(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (Exception exception) {
            return fallback;
        }
    }

    /** 读数字，规范键优先、别名兜底。 */
    private static double number(JsonObject object, String canonical, String[] aliases, double fallback) {
        JsonElement element = value(object, canonical, aliases);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static String trim(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? Long.toString((long) value) : Double.toString(value);
    }

    /** 供测试与界面展示：把 {@code true}/{@code false} 之外的东西也当字符串原样看。 */
    static JsonPrimitive primitive(String value) {
        return new JsonPrimitive(value);
    }
}
