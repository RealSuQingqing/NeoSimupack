/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.creation.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.rstech.simupack.creation.industrial.IndustrialCommonFields;
import com.rstech.simupack.creation.industrial.IndustrialStepType;
import com.rstech.simupack.creation.json.DefinitionField;
import com.rstech.simupack.creation.json.DefinitionIssue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public final class BuildingJsonDocument {
    public static final int MAX_STEPS = 256;
    public static final int MAX_RECIPES = 64;
    public static final int MAX_POSITIONS = 64;
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    public static final String GROUP_ALL = "all";
    public static final String GROUP_ANY = "any";
    public static final String GROUP_ROOT = "";
    private static final String[] ALL_ALIASES = new String[]{"all", "and", "allOf", "\u4e0e"};
    private static final String[] ANY_ALIASES = new String[]{"any", "or", "anyOf", "\u6216"};
    private final JsonObject root;
    private String selectedRecipeId;
    private final Map<String, JsonObject> renamedRecipes = new HashMap<String, JsonObject>();
    public static final String EDITOR_LAYOUT_KEY = "_simupackEditor";
    private static final String CONTAINER_LINK = "_simupackContainerPoint";

    public boolean renameRecipe(String oldId, String newId) {
        JsonObject recipe = this.recipe(oldId);
        if (recipe == null || newId == null || newId.isBlank() || oldId.equals(newId) || this.recipe(newId) != null) {
            return false;
        }
        this.renamedRecipes.put(oldId, recipe);
        recipe.addProperty("id", newId);
        if (oldId.equals(this.selectedRecipeId)) {
            this.selectedRecipeId = newId;
        }
        return true;
    }

    private String currentOwnerRecipe(String id) {
        JsonObject renamed = this.renamedRecipes.get(id);
        if (renamed != null && this.recipes().stream().anyMatch(recipe -> recipe == renamed)) {
            return BuildingJsonDocument.string(renamed, "id");
        }
        return id;
    }

    private BuildingJsonDocument(JsonObject root) {
        this.root = root;
        List<JsonObject> recipes = this.recipes();
        this.selectedRecipeId = recipes.isEmpty() ? GROUP_ROOT : BuildingJsonDocument.recipeId(recipes.get(0), 0);
    }

    public static BuildingJsonDocument parse(String text) {
        JsonElement parsed;
        if (text == null || text.isBlank()) {
            JsonObject empty = new JsonObject();
            empty.add("recipes", (JsonElement)new JsonArray());
            return new BuildingJsonDocument(empty);
        }
        try {
            parsed = JsonParser.parseString((String)text);
        }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException("\u4e0d\u662f\u5408\u6cd5\u7684 JSON\uff1a" + exception.getMessage(), exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("\u9876\u5c42\u5fc5\u987b\u662f JSON \u5bf9\u8c61");
        }
        return new BuildingJsonDocument(parsed.getAsJsonObject());
    }

    public static BuildingJsonDocument createDefault(String id, String name) {
        JsonObject created = new JsonObject();
        if (id != null && !id.isBlank()) {
            created.addProperty("id", id);
        }
        if (name != null && !name.isBlank()) {
            created.addProperty("name", name);
        }
        created.add("recipes", (JsonElement)new JsonArray());
        return new BuildingJsonDocument(created);
    }

    public String toJsonString() {
        return PRETTY.toJson((JsonElement)this.root);
    }

    public JsonObject root() {
        return this.root;
    }

    public JsonObject editorLayout(boolean create) {
        JsonElement existing = this.root.get(EDITOR_LAYOUT_KEY);
        if (existing != null && existing.isJsonObject()) {
            return existing.getAsJsonObject();
        }
        if (!create) {
            return null;
        }
        JsonObject created = new JsonObject();
        this.root.add(EDITOR_LAYOUT_KEY, (JsonElement)created);
        return created;
    }

    public List<String> pointKeys() {
        return List.copyOf(BuildingJsonDocument.objectKeys(this.root.get("points")));
    }

    public List<String> containerKeys() {
        return List.copyOf(BuildingJsonDocument.objectKeys(this.root.get("containers")));
    }

    public boolean hasDefinition(String name, DefinitionKind kind) {
        JsonObject map = this.definitionMap(kind, false);
        return map != null && name != null && !name.isBlank() && map.has(name);
    }

    public JsonObject definitionEntry(String name, DefinitionKind kind) {
        JsonObject map = this.definitionMap(kind, false);
        if (map == null || name == null || !map.has(name)) {
            return null;
        }
        JsonElement entry = map.get(name);
        return entry != null && entry.isJsonObject() ? entry.getAsJsonObject() : null;
    }

    public boolean isContainerPoint(String name) {
        return this.hasDefinition(name, DefinitionKind.CONTAINER);
    }

    public boolean definePoint(String name, DefinitionKind kind, List<int[]> positions, String select) {
        if (name == null || name.isBlank() || positions == null || positions.isEmpty()) {
            return false;
        }
        JsonObject map = this.definitionMap(kind, true);
        if (map.has(name)) {
            return false;
        }
        map.add(name, (JsonElement)BuildingJsonDocument.definitionJson(positions, kind == DefinitionKind.POINT ? select : null));
        return true;
    }

    public boolean setDefinitionPositions(String name, List<int[]> positions, String select) {
        if (name == null || positions == null || positions.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (DefinitionKind kind : DefinitionKind.values()) {
            JsonObject map = this.definitionMap(kind, false);
            if (map == null || !map.has(name)) continue;
            this.setDefinitionPositions(name, kind, positions, select);
            changed = true;
        }
        return changed;
    }

    public boolean setDefinitionPositions(String name, DefinitionKind kind, List<int[]> positions, String select) {
        String nextSelect;
        JsonObject entry = this.definitionEntry(name, kind);
        if (entry == null || positions == null || positions.isEmpty()) {
            return false;
        }
        JsonObject replacement = BuildingJsonDocument.definitionJson(positions, kind == DefinitionKind.POINT ? select : null);
        String previousSelect = entry.has("select") ? BuildingJsonDocument.string(entry, "select") : "nearest";
        String string = nextSelect = replacement.has("select") ? BuildingJsonDocument.string(replacement, "select") : "nearest";
        if (BuildingJsonDocument.positionList(entry).equals(BuildingJsonDocument.positionList(replacement)) && (kind == DefinitionKind.CONTAINER || positions.size() == 1 || previousSelect.equals(nextSelect))) {
            return false;
        }
        JsonObject before = entry.deepCopy();
        entry.remove("pos");
        entry.add("positions", replacement.get("positions").deepCopy());
        if (kind == DefinitionKind.POINT) {
            entry.remove("select");
            if (replacement.has("select")) {
                entry.add("select", replacement.get("select"));
            }
            entry.remove(CONTAINER_LINK);
        } else {
            JsonObject mirror = this.definitionEntry(name, DefinitionKind.POINT);
            if (mirror != null && name.equals(BuildingJsonDocument.string(mirror, CONTAINER_LINK))) {
                mirror.remove("pos");
                mirror.add("positions", replacement.get("positions").deepCopy());
            }
        }
        return !before.equals((Object)entry);
    }

    private boolean linkedPoint(String name) {
        JsonObject point = this.definitionEntry(name, DefinitionKind.POINT);
        return point != null && name.equals(BuildingJsonDocument.string(point, CONTAINER_LINK));
    }

    public List<Reference> findReferences(String name, DefinitionKind kind) {
        ArrayList<Reference> result = new ArrayList<Reference>();
        boolean mirror = kind == DefinitionKind.CONTAINER && this.linkedPoint(name);
        this.forEachReferencingField((recipeId, path, step, field) -> {
            boolean matchesKind;
            boolean bl = matchesKind = field.kind() == (kind == DefinitionKind.CONTAINER ? DefinitionField.Kind.CONTAINER_REF : DefinitionField.Kind.POINT_REF) || mirror && field.kind() == DefinitionField.Kind.POINT_REF;
            if (matchesKind) {
                for (String key : field.acceptedKeys()) {
                    Reference reference;
                    if (!name.equals(BuildingJsonDocument.string(step, key)) || result.contains(reference = new Reference(recipeId, path, key))) continue;
                    result.add(reference);
                }
            }
        });
        return result;
    }

    public boolean renameDefinition(String oldName, String newName, DefinitionKind kind) {
        if (newName == null || newName.isBlank() || oldName.equals(newName) || !this.hasDefinition(oldName, kind) || this.hasDefinition(newName, DefinitionKind.POINT) || this.hasDefinition(newName, DefinitionKind.CONTAINER)) {
            return false;
        }
        List<Reference> references = this.findReferences(oldName, kind);
        boolean mirror = kind == DefinitionKind.CONTAINER && this.linkedPoint(oldName);
        JsonObject map = this.definitionMap(kind, false);
        map.add(newName, map.remove(oldName));
        if (mirror) {
            JsonObject points = this.definitionMap(DefinitionKind.POINT, false);
            JsonObject point = points.remove(oldName).getAsJsonObject();
            point.addProperty(CONTAINER_LINK, newName);
            points.add(newName, (JsonElement)point);
        } else if (kind == DefinitionKind.POINT) {
            this.definitionEntry(newName, kind).remove(CONTAINER_LINK);
        }
        for (Reference reference : references) {
            this.step(reference.recipeId(), reference.path()).addProperty(reference.fieldKey(), newName);
        }
        return true;
    }

    public DeleteResult deleteDefinition(String name, DefinitionKind kind, boolean force) {
        List<Reference> references = this.findReferences(name, kind);
        if (!this.hasDefinition(name, kind) || !force && !references.isEmpty()) {
            return new DeleteResult(false, references);
        }
        boolean mirror = kind == DefinitionKind.CONTAINER && this.linkedPoint(name);
        this.definitionMap(kind, false).remove(name);
        if (mirror) {
            this.definitionMap(DefinitionKind.POINT, false).remove(name);
        }
        if (force) {
            for (Reference reference : references) {
                this.step(reference.recipeId(), reference.path()).remove(reference.fieldKey());
            }
        }
        return new DeleteResult(true, references);
    }

    public int renameDefinition(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.isBlank() || newName.isBlank() || oldName.equals(newName)) {
            return 0;
        }
        for (DefinitionKind kind : DefinitionKind.values()) {
            JsonElement value;
            JsonObject map = this.definitionMap(kind, false);
            if (map == null || !map.has(oldName) || (value = map.remove(oldName)) == null) continue;
            map.add(newName, value);
        }
        return this.rewriteReferences(oldName, newName);
    }

    public DeleteResult deleteDefinition(String name, boolean force) {
        if (name == null || name.isBlank()) {
            return new DeleteResult(false, List.of());
        }
        List<Reference> references = this.findReferences(name);
        if (!references.isEmpty() && !force) {
            return new DeleteResult(false, references);
        }
        for (DefinitionKind kind : DefinitionKind.values()) {
            JsonObject map = this.definitionMap(kind, false);
            if (map == null) continue;
            map.remove(name);
        }
        if (force) {
            for (Reference reference : references) {
                JsonObject step = this.step(reference.recipeId(), reference.path());
                if (step == null) continue;
                step.remove(reference.fieldKey());
            }
        }
        return new DeleteResult(true, references);
    }

    public boolean ensurePointEntry(String name) {
        if (name == null || name.isBlank() || this.hasDefinition(name, DefinitionKind.POINT)) {
            return false;
        }
        JsonObject container = this.definitionEntry(name, DefinitionKind.CONTAINER);
        if (container == null) {
            return false;
        }
        JsonObject mirror = container.deepCopy();
        mirror.addProperty(CONTAINER_LINK, name);
        this.definitionMap(DefinitionKind.POINT, true).add(name, (JsonElement)mirror);
        return true;
    }

    public List<Reference> findReferences(String name) {
        ArrayList<Reference> result = new ArrayList<Reference>();
        if (name == null || name.isBlank()) {
            return result;
        }
        this.forEachReferencingField((recipeId, path, step, field) -> {
            if (name.equals(BuildingJsonDocument.readString(step, field))) {
                result.add(new Reference(recipeId, path, BuildingJsonDocument.effectiveKey(step, field)));
            }
        });
        return result;
    }

    private int rewriteReferences(String oldName, String newName) {
        int[] count = new int[]{0};
        this.forEachReferencingField((recipeId, path, step, field) -> {
            if (oldName.equals(BuildingJsonDocument.readString(step, field))) {
                BuildingJsonDocument.writeString(step, field, newName);
                count[0] = count[0] + 1;
            }
        });
        return count[0];
    }

    private void forEachReferencingField(ReferencingFieldVisitor visitor) {
        for (JsonObject recipe : this.recipes()) {
            String id = BuildingJsonDocument.string(recipe, "id");
            if (id.isBlank()) continue;
            this.walkChain(id, this.stepsArray(id, null, false), null, (path, step) -> {
                IndustrialStepType type = IndustrialStepType.byRawType(BuildingJsonDocument.string(step, "type"));
                if (type == null) {
                    return;
                }
                for (DefinitionField field : type.fields()) {
                    DefinitionField.Kind kind = field.kind();
                    if (kind != DefinitionField.Kind.POINT_REF && kind != DefinitionField.Kind.CONTAINER_REF || !BuildingJsonDocument.hasField(step, field)) continue;
                    visitor.accept(id, (StepPath)path, (JsonObject)step, field);
                }
            });
        }
    }

    private void walkChain(String recipeId, JsonArray array, StepPath parent, BiConsumer<StepPath, JsonObject> visitor) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); ++i) {
            JsonElement element = array.get(i);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject step = element.getAsJsonObject();
            StepPath path = parent == null ? StepPath.root(i) : parent.down(i);
            visitor.accept(path, step);
            IndustrialStepType type = IndustrialStepType.byRawType(BuildingJsonDocument.string(step, "type"));
            if (type == null || !type.isRepeat()) continue;
            this.walkChain(recipeId, this.stepsArray(recipeId, path, false), path, visitor);
        }
    }

    private JsonObject definitionMap(DefinitionKind kind, boolean create) {
        JsonElement element = this.root.get(kind.key());
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        if (!create) {
            return null;
        }
        JsonObject map = new JsonObject();
        this.root.add(kind.key(), (JsonElement)map);
        return map;
    }

    private static JsonObject definitionJson(List<int[]> positions, String select) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "structure_pos");
        JsonArray array = new JsonArray();
        for (int[] position : positions) {
            JsonArray one = new JsonArray();
            one.add((Number)position[0]);
            one.add((Number)position[1]);
            one.add((Number)position[2]);
            array.add((JsonElement)one);
        }
        entry.add("positions", (JsonElement)array);
        if (select != null && !select.isBlank() && array.size() > 1) {
            entry.addProperty("select", select);
        }
        return entry;
    }

    public String definitionId() {
        return BuildingJsonDocument.string(this.root, "id");
    }

    public String definitionName() {
        return BuildingJsonDocument.string(this.root, "name");
    }

    public String definitionField(String key) {
        return BuildingJsonDocument.string(this.root, key);
    }

    public List<JsonObject> recipes() {
        ArrayList<JsonObject> result = new ArrayList<JsonObject>();
        if (!this.root.has("recipes") || !this.root.get("recipes").isJsonArray()) {
            return result;
        }
        for (JsonElement element : this.root.getAsJsonArray("recipes")) {
            if (element == null || !element.isJsonObject()) continue;
            result.add(element.getAsJsonObject());
        }
        return result;
    }

    public String selectedRecipeId() {
        return this.selectedRecipeId;
    }

    public void selectRecipe(String recipeId) {
        for (JsonObject recipe : this.recipes()) {
            if (!BuildingJsonDocument.recipeId(recipe, 0).equals(recipeId)) continue;
            this.selectedRecipeId = recipeId;
            return;
        }
        List<JsonObject> all = this.recipes();
        this.selectedRecipeId = all.isEmpty() ? GROUP_ROOT : BuildingJsonDocument.recipeId(all.get(0), 0);
    }

    public JsonObject selectedRecipe() {
        return this.recipe(this.selectedRecipeId);
    }

    public JsonObject recipe(String recipeId) {
        for (JsonObject recipe : this.recipes()) {
            if (!BuildingJsonDocument.recipeId(recipe, 0).equals(recipeId)) continue;
            return recipe;
        }
        return null;
    }

    public String recipeDisplayName(JsonObject recipe) {
        String name = BuildingJsonDocument.string(recipe, "name");
        return name.isBlank() ? BuildingJsonDocument.recipeId(recipe, 0) : name;
    }

    public JsonObject addRecipe(String baseId) {
        JsonObject createdRecipe;
        JsonArray array;
        JsonArray jsonArray = array = this.root.has("recipes") && this.root.get("recipes").isJsonArray() ? this.root.getAsJsonArray("recipes") : new JsonArray();
        if (!this.root.has("recipes")) {
            this.root.add("recipes", (JsonElement)array);
        }
        LinkedHashSet<String> used = new LinkedHashSet<String>();
        for (JsonObject recipe : this.recipes()) {
            used.add(BuildingJsonDocument.recipeId(recipe, 0));
        }
        String id = BuildingJsonDocument.uniqueId(baseId == null || baseId.isBlank() ? "recipe" : baseId, used);
        createdRecipe = new JsonObject();
        createdRecipe.addProperty("id", id);
        createdRecipe.addProperty("name", id);
        createdRecipe.add("inputs", (JsonElement)new JsonArray());
        createdRecipe.add("outputs", (JsonElement)new JsonArray());
        createdRecipe.add("steps", (JsonElement)new JsonArray());
        array.add((JsonElement)createdRecipe);
        this.selectedRecipeId = id;
        return createdRecipe;
    }

    public JsonObject duplicateRecipe(String recipeId) {
        JsonObject source = this.recipe(recipeId);
        if (source == null) {
            return null;
        }
        JsonArray array = this.root.getAsJsonArray("recipes");
        JsonObject copy = source.deepCopy();
        LinkedHashSet<String> used = new LinkedHashSet<String>();
        for (JsonObject recipe : this.recipes()) {
            used.add(BuildingJsonDocument.recipeId(recipe, 0));
        }
        String id = BuildingJsonDocument.uniqueId(BuildingJsonDocument.recipeId(source, 0) + "_copy", used);
        copy.addProperty("id", id);
        copy.addProperty("name", id);
        array.add((JsonElement)copy);
        this.selectedRecipeId = id;
        return copy;
    }

    public boolean removeRecipe(String recipeId) {
        JsonArray array;
        JsonArray jsonArray = array = this.root.has("recipes") && this.root.get("recipes").isJsonArray() ? this.root.getAsJsonArray("recipes") : null;
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.size(); ++i) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject() || !BuildingJsonDocument.recipeId(element.getAsJsonObject(), i).equals(recipeId)) continue;
            array.remove(i);
            if (this.selectedRecipeId.equals(recipeId)) {
                List<JsonObject> rest = this.recipes();
                this.selectedRecipeId = rest.isEmpty() ? GROUP_ROOT : BuildingJsonDocument.recipeId(rest.get(0), 0);
            }
            return true;
        }
        return false;
    }

    public void setRecipeProperty(String recipeId, String key, String value) {
        JsonObject recipe = this.recipe(recipeId);
        if (recipe == null) {
            return;
        }
        if (value == null || value.isBlank()) {
            recipe.remove(key);
        } else {
            recipe.addProperty(key, value);
        }
    }

    public List<JsonObject> steps(String recipeId) {
        return this.steps(recipeId, null);
    }

    public List<JsonObject> steps(String recipeId, StepPath parent) {
        ArrayList<JsonObject> result = new ArrayList<JsonObject>();
        JsonArray array = this.stepsArray(recipeId, parent, false);
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            result.add(element.getAsJsonObject());
        }
        return result;
    }

    public JsonObject step(String recipeId, StepPath path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        JsonArray array = this.stepsArray(recipeId, path.up(), false);
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

    public JsonArray siblingArray(String recipeId, StepPath path) {
        return path == null ? null : this.stepsArray(recipeId, path.up(), false);
    }

    public JsonArray stepsArray(String recipeId, StepPath parent, boolean create) {
        JsonObject owner = parent == null || parent.isEmpty() ? this.recipe(recipeId) : this.step(recipeId, parent);
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
        owner.add("steps", (JsonElement)array);
        return array;
    }

    public StepPath insertStep(String recipeId, StepPath parent, int index, IndustrialStepType type) {
        if (type == null) {
            return null;
        }
        return this.insertExistingStep(recipeId, parent, index, type.newStepJson());
    }

    public StepPath insertExistingStep(String recipeId, StepPath parent, int index, JsonObject step) {
        JsonArray array = this.stepsArray(recipeId, parent, true);
        if (array == null || step == null) {
            return null;
        }
        int at = Math.max(0, Math.min(index, array.size()));
        BuildingJsonDocument.arrayInsert(array, at, (JsonElement)step.deepCopy());
        ArrayList<Integer> indices = parent == null ? new ArrayList<Integer>() : new ArrayList<Integer>(parent.indices());
        indices.add(at);
        return new StepPath(indices);
    }

    public StepPath moveStepTo(String recipeId, StepPath from, StepPath targetParent, int index) {
        if (from == null) {
            return null;
        }
        JsonObject node = this.step(recipeId, from);
        if (node == null) {
            return from;
        }
        if (!this.canMoveInto(from, targetParent)) {
            return from;
        }
        int target = Math.max(0, index);
        if (BuildingJsonDocument.sameChain(from, targetParent) && target > from.leaf()) {
            --target;
        }
        JsonObject copy = node.deepCopy();
        if (!this.removeStep(recipeId, from)) {
            return from;
        }
        StepPath created = this.insertExistingStep(recipeId, targetParent, target, copy);
        return created == null ? from : created;
    }

    public static boolean sameChain(StepPath a, StepPath b) {
        return BuildingJsonDocument.parentChainOf(a).equals(BuildingJsonDocument.parentChainOf(b));
    }

    private static List<Integer> parentChainOf(StepPath path) {
        if (path == null) {
            return List.of();
        }
        StepPath up = path.up();
        return up == null ? List.of() : up.indices();
    }

    public StepPath insertStepAfter(String recipeId, StepPath path, IndustrialStepType type) {
        if (path == null || path.isEmpty()) {
            return this.insertStep(recipeId, null, Integer.MAX_VALUE, type);
        }
        return this.insertStep(recipeId, path.up(), path.leaf() + 1, type);
    }

    public boolean removeStep(String recipeId, StepPath path) {
        JsonArray array = this.siblingArray(recipeId, path);
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

    public StepPath duplicateStep(String recipeId, StepPath path) {
        JsonObject source = this.step(recipeId, path);
        JsonArray array = this.siblingArray(recipeId, path);
        if (source == null || array == null) {
            return null;
        }
        int at = path.leaf() + 1;
        BuildingJsonDocument.arrayInsert(array, at, (JsonElement)source.deepCopy());
        return path.withLeaf(at);
    }

    public StepPath moveStep(String recipeId, StepPath path, int delta) {
        JsonArray array = this.siblingArray(recipeId, path);
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
        BuildingJsonDocument.arrayInsert(array, to, moved);
        return path.withLeaf(to);
    }

    public StepPath reorderStep(String recipeId, StepPath path, int targetIndex) {
        JsonArray array = this.siblingArray(recipeId, path);
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
        BuildingJsonDocument.arrayInsert(array, to, moved);
        return path.withLeaf(to);
    }

    public boolean canMoveInto(StepPath moving, StepPath targetParent) {
        if (moving == null || targetParent == null) {
            return true;
        }
        if (targetParent.indices().size() < moving.indices().size()) {
            return true;
        }
        return !targetParent.indices().subList(0, moving.indices().size()).equals(moving.indices());
    }

    public static boolean hasField(JsonObject step, DefinitionField field) {
        for (String key : field.acceptedKeys()) {
            if (!step.has(key) || step.get(key).isJsonNull()) continue;
            return true;
        }
        return false;
    }

    public static String effectiveKey(JsonObject step, DefinitionField field) {
        for (String key : field.acceptedKeys()) {
            if (!step.has(key) || step.get(key).isJsonNull()) continue;
            return key;
        }
        return field.key();
    }

    public static String readString(JsonObject step, DefinitionField field) {
        String key = BuildingJsonDocument.effectiveKey(step, field);
        if (!step.has(key) || step.get(key).isJsonNull()) {
            return field.fallback();
        }
        JsonElement element = step.get(key);
        try {
            double number;
            String text = element.getAsString();
            if (field.kind() == DefinitionField.Kind.INTEGER && Double.isFinite(number = Double.parseDouble(text.trim())) && number == Math.rint(number) && number >= -9.223372036854776E18 && number <= 9.223372036854776E18) {
                return Long.toString((long)number);
            }
            return text;
        }
        catch (RuntimeException exception) {
            return field.fallback();
        }
    }

    public static double readNumber(JsonObject step, DefinitionField field) {
        String key = BuildingJsonDocument.effectiveKey(step, field);
        if (!step.has(key) || step.get(key).isJsonNull()) {
            return BuildingJsonDocument.parseNumber(field.fallback(), 0.0);
        }
        try {
            return step.get(key).getAsDouble();
        }
        catch (RuntimeException exception) {
            return BuildingJsonDocument.parseNumber(field.fallback(), 0.0);
        }
    }

    public static boolean readFlag(JsonObject step, DefinitionField field) {
        String key = BuildingJsonDocument.effectiveKey(step, field);
        if (!step.has(key) || step.get(key).isJsonNull()) {
            return Boolean.parseBoolean(field.fallback());
        }
        try {
            return step.get(key).getAsBoolean();
        }
        catch (RuntimeException exception) {
            return Boolean.parseBoolean(field.fallback());
        }
    }

    public static void writeField(JsonObject step, DefinitionField field, JsonElement value) {
        BuildingJsonDocument.clearField(step, field);
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().isBlank()) {
            return;
        }
        step.add(field.key(), value);
    }

    public static void writeString(JsonObject step, DefinitionField field, String value) {
        if (value == null) {
            BuildingJsonDocument.clearField(step, field);
            return;
        }
        BuildingJsonDocument.writeField(step, field, (JsonElement)new JsonPrimitive(value));
    }

    public static void writeNumber(JsonObject step, DefinitionField field, double value) {
        JsonPrimitive number = field.kind() == DefinitionField.Kind.INTEGER ? new JsonPrimitive((Number)((long)value)) : new JsonPrimitive((Number)value);
        BuildingJsonDocument.writeField(step, field, (JsonElement)number);
    }

    public static void writeFlag(JsonObject step, DefinitionField field, boolean value) {
        BuildingJsonDocument.writeField(step, field, (JsonElement)new JsonPrimitive(Boolean.valueOf(value)));
    }

    public static void clearField(JsonObject step, DefinitionField field) {
        IndustrialStepType type = IndustrialStepType.byRawType(BuildingJsonDocument.string(step, "type"));
        if (type != null && field.kind() == DefinitionField.Kind.CONTAINER_REF) {
            for (DefinitionField other2 : type.fields()) {
                if (other2.kind() != DefinitionField.Kind.CONTAINER_REF || other2.key().equals(field.key()) || !BuildingJsonDocument.hasField(step, other2) || !field.acceptedKeys().contains(BuildingJsonDocument.effectiveKey(step, other2))) continue;
                step.addProperty(other2.key(), BuildingJsonDocument.readString(step, other2));
            }
        }
        for (String key : field.acceptedKeys()) {
            boolean otherCanonical = type != null && type.fields().stream().anyMatch(other -> !other.key().equals(field.key()) && other.key().equals(key));
            if (otherCanonical) continue;
            step.remove(key);
        }
    }

    public JsonObject ownerObject(InputOwner owner) {
        if (owner == null) {
            return null;
        }
        String id = this.currentOwnerRecipe(owner.recipeId());
        return owner.stepPath() == null ? this.recipe(id) : this.step(id, owner.stepPath());
    }

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
            owner.add("inputs", (JsonElement)array);
            return array;
        }
        String actual = BuildingJsonDocument.groupKey(owner, arrayKey);
        if (actual != null) {
            return owner.getAsJsonArray(actual);
        }
        if (!create) {
            return null;
        }
        JsonArray created = new JsonArray();
        owner.add(GROUP_ANY.equals(arrayKey) ? GROUP_ANY : GROUP_ALL, (JsonElement)created);
        return created;
    }

    private static String groupKey(JsonObject groupOwner, String canonical) {
        String[] candidates;
        if (groupOwner == null) {
            return null;
        }
        for (String key : candidates = GROUP_ANY.equals(canonical) ? ANY_ALIASES : ALL_ALIASES) {
            if (!groupOwner.has(key) || !groupOwner.get(key).isJsonArray()) continue;
            return key;
        }
        return null;
    }

    public JsonObject inputNode(InputOwner owner, InputPath path) {
        JsonObject target = this.ownerObject(owner);
        if (target == null || path == null || path.isEmpty()) {
            return null;
        }
        JsonObject current = target;
        for (InputRef ref : path.refs()) {
            JsonArray array = this.inputArray(current, ref.arrayKey(), false);
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

    public static boolean isAnyGroup(JsonObject node) {
        for (String key : ANY_ALIASES) {
            if (!node.has(key) || !node.get(key).isJsonArray()) continue;
            return true;
        }
        return false;
    }

    public static boolean isGroup(JsonObject node) {
        for (String key : ANY_ALIASES) {
            if (!node.has(key) || !node.get(key).isJsonArray()) continue;
            return true;
        }
        for (String key : ALL_ALIASES) {
            if (!node.has(key) || !node.get(key).isJsonArray()) continue;
            return true;
        }
        return false;
    }

    public InputPath addInputItem(InputOwner owner, InputPath parent) {
        JsonObject group;
        JsonObject target = this.ownerObject(owner);
        if (target == null) {
            return null;
        }
        boolean atRoot = parent == null || parent.isEmpty();
        JsonObject jsonObject = group = atRoot ? target : this.inputNode(owner, parent);
        if (group == null) {
            return null;
        }
        String childKey = atRoot ? GROUP_ROOT : (BuildingJsonDocument.isAnyGroup(group) ? GROUP_ANY : GROUP_ALL);
        JsonArray array = this.inputArray(group, childKey, true);
        if (array == null) {
            return null;
        }
        JsonObject leaf = new JsonObject();
        leaf.addProperty("item", "minecraft:stone");
        array.add((JsonElement)leaf);
        int index = array.size() - 1;
        return atRoot ? InputPath.root(index) : parent.down(childKey, index);
    }

    public InputPath addInputGroup(InputOwner owner, InputPath parent, boolean any) {
        JsonObject group;
        JsonObject target = this.ownerObject(owner);
        if (target == null) {
            return null;
        }
        JsonObject jsonObject = group = parent == null || parent.isEmpty() ? target : this.inputNode(owner, parent);
        if (group == null) {
            return null;
        }
        String parentKey = parent == null || parent.isEmpty() ? GROUP_ROOT : (BuildingJsonDocument.isAnyGroup(group) ? GROUP_ANY : GROUP_ALL);
        JsonArray array = this.inputArray(group, parentKey, true);
        if (array == null) {
            return null;
        }
        JsonObject child = new JsonObject();
        child.add(any ? GROUP_ANY : GROUP_ALL, (JsonElement)new JsonArray());
        array.add((JsonElement)child);
        int index = array.size() - 1;
        return parent == null || parent.isEmpty() ? InputPath.root(index) : parent.down(parentKey, index);
    }

    public boolean removeInput(InputOwner owner, InputPath path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        JsonObject target = this.ownerObject(owner);
        if (target == null) {
            return false;
        }
        JsonObject current = target;
        int last = path.refs().size() - 1;
        for (int i = 0; i < last; ++i) {
            InputRef ref = path.refs().get(i);
            JsonArray array = this.inputArray(current, ref.arrayKey(), false);
            if (array == null || ref.index() < 0 || ref.index() >= array.size()) {
                return false;
            }
            current = array.get(ref.index()).getAsJsonObject();
        }
        InputRef leaf = path.refs().get(last);
        JsonArray array = this.inputArray(current, leaf.arrayKey(), false);
        if (array == null || leaf.index() < 0 || leaf.index() >= array.size()) {
            return false;
        }
        array.remove(leaf.index());
        return true;
    }

    public boolean setInputGroupAny(InputOwner owner, InputPath path, boolean any) {
        JsonObject node = this.inputNode(owner, path);
        if (node == null || !BuildingJsonDocument.isGroup(node)) {
            return false;
        }
        String current = BuildingJsonDocument.isAnyGroup(node) ? GROUP_ANY : GROUP_ALL;
        JsonArray children = node.getAsJsonArray(BuildingJsonDocument.groupKey(node, current));
        node.remove(GROUP_ALL);
        node.remove(GROUP_ANY);
        for (String alias : ANY_ALIASES) {
            node.remove(alias);
        }
        for (String alias : ALL_ALIASES) {
            node.remove(alias);
        }
        node.add(any ? GROUP_ANY : GROUP_ALL, (JsonElement)children);
        return true;
    }

    public JsonArray outputArray(InputOwner owner, boolean create) {
        JsonObject target = this.ownerObject(owner);
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
        target.add("outputs", (JsonElement)array);
        return array;
    }

    public int addOutput(InputOwner owner) {
        JsonArray array = this.outputArray(owner, true);
        if (array == null) {
            return -1;
        }
        JsonObject output = new JsonObject();
        output.addProperty("item", "minecraft:stone");
        output.addProperty("baseAmount", (Number)1);
        array.add((JsonElement)output);
        return array.size() - 1;
    }

    public boolean removeOutput(InputOwner owner, int index) {
        JsonArray array = this.outputArray(owner, false);
        if (array == null || index < 0 || index >= array.size()) {
            return false;
        }
        array.remove(index);
        return true;
    }

    public int expandedStepCount(String recipeId) {
        return this.expandedStepCount(this.steps(recipeId), new LinkedHashSet<JsonObject>());
    }

    private int expandedStepCount(List<JsonObject> chain, Set<JsonObject> guard) {
        int total = 0;
        for (JsonObject step : chain) {
            if (!guard.add(step)) continue;
            IndustrialStepType type = IndustrialStepType.byRawType(BuildingJsonDocument.string(step, "type"));
            if (type != null && type.isRepeat()) {
                int repetitions = BuildingJsonDocument.repeatCount(step);
                int childCount = this.expandedStepCount(this.childrenOf(step), guard);
                total += repetitions <= 0 ? 0 : (int)Math.min(256L, (long)repetitions * (long)childCount);
            } else {
                ++total;
            }
            guard.remove(step);
            if (total < 256) continue;
            return 256;
        }
        return total;
    }

    public static int repeatCount(JsonObject repeat) {
        int positions = BuildingJsonDocument.positionList(repeat).size();
        if (positions > 0) {
            return positions;
        }
        return Math.max(0, (int)BuildingJsonDocument.readNumber(repeat, IndustrialCommonFields.COUNT));
    }

    public List<JsonObject> childrenOf(JsonObject repeat) {
        ArrayList<JsonObject> result = new ArrayList<JsonObject>();
        if (repeat == null || !repeat.has("steps") || !repeat.get("steps").isJsonArray()) {
            return result;
        }
        for (JsonElement element : repeat.getAsJsonArray("steps")) {
            if (element == null || !element.isJsonObject()) continue;
            result.add(element.getAsJsonObject());
        }
        return result;
    }

    public static List<JsonArray> positionList(JsonObject owner) {
        ArrayList<JsonArray> result = new ArrayList<JsonArray>();
        if (owner == null) {
            return result;
        }
        if (owner.has("positions") && owner.get("positions").isJsonArray()) {
            for (JsonElement element : owner.getAsJsonArray("positions")) {
                if (element == null || !element.isJsonArray()) continue;
                result.add(element.getAsJsonArray());
            }
        } else if (owner.has("pos") && owner.get("pos").isJsonArray()) {
            result.add(owner.getAsJsonArray("pos"));
        }
        return result;
    }

    public List<DefinitionIssue> validate(StructureBounds bounds) {
        ArrayList<DefinitionIssue> issues = new ArrayList<DefinitionIssue>();
        BuildingJsonDocument.validateShapes((JsonElement)this.root, GROUP_ROOT, issues, 0);
        if (!issues.isEmpty()) {
            return issues;
        }
        StructureBounds effective = bounds == null ? StructureBounds.UNKNOWN : bounds;
        Set<String> pointKeys = BuildingJsonDocument.objectKeys(this.root.get("points"));
        Set<String> containerKeys = BuildingJsonDocument.objectKeys(this.root.get("containers"));
        this.validateDefinitions(issues, effective);
        if (!this.root.has("recipes") || !this.root.get("recipes").isJsonArray() || this.root.getAsJsonArray("recipes").isEmpty()) {
            issues.add(DefinitionIssue.error("missing_recipes", "recipes", GROUP_ROOT));
            return issues;
        }
        List<JsonObject> recipes = this.recipes();
        if (recipes.size() > 64) {
            issues.add(DefinitionIssue.warning("too_many_recipes", "recipes", recipes.size() + " > 64"));
        }
        LinkedHashSet<String> recipeIds = new LinkedHashSet<String>();
        for (int i = 0; i < recipes.size(); ++i) {
            JsonObject recipe = recipes.get(i);
            String recipeId = BuildingJsonDocument.recipeId(recipe, i);
            String where = "recipes[" + i + "]";
            if (!recipeIds.add(recipeId)) {
                issues.add(DefinitionIssue.error("duplicate_recipe_id", where, recipeId));
            }
            this.validateSteps(recipe, "steps", recipeId, where + ".steps", pointKeys, containerKeys, effective, issues, 0);
            this.validateInputs(BuildingJsonDocument.arrayOrNull(recipe, "inputs"), where + ".inputs", issues, 0);
            this.validateOutputs(BuildingJsonDocument.arrayOrNull(recipe, "outputs"), where + ".outputs", issues);
            int expanded = this.expandedStepCount(recipeId);
            if (expanded >= 256) {
                issues.add(DefinitionIssue.error("steps_exceeded", where + ".steps", Integer.toString(expanded)));
            }
            ArrayList<JsonObject> top = new ArrayList<JsonObject>();
            if (recipe.has("steps") && recipe.get("steps").isJsonArray()) {
                for (JsonElement element : recipe.getAsJsonArray("steps")) {
                    if (!element.isJsonObject()) continue;
                    top.add(element.getAsJsonObject());
                }
            }
            if (!top.isEmpty()) continue;
            issues.add(DefinitionIssue.warning("empty_steps", where + ".steps", GROUP_ROOT));
        }
        return issues;
    }

    private void validateSteps(JsonObject ownerChain, String arrayKey, String recipeId, String location, Set<String> pointKeys, Set<String> containerKeys, StructureBounds bounds, List<DefinitionIssue> issues, int depth) {
        if (!ownerChain.has(arrayKey) || !ownerChain.get(arrayKey).isJsonArray()) {
            return;
        }
        JsonArray array = ownerChain.getAsJsonArray(arrayKey);
        for (int i = 0; i < array.size(); ++i) {
            JsonElement element = array.get(i);
            String where = location + "[" + i + "]";
            if (element == null || !element.isJsonObject()) {
                issues.add(DefinitionIssue.error("invalid_step", where, GROUP_ROOT));
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            this.validateStep(step, where, recipeId, pointKeys, containerKeys, bounds, issues, depth);
        }
    }

    private void validateStep(JsonObject step, String where, String recipeId, Set<String> pointKeys, Set<String> containerKeys, StructureBounds bounds, List<DefinitionIssue> issues, int depth) {
        DefinitionField skip;
        String rawType = BuildingJsonDocument.string(step, "type");
        if (rawType.isBlank()) {
            issues.add(DefinitionIssue.error("missing_type", where, GROUP_ROOT));
            return;
        }
        IndustrialStepType type = IndustrialStepType.byRawType(rawType);
        if (type == null) {
            issues.add(DefinitionIssue.error("unknown_type", where, rawType));
            return;
        }
        if (!type.canonical().equals(rawType)) {
            issues.add(new DefinitionIssue(DefinitionIssue.Severity.INFO, "alias_type", where, rawType + " \u2192 " + type.canonical()));
        }
        Set<String> legal = type.acceptedKeys();
        for (String key : step.keySet()) {
            if (!legal.contains(key)) {
                issues.add(DefinitionIssue.warning("unexpected_key", where, key));
                continue;
            }
            DefinitionField field = type.field(key);
            if (field == null || !field.aliases().contains(key)) continue;
            issues.add(new DefinitionIssue(DefinitionIssue.Severity.INFO, "alias_key", where, key + " \u2192 " + field.key()));
        }
        for (DefinitionField field : type.fields()) {
            if (!field.numeric() || !BuildingJsonDocument.hasField(step, field)) continue;
            String key = BuildingJsonDocument.effectiveKey(step, field);
            JsonElement value = step.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                issues.add(DefinitionIssue.error("invalid_number", where + "." + key, BuildingJsonDocument.string(step, key)));
                continue;
            }
            double number = value.getAsDouble();
            if (!Double.isFinite(number)) {
                issues.add(DefinitionIssue.error("invalid_number", where + "." + key, value.toString()));
                continue;
            }
            if (field.kind() == DefinitionField.Kind.INTEGER && number != Math.rint(number)) {
                issues.add(DefinitionIssue.error("not_integer", where + "." + key, Double.toString(number)));
            }
            if (!(number < field.min()) && (!field.hasMax() || !(number > field.max()))) continue;
            issues.add(DefinitionIssue.error("out_of_range", where + "." + key, BuildingJsonDocument.trim(number) + " \u2209 [" + BuildingJsonDocument.trim(field.min()) + ", " + (field.hasMax() ? BuildingJsonDocument.trim(field.max()) : "\u221e") + "]"));
        }
        for (DefinitionField field : type.fields()) {
            String container;
            if (field.kind() == DefinitionField.Kind.POINT_REF && BuildingJsonDocument.hasField(step, field)) {
                String point = BuildingJsonDocument.readString(step, field);
                if (point.isBlank() || pointKeys.contains(point)) continue;
                issues.add(DefinitionIssue.error("unknown_point", where + "." + BuildingJsonDocument.effectiveKey(step, field), point));
                continue;
            }
            if (field.kind() != DefinitionField.Kind.CONTAINER_REF || !BuildingJsonDocument.hasField(step, field) || (container = BuildingJsonDocument.readString(step, field)).isBlank() || containerKeys.contains(container)) continue;
            issues.add(DefinitionIssue.error("unknown_container", where + "." + BuildingJsonDocument.effectiveKey(step, field), container));
        }
        List<JsonArray> positions = BuildingJsonDocument.positionList(step);
        if (positions.size() > 64) {
            issues.add(DefinitionIssue.error("too_many_positions", where + ".positions", positions.size() + " > 64"));
        }
        if (bounds.known()) {
            for (int i = 0; i < positions.size(); ++i) {
                JsonArray position = positions.get(i);
                if (position.size() < 3) {
                    issues.add(DefinitionIssue.error("invalid_position", where + ".positions[" + i + "]", position.toString()));
                    continue;
                }
                int x = position.get(0).getAsInt();
                int y = position.get(1).getAsInt();
                int z = position.get(2).getAsInt();
                if (x >= 0 && y >= 0 && z >= 0 && x < bounds.sizeX() && y < bounds.sizeY() && z < bounds.sizeZ()) continue;
                issues.add(DefinitionIssue.warning("position_out_of_bounds", where + ".positions[" + i + "]", x + "," + y + "," + z + " \u2209 " + bounds.sizeX() + "x" + bounds.sizeY() + "x" + bounds.sizeZ()));
            }
        }
        if (type.isRepeat()) {
            if (!step.has("steps") || !step.get("steps").isJsonArray() || step.getAsJsonArray("steps").isEmpty()) {
                issues.add(DefinitionIssue.error("missing_repeat_steps", where, GROUP_ROOT));
            } else {
                this.validateSteps(step, "steps", recipeId, where + ".steps", pointKeys, containerKeys, bounds, issues, depth + 1);
            }
            if (BuildingJsonDocument.repeatCount(step) <= 0) {
                issues.add(DefinitionIssue.error("missing_repeat_count", where, GROUP_ROOT));
            }
            if (depth > 8) {
                issues.add(DefinitionIssue.warning("repeat_too_deep", where, Integer.toString(depth)));
            }
        }
        if ((skip = type.field("skipOnTimeout")) != null && BuildingJsonDocument.hasField(step, skip) && BuildingJsonDocument.readFlag(step, skip) && type.field("timeoutTicks") != null && !BuildingJsonDocument.hasField(step, type.field("timeoutTicks"))) {
            issues.add(DefinitionIssue.warning("skip_timeout_default", where, type.field("timeoutTicks").fallback()));
        }
        this.validateInputs(BuildingJsonDocument.arrayOrNull(step, "inputs"), where + ".inputs", issues, 0);
        this.validateOutputs(BuildingJsonDocument.arrayOrNull(step, "outputs"), where + ".outputs", issues);
        if (type.field("inputs") != null && step.has("inputs") && step.get("inputs").isJsonArray() && step.getAsJsonArray("inputs").isEmpty()) {
            issues.add(DefinitionIssue.warning("empty_inputs_override", where + ".inputs", GROUP_ROOT));
        }
        if (type.field("outputs") != null && step.has("outputs") && step.get("outputs").isJsonArray() && step.getAsJsonArray("outputs").isEmpty()) {
            issues.add(DefinitionIssue.warning("empty_outputs_override", where + ".outputs", GROUP_ROOT));
        }
    }

    private void validateInputs(JsonArray array, String location, List<DefinitionIssue> issues, int depth) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); ++i) {
            JsonElement element = array.get(i);
            String where = location + "[" + i + "]";
            if (element == null || !element.isJsonObject()) {
                issues.add(DefinitionIssue.error("invalid_input", where, GROUP_ROOT));
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            if (BuildingJsonDocument.isGroup(node)) {
                String childKey;
                if (depth > 8) {
                    issues.add(DefinitionIssue.warning("input_group_too_deep", where, Integer.toString(depth)));
                    continue;
                }
                String string = childKey = BuildingJsonDocument.isAnyGroup(node) ? BuildingJsonDocument.groupKey(node, GROUP_ANY) : BuildingJsonDocument.groupKey(node, GROUP_ALL);
                if (childKey == null) {
                    issues.add(DefinitionIssue.error("empty_input_group", where, GROUP_ROOT));
                    continue;
                }
                this.validateInputs(node.getAsJsonArray(childKey), where + "." + childKey, issues, depth + 1);
                continue;
            }
            String item = BuildingJsonDocument.itemIdOf(node);
            if (item.isBlank()) {
                issues.add(DefinitionIssue.error("empty_input_item", where, GROUP_ROOT));
            }
            if (!node.has("count") || node.get("count").getAsInt() >= 1) continue;
            issues.add(DefinitionIssue.error("invalid_count", where + ".count", node.get("count").getAsString()));
        }
    }

    private void validateDefinitions(List<DefinitionIssue> issues, StructureBounds bounds) {
        this.validateDefinitionMap(issues, bounds, "points", true);
        this.validateDefinitionMap(issues, bounds, "containers", false);
    }

    private void validateDefinitionMap(List<DefinitionIssue> issues, StructureBounds bounds, String mapKey, boolean isPoint) {
        JsonElement element = this.root.get(mapKey);
        if (element == null || !element.isJsonObject()) {
            return;
        }
        for (Map.Entry entry : element.getAsJsonObject().entrySet()) {
            String select;
            List<JsonArray> positions;
            String type;
            String where = mapKey + "." + (String)entry.getKey();
            if (entry.getValue() == null || !((JsonElement)entry.getValue()).isJsonObject()) {
                issues.add(DefinitionIssue.error("invalid_definition", where, "\u4e0d\u662f\u5bf9\u8c61"));
                continue;
            }
            JsonObject definition = ((JsonElement)entry.getValue()).getAsJsonObject();
            String string = type = definition.has("type") ? BuildingJsonDocument.string(definition, "type") : "structure_pos";
            if (!"structure_pos".equalsIgnoreCase(type)) {
                issues.add(DefinitionIssue.error(isPoint ? "point_bad_type" : "container_bad_type", where + ".type", type));
            }
            if ((positions = BuildingJsonDocument.positionList(definition)).isEmpty()) {
                issues.add(DefinitionIssue.error("definition_missing_positions", where + ".positions", GROUP_ROOT));
                continue;
            }
            if (positions.size() > 64) {
                issues.add(DefinitionIssue.error("too_many_positions", where + ".positions", positions.size() + " > 64"));
            }
            if (bounds.known()) {
                for (int i = 0; i < positions.size(); ++i) {
                    JsonArray position = positions.get(i);
                    if (position.size() < 3) {
                        issues.add(DefinitionIssue.error("invalid_position", where + ".positions[" + i + "]", position.toString()));
                        continue;
                    }
                    int x = position.get(0).getAsInt();
                    int y = position.get(1).getAsInt();
                    int z = position.get(2).getAsInt();
                    if (x >= 0 && y >= 0 && z >= 0 && x < bounds.sizeX() && y < bounds.sizeY() && z < bounds.sizeZ()) continue;
                    issues.add(DefinitionIssue.warning("position_out_of_bounds", where + ".positions[" + i + "]", x + "," + y + "," + z + " \u2209 " + bounds.sizeX() + "x" + bounds.sizeY() + "x" + bounds.sizeZ()));
                }
            }
            if (!isPoint || !definition.has("select") || "nearest".equalsIgnoreCase(select = BuildingJsonDocument.string(definition, "select")) || "ordered".equalsIgnoreCase(select)) continue;
            issues.add(DefinitionIssue.warning("point_bad_select", where + ".select", select));
        }
    }

    private static void validateShapes(JsonElement value, String where, List<DefinitionIssue> issues, int depth) {
        block9: {
            block8: {
                if (depth > 64) {
                    issues.add(DefinitionIssue.error("invalid_definition", where, "nesting > 64"));
                    return;
                }
                if (!value.isJsonArray()) break block8;
                for (int i = 0; i < value.getAsJsonArray().size(); ++i) {
                    BuildingJsonDocument.validateShapes(value.getAsJsonArray().get(i), where + "[" + i + "]", issues, depth + 1);
                }
                break block9;
            }
            if (!value.isJsonObject()) break block9;
            for (Map.Entry entry : value.getAsJsonObject().entrySet()) {
                boolean decimal;
                String key = (String)entry.getKey();
                String location = where.isEmpty() ? key : where + "." + key;
                JsonElement child = (JsonElement)entry.getValue();
                if (Set.of("count", "amount", "baseAmount", "randomRange", "probability").contains(key) && !BuildingJsonDocument.validNumber(child, decimal = "probability".equals(key))) {
                    issues.add(DefinitionIssue.error("invalid_number", location, child.toString()));
                }
                if ("pos".equals(key)) {
                    if (BuildingJsonDocument.validPosition(child)) continue;
                    issues.add(DefinitionIssue.error("invalid_position", location, child.toString()));
                    continue;
                }
                if ("positions".equals(key)) {
                    if (!child.isJsonArray()) {
                        issues.add(DefinitionIssue.error("invalid_position", location, child.toString()));
                        continue;
                    }
                    for (int i = 0; i < child.getAsJsonArray().size(); ++i) {
                        JsonElement point = child.getAsJsonArray().get(i);
                        if (BuildingJsonDocument.validPosition(point)) continue;
                        issues.add(DefinitionIssue.error("invalid_position", location + "[" + i + "]", point.toString()));
                    }
                    continue;
                }
                if (!BuildingJsonDocument.knownShapeBranch(where, key)) continue;
                BuildingJsonDocument.validateShapes(child, location, issues, depth + 1);
            }
        }
    }

    private static boolean knownShapeBranch(String where, String key) {
        if (where.isEmpty()) {
            return Set.of("points", "containers", "recipes").contains(key);
        }
        if (where.equals("points") || where.equals("containers")) {
            return true;
        }
        if (where.startsWith("points.") || where.startsWith("containers.")) {
            return false;
        }
        return Set.of("steps", "inputs", "outputs", GROUP_ALL, GROUP_ANY, "and", "or", "allOf", "anyOf", "\u4e0e", "\u6216").contains(key);
    }

    private static boolean validNumber(JsonElement value, boolean decimal) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        double number = value.getAsDouble();
        return Double.isFinite(number) && (decimal || number == Math.rint(number) && number >= -2.147483648E9 && number <= 2.147483647E9);
    }

    public static boolean validPosition(JsonElement value) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            return false;
        }
        for (JsonElement coordinate : value.getAsJsonArray()) {
            if (BuildingJsonDocument.validNumber(coordinate, false)) continue;
            return false;
        }
        return true;
    }

    private static String itemIdOf(JsonObject node) {
        return BuildingJsonDocument.firstNonBlank(BuildingJsonDocument.string(node, "item"), BuildingJsonDocument.stringAny(node, "tag", "itemTag", "item_tag"), BuildingJsonDocument.string(node, "itemStack"), BuildingJsonDocument.string(node, "itemString"), BuildingJsonDocument.string(node, "stack"));
    }

    private void validateOutputs(JsonArray array, String location, List<DefinitionIssue> issues) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); ++i) {
            double probability;
            int range;
            int amount;
            JsonElement element = array.get(i);
            String where = location + "[" + i + "]";
            if (element == null || !element.isJsonObject()) {
                issues.add(DefinitionIssue.error("invalid_output", where, GROUP_ROOT));
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            String item = BuildingJsonDocument.itemIdOf(node);
            if (item.isBlank()) {
                issues.add(DefinitionIssue.error("empty_output_item", where, GROUP_ROOT));
            }
            if (node.has("baseAmount") && (amount = node.get("baseAmount").getAsInt()) < 1) {
                issues.add(DefinitionIssue.error("invalid_amount", where + ".baseAmount", Integer.toString(amount)));
            }
            if (node.has("randomRange") && (range = node.get("randomRange").getAsInt()) < 0) {
                issues.add(DefinitionIssue.error("invalid_random_range", where + ".randomRange", Integer.toString(range)));
            }
            if (!node.has("probability") || !((probability = node.get("probability").getAsDouble()) < 0.0) && !(probability > 1.0)) continue;
            issues.add(DefinitionIssue.error("invalid_probability", where + ".probability", BuildingJsonDocument.trim(probability)));
        }
    }

    public static String readItemId(JsonObject step) {
        if (step == null) {
            return GROUP_ROOT;
        }
        String item = BuildingJsonDocument.string(step, "item");
        if (!item.isBlank()) {
            return item;
        }
        String tag = BuildingJsonDocument.stringAny(step, "tag", "itemTag", "item_tag");
        if (!tag.isBlank()) {
            return "#" + tag;
        }
        String stack = BuildingJsonDocument.stringAny(step, "itemStack", "itemString", "stack");
        if (!stack.isBlank()) {
            int componentStart = stack.indexOf(91);
            return componentStart >= 0 ? stack.substring(0, componentStart) : stack;
        }
        return GROUP_ROOT;
    }

    public static boolean hasItemSpec(JsonObject step) {
        return !BuildingJsonDocument.readItemId(step).isBlank();
    }

    public static String summary(JsonObject step) {
        String itemId;
        IndustrialStepType type = IndustrialStepType.byRawType(BuildingJsonDocument.string(step, "type"));
        if (type == null) {
            return BuildingJsonDocument.string(step, "type");
        }
        if (type.isRepeat()) {
            int repetitions = BuildingJsonDocument.repeatCount(step);
            boolean byPositions = !BuildingJsonDocument.positionList(step).isEmpty();
            return (byPositions ? "positions \u00d7" : "count \u00d7") + repetitions;
        }
        ArrayList<String> parts = new ArrayList<String>();
        DefinitionField container = type.field("container");
        if (container != null && BuildingJsonDocument.hasField(step, container)) {
            parts.add(BuildingJsonDocument.readString(step, container));
        } else if (type.field("output") != null && BuildingJsonDocument.hasField(step, type.field("output"))) {
            parts.add(BuildingJsonDocument.readString(step, type.field("output")));
        }
        DefinitionField point = type.field("point");
        if (point != null && BuildingJsonDocument.hasField(step, point)) {
            parts.add(BuildingJsonDocument.readString(step, point));
        }
        if (!(itemId = BuildingJsonDocument.readItemId(step)).isBlank()) {
            parts.add(itemId);
        } else if (type.field("block") != null && BuildingJsonDocument.hasField(step, type.field("block"))) {
            parts.add(BuildingJsonDocument.readString(step, type.field("block")));
        } else if (type.field("fluid") != null && BuildingJsonDocument.hasField(step, type.field("fluid"))) {
            parts.add(BuildingJsonDocument.readString(step, type.field("fluid")));
        }
        DefinitionField ticks = type.field("ticks");
        if (ticks != null && BuildingJsonDocument.hasField(step, ticks)) {
            parts.add(BuildingJsonDocument.trim(BuildingJsonDocument.readNumber(step, ticks)) + "t");
        }
        if (parts.isEmpty()) {
            return GROUP_ROOT;
        }
        return String.join((CharSequence)" \u00b7 ", parts);
    }

    public static boolean isItemSpecKey(String key) {
        return IndustrialStepType.ITEM_SPEC_KEYS.contains(key) || IndustrialStepType.ITEM_LIST_KEYS.contains(key);
    }

    private static JsonArray arrayOrNull(JsonObject owner, String key) {
        if (owner == null || !owner.has(key) || !owner.get(key).isJsonArray()) {
            return null;
        }
        return owner.getAsJsonArray(key);
    }

    private static Set<String> objectKeys(JsonElement element) {
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        if (element != null && element.isJsonObject()) {
            keys.addAll(element.getAsJsonObject().keySet());
        }
        return keys;
    }

    private static String recipeId(JsonObject recipe, int index) {
        String id = BuildingJsonDocument.string(recipe, "id");
        return id.isBlank() ? "recipe_" + index : id;
    }

    private static String uniqueId(String base, Set<String> used) {
        if (!used.contains(base)) {
            return base;
        }
        int index = 2;
        while (used.contains(base + "_" + index)) {
            ++index;
        }
        return base + "_" + index;
    }

    private static void arrayInsert(JsonArray array, int index, JsonElement element) {
        int at = Math.max(0, Math.min(index, array.size()));
        ArrayList<JsonElement> tail = new ArrayList<JsonElement>();
        for (int i = at; i < array.size(); ++i) {
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
            return GROUP_ROOT;
        }
        try {
            return object.get(key).getAsString();
        }
        catch (RuntimeException exception) {
            return GROUP_ROOT;
        }
    }

    private static String stringAny(JsonObject object, String ... keys) {
        for (String key : keys) {
            String value = BuildingJsonDocument.string(object, key);
            if (value.isBlank()) continue;
            return value;
        }
        return GROUP_ROOT;
    }

    private static String firstNonBlank(String ... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            return value;
        }
        return GROUP_ROOT;
    }

    private static double parseNumber(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        }
        catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long)value) : Double.toString(value);
    }

    public static enum DefinitionKind {
        POINT("points"),
        CONTAINER("containers");

        private final String key;

        private DefinitionKind(String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }
    }

    private static interface ReferencingFieldVisitor {
        public void accept(String var1, StepPath var2, JsonObject var3, DefinitionField var4);
    }

    public record Reference(String recipeId, StepPath path, String fieldKey) {
    }

    public record StepPath(List<Integer> indices) {
        public StepPath {
            indices = List.copyOf(indices);
        }

        public static StepPath of(int ... values) {
            ArrayList<Integer> list = new ArrayList<Integer>(values.length);
            for (int value : values) {
                list.add(value);
            }
            return new StepPath(list);
        }

        public static StepPath root(int index) {
            return new StepPath(List.of(Integer.valueOf(index)));
        }

        public StepPath down(int index) {
            ArrayList<Integer> next = new ArrayList<Integer>(this.indices);
            next.add(index);
            return new StepPath(next);
        }

        public StepPath up() {
            if (this.indices.isEmpty()) {
                return null;
            }
            return new StepPath(this.indices.subList(0, this.indices.size() - 1));
        }

        public boolean topLevel() {
            return this.indices.size() == 1;
        }

        public int depth() {
            return this.indices.size();
        }

        public int leaf() {
            return this.indices.isEmpty() ? -1 : this.indices.get(this.indices.size() - 1);
        }

        public StepPath withLeaf(int index) {
            ArrayList<Integer> next = new ArrayList<Integer>(this.indices);
            if (next.isEmpty()) {
                next.add(index);
            } else {
                next.set(next.size() - 1, index);
            }
            return new StepPath(next);
        }

        public boolean isEmpty() {
            return this.indices.isEmpty();
        }

        public String display() {
            StringBuilder builder = new StringBuilder();
            for (int index : this.indices) {
                if (builder.length() > 0) {
                    builder.append('.');
                }
                builder.append(index);
            }
            return builder.toString();
        }

        @Override
        public String toString() {
            return this.display();
        }
    }

    public record DeleteResult(boolean removed, List<Reference> references) {
    }

    public record InputOwner(String recipeId, StepPath stepPath) {
        public static InputOwner ofRecipe(String recipeId) {
            return new InputOwner(recipeId, null);
        }

        public static InputOwner ofStep(String recipeId, StepPath stepPath) {
            return new InputOwner(recipeId, stepPath);
        }
    }

    public record InputPath(List<InputRef> refs) {
        public InputPath {
            refs = List.copyOf(refs);
        }

        public static InputPath root(int index) {
            return new InputPath(List.of(new InputRef(BuildingJsonDocument.GROUP_ROOT, index)));
        }

        public InputPath down(String groupKey, int index) {
            ArrayList<InputRef> next = new ArrayList<InputRef>(this.refs);
            next.add(new InputRef(groupKey, index));
            return new InputPath(next);
        }

        public boolean isEmpty() {
            return this.refs.isEmpty();
        }
    }

    public record InputRef(String arrayKey, int index) {
    }

    public record StructureBounds(int sizeX, int sizeY, int sizeZ) {
        public static final StructureBounds UNKNOWN = new StructureBounds(0, 0, 0);

        public boolean known() {
            return this.sizeX > 0 && this.sizeY > 0 && this.sizeZ > 0;
        }
    }
}

