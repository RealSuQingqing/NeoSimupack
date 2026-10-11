package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工业动作组（{@code <建筑 id>.json} 里的 {@code recipes[].steps[]}）的编辑模型。
 *
 * <p><b>核心工程约束：原地编辑，绝不重建。</b>模型持有解析后的整个 {@link JsonObject}
 * 根，只改动 {@code recipes[].steps[] / inputs / outputs} 以及配方本身这几个子树，
 * 其余键（{@code points} / {@code containers} / {@code workArea} / {@code spawnEntity} /
 * 未来版本新增的键 / 其它模组附加的键）一律原样保留。
 * 这样才不会出现「打开再保存就损坏配置」。
 *
 * <p>本类<b>不依赖任何 Minecraft 类</b>，因此可以脱离客户端做单元测试——
 * 这是整个功能最容易出错、也最值得测的部分。
 *
 * <p>另外两条来自 NSUK 加载器的硬规则（见 {@code IndustrialDefinitionLoader}）：
 * <ul>
 *   <li>读取时接受大量键名别名，写入时统一用规范名；</li>
 *   <li>{@code repeat} / {@code loop} 在<b>加载期</b>展开成普通线性步骤，运行时不存在这个步骤。
 *       因此编辑器以嵌套形式写回，由 NSUK 自己展开，并实时计算展开后的总步数。</li>
 * </ul>
 */
public final class IndustrialActionModel {
    /** 单个配方最多 256 步（{@code IndustrialDefinitionLoader.MAX_STEPS}）。 */
    public static final int MAX_STEPS = 256;
    /** 最多 64 个配方（{@code MAX_RECIPES}），超出部分被静默丢弃。 */
    public static final int MAX_RECIPES = 64;
    /** 坐标列表最多 64 个（{@code MAX_POSITIONS}），超出部分被静默丢弃。 */
    public static final int MAX_POSITIONS = 64;

    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    /** 输入条件分组的规范键。 */
    public static final String GROUP_ALL = "all";
    public static final String GROUP_ANY = "any";

    /** 根输入数组的引用键（配方或步骤上的 {@code inputs}）。 */
    public static final String GROUP_ROOT = "";

    private static final String[] ALL_ALIASES = {"all", "and", "allOf", "与"};
    private static final String[] ANY_ALIASES = {"any", "or", "anyOf", "或"};

    private final JsonObject root;
    private String selectedRecipeId;
    private final java.util.Map<String, JsonObject> renamedRecipes = new java.util.HashMap<>();

    /** UI handles captured before a rename keep pointing at the same live recipe. */
    public boolean renameRecipe(String oldId, String newId) {
        JsonObject recipe = recipe(oldId);
        if (recipe == null || newId == null || newId.isBlank() || oldId.equals(newId) || recipe(newId) != null) return false;
        renamedRecipes.put(oldId, recipe);
        recipe.addProperty("id", newId);
        if (oldId.equals(selectedRecipeId)) selectedRecipeId = newId;
        return true;
    }

    private String currentOwnerRecipe(String id) {
        JsonObject renamed = renamedRecipes.get(id);
        if (renamed != null && recipes().stream().anyMatch(recipe -> recipe == renamed)) return string(renamed, "id");
        return id;
    }

    private IndustrialActionModel(JsonObject root) {
        this.root = root;
        List<JsonObject> recipes = recipes();
        this.selectedRecipeId = recipes.isEmpty() ? "" : recipeId(recipes.get(0), 0);
    }

    // ── 构造与序列化 ───────────────────────────────────────────────────

    /**
     * 解析一份工业 JSON。
     *
     * @param text 现有配置文本；空白时得到一份可直接编辑的空骨架
     * @throws IllegalArgumentException 文本不是合法 JSON 对象
     */
    public static IndustrialActionModel parse(String text) {
        if (text == null || text.isBlank()) {
            JsonObject empty = new JsonObject();
            empty.add("recipes", new JsonArray());
            return new IndustrialActionModel(empty);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("不是合法的 JSON：" + exception.getMessage(), exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("顶层必须是 JSON 对象");
        }
        return new IndustrialActionModel(parsed.getAsJsonObject());
    }

    /**
     * 新建一份只有动作组的工业 JSON 骨架。
     *
     * <p>只写入最小必要内容，不替用户编造 {@code points} / {@code containers}——
     * 那些要由作者自己决定。
     */
    public static IndustrialActionModel createDefault(String id, String name) {
        JsonObject created = new JsonObject();
        if (id != null && !id.isBlank()) {
            created.addProperty("id", id);
        }
        if (name != null && !name.isBlank()) {
            created.addProperty("name", name);
        }
        created.add("recipes", new JsonArray());
        return new IndustrialActionModel(created);
    }

    /** 美化输出。未识别的键原样保留。 */
    public String toJsonString() {
        return PRETTY.toJson(root);
    }

    /** 底层根对象（只读用途；改动请走本类方法）。 */
    public JsonObject root() {
        return root;
    }

    /**
     * 编辑器自己的元数据键（动作链的顺序与摆放位置）。
     *
     * <p><b>为什么允许放在这里</b>：NSUK 的 {@code IndustrialDefinitionLoader.loadText} 只读它认识的
     * 键（{@code id}/{@code points}/{@code containers}/{@code recipes}/…），从不遍历根对象去拒绝
     * 未知键，错误清单也只由已知结构坏掉产生——所以多一个私有键不会让定义失效，也不改变运行行为。
     *
     * <p><b>导出时会剥掉</b>（{@code CreationWorkspace.exportJson}），所以发布出去的包仍然干净；
     * 它只留在你自己的工程文件里，用来支持「撤销拖动」和「重开建筑记住位置与空链」。
     */
    public static final String EDITOR_LAYOUT_KEY = "_simupackEditor";

    /** 编辑器元数据对象；{@code create} 为 true 时不存在就建一个。 */
    public JsonObject editorLayout(boolean create) {
        JsonElement existing = root.get(EDITOR_LAYOUT_KEY);
        if (existing != null && existing.isJsonObject()) {
            return existing.getAsJsonObject();
        }
        if (!create) {
            return null;
        }
        JsonObject created = new JsonObject();
        root.add(EDITOR_LAYOUT_KEY, created);
        return created;
    }

    /** {@code points} 里已定义的点位名。检查器用它做下拉候选。 */
    public List<String> pointKeys() {
        return List.copyOf(objectKeys(root.get("points")));
    }

    /** {@code containers} 里已定义的容器名。 */
    public List<String> containerKeys() {
        return List.copyOf(objectKeys(root.get("containers")));
    }

    // ── 点位 / 容器的定义管理 ───────────────────────────────────────────
    //
    // NSUK 的模型是**两个独立的顶层 map**：
    //   points     { 名字: { type, positions, select } }
    //   containers { 名字: { type, positions } }
    // 步骤类型各自只读其中一个（move_to / look_at 读 points；insert_item 等读 containers），
    // 所以界面上「一个点位」在 JSON 里**可能同时存在于两个 map**——同名是允许的，
    // 两个 map 相互独立（同一个箱子既可以被 move_to_container 引用，也可以被 look_at 引用）。
    //
    // 这里的原则：**改名与改坐标时，两个 map 里同名的条目一起改**。
    // 否则会出现「同一个名字指两个不同的地方」，那是纯粹的坑。

    /** 定义条目所在的 map。 */
    public enum DefinitionKind {
        POINT("points"),
        CONTAINER("containers");

        private final String key;

        DefinitionKind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** 一处引用：哪个配方的哪个步骤的哪个字段。 */
    public record Reference(String recipeId, StepPath path, String fieldKey) {
    }

    /** 删除的结果。{@code removed=false} 表示因为有引用而被拒绝。 */
    public record DeleteResult(boolean removed, List<Reference> references) {
    }

    public boolean hasDefinition(String name, DefinitionKind kind) {
        JsonObject map = definitionMap(kind, false);
        return map != null && name != null && !name.isBlank() && map.has(name);
    }

    /** 读取一个定义条目；不存在返回 null。 */
    public JsonObject definitionEntry(String name, DefinitionKind kind) {
        JsonObject map = definitionMap(kind, false);
        if (map == null || name == null || !map.has(name)) {
            return null;
        }
        JsonElement entry = map.get(name);
        return entry != null && entry.isJsonObject() ? entry.getAsJsonObject() : null;
    }

    /** 这个点位是否被登记为容器（{@code containers} 里有同名条目）。 */
    public boolean isContainerPoint(String name) {
        return hasDefinition(name, DefinitionKind.CONTAINER);
    }

    /**
     * 新建一个定义条目。
     *
     * @return {@code false} 表示同名条目已存在（调用方应拒绝并提示，不要覆盖）
     */
    public boolean definePoint(String name, DefinitionKind kind, List<int[]> positions, String select) {
        if (name == null || name.isBlank() || positions == null || positions.isEmpty()) {
            return false;
        }
        JsonObject map = definitionMap(kind, true);
        if (map.has(name)) {
            return false;
        }
        map.add(name, definitionJson(positions, kind == DefinitionKind.POINT ? select : null));
        return true;
    }

    /**
     * 改坐标：两个 map 里同名的条目一起改。
     *
     * <p>容器的条目**不带 {@code select}**（{@code ContainerDefinition} 里没有这个字段），
     * 所以这里按 kind 分别处理，不能把点位的那一份照抄给容器。
     */
    public boolean setDefinitionPositions(String name, List<int[]> positions, String select) {
        if (name == null || positions == null || positions.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (DefinitionKind kind : DefinitionKind.values()) {
            JsonObject map = definitionMap(kind, false);
            if (map == null || !map.has(name)) {
                continue;
            }
            setDefinitionPositions(name, kind, positions, select);
            changed = true;
        }
        return changed;
    }

    /** Explicitly linked container mirror, never infer identity from coincident names. */
    private static final String CONTAINER_LINK = "_simupackContainerPoint";

    public boolean setDefinitionPositions(String name, DefinitionKind kind, List<int[]> positions, String select) {
        JsonObject entry = definitionEntry(name, kind);
        if (entry == null || positions == null || positions.isEmpty()) return false;
        JsonObject replacement = definitionJson(positions, kind == DefinitionKind.POINT ? select : null);
        String previousSelect = entry.has("select") ? string(entry, "select") : "nearest";
        String nextSelect = replacement.has("select") ? string(replacement, "select") : "nearest";
        if (positionList(entry).equals(positionList(replacement))
                && (kind == DefinitionKind.CONTAINER || positions.size() == 1 || previousSelect.equals(nextSelect))) return false;
        JsonObject before = entry.deepCopy();
        entry.remove("pos");
        entry.add("positions", replacement.get("positions").deepCopy());
        if (kind == DefinitionKind.POINT) {
            entry.remove("select");
            if (replacement.has("select")) entry.add("select", replacement.get("select"));
            // A deliberately edited point becomes independent of its former container.
            entry.remove(CONTAINER_LINK);
        } else {
            JsonObject mirror = definitionEntry(name, DefinitionKind.POINT);
            if (mirror != null && name.equals(string(mirror, CONTAINER_LINK))) {
                mirror.remove("pos");
                mirror.add("positions", replacement.get("positions").deepCopy());
            }
        }
        return !before.equals(entry);
    }

    private boolean linkedPoint(String name) {
        JsonObject point = definitionEntry(name, DefinitionKind.POINT);
        return point != null && name.equals(string(point, CONTAINER_LINK));
    }

    public List<Reference> findReferences(String name, DefinitionKind kind) {
        List<Reference> result = new ArrayList<>();
        boolean mirror = kind == DefinitionKind.CONTAINER && linkedPoint(name);
        forEachReferencingField((recipeId, path, step, field) -> {
            boolean matchesKind = field.kind() == (kind == DefinitionKind.CONTAINER
                    ? DefinitionField.Kind.CONTAINER_REF : DefinitionField.Kind.POINT_REF)
                    || (mirror && field.kind() == DefinitionField.Kind.POINT_REF);
            if (matchesKind) for (String key : field.acceptedKeys()) {
                if (name.equals(string(step, key))) {
                    Reference reference = new Reference(recipeId, path, key);
                    if (!result.contains(reference)) result.add(reference);
                }
            }
        });
        return result;
    }

    public boolean renameDefinition(String oldName, String newName, DefinitionKind kind) {
        if (newName == null || newName.isBlank() || oldName.equals(newName)
                || !hasDefinition(oldName, kind)
                || hasDefinition(newName, DefinitionKind.POINT) || hasDefinition(newName, DefinitionKind.CONTAINER)) return false;
        var references = findReferences(oldName, kind);
        boolean mirror = kind == DefinitionKind.CONTAINER && linkedPoint(oldName);
        JsonObject map = definitionMap(kind, false);
        map.add(newName, map.remove(oldName));
        if (mirror) {
            JsonObject points = definitionMap(DefinitionKind.POINT, false);
            JsonObject point = points.remove(oldName).getAsJsonObject();
            point.addProperty(CONTAINER_LINK, newName);
            points.add(newName, point);
        } else if (kind == DefinitionKind.POINT) definitionEntry(newName, kind).remove(CONTAINER_LINK);
        for (Reference reference : references) step(reference.recipeId(), reference.path()).addProperty(reference.fieldKey(), newName);
        return true;
    }

    public DeleteResult deleteDefinition(String name, DefinitionKind kind, boolean force) {
        var references = findReferences(name, kind);
        if (!hasDefinition(name, kind) || (!force && !references.isEmpty())) return new DeleteResult(false, references);
        boolean mirror = kind == DefinitionKind.CONTAINER && linkedPoint(name);
        definitionMap(kind, false).remove(name);
        if (mirror) definitionMap(DefinitionKind.POINT, false).remove(name);
        if (force) for (Reference reference : references) step(reference.recipeId(), reference.path()).remove(reference.fieldKey());
        return new DeleteResult(true, references);
    }

    /**
     * 把名字在两个 map 里一起改，并把**所有引用**同步过去。
     *
     * @return 被更新的引用处数
     */
    public int renameDefinition(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.isBlank() || newName.isBlank()
                || oldName.equals(newName)) {
            return 0;
        }
        for (DefinitionKind kind : DefinitionKind.values()) {
            JsonObject map = definitionMap(kind, false);
            if (map == null || !map.has(oldName)) {
                continue;
            }
            JsonElement value = map.remove(oldName);
            if (value != null) {
                map.add(newName, value);
            }
        }
        return rewriteReferences(oldName, newName);
    }

    /**
     * 删除一个定义。
     *
     * @param force {@code false} = 仍被引用就拒绝；{@code true} = 照删，并把所有引用<b>清空</b>
     *              （把那几个字段直接移除，于是步骤上显示为「空」，而不是留一个悬空名字）
     */
    public DeleteResult deleteDefinition(String name, boolean force) {
        if (name == null || name.isBlank()) {
            return new DeleteResult(false, List.of());
        }
        List<Reference> references = findReferences(name);
        if (!references.isEmpty() && !force) {
            return new DeleteResult(false, references);
        }
        for (DefinitionKind kind : DefinitionKind.values()) {
            JsonObject map = definitionMap(kind, false);
            if (map != null) {
                map.remove(name);
            }
        }
        if (force) {
            for (Reference reference : references) {
                JsonObject step = step(reference.recipeId(), reference.path());
                if (step != null) {
                    step.remove(reference.fieldKey());
                }
            }
        }
        return new DeleteResult(true, references);
    }

    /**
     * 确保 {@code points} 里有这个条目（容器被拖进点位槽时用）。
     *
     * <p>为什么需要它：{@code look_at} 只读 {@code points}，而 {@code move_to_container} 只读
     * {@code containers}。容器如果只登记在 {@code containers} 里，「看向那个箱子」这条就
     * 引用不到它（NSUK 会静默返回 null）。所以拖进点位槽时要**自动补一条同名点位**。
     *
     * @return {@code true} 表示确实补了一条
     */
    public boolean ensurePointEntry(String name) {
        if (name == null || name.isBlank() || hasDefinition(name, DefinitionKind.POINT)) {
            return false;
        }
        JsonObject container = definitionEntry(name, DefinitionKind.CONTAINER);
        if (container == null) {
            return false;
        }
        // 深拷贝：两个 map 里的条目必须是各自独立的对象，否则改一处会连带改另一处。
        JsonObject mirror = container.deepCopy();
        mirror.addProperty(CONTAINER_LINK, name);
        definitionMap(DefinitionKind.POINT, true).add(name, mirror);
        return true;
    }

    /** 找出所有引用了这个名字的步骤字段（含 repeat 内部的嵌套链）。 */
    public List<Reference> findReferences(String name) {
        List<Reference> result = new ArrayList<>();
        if (name == null || name.isBlank()) {
            return result;
        }
        forEachReferencingField((recipeId, path, step, field) -> {
            if (name.equals(readString(step, field))) {
                result.add(new Reference(recipeId, path, effectiveKey(step, field)));
            }
        });
        return result;
    }

    /** 把所有引用 {@code oldName} 的字段改成 {@code newName}；返回改了几处。 */
    private int rewriteReferences(String oldName, String newName) {
        int[] count = {0};
        forEachReferencingField((recipeId, path, step, field) -> {
            if (oldName.equals(readString(step, field))) {
                writeString(step, field, newName);
                count[0]++;
            }
        });
        return count[0];
    }

    /** 遍历所有配方所有步骤里的「点位/容器引用」字段。 */
    private void forEachReferencingField(ReferencingFieldVisitor visitor) {
        for (JsonObject recipe : recipes()) {
            String id = string(recipe, "id");
            if (id.isBlank()) {
                continue;
            }
            walkChain(id, stepsArray(id, null, false), null, (path, step) -> {
                IndustrialStepType type = IndustrialStepType.byRawType(string(step, "type"));
                if (type == null) {
                    return;
                }
                for (DefinitionField field : type.fields()) {
                    DefinitionField.Kind kind = field.kind();
                    if (kind != DefinitionField.Kind.POINT_REF && kind != DefinitionField.Kind.CONTAINER_REF) {
                        continue;
                    }
                    if (hasField(step, field)) {
                        visitor.accept(id, path, step, field);
                    }
                }
            });
        }
    }

    /** 引用字段的访问者。 */
    private interface ReferencingFieldVisitor {
        void accept(String recipeId, StepPath path, JsonObject step, DefinitionField field);
    }

    /** 递归遍历一条步骤链（含 repeat 内部的嵌套链）。 */
    private void walkChain(String recipeId, JsonArray array, StepPath parent,
                           java.util.function.BiConsumer<StepPath, JsonObject> visitor) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            StepPath path = parent == null ? StepPath.root(i) : parent.down(i);
            visitor.accept(path, step);
            IndustrialStepType type = IndustrialStepType.byRawType(string(step, "type"));
            if (type != null && type.isRepeat()) {
                walkChain(recipeId, stepsArray(recipeId, path, false), path, visitor);
            }
        }
    }

    private JsonObject definitionMap(DefinitionKind kind, boolean create) {
        JsonElement element = root.get(kind.key());
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        if (!create) {
            return null;
        }
        JsonObject map = new JsonObject();
        root.add(kind.key(), map);
        return map;
    }

    /**
     * 构造一个定义条目。
     *
     * <p><b>{@code type} 必须写 {@code structure_pos}。</b>NSUK 的
     * {@code IndustrialControlBoxService.resolveContainerPositions} 是
     * {@code if (container == null || !"structure_pos".equalsIgnoreCase(container.type())) return List.of();}
     * —— 漏写或写错<b>不报错，直接当作没找到</b>。所以这一条由编辑器保证，不能交给用户填。
     *
     * <p>{@code select} 只在**多个候选**时才有意义：只有一个坐标时 {@code nearest} 与
     * {@code ordered} 完全等价，写了只是噪声。所以单坐标不写这个键。
     */
    private static JsonObject definitionJson(List<int[]> positions, String select) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "structure_pos");
        JsonArray array = new JsonArray();
        for (int[] position : positions) {
            JsonArray one = new JsonArray();
            one.add(position[0]);
            one.add(position[1]);
            one.add(position[2]);
            array.add(one);
        }
        entry.add("positions", array);
        if (select != null && !select.isBlank() && array.size() > 1) {
            entry.addProperty("select", select);
        }
        return entry;
    }

    /** 顶层 {@code id}（只读摘要用）。 */
    public String definitionId() {
        return string(root, "id");
    }

    /** 顶层 {@code name}（只读摘要用）。 */
    public String definitionName() {
        return string(root, "name");
    }

    /** 顶层 {@code jobType} / {@code jobName} / {@code heldItem} 等只读摘要。 */
    public String definitionField(String key) {
        return string(root, key);
    }

    // ── 配方 ───────────────────────────────────────────────────────────

    /** 所有配方。缺 {@code recipes} 时返回空列表，不会凭空创建。 */
    public List<JsonObject> recipes() {
        List<JsonObject> result = new ArrayList<>();
        if (!root.has("recipes") || !root.get("recipes").isJsonArray()) {
            return result;
        }
        for (JsonElement element : root.getAsJsonArray("recipes")) {
            if (element != null && element.isJsonObject()) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }

    /** 当前选中的配方 id。 */
    public String selectedRecipeId() {
        return selectedRecipeId;
    }

    /** 切换当前配方；id 不存在时回退到第一个配方。 */
    public void selectRecipe(String recipeId) {
        for (JsonObject recipe : recipes()) {
            if (recipeId(recipe, 0).equals(recipeId)) {
                this.selectedRecipeId = recipeId;
                return;
            }
        }
        List<JsonObject> all = recipes();
        this.selectedRecipeId = all.isEmpty() ? "" : recipeId(all.get(0), 0);
    }

    /** 当前选中的配方对象；没有配方时为 null。 */
    public JsonObject selectedRecipe() {
        return recipe(selectedRecipeId);
    }

    /** 按 id 找配方。 */
    public JsonObject recipe(String recipeId) {
        for (JsonObject recipe : recipes()) {
            if (recipeId(recipe, 0).equals(recipeId)) {
                return recipe;
            }
        }
        return null;
    }

    /** 配方显示名：{@code name} 优先，其次 {@code id}。 */
    public String recipeDisplayName(JsonObject recipe) {
        String name = string(recipe, "name");
        return name.isBlank() ? recipeId(recipe, 0) : name;
    }

    /**
     * 新增一个配方并选中它。
     *
     * @param baseId 期望的 id，重复时自动加序号
     */
    public JsonObject addRecipe(String baseId) {
        JsonArray array = root.has("recipes") && root.get("recipes").isJsonArray()
                ? root.getAsJsonArray("recipes")
                : new JsonArray();
        if (!root.has("recipes")) {
            root.add("recipes", array);
        }
        Set<String> used = new LinkedHashSet<>();
        for (JsonObject recipe : recipes()) {
            used.add(recipeId(recipe, 0));
        }
        String id = uniqueId(baseId == null || baseId.isBlank() ? "recipe" : baseId, used);
        JsonObject recipe = new JsonObject();
        recipe.addProperty("id", id);
        recipe.addProperty("name", id);
        recipe.add("inputs", new JsonArray());
        recipe.add("outputs", new JsonArray());
        recipe.add("steps", new JsonArray());
        array.add(recipe);
        selectedRecipeId = id;
        return recipe;
    }

    /** 复制一个配方（深拷贝，含全部步骤），并把新配方设为当前。 */
    public JsonObject duplicateRecipe(String recipeId) {
        JsonObject source = recipe(recipeId);
        if (source == null) {
            return null;
        }
        JsonArray array = root.getAsJsonArray("recipes");
        JsonObject copy = source.deepCopy();
        Set<String> used = new LinkedHashSet<>();
        for (JsonObject recipe : recipes()) {
            used.add(recipeId(recipe, 0));
        }
        String id = uniqueId(recipeId(source, 0) + "_copy", used);
        copy.addProperty("id", id);
        copy.addProperty("name", id);
        array.add(copy);
        selectedRecipeId = id;
        return copy;
    }

    /** 删除一个配方。返回是否真的删掉了。 */
    public boolean removeRecipe(String recipeId) {
        JsonArray array = root.has("recipes") && root.get("recipes").isJsonArray()
                ? root.getAsJsonArray("recipes")
                : null;
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element.isJsonObject() && recipeId(element.getAsJsonObject(), i).equals(recipeId)) {
                array.remove(i);
                if (selectedRecipeId.equals(recipeId)) {
                    List<JsonObject> rest = recipes();
                    selectedRecipeId = rest.isEmpty() ? "" : recipeId(rest.get(0), 0);
                }
                return true;
            }
        }
        return false;
    }

    /** 写入配方的一个标量属性（{@code id} / {@code name} / {@code heldItem}）。 */
    public void setRecipeProperty(String recipeId, String key, String value) {
        JsonObject recipe = recipe(recipeId);
        if (recipe == null) {
            return;
        }
        if (value == null || value.isBlank()) {
            recipe.remove(key);
        } else {
            recipe.addProperty(key, value);
        }
    }

    // ── 步骤：定位 ─────────────────────────────────────────────────────

    /**
     * 步骤在配方内的位置。
     *
     * <p>{@code indices} 的每一层都是「某条 {@code steps} 数组里的下标」，
     * 第 0 层是配方的 {@code steps}，第 1 层是某个 {@code repeat} 的 {@code steps}，依此类推。
     */
    public record StepPath(List<Integer> indices) {
        public StepPath {
            indices = List.copyOf(indices);
        }

        public static StepPath of(int... values) {
            List<Integer> list = new ArrayList<>(values.length);
            for (int value : values) {
                list.add(value);
            }
            return new StepPath(list);
        }

        public static StepPath root(int index) {
            return new StepPath(List.of(index));
        }

        /** 进入下一层（本步骤必须是 repeat）。 */
        public StepPath down(int index) {
            List<Integer> next = new ArrayList<>(indices);
            next.add(index);
            return new StepPath(next);
        }

        /** 回到上一层；已在顶层时返回 null。 */
        public StepPath up() {
            if (indices.isEmpty()) {
                return null;
            }
            return new StepPath(indices.subList(0, indices.size() - 1));
        }

        /** 顶层路径（只有一节）。 */
        public boolean topLevel() {
            return indices.size() == 1;
        }

        public int depth() {
            return indices.size();
        }

        /** 路径末端的下标。 */
        public int leaf() {
            return indices.isEmpty() ? -1 : indices.get(indices.size() - 1);
        }

        /** 把末端换成另一个下标（同级移动用）。 */
        public StepPath withLeaf(int index) {
            List<Integer> next = new ArrayList<>(indices);
            if (next.isEmpty()) {
                next.add(index);
            } else {
                next.set(next.size() - 1, index);
            }
            return new StepPath(next);
        }

        public boolean isEmpty() {
            return indices.isEmpty();
        }

        /** 展示用：{@code 2} 或 {@code 2.0}（父链.子下标）。 */
        public String display() {
            StringBuilder builder = new StringBuilder();
            for (int index : indices) {
                if (builder.length() > 0) {
                    builder.append('.');
                }
                builder.append(index);
            }
            return builder.toString();
        }

        @Override
        public String toString() {
            return display();
        }
    }

    /** 顶层步骤链（配方的 {@code steps}）。 */
    public List<JsonObject> steps(String recipeId) {
        return steps(recipeId, null);
    }

    /**
     * 某条步骤链。
     *
     * @param parent 父级 repeat 步骤的路径；{@code null} 表示配方的顶层链
     */
    public List<JsonObject> steps(String recipeId, StepPath parent) {
        List<JsonObject> result = new ArrayList<>();
        JsonArray array = stepsArray(recipeId, parent, false);
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            if (element != null && element.isJsonObject()) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }

    /** 按路径取步骤对象；路径无效时返回 null。 */
    public JsonObject step(String recipeId, StepPath path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        JsonArray array = stepsArray(recipeId, path.up(), false);
        if (array == null) {
            return null;
        }
        int index = path.leaf();
        if (index < 0 || index >= array.size()) {
            return null;
        }
        JsonElement element = array.get(index);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** 该步骤所属的兄弟链（用于同级移动与删除）。 */
    public JsonArray siblingArray(String recipeId, StepPath path) {
        return path == null ? null : stepsArray(recipeId, path.up(), false);
    }

    /** 取（必要时创建）某条 {@code steps} 数组。 */
    public JsonArray stepsArray(String recipeId, StepPath parent, boolean create) {
        JsonObject owner;
        if (parent == null || parent.isEmpty()) {
            owner = recipe(recipeId);
        } else {
            owner = step(recipeId, parent);
        }
        if (owner == null) {
            return null;
        }
        if (owner.has("steps") && owner.get("steps").isJsonArray()) {
            return owner.getAsJsonArray("steps");
        }
        if (!create) {
            return null;
        }
        JsonArray array = new JsonArray();
        owner.add("steps", array);
        return array;
    }

    // ── 步骤：增删改 ───────────────────────────────────────────────────

    /**
     * 插入一个新步骤。
     *
     * @param parent 目标链的父级 repeat 路径；{@code null} 表示顶层
     * @param index  插入位置；越界会被夹到合法范围
     * @return 新步骤的路径；失败返回 null
     */
    public StepPath insertStep(String recipeId, StepPath parent, int index, IndustrialStepType type) {
        if (type == null) {
            return null;
        }
        return insertExistingStep(recipeId, parent, index, type.newStepJson());
    }

    /**
     * 插入一个已有步骤对象（会深拷贝一份）。
     *
     * <p>深拷贝是必须的：拖拽可能跨层，直接搬引用会让同一个对象同时挂在两个数组里。
     */
    public StepPath insertExistingStep(String recipeId, StepPath parent, int index, JsonObject step) {
        JsonArray array = stepsArray(recipeId, parent, true);
        if (array == null || step == null) {
            return null;
        }
        int at = Math.max(0, Math.min(index, array.size()));
        arrayInsert(array, at, step.deepCopy());
        List<Integer> indices = parent == null ? new ArrayList<>() : new ArrayList<>(parent.indices());
        indices.add(at);
        return new StepPath(indices);
    }

    /**
     * 把已有步骤移到另一条链的指定位置（拖拽排序 / 跨层拖动用）。
     *
     * <p>同一条链内往后移时下标要减一——元素先被摘掉了。
     *
     * @return 移动后的新路径；移动无效（路径不存在，或想把 repeat 拖进自己的子树）时返回原路径
     */
    public StepPath moveStepTo(String recipeId, StepPath from, StepPath targetParent, int index) {
        if (from == null) {
            return null;
        }
        JsonObject node = step(recipeId, from);
        if (node == null) {
            return from;
        }
        if (!canMoveInto(from, targetParent)) {
            return from;
        }
        int target = Math.max(0, index);
        if (sameChain(from, targetParent) && target > from.leaf()) {
            target--;
        }
        JsonObject copy = node.deepCopy();
        if (!removeStep(recipeId, from)) {
            return from;
        }
        StepPath created = insertExistingStep(recipeId, targetParent, target, copy);
        return created == null ? from : created;
    }

    /** 两个路径是否在同一条链上（只比较父链，忽略末端下标）。 */
    public static boolean sameChain(StepPath a, StepPath b) {
        return parentChainOf(a).equals(parentChainOf(b));
    }

    private static List<Integer> parentChainOf(StepPath path) {
        if (path == null) {
            return List.of();
        }
        StepPath up = path.up();
        return up == null ? List.of() : up.indices();
    }

    /** 在指定路径后插入一个新步骤（同级）。 */
    public StepPath insertStepAfter(String recipeId, StepPath path, IndustrialStepType type) {
        if (path == null || path.isEmpty()) {
            return insertStep(recipeId, null, Integer.MAX_VALUE, type);
        }
        return insertStep(recipeId, path.up(), path.leaf() + 1, type);
    }

    /** 删除一个步骤。 */
    public boolean removeStep(String recipeId, StepPath path) {
        JsonArray array = siblingArray(recipeId, path);
        if (array == null || path == null) {
            return false;
        }
        int index = path.leaf();
        if (index < 0 || index >= array.size()) {
            return false;
        }
        array.remove(index);
        return true;
    }

    /** 复制一个步骤（深拷贝）插到它后面，返回新路径。 */
    public StepPath duplicateStep(String recipeId, StepPath path) {
        JsonObject source = step(recipeId, path);
        JsonArray array = siblingArray(recipeId, path);
        if (source == null || array == null) {
            return null;
        }
        int at = path.leaf() + 1;
        arrayInsert(array, at, source.deepCopy());
        return path.withLeaf(at);
    }

    /**
     * 在同一条链内上下移动一个步骤。
     *
     * @param delta -1 上移，+1 下移
     * @return 移动后的新路径；没有移动返回原路径
     */
    public StepPath moveStep(String recipeId, StepPath path, int delta) {
        JsonArray array = siblingArray(recipeId, path);
        if (array == null || path == null) {
            return path;
        }
        int from = path.leaf();
        int to = from + delta;
        if (from < 0 || from >= array.size() || to < 0 || to >= array.size()) {
            return path;
        }
        JsonElement moved = array.get(from);
        array.remove(from);
        arrayInsert(array, to, moved);
        return path.withLeaf(to);
    }

    /**
     * 把一个步骤移动到同一条链的任意下标（拖拽排序用）。
     */
    public StepPath reorderStep(String recipeId, StepPath path, int targetIndex) {
        JsonArray array = siblingArray(recipeId, path);
        if (array == null || path == null) {
            return path;
        }
        int from = path.leaf();
        int to = Math.max(0, Math.min(targetIndex, array.size() - 1));
        if (from < 0 || from >= array.size() || from == to) {
            return path;
        }
        JsonElement moved = array.get(from);
        array.remove(from);
        arrayInsert(array, to, moved);
        return path.withLeaf(to);
    }

    /**
     * 判断 {@code moving} 能否放进 {@code targetParent} 这条链里。
     *
     * <p>不允许把 repeat 拖进自己的子树——那会构造出一个自包含的 JSON，
     * 加载时无限展开。
     */
    public boolean canMoveInto(StepPath moving, StepPath targetParent) {
        if (moving == null || targetParent == null) {
            return true;
        }
        if (targetParent.indices().size() < moving.indices().size()) {
            return true;
        }
        return !targetParent.indices().subList(0, moving.indices().size()).equals(moving.indices());
    }

    // ── 字段读写（别名归一化） ─────────────────────────────────────────

    /** 该步骤是否显式写了某个字段（含别名拼写）。 */
    public static boolean hasField(JsonObject step, DefinitionField field) {
        for (String key : field.acceptedKeys()) {
            if (step.has(key) && !step.get(key).isJsonNull()) {
                return true;
            }
        }
        return false;
    }

    /** 实际生效的键名（规范键或某个别名）；没写返回规范键。 */
    public static String effectiveKey(JsonObject step, DefinitionField field) {
        for (String key : field.acceptedKeys()) {
            if (step.has(key) && !step.get(key).isJsonNull()) {
                return key;
            }
        }
        return field.key();
    }

    /**
     * 读取字符串值。
     *
     * <p>没写该键时返回加载器的默认值 {@link DefinitionField#fallback()}。
     */
    public static String readString(JsonObject step, DefinitionField field) {
        String key = effectiveKey(step, field);
        if (!step.has(key) || step.get(key).isJsonNull()) {
            return field.fallback();
        }
        JsonElement element = step.get(key);
        try {
            return element.getAsString();
        } catch (RuntimeException exception) {
            return field.fallback();
        }
    }

    /** 读取数值；读不出来返回加载器默认值。 */
    public static double readNumber(JsonObject step, DefinitionField field) {
        String key = effectiveKey(step, field);
        if (!step.has(key) || step.get(key).isJsonNull()) {
            return parseNumber(field.fallback(), 0D);
        }
        try {
            return step.get(key).getAsDouble();
        } catch (RuntimeException exception) {
            return parseNumber(field.fallback(), 0D);
        }
    }

    /** 读取布尔值；读不出来返回加载器默认值。 */
    public static boolean readFlag(JsonObject step, DefinitionField field) {
        String key = effectiveKey(step, field);
        if (!step.has(key) || step.get(key).isJsonNull()) {
            return Boolean.parseBoolean(field.fallback());
        }
        try {
            return step.get(key).getAsBoolean();
        } catch (RuntimeException exception) {
            return Boolean.parseBoolean(field.fallback());
        }
    }

    /**
     * 写入字段：先删掉所有别名拼写，再写规范键。
     *
     * <p>写 {@code null} 或空串等同于删除该键——这样「清空」不会留下空字符串垃圾。
     */
    public static void writeField(JsonObject step, DefinitionField field, JsonElement value) {
        clearField(step, field);
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && value.getAsString().isBlank()) {
            return;
        }
        step.add(field.key(), value);
    }

    public static void writeString(JsonObject step, DefinitionField field, String value) {
        if (value == null) {
            clearField(step, field);
            return;
        }
        writeField(step, field, new com.google.gson.JsonPrimitive(value));
    }

    public static void writeNumber(JsonObject step, DefinitionField field, double value) {
        writeField(step, field, new com.google.gson.JsonPrimitive(value));
    }

    public static void writeFlag(JsonObject step, DefinitionField field, boolean value) {
        writeField(step, field, new com.google.gson.JsonPrimitive(value));
    }

    /** 删除字段（含全部别名拼写）。 */
    public static void clearField(JsonObject step, DefinitionField field) {
        IndustrialStepType type = IndustrialStepType.byRawType(string(step, "type"));
        if (type != null && field.kind() == DefinitionField.Kind.CONTAINER_REF) {
            for (DefinitionField other : type.fields()) {
                if (other.kind() != DefinitionField.Kind.CONTAINER_REF || other.key().equals(field.key())) continue;
                if (hasField(step, other) && field.acceptedKeys().contains(effectiveKey(step, other))) {
                    step.addProperty(other.key(), readString(step, other));
                }
            }
        }
        for (String key : field.acceptedKeys()) {
            // A fallback can also be another slot's canonical field.
            boolean otherCanonical = type != null && type.fields().stream().anyMatch(other ->
                    !other.key().equals(field.key()) && other.key().equals(key));
            if (!otherCanonical) step.remove(key);
        }
    }

    // ── 输入条件树（ALL / ANY） ────────────────────────────────────────

    /**
     * 输入条件的归属：配方级或某个步骤级。
     *
     * @param recipeId 配方 id
     * @param stepPath 步骤路径；{@code null} 表示配方自身的 {@code inputs}
     */
    public record InputOwner(String recipeId, StepPath stepPath) {
        public static InputOwner ofRecipe(String recipeId) {
            return new InputOwner(recipeId, null);
        }

        public static InputOwner ofStep(String recipeId, StepPath stepPath) {
            return new InputOwner(recipeId, stepPath);
        }
    }

    /**
     * 输入树里一个节点的位置。
     *
     * @param refs 每一层：(所在数组的键, 下标)。根层键为 {@link #GROUP_ROOT}，
     *             分组层为 {@link #GROUP_ALL} 或 {@link #GROUP_ANY}
     */
    public record InputPath(List<InputRef> refs) {
        public InputPath {
            refs = List.copyOf(refs);
        }

        public static InputPath root(int index) {
            return new InputPath(List.of(new InputRef(GROUP_ROOT, index)));
        }

        public InputPath down(String groupKey, int index) {
            List<InputRef> next = new ArrayList<>(refs);
            next.add(new InputRef(groupKey, index));
            return new InputPath(next);
        }

        public boolean isEmpty() {
            return refs.isEmpty();
        }
    }

    /** 输入树里的一节：某个数组里第 index 个元素。 */
    public record InputRef(String arrayKey, int index) {
    }

    /** 归属对象（配方或步骤）本身。 */
    public JsonObject ownerObject(InputOwner owner) {
        if (owner == null) {
            return null;
        }
        String id = currentOwnerRecipe(owner.recipeId());
        return owner.stepPath() == null ? recipe(id) : step(id, owner.stepPath());
    }

    /** 取输入数组；{@code arrayKey} 为 {@link #GROUP_ROOT} 时是 {@code inputs}。 */
    public JsonArray inputArray(JsonObject owner, String arrayKey, boolean create) {
        if (owner == null) {
            return null;
        }
        if (GROUP_ROOT.equals(arrayKey)) {
            if (owner.has("inputs") && owner.get("inputs").isJsonArray()) {
                return owner.getAsJsonArray("inputs");
            }
            if (!create) {
                return null;
            }
            JsonArray array = new JsonArray();
            owner.add("inputs", array);
            return array;
        }
        String actual = groupKey(owner, arrayKey);
        if (actual != null) {
            return owner.getAsJsonArray(actual);
        }
        if (!create) {
            return null;
        }
        // 分组节点只在写入时用规范键（all / any），别名留给读取。
        JsonArray created = new JsonArray();
        owner.add(GROUP_ANY.equals(arrayKey) ? GROUP_ANY : GROUP_ALL, created);
        return created;
    }

    /** 父节点上的分组键实际拼写（可能是别名），没有则返回 null。 */
    private static String groupKey(JsonObject groupOwner, String canonical) {
        if (groupOwner == null) {
            return null;
        }
        String[] candidates = GROUP_ANY.equals(canonical) ? ANY_ALIASES : ALL_ALIASES;
        for (String key : candidates) {
            if (groupOwner.has(key) && groupOwner.get(key).isJsonArray()) {
                return key;
            }
        }
        return null;
    }

    /** 按输入路径取节点；无效返回 null。 */
    public JsonObject inputNode(InputOwner owner, InputPath path) {
        JsonObject target = ownerObject(owner);
        if (target == null || path == null || path.isEmpty()) {
            return null;
        }
        JsonObject current = target;
        for (InputRef ref : path.refs()) {
            JsonArray array = inputArray(current, ref.arrayKey(), false);
            if (array == null || ref.index() < 0 || ref.index() >= array.size()) {
                return null;
            }
            JsonElement element = array.get(ref.index());
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            current = element.getAsJsonObject();
        }
        return current;
    }

    /** 某个分组的逻辑：true 表示 ANY（任一满足），false 表示 ALL。 */
    public static boolean isAnyGroup(JsonObject node) {
        for (String key : ANY_ALIASES) {
            if (node.has(key) && node.get(key).isJsonArray()) {
                return true;
            }
        }
        return false;
    }

    /** 该节点是不是分组（而非叶子物品条件）。 */
    public static boolean isGroup(JsonObject node) {
        for (String key : ANY_ALIASES) {
            if (node.has(key) && node.get(key).isJsonArray()) {
                return true;
            }
        }
        for (String key : ALL_ALIASES) {
            if (node.has(key) && node.get(key).isJsonArray()) {
                return true;
            }
        }
        return false;
    }

    /** 在某个分组（或根）末尾追加一个叶子物品条件。 */
    public InputPath addInputItem(InputOwner owner, InputPath parent) {
        JsonObject target = ownerObject(owner);
        if (target == null) {
            return null;
        }
        boolean atRoot = parent == null || parent.isEmpty();
        JsonObject group = atRoot ? target : inputNode(owner, parent);
        if (group == null) {
            return null;
        }
        // 根层追加进 owner 的 inputs；分组层追加进该分组自己的子数组。
        String childKey = atRoot ? GROUP_ROOT : (isAnyGroup(group) ? GROUP_ANY : GROUP_ALL);
        JsonArray array = inputArray(group, childKey, true);
        if (array == null) {
            return null;
        }
        JsonObject leaf = new JsonObject();
        leaf.addProperty("item", "minecraft:stone");
        array.add(leaf);
        int index = array.size() - 1;
        return atRoot ? InputPath.root(index) : parent.down(childKey, index);
    }

    /** 在某个分组（或根）末尾追加一个子分组。 */
    public InputPath addInputGroup(InputOwner owner, InputPath parent, boolean any) {
        JsonObject target = ownerObject(owner);
        if (target == null) {
            return null;
        }
        JsonObject group = parent == null || parent.isEmpty() ? target : inputNode(owner, parent);
        if (group == null) {
            return null;
        }
        String parentKey = parent == null || parent.isEmpty()
                ? GROUP_ROOT
                : (isAnyGroup(group) ? GROUP_ANY : GROUP_ALL);
        JsonArray array = inputArray(group, parentKey, true);
        if (array == null) {
            return null;
        }
        JsonObject child = new JsonObject();
        child.add(any ? GROUP_ANY : GROUP_ALL, new JsonArray());
        array.add(child);
        int index = array.size() - 1;
        return parent == null || parent.isEmpty()
                ? InputPath.root(index)
                : parent.down(parentKey, index);
    }

    /** 删除一个输入节点。 */
    public boolean removeInput(InputOwner owner, InputPath path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        JsonObject target = ownerObject(owner);
        if (target == null) {
            return false;
        }
        JsonObject current = target;
        int last = path.refs().size() - 1;
        for (int i = 0; i < last; i++) {
            InputRef ref = path.refs().get(i);
            JsonArray array = inputArray(current, ref.arrayKey(), false);
            if (array == null || ref.index() < 0 || ref.index() >= array.size()) {
                return false;
            }
            current = array.get(ref.index()).getAsJsonObject();
        }
        InputRef leaf = path.refs().get(last);
        JsonArray array = inputArray(current, leaf.arrayKey(), false);
        if (array == null || leaf.index() < 0 || leaf.index() >= array.size()) {
            return false;
        }
        array.remove(leaf.index());
        return true;
    }

    /** 切换分组逻辑（ALL / ANY），保留子节点。 */
    public boolean setInputGroupAny(InputOwner owner, InputPath path, boolean any) {
        JsonObject node = inputNode(owner, path);
        if (node == null || !isGroup(node)) {
            return false;
        }
        String current = isAnyGroup(node) ? GROUP_ANY : GROUP_ALL;
        JsonArray children = node.getAsJsonArray(groupKey(node, current));
        node.remove(GROUP_ALL);
        node.remove(GROUP_ANY);
        for (String alias : ANY_ALIASES) {
            node.remove(alias);
        }
        for (String alias : ALL_ALIASES) {
            node.remove(alias);
        }
        node.add(any ? GROUP_ANY : GROUP_ALL, children);
        return true;
    }

    // ── 产出列表 ───────────────────────────────────────────────────────

    /** 取产出数组（配方级或步骤级），必要时创建。 */
    public JsonArray outputArray(InputOwner owner, boolean create) {
        JsonObject target = ownerObject(owner);
        if (target == null) {
            return null;
        }
        if (target.has("outputs") && target.get("outputs").isJsonArray()) {
            return target.getAsJsonArray("outputs");
        }
        if (!create) {
            return null;
        }
        JsonArray array = new JsonArray();
        target.add("outputs", array);
        return array;
    }

    /** 追加一个产出条目，返回它的下标；失败返回 -1。 */
    public int addOutput(InputOwner owner) {
        JsonArray array = outputArray(owner, true);
        if (array == null) {
            return -1;
        }
        JsonObject output = new JsonObject();
        output.addProperty("item", "minecraft:stone");
        output.addProperty("baseAmount", 1);
        array.add(output);
        return array.size() - 1;
    }

    public boolean removeOutput(InputOwner owner, int index) {
        JsonArray array = outputArray(owner, false);
        if (array == null || index < 0 || index >= array.size()) {
            return false;
        }
        array.remove(index);
        return true;
    }

    // ── 展开步数 ───────────────────────────────────────────────────────

    /**
     * 该配方在 NSUK 加载期展开后的总步数。
     *
     * <p>{@code repeat} 会按「{@code positions} 个数，否则 {@code count}」复制子步骤，
     * 且可递归嵌套，因此很容易指数增长并撞上 256 步上限。
     * 超过上限时会被夹到 {@link #MAX_STEPS}，同时让校验器报错。
     */
    public int expandedStepCount(String recipeId) {
        return expandedStepCount(steps(recipeId), new LinkedHashSet<>());
    }

    private int expandedStepCount(List<JsonObject> chain, Set<JsonObject> guard) {
        int total = 0;
        for (JsonObject step : chain) {
            if (!guard.add(step)) {
                continue;
            }
            IndustrialStepType type = IndustrialStepType.byRawType(string(step, "type"));
            if (type != null && type.isRepeat()) {
                int repetitions = repeatCount(step);
                int childCount = expandedStepCount(childrenOf(step), guard);
                total += repetitions <= 0 ? 0 : (int) Math.min(MAX_STEPS, (long) repetitions * childCount);
            } else {
                total++;
            }
            guard.remove(step);
            if (total >= MAX_STEPS) {
                return MAX_STEPS;
            }
        }
        return total;
    }

    /** {@code repeat} 的复制次数：{@code positions} 优先，其次 {@code count}。 */
    public static int repeatCount(JsonObject repeat) {
        int positions = positionList(repeat).size();
        if (positions > 0) {
            return positions;
        }
        return Math.max(0, (int) readNumber(repeat, IndustrialCommonFields.COUNT));
    }

    /** 某个 repeat 的嵌套步骤链。 */
    public List<JsonObject> childrenOf(JsonObject repeat) {
        List<JsonObject> result = new ArrayList<>();
        if (repeat == null || !repeat.has("steps") || !repeat.get("steps").isJsonArray()) {
            return result;
        }
        for (JsonElement element : repeat.getAsJsonArray("steps")) {
            if (element != null && element.isJsonObject()) {
                result.add(element.getAsJsonObject());
            }
        }
        return result;
    }

    /** 读取 {@code positions} / {@code pos}；返回原始数组列表。 */
    public static List<JsonArray> positionList(JsonObject owner) {
        List<JsonArray> result = new ArrayList<>();
        if (owner == null) {
            return result;
        }
        if (owner.has("positions") && owner.get("positions").isJsonArray()) {
            for (JsonElement element : owner.getAsJsonArray("positions")) {
                if (element != null && element.isJsonArray()) {
                    result.add(element.getAsJsonArray());
                }
            }
        } else if (owner.has("pos") && owner.get("pos").isJsonArray()) {
            result.add(owner.getAsJsonArray("pos"));
        }
        return result;
    }

    // ── 校验 ───────────────────────────────────────────────────────────

    /**
     * 结构的尺寸，用于坐标越界检查。
     *
     * @param sizeX/sizeY/sizeZ 结构各轴长度；任一为 0 表示尺寸未知，跳过越界检查
     */
    public record StructureBounds(int sizeX, int sizeY, int sizeZ) {
        public static final StructureBounds UNKNOWN = new StructureBounds(0, 0, 0);

        public boolean known() {
            return sizeX > 0 && sizeY > 0 && sizeZ > 0;
        }
    }

    /**
     * 全量静态校验。
     *
     * <p>NSUK 只在<b>加载时</b>记录 errors，游戏里表现为「步骤卡住」；
     * 这里提前把同类问题报出来。没有运行时求值，所以做不到单步调试。
     */
    public List<IndustrialActionIssue> validate(StructureBounds bounds) {
        List<IndustrialActionIssue> issues = new ArrayList<>();
        validateShapes(root, "", issues, 0);
        if (!issues.isEmpty()) return issues; // Never feed malformed numeric/coordinate nodes into legacy readers.
        StructureBounds effective = bounds == null ? StructureBounds.UNKNOWN : bounds;

        Set<String> pointKeys = objectKeys(root.get("points"));
        Set<String> containerKeys = objectKeys(root.get("containers"));

        // 定义侧先查：它与「配方缺失」无关，而且这些错会让 NSUK 静默失效，
        // 早于 recipes 的提前返回之前报出来更有用。
        validateDefinitions(issues, effective);

        if (!root.has("recipes") || !root.get("recipes").isJsonArray()
                || root.getAsJsonArray("recipes").isEmpty()) {
            issues.add(IndustrialActionIssue.error("missing_recipes", "recipes", ""));
            return issues;
        }
        List<JsonObject> recipes = recipes();
        if (recipes.size() > MAX_RECIPES) {
            issues.add(IndustrialActionIssue.warning("too_many_recipes", "recipes",
                    recipes.size() + " > " + MAX_RECIPES));
        }

        Set<String> recipeIds = new LinkedHashSet<>();
        for (int i = 0; i < recipes.size(); i++) {
            JsonObject recipe = recipes.get(i);
            String recipeId = recipeId(recipe, i);
            String where = "recipes[" + i + "]";
            if (!recipeIds.add(recipeId)) {
                issues.add(IndustrialActionIssue.error("duplicate_recipe_id", where, recipeId));
            }
            validateSteps(recipe, "steps", recipeId, where + ".steps", pointKeys, containerKeys,
                    effective, issues, 0);
            validateInputs(arrayOrNull(recipe, "inputs"), where + ".inputs", issues, 0);
            validateOutputs(arrayOrNull(recipe, "outputs"), where + ".outputs", issues);

            int expanded = expandedStepCount(recipeId);
            if (expanded >= MAX_STEPS) {
                issues.add(IndustrialActionIssue.error("steps_exceeded", where + ".steps",
                        Integer.toString(expanded)));
            }
            List<JsonObject> top = new ArrayList<>();
            if (recipe.has("steps") && recipe.get("steps").isJsonArray()) {
                for (JsonElement element : recipe.getAsJsonArray("steps")) {
                    if (element.isJsonObject()) {
                        top.add(element.getAsJsonObject());
                    }
                }
            }
            if (top.isEmpty()) {
                issues.add(IndustrialActionIssue.warning("empty_steps", where + ".steps", ""));
            }
        }
        return issues;
    }

    private void validateSteps(JsonObject ownerChain,
                               String arrayKey,
                               String recipeId,
                               String location,
                               Set<String> pointKeys,
                               Set<String> containerKeys,
                               StructureBounds bounds,
                               List<IndustrialActionIssue> issues,
                               int depth) {
        if (!ownerChain.has(arrayKey) || !ownerChain.get(arrayKey).isJsonArray()) {
            return;
        }
        JsonArray array = ownerChain.getAsJsonArray(arrayKey);
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            String where = location + "[" + i + "]";
            if (element == null || !element.isJsonObject()) {
                issues.add(IndustrialActionIssue.error("invalid_step", where, ""));
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            validateStep(step, where, recipeId, pointKeys, containerKeys, bounds, issues, depth);
        }
    }

    private void validateStep(JsonObject step,
                              String where,
                              String recipeId,
                              Set<String> pointKeys,
                              Set<String> containerKeys,
                              StructureBounds bounds,
                              List<IndustrialActionIssue> issues,
                              int depth) {
        String rawType = string(step, "type");
        if (rawType.isBlank()) {
            issues.add(IndustrialActionIssue.error("missing_type", where, ""));
            return;
        }
        IndustrialStepType type = IndustrialStepType.byRawType(rawType);
        if (type == null) {
            // NSUK 落到 switch 的 default：状态置 invalid_step 并无限 WAITING_RETRY。
            issues.add(IndustrialActionIssue.error("unknown_type", where, rawType));
            return;
        }
        if (!type.canonical().equals(rawType)) {
            issues.add(new IndustrialActionIssue(IndustrialActionIssue.Severity.INFO, "alias_type",
                    where, rawType + " → " + type.canonical()));
        }

        // 写了这个 type 根本不读的键 → 白填（NSUK 会静默忽略）。
        Set<String> legal = type.acceptedKeys();
        for (String key : step.keySet()) {
            if (!legal.contains(key)) {
                issues.add(IndustrialActionIssue.warning("unexpected_key", where, key));
                continue;
            }
            DefinitionField field = type.field(key);
            if (field != null && field.aliases().contains(key)) {
                issues.add(new IndustrialActionIssue(IndustrialActionIssue.Severity.INFO, "alias_key",
                        where, key + " → " + field.key()));
            }
        }

        // 数值上下限。
        for (DefinitionField field : type.fields()) {
            if (!field.numeric() || !hasField(step, field)) {
                continue;
            }
            String key = effectiveKey(step, field);
            JsonElement value = step.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                issues.add(IndustrialActionIssue.error("invalid_number", where + "." + key, string(step, key)));
                continue;
            }
            double number = value.getAsDouble();
            if (!Double.isFinite(number)) {
                issues.add(IndustrialActionIssue.error("invalid_number", where + "." + key, value.toString()));
                continue;
            }
            if (field.kind() == DefinitionField.Kind.INTEGER && number != Math.rint(number)) {
                issues.add(IndustrialActionIssue.error("not_integer", where + "." + key,
                        Double.toString(number)));
            }
            if (number < field.min() || (field.hasMax() && number > field.max())) {
                issues.add(IndustrialActionIssue.error("out_of_range", where + "." + key,
                        trim(number) + " ∉ [" + trim(field.min()) + ", "
                                + (field.hasMax() ? trim(field.max()) : "∞") + "]"));
            }
        }

        // 命名点位 / 容器引用。
        // 合并兜底链之后一个 type 可能有多个容器槽（如 craft_recipe 的 input 与 output），
        // 所以这里遍历所有同类字段，不能只查 type.field("container") 一个。
        for (DefinitionField field : type.fields()) {
            if (field.kind() == DefinitionField.Kind.POINT_REF && hasField(step, field)) {
                String point = readString(step, field);
                if (!point.isBlank() && !pointKeys.contains(point)) {
                    issues.add(IndustrialActionIssue.error("unknown_point",
                            where + "." + effectiveKey(step, field), point));
                }
            } else if (field.kind() == DefinitionField.Kind.CONTAINER_REF && hasField(step, field)) {
                String container = readString(step, field);
                if (!container.isBlank() && !containerKeys.contains(container)) {
                    issues.add(IndustrialActionIssue.error("unknown_container",
                            where + "." + effectiveKey(step, field), container));
                }
            }
        }

        // 坐标数量与越界。
        List<JsonArray> positions = positionList(step);
        if (positions.size() > MAX_POSITIONS) {
            issues.add(IndustrialActionIssue.error("too_many_positions", where + ".positions",
                    positions.size() + " > " + MAX_POSITIONS));
        }
        if (bounds.known()) {
            for (int i = 0; i < positions.size(); i++) {
                JsonArray position = positions.get(i);
                if (position.size() < 3) {
                    issues.add(IndustrialActionIssue.error("invalid_position", where + ".positions[" + i + "]",
                            position.toString()));
                    continue;
                }
                int x = position.get(0).getAsInt();
                int y = position.get(1).getAsInt();
                int z = position.get(2).getAsInt();
                if (x < 0 || y < 0 || z < 0
                        || x >= bounds.sizeX() || y >= bounds.sizeY() || z >= bounds.sizeZ()) {
                    issues.add(IndustrialActionIssue.warning("position_out_of_bounds",
                            where + ".positions[" + i + "]",
                            x + "," + y + "," + z + " ∉ "
                                    + bounds.sizeX() + "x" + bounds.sizeY() + "x" + bounds.sizeZ()));
                }
            }
        }

        // repeat 专属检查。
        if (type.isRepeat()) {
            if (!step.has("steps") || !step.get("steps").isJsonArray()
                    || step.getAsJsonArray("steps").isEmpty()) {
                issues.add(IndustrialActionIssue.error("missing_repeat_steps", where, ""));
            } else {
                validateSteps(step, "steps", recipeId, where + ".steps", pointKeys, containerKeys,
                        bounds, issues, depth + 1);
            }
            if (repeatCount(step) <= 0) {
                issues.add(IndustrialActionIssue.error("missing_repeat_count", where, ""));
            }
            if (depth > 8) {
                issues.add(IndustrialActionIssue.warning("repeat_too_deep", where, Integer.toString(depth)));
            }
        }

        // skipOnTimeout 依赖 timeoutTicks；缺省值是 12000 tick（10 分钟），几乎等于不生效。
        DefinitionField skip = type.field("skipOnTimeout");
        if (skip != null && hasField(step, skip) && readFlag(step, skip)
                && type.field("timeoutTicks") != null && !hasField(step, type.field("timeoutTicks"))) {
            issues.add(IndustrialActionIssue.warning("skip_timeout_default", where,
                    type.field("timeoutTicks").fallback()));
        }

        // 步骤级输入 / 产出。
        validateInputs(arrayOrNull(step, "inputs"), where + ".inputs", issues, 0);
        validateOutputs(arrayOrNull(step, "outputs"), where + ".outputs", issues);

        // 空数组也是「覆盖」：craft_* 与 real_machine_recipe 只要看到 inputs/outputs 键存在
        // 就当成覆盖，空数组意味着「零需求 / 零产出」，很容易误伤。
        if (type.field("inputs") != null && step.has("inputs") && step.get("inputs").isJsonArray()
                && step.getAsJsonArray("inputs").isEmpty()) {
            issues.add(IndustrialActionIssue.warning("empty_inputs_override", where + ".inputs", ""));
        }
        if (type.field("outputs") != null && step.has("outputs") && step.get("outputs").isJsonArray()
                && step.getAsJsonArray("outputs").isEmpty()) {
            issues.add(IndustrialActionIssue.warning("empty_outputs_override", where + ".outputs", ""));
        }
    }

    /**
     * 校验一棵输入条件树。
     *
     * <p>{@code all} / {@code any} 可以互相递归嵌套，因此这里也是递归的。
     */
    private void validateInputs(JsonArray array, String location,
                                List<IndustrialActionIssue> issues, int depth) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            String where = location + "[" + i + "]";
            if (element == null || !element.isJsonObject()) {
                issues.add(IndustrialActionIssue.error("invalid_input", where, ""));
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            if (isGroup(node)) {
                if (depth > 8) {
                    issues.add(IndustrialActionIssue.warning("input_group_too_deep", where,
                            Integer.toString(depth)));
                    continue;
                }
                String childKey = isAnyGroup(node) ? groupKey(node, GROUP_ANY) : groupKey(node, GROUP_ALL);
                if (childKey == null) {
                    issues.add(IndustrialActionIssue.error("empty_input_group", where, ""));
                    continue;
                }
                validateInputs(node.getAsJsonArray(childKey), where + "." + childKey,
                        issues, depth + 1);
                continue;
            }
            String item = itemIdOf(node);
            if (item.isBlank()) {
                issues.add(IndustrialActionIssue.error("empty_input_item", where, ""));
            }
            if (node.has("count") && node.get("count").getAsInt() < 1) {
                issues.add(IndustrialActionIssue.error("invalid_count", where + ".count",
                        node.get("count").getAsString()));
            }
        }
    }

    /** 物品规格里的物品 id（单值或标签写法），用于「是不是空条件」的判断。 */
    /**
     * 校验**定义侧**：{@code points} 与 {@code containers} 里的每一条。
     *
     * <p>步骤侧只查「引用的名字存不存在」（{@code unknown_point} / {@code unknown_container}），
     * 这里补的是「那个名字自己写对没有」。这两类错性质完全不同：前者是引用写错，
     * 后者是**定义写坏** —— 而 NSUK 对后者**不报错，直接当作没找到**。
     */
    private void validateDefinitions(List<IndustrialActionIssue> issues, StructureBounds bounds) {
        validateDefinitionMap(issues, bounds, "points", true);
        validateDefinitionMap(issues, bounds, "containers", false);
    }

    private void validateDefinitionMap(List<IndustrialActionIssue> issues, StructureBounds bounds,
                                       String mapKey, boolean isPoint) {
        JsonElement element = root.get(mapKey);
        if (element == null || !element.isJsonObject()) {
            return;
        }
        for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String where = mapKey + "." + entry.getKey();
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                issues.add(IndustrialActionIssue.error("invalid_definition", where, "不是对象"));
                continue;
            }
            JsonObject definition = entry.getValue().getAsJsonObject();

            // ① type 必须是 structure_pos。
            // NSUK 是 if (!"structure_pos".equalsIgnoreCase(type)) return List.of(); —— 不报错，
            // 直接当作「没找到」。所以这条必须是 error，不能降级成 warning。
            String type = definition.has("type") ? string(definition, "type") : "structure_pos";
            if (!"structure_pos".equalsIgnoreCase(type)) {
                issues.add(IndustrialActionIssue.error(
                        isPoint ? "point_bad_type" : "container_bad_type", where + ".type", type));
            }

            // ② 坐标不能为空。NSUK 的 parsePositions 会记 missing_positions:<id>，
            //    而这条错误会让整个建筑被判为 invalid_definition（整栋失效，不只是这一条）。
            List<JsonArray> positions = positionList(definition);
            if (positions.isEmpty()) {
                issues.add(IndustrialActionIssue.error(
                        "definition_missing_positions", where + ".positions", ""));
                continue;
            }
            // ③ 上限与步骤侧同一套。
            if (positions.size() > MAX_POSITIONS) {
                issues.add(IndustrialActionIssue.error("too_many_positions", where + ".positions",
                        positions.size() + " > " + MAX_POSITIONS));
            }
            // ④ 越界（与步骤侧同样的判据）。
            if (bounds.known()) {
                for (int i = 0; i < positions.size(); i++) {
                    JsonArray position = positions.get(i);
                    if (position.size() < 3) {
                        issues.add(IndustrialActionIssue.error("invalid_position",
                                where + ".positions[" + i + "]", position.toString()));
                        continue;
                    }
                    int x = position.get(0).getAsInt();
                    int y = position.get(1).getAsInt();
                    int z = position.get(2).getAsInt();
                    if (x < 0 || y < 0 || z < 0
                            || x >= bounds.sizeX() || y >= bounds.sizeY() || z >= bounds.sizeZ()) {
                        issues.add(IndustrialActionIssue.warning("position_out_of_bounds",
                                where + ".positions[" + i + "]",
                                x + "," + y + "," + z + " ∉ " + bounds.sizeX() + "x"
                                        + bounds.sizeY() + "x" + bounds.sizeZ()));
                    }
                }
            }
            // ⑤ 点位专有：select 写错会被 SelectionMode.fromName 静默当成 nearest。
            if (isPoint && definition.has("select")) {
                String select = string(definition, "select");
                if (!"nearest".equalsIgnoreCase(select) && !"ordered".equalsIgnoreCase(select)) {
                    issues.add(IndustrialActionIssue.warning("point_bad_select",
                            where + ".select", select));
                }
            }
        }
    }

    private static void validateShapes(JsonElement value, String where, List<IndustrialActionIssue> issues, int depth) {
        if (depth > 64) {
            issues.add(IndustrialActionIssue.error("invalid_definition", where, "nesting > 64"));
            return;
        }
        if (value.isJsonArray()) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++)
                validateShapes(value.getAsJsonArray().get(i), where + "[" + i + "]", issues, depth + 1);
        } else if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                String key = entry.getKey(), location = where.isEmpty() ? key : where + "." + key;
                JsonElement child = entry.getValue();
                if (Set.of("count", "amount", "baseAmount", "randomRange", "probability").contains(key)) {
                    boolean decimal = "probability".equals(key);
                    if (!validNumber(child, decimal)) issues.add(IndustrialActionIssue.error("invalid_number", location, child.toString()));
                }
                if ("pos".equals(key)) {
                    if (!validPosition(child)) issues.add(IndustrialActionIssue.error("invalid_position", location, child.toString()));
                } else if ("positions".equals(key)) {
                    if (!child.isJsonArray()) issues.add(IndustrialActionIssue.error("invalid_position", location, child.toString()));
                    else for (int i = 0; i < child.getAsJsonArray().size(); i++) {
                        JsonElement point = child.getAsJsonArray().get(i);
                        if (!validPosition(point)) issues.add(IndustrialActionIssue.error("invalid_position", location + "[" + i + "]", point.toString()));
                    }
                } else if (knownShapeBranch(where, key)) {
                    validateShapes(child, location, issues, depth + 1);
                }
            }
        }
    }

    private static boolean knownShapeBranch(String where, String key) {
        if (where.isEmpty()) return Set.of("points", "containers", "recipes").contains(key);
        if (where.equals("points") || where.equals("containers")) return true;
        if (where.startsWith("points.") || where.startsWith("containers.")) return false;
        return Set.of("steps", "inputs", "outputs", "all", "any", "and", "or", "allOf", "anyOf", "与", "或").contains(key);
    }

    private static boolean validNumber(JsonElement value, boolean decimal) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        double number = value.getAsDouble();
        return Double.isFinite(number) && (decimal || (number == Math.rint(number)
                && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE));
    }

    public static boolean validPosition(JsonElement value) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) return false;
        for (JsonElement coordinate : value.getAsJsonArray()) if (!validNumber(coordinate, false)) return false;
        return true;
    }

    private static String itemIdOf(JsonObject node) {
        return firstNonBlank(string(node, "item"),
                stringAny(node, "tag", "itemTag", "item_tag"),
                string(node, "itemStack"), string(node, "itemString"), string(node, "stack"));
    }

    private void validateOutputs(JsonArray array, String location,
                                 List<IndustrialActionIssue> issues) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            String where = location + "[" + i + "]";
            if (element == null || !element.isJsonObject()) {
                issues.add(IndustrialActionIssue.error("invalid_output", where, ""));
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            String item = itemIdOf(node);
            if (item.isBlank()) {
                issues.add(IndustrialActionIssue.error("empty_output_item", where, ""));
            }
            if (node.has("baseAmount")) {
                int amount = node.get("baseAmount").getAsInt();
                if (amount < 1) {
                    issues.add(IndustrialActionIssue.error("invalid_amount", where + ".baseAmount",
                            Integer.toString(amount)));
                }
            }
            if (node.has("randomRange")) {
                int range = node.get("randomRange").getAsInt();
                if (range < 0) {
                    issues.add(IndustrialActionIssue.error("invalid_random_range", where + ".randomRange",
                            Integer.toString(range)));
                }
            }
            if (node.has("probability")) {
                double probability = node.get("probability").getAsDouble();
                if (probability < 0D || probability > 1D) {
                    issues.add(IndustrialActionIssue.error("invalid_probability", where + ".probability",
                            trim(probability)));
                }
            }
        }
    }

    // ── 展示辅助 ───────────────────────────────────────────────────────

    /**
     * 物品规格的展示用 id。
     *
     * <p>等价于 NSUK 的 {@code IndustrialItemStackSpec.displayItemId()}：物品规格在 JSON 里
     * 分散在多个同层键上，且有固定优先级（{@code item} → {@code tag} → {@code itemStack}），
     * 所以不能用 {@link #readString} 按单个键去读。
     */
    public static String readItemId(JsonObject step) {
        if (step == null) {
            return "";
        }
        String item = string(step, "item");
        if (!item.isBlank()) {
            return item;
        }
        String tag = stringAny(step, "tag", "itemTag", "item_tag");
        if (!tag.isBlank()) {
            return "#" + tag;
        }
        String stack = stringAny(step, "itemStack", "itemString", "stack");
        if (!stack.isBlank()) {
            int componentStart = stack.indexOf('[');
            return componentStart >= 0 ? stack.substring(0, componentStart) : stack;
        }
        return "";
    }

    /** 是否写了任何形式的物品规格（单值）。 */
    public static boolean hasItemSpec(JsonObject step) {
        return !readItemId(step).isBlank();
    }

    /**
     * 积木右侧的「就地可读」短标签，例如 {@code output}、{@code 40t}、{@code ×3}。
     *
     * <p>只挑该 type 最有辨识度的一两个参数，完整编辑交给检查器。
     */
    public static String summary(JsonObject step) {
        IndustrialStepType type = IndustrialStepType.byRawType(string(step, "type"));
        if (type == null) {
            return string(step, "type");
        }
        if (type.isRepeat()) {
            int repetitions = repeatCount(step);
            boolean byPositions = !positionList(step).isEmpty();
            return (byPositions ? "positions ×" : "count ×") + repetitions;
        }
        List<String> parts = new ArrayList<>();
        DefinitionField container = type.field("container");
        if (container != null && hasField(step, container)) {
            parts.add(readString(step, container));
        } else if (type.field("output") != null && hasField(step, type.field("output"))) {
            parts.add(readString(step, type.field("output")));
        }
        DefinitionField point = type.field("point");
        if (point != null && hasField(step, point)) {
            parts.add(readString(step, point));
        }
        String itemId = readItemId(step);
        if (!itemId.isBlank()) {
            parts.add(itemId);
        } else if (type.field("block") != null && hasField(step, type.field("block"))) {
            parts.add(readString(step, type.field("block")));
        } else if (type.field("fluid") != null && hasField(step, type.field("fluid"))) {
            parts.add(readString(step, type.field("fluid")));
        }
        DefinitionField ticks = type.field("ticks");
        if (ticks != null && hasField(step, ticks)) {
            parts.add(trim(readNumber(step, ticks)) + "t");
        }
        if (parts.isEmpty()) {
            return "";
        }
        return String.join(" · ", parts);
    }

    // ── 内部工具 ───────────────────────────────────────────────────────

    /**
     * 物品规格在 JSON 里不是单个键，而是一组同层键。
     *
     * <p>判定某个 type 是否读这些键，请用 {@link IndustrialStepType#field(String)}，
     * 不要用这个方法直接放行。
     */
    public static boolean isItemSpecKey(String key) {
        return IndustrialStepType.ITEM_SPEC_KEYS.contains(key)
                || IndustrialStepType.ITEM_LIST_KEYS.contains(key);
    }

    private static JsonArray arrayOrNull(JsonObject owner, String key) {
        if (owner == null || !owner.has(key) || !owner.get(key).isJsonArray()) {
            return null;
        }
        return owner.getAsJsonArray(key);
    }

    private static Set<String> objectKeys(JsonElement element) {
        Set<String> keys = new LinkedHashSet<>();
        if (element != null && element.isJsonObject()) {
            keys.addAll(element.getAsJsonObject().keySet());
        }
        return keys;
    }

    private static String recipeId(JsonObject recipe, int index) {
        String id = string(recipe, "id");
        return id.isBlank() ? "recipe_" + index : id;
    }

    private static String uniqueId(String base, Set<String> used) {
        if (!used.contains(base)) {
            return base;
        }
        int index = 2;
        while (used.contains(base + "_" + index)) {
            index++;
        }
        return base + "_" + index;
    }

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

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String stringAny(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static double parseNumber(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
