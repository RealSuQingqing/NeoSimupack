package com.rstech.simupack.creation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rstech.simupack.creation.residential.CreationUnit;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个正在进行中的拓展包创作工程。
 *
 * <p>工程以文件形式保存在 {@code config/simupack/workspace/<工程目录>/}，
 * 由 {@link CreationWorkspace} 负责读写。工程内容与最终导出的 ZIP 布局一一对应，
 * 因此导出只是打包，不涉及格式转换。
 */
public final class CreationProject {
    /** 工程格式版本，便于后续迁移。 */
    public static final int FORMAT_VERSION = 1;

    private String id = "";
    private String name = "";
    private String version = "1.0.0";
    private String author = "";
    private String description = "";
    private final List<CreationBuilding> buildings = new ArrayList<>();

    public CreationProject() {
    }

    public CreationProject(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String version() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version == null ? "" : version;
    }

    public String author() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author == null ? "" : author;
    }

    public String description() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public List<CreationBuilding> buildings() {
        return buildings;
    }

    public CreationBuilding findBuilding(String category, String buildingId) {
        for (CreationBuilding building : buildings) {
            if (building.category().equals(category) && building.id().equals(buildingId)) {
                return building;
            }
        }
        return null;
    }

    /**
     * 跨分类按 id 找建筑。
     *
     * <p><b>建筑 id 在整个包内必须唯一，不是每个分类各自唯一。</b>
     * 这条约束是硬的：导出时三类建筑的 .sk/.nbt/.json 都写进同一个包，
     * NSUK 也按 id 索引它们 —— 两个分类里各有一个 new_building 就会撞车。
     */
    public CreationBuilding findBuildingById(String buildingId) {
        for (CreationBuilding building : buildings) {
            if (building.id().equals(buildingId)) {
                return building;
            }
        }
        return null;
    }

    /**
     * 生成一个<b>在整个工程内</b>未被占用的建筑 id。
     *
     * <p>刻意不接受分类参数：早期版本按分类查重，于是每个分类新建出来的第一个建筑
     * 都叫 {@code new_building}，切到另一个分类再建一个就重名了（真出过这个 bug）。
     */
    public String nextBuildingId(String base) {
        String candidate = base;
        int index = 2;
        while (findBuildingById(candidate) != null) {
            candidate = base + "_" + index;
            index++;
        }
        return candidate;
    }

    // ── 序列化 ─────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("id", id);
        root.addProperty("name", name);
        root.addProperty("version", version);
        root.addProperty("author", author);
        root.addProperty("description", description);

        JsonArray array = new JsonArray();
        for (CreationBuilding building : buildings) {
            array.add(buildingToJson(building));
        }
        root.add("buildings", array);
        return root;
    }

    private static JsonObject buildingToJson(CreationBuilding building) {
        JsonObject json = new JsonObject();
        json.addProperty("id", building.id());
        json.addProperty("category", building.category());
        json.addProperty("hasStructure", building.hasStructure());

        if (building.hasRegion()) {
            json.add("pos1", toJson(building.regionPos1()));
            json.add("pos2", toJson(building.regionPos2()));
        }
        if (!building.materials().isEmpty()) {
            JsonObject materials = new JsonObject();
            building.materials().forEach(materials::addProperty);
            json.add("materials", materials);
        }

        JsonObject fields = new JsonObject();
        building.skFields().forEach(fields::addProperty);
        json.add("sk", fields);

        if (!building.units().isEmpty()) {
            JsonArray units = new JsonArray();
            for (CreationUnit unit : building.units()) {
                JsonObject item = new JsonObject();
                item.addProperty("id", unit.id());
                item.addProperty("label", unit.label());
                if (unit.hasRange()) {
                    item.add("min", toJson(unit.min()));
                    item.add("max", toJson(unit.max()));
                }
                units.add(item);
            }
            json.add("units", units);
        }

        if (!building.jsonConfig().isBlank()) {
            json.addProperty("jsonConfig", building.jsonConfig());
        }
        return json;
    }

    public static CreationProject fromJson(JsonObject root) {
        CreationProject project = new CreationProject();
        project.id = string(root, "id");
        project.name = string(root, "name");
        project.version = string(root, "version");
        project.author = string(root, "author");
        project.description = string(root, "description");

        if (root.has("buildings") && root.get("buildings").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("buildings")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                project.buildings.add(buildingFromJson(element.getAsJsonObject()));
            }
        }
        return project;
    }

    private static CreationBuilding buildingFromJson(JsonObject json) {
        CreationBuilding building = new CreationBuilding(string(json, "id"), string(json, "category"));
        building.setHasStructure(json.has("hasStructure") && json.get("hasStructure").getAsBoolean());

        if (json.has("sk") && json.get("sk").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("sk").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    building.setSkField(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        building.setJsonConfig(string(json, "jsonConfig"));

        if (json.has("units") && json.get("units").isJsonArray()) {
            List<CreationUnit> units = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("units")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                BlockPos min = blockPosFromJsonObject(item, "min");
                BlockPos max = blockPosFromJsonObject(item, "max");
                units.add(new CreationUnit(string(item, "id"), string(item, "label"), min, max));
            }
            building.setUnits(units);
        } else if (json.has("sk") && json.get("sk").isJsonObject()) {
            JsonElement legacy = json.getAsJsonObject("sk").get("unit");
            if (legacy != null && legacy.isJsonPrimitive()) {
                building.setUnits(com.rstech.simupack.creation.residential.ResidentialUnitCodec.parse(
                        "unit: " + legacy.getAsString()));
            }
        }

        if (json.has("pos1") && json.has("pos2")) {
            var from = blockPosFromJson(json.getAsJsonObject("pos1"));
            var to = blockPosFromJson(json.getAsJsonObject("pos2"));
            if (from != null && to != null) {
                building.setRegion(from, to);
            }
        }
        if (json.has("materials") && json.get("materials").isJsonObject()) {
            Map<String, Integer> materials = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("materials").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    materials.put(entry.getKey(), entry.getValue().getAsInt());
                }
            }
            building.setMaterials(materials);
        }

        return building;
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    private static JsonObject toJson(net.minecraft.core.BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static BlockPos blockPosFromJson(JsonObject json) {
        return json == null ? null : new BlockPos(
                json.has("x") ? json.get("x").getAsInt() : 0,
                json.has("y") ? json.get("y").getAsInt() : 0,
                json.has("z") ? json.get("z").getAsInt() : 0);
    }

    private static BlockPos blockPosFromJsonObject(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject()
                ? blockPosFromJson(parent.getAsJsonObject(key)) : null;
    }
}
