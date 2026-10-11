package com.rstech.simupack.creation.commercial;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rstech.simupack.creation.json.DefinitionIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商业定义的 {@code containers} 段：给<b>物流系统</b>用的显式容器声明。
 *
 * <h2>它到底服务谁（这点必须先说清，否则很容易做错）</h2>
 *
 * <p>NSUK 里「商业建筑的箱子」有<b>两套完全无关</b>的机制：
 *
 * <ol>
 *   <li><b>材料供给</b>（{@code stock.materials}）：由
 *       {@code CommercialTradeSupplyService.nearbyContainers} 以商业控制盒为中心
 *       <b>扫描</b> XZ±5 / Y±2 范围内的容器，成交时逐个扣除。
 *       <b>不需要、也无法显式绑定</b> —— 箱子放近点就行。</li>
 *   <li><b>物流端口</b>（{@code containers}）：{@code CommercialDefinition.containers()}
 *       在整个 NSUK 里只有一个消费者 ——
 *       {@code LogisticsAutoClientService.appendCommercialClient}，
 *       它把每个 container 注册成该建筑的物流端口。</li>
 * </ol>
 *
 * <p>所以要分清需求：想让商店<b>有原料可卖</b> → 箱子放控制盒附近即可；
 * 想让<b>物流系统</b>把这些箱子当成这个建筑的端口 → 才写 {@code containers}。
 *
 * <h2>格式</h2>
 *
 * <pre>{@code
 * "containers": {
 *   "input":  { "type": "structure_pos", "positions": [[2,1,4],[3,1,4]] },
 *   "output": { "type": "structure_pos", "pos": [6,1,4] }
 * }
 * }</pre>
 *
 * <p>照抄 {@code CommercialDefinitionLoader.parsePositions}：单个 {@code pos}
 * 与数组 {@code positions} <b>都接受</b>（两者都有时都会收下）；{@code type}
 * 目前只支持 {@code structure_pos}，也是缺省值。上限
 * {@link #MAX_POSITIONS} 与 NSUK 的 {@code MAX_POSITIONS} 一致。
 *
 * <p>纯 Gson、不碰 Minecraft，所以能离线单测。
 */
public final class CommercialContainersModel {

    /** NSUK {@code DefinitionLoaderConstants.MAX_POSITIONS}：单个容器声明的坐标数上限。 */
    public static final int MAX_POSITIONS = 64;
    /** 只支持这一种来源类型（NSUK：{@code "structure_pos"}，也是缺省值）。 */
    public static final String DEFAULT_TYPE = "structure_pos";

    private final JsonObject root;

    private CommercialContainersModel(JsonObject root) {
        this.root = root;
    }

    /** 包住一份商业定义的根对象。 */
    public static CommercialContainersModel wrap(JsonObject root) {
        return new CommercialContainersModel(root);
    }

    /** {@code containers} 对象；不存在时按需创建。 */
    public JsonObject containers(boolean create) {
        if (root == null) {
            return null;
        }
        JsonElement existing = root.get("containers");
        if (existing != null && existing.isJsonObject()) {
            return existing.getAsJsonObject();
        }
        if (!create) {
            return null;
        }
        JsonObject created = new JsonObject();
        root.add("containers", created);
        return created;
    }

    /** 容器 id 列表（保持文件里的顺序）。 */
    public List<String> ids() {
        JsonObject object = containers(false);
        return object == null ? List.of() : List.copyOf(object.keySet());
    }

    /** 一个容器的坐标列表，按 {@code pos} 与 {@code positions} 的读取顺序合并。 */
    public List<int[]> positions(String id) {
        JsonObject container = container(id);
        return container == null ? List.of() : positionsOf(container);
    }

    /** 单个容器对象，不存在返回 null。 */
    public JsonObject container(String id) {
        JsonObject object = containers(false);
        if (object == null || id == null) {
            return null;
        }
        JsonElement element = object.get(id);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** 容器声明的 {@code type}；缺失时返回 {@link #DEFAULT_TYPE}（与 NSUK 缺省一致）。 */
    public static String typeOf(JsonObject container) {
        JsonElement type = container == null ? null : container.get("type");
        return type == null || !type.isJsonPrimitive() || type.getAsString().isBlank()
                ? DEFAULT_TYPE : type.getAsString().trim();
    }

    /** 读取一个容器对象里的坐标（{@code pos} 单个 + {@code positions} 数组，都要）。 */
    public static List<int[]> positionsOf(JsonObject container) {
        List<int[]> positions = new ArrayList<>();
        if (container == null) {
            return positions;
        }
        addPosition(positions, container.get("pos"));
        JsonElement array = container.get("positions");
        if (array != null && array.isJsonArray()) {
            JsonArray list = array.getAsJsonArray();
            int limit = Math.min(list.size(), MAX_POSITIONS);
            for (int index = 0; index < limit; index++) {
                addPosition(positions, list.get(index));
            }
        }
        return positions;
    }

    private static void addPosition(List<int[]> positions, JsonElement element) {
        int[] parsed = parsePosition(element);
        if (parsed != null) {
            positions.add(parsed);
        }
    }

    /** 解析 {@code [x, y, z]}；不是长度 ≥3 的数字数组就返回 null。 */
    public static int[] parsePosition(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 3) {
            return null;
        }
        int[] result = new int[3];
        for (int index = 0; index < 3; index++) {
            JsonElement value = array.get(index);
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            result[index] = (int) Math.floor(value.getAsDouble());
        }
        return result;
    }

    /**
     * 新建一个容器声明。
     *
     * @param id        容器 id；已存在或为空时返回 null（<b>绝不覆盖</b>已有条目）
     * @param positions 初始坐标（可为空列表）
     */
    public JsonObject addContainer(String id, List<int[]> positions) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim();
        JsonObject object = containers(true);
        if (object == null || object.has(key)) {
            return null;
        }
        JsonObject container = new JsonObject();
        container.addProperty("type", DEFAULT_TYPE);
        container.add("positions", toArray(positions));
        object.add(key, container);
        return container;
    }

    /** 改容器 id。目标 id 已存在时拒绝（返回 false），绝不覆盖。 */
    public boolean renameContainer(String from, String to) {
        JsonObject object = containers(false);
        if (object == null || from == null || to == null) {
            return false;
        }
        String key = to.trim();
        if (key.isEmpty() || !object.has(from) || key.equals(from) || object.has(key)) {
            return false;
        }
        // 重建以保持顺序（JsonObject 是 LinkedTreeMap，remove+add 会把条目挪到末尾）。
        Map<String, JsonElement> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            rebuilt.put(entry.getKey().equals(from) ? key : entry.getKey(), entry.getValue());
        }
        JsonObject replacement = new JsonObject();
        rebuilt.forEach(replacement::add);
        root.add("containers", replacement);
        return true;
    }

    /** 删除一个容器声明。 */
    public boolean removeContainer(String id) {
        JsonObject object = containers(false);
        return object != null && id != null && object.remove(id) != null;
    }

    /** 追加一个坐标；到上限或坐标重复时返回 false（NSUK 会去重）。 */
    public boolean addPosition(String id, int[] position) {
        JsonObject container = container(id);
        if (container == null || position == null || position.length < 3) {
            return false;
        }
        List<int[]> existing = positionsOf(container);
        if (existing.size() >= MAX_POSITIONS || contains(existing, position)) {
            return false;
        }
        existing.add(new int[]{position[0], position[1], position[2]});
        container.add("positions", toArray(existing));
        // 统一成 positions 数组一种写法，免得文件里 pos 与 positions 混着出现。
        container.remove("pos");
        return true;
    }

    /** 替换某个容器的坐标列表（用于「选取点位」整批落盘）。 */
    public boolean setPositions(String id, List<int[]> positions) {
        JsonObject container = container(id);
        if (container == null) {
            return false;
        }
        container.add("positions", toArray(positions));
        container.remove("pos");
        return true;
    }

    /** 删除某个容器里的第 index 个坐标。 */
    public boolean removePosition(String id, int index) {
        JsonObject container = container(id);
        if (container == null) {
            return false;
        }
        List<int[]> positions = new ArrayList<>(positionsOf(container));
        if (index < 0 || index >= positions.size()) {
            return false;
        }
        positions.remove(index);
        container.add("positions", toArray(positions));
        container.remove("pos");
        return true;
    }

    /** 改某个坐标的分量（{@code axis} 取 0/1/2）。 */
    public boolean setPositionAxis(String id, int index, int axis, int value) {
        JsonObject container = container(id);
        if (container == null || axis < 0 || axis > 2) {
            return false;
        }
        List<int[]> positions = new ArrayList<>(positionsOf(container));
        if (index < 0 || index >= positions.size()) {
            return false;
        }
        positions.get(index)[axis] = value;
        container.add("positions", toArray(positions));
        container.remove("pos");
        return true;
    }

    /** 是否已经声明了至少一个容器。 */
    public boolean isEmpty() {
        return ids().isEmpty();
    }

    /**
     * 静态校验。
     *
     * <p>只报「会让这段声明失效」或「会静默丢数据」的问题：
     * 坐标为空的容器会被 NSUK 直接跳过（{@code parseContainers} 里
     * {@code if (!positions.isEmpty())} 才 put），所以那是错误级；
     * 超过上限的坐标会被静默截断，也是错误级。
     */
    public List<DefinitionIssue> validate() {
        List<DefinitionIssue> issues = new ArrayList<>();
        JsonObject object = containers(false);
        if (object == null) {
            return issues;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String id = entry.getKey();
            String where = "containers." + id;
            if (id.isBlank()) {
                issues.add(DefinitionIssue.error("container_blank_id", where, ""));
                continue;
            }
            if (!entry.getValue().isJsonObject()) {
                issues.add(DefinitionIssue.error("container_not_object", where, ""));
                continue;
            }
            JsonObject container = entry.getValue().getAsJsonObject();
            if (!DEFAULT_TYPE.equals(typeOf(container))) {
                issues.add(DefinitionIssue.warning("container_unknown_type", where,
                        typeOf(container)));
            }
            List<int[]> positions = positionsOf(container);
            if (positions.isEmpty()) {
                // NSUK 的 parseContainers 会直接跳过它 —— 写了等于没写。
                // detail 带容器 id：文案要说清是哪一个容器。
                issues.add(DefinitionIssue.error("container_no_positions", where, id));
            } else if (rawPositionCount(container) > MAX_POSITIONS) {
                issues.add(DefinitionIssue.error("container_too_many_positions", where,
                        Integer.toString(MAX_POSITIONS)));
            }
        }
        return issues;
    }

    /** 文件里实际写了多少个坐标（含超上限、含非法项），用于「会被静默截断」的判断。 */
    private static int rawPositionCount(JsonObject container) {
        int count = container.has("pos") ? 1 : 0;
        JsonElement array = container.get("positions");
        if (array != null && array.isJsonArray()) {
            count += array.getAsJsonArray().size();
        }
        return count;
    }

    private static boolean contains(List<int[]> positions, int[] candidate) {
        for (int[] position : positions) {
            if (position[0] == candidate[0] && position[1] == candidate[1]
                    && position[2] == candidate[2]) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray toArray(List<int[]> positions) {
        JsonArray array = new JsonArray();
        if (positions == null) {
            return array;
        }
        for (int[] position : positions) {
            if (position == null || position.length < 3) {
                continue;
            }
            JsonArray entry = new JsonArray();
            entry.add(position[0]);
            entry.add(position[1]);
            entry.add(position[2]);
            array.add(entry);
            if (array.size() >= MAX_POSITIONS) {
                break;
            }
        }
        return array;
    }
}
