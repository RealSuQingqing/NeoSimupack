/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.creation.industrial;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rstech.simupack.creation.industrial.IndustrialCommonFields;
import com.rstech.simupack.creation.industrial.IndustrialStepCategory;
import com.rstech.simupack.creation.json.DefinitionField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public enum IndustrialStepType {
    MOVE_TO("move_to", IndustrialStepCategory.MOVE, List.of(), List.of(DefinitionField.pointRef(), IndustrialStepType.range(1.5))),
    MOVE_TO_CONTAINER("move_to_container", IndustrialStepCategory.MOVE, List.of("move_to_chest"), List.of(DefinitionField.containerRef("container", "input", "output"), IndustrialStepType.range(1.5))),
    MOVE_TO_ENTITY("move_to_entity", IndustrialStepCategory.MOVE, List.of(), List.of(DefinitionField.pointRef(), IndustrialStepType.radius(6.0), IndustrialStepType.range(1.5), DefinitionField.text("entityType", "", new String[0]))),
    LOOK_AT("look_at", IndustrialStepCategory.MOVE, List.of(), List.of(DefinitionField.pointRef())),
    LOOK_AT_CONTAINER("look_at_container", IndustrialStepCategory.MOVE, List.of("look_at_chest"), List.of(DefinitionField.containerRef("container", "input", "output"))),
    SET_HELD_ITEM("set_held_item", IndustrialStepCategory.HOLD, List.of(), List.of(DefinitionField.item(new String[0]))),
    USE_ITEM("use_item", IndustrialStepCategory.HOLD, List.of(), List.of(DefinitionField.integer("ticks", 1, 1, Integer.MAX_VALUE, new String[0]), DefinitionField.flag("swing", false, new String[0]))),
    SET_STATUS("set_status", IndustrialStepCategory.HOLD, List.of(), List.of(DefinitionField.text("statusKey", "", new String[0]), DefinitionField.text("statusText", "", new String[0]))),
    REQUIRE_INPUTS("require_inputs", IndustrialStepCategory.MATERIAL, List.of(), List.of(DefinitionField.containerRef("container", "input"))),
    REQUIRE_OUTPUT_SPACE("require_output_space", IndustrialStepCategory.MATERIAL, List.of(), List.of(DefinitionField.containerRef("container", "output"))),
    CRAFT_RECIPE("craft_recipe", IndustrialStepCategory.MATERIAL, List.of(), List.of(DefinitionField.containerRef("input", "container"), DefinitionField.containerRef("output", "container"), DefinitionField.inputs(), DefinitionField.outputs())),
    CRAFT_AVAILABLE_RECIPE("craft_available_recipe", IndustrialStepCategory.MATERIAL, List.of("craft_all_recipe"), List.of(DefinitionField.containerRef("input", "container"), DefinitionField.containerRef("output", "container"), DefinitionField.inputs(), DefinitionField.outputs())),
    REAL_MACHINE_RECIPE("real_machine_recipe", IndustrialStepCategory.MATERIAL, List.of(), List.of(DefinitionField.pointRef(), DefinitionField.containerRef("input", "container"), DefinitionField.containerRef("output", "container"), DefinitionField.choice("outputPolicy", "extract_to_output", "extract_to_output", "keep_in_machine"), DefinitionField.integer("timeoutTicks", 12000, 1, Integer.MAX_VALUE, "timeout"), DefinitionField.integer("pollTicks", 20, 1, Integer.MAX_VALUE, "poll"), DefinitionField.flag("swing", false, new String[0]), DefinitionField.inputs(), DefinitionField.outputs())),
    INSPECT_CONTAINER("inspect_container", IndustrialStepCategory.CONTAINER, List.of("open_container"), List.of(DefinitionField.containerRef("container", "input"), DefinitionField.integer("ticks", 1, 1, Integer.MAX_VALUE, new String[0]))),
    INSERT_ITEM("insert_item", IndustrialStepCategory.CONTAINER, List.of("store_item", "put_item"), List.of(DefinitionField.containerRef("container", "output"), DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0]), DefinitionField.integer("ticks", 1, 1, Integer.MAX_VALUE, new String[0]), DefinitionField.item(new String[0]))),
    FILL_ITEM("fill_item", IndustrialStepCategory.CONTAINER, List.of("fill_slot", "refill_item", "refill_slot"), List.of(DefinitionField.pointRef(), DefinitionField.containerRef("input", "container"), DefinitionField.integer("slot", -1, -1, Integer.MAX_VALUE, new String[0]), DefinitionField.integer("targetCount", 0, 0, Integer.MAX_VALUE, "target", "fillTo"), DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0]), DefinitionField.integer("thresholdCount", -1, -1, Integer.MAX_VALUE, "threshold"), DefinitionField.flag("swing", false, new String[0]), DefinitionField.itemList(new String[0]), DefinitionField.inputs(), DefinitionField.item(new String[0]))),
    DEPOSIT_CARRIED_ITEMS("deposit_carried_items", IndustrialStepCategory.CONTAINER, List.of("store_carried_items", "put_carried_items"), List.of(DefinitionField.containerRef("container", "output"), DefinitionField.itemList(new String[0]), DefinitionField.integer("ticks", 1, 1, Integer.MAX_VALUE, new String[0]))),
    PLACE_BLOCK("place_block", IndustrialStepCategory.BLOCK, List.of("set_block"), List.of(DefinitionField.text("block", "", new String[0]), DefinitionField.item(new String[0]), DefinitionField.positions(), DefinitionField.pointRef(), DefinitionField.flag("replace", false, new String[0]), DefinitionField.flag("consume", false, new String[0]), DefinitionField.containerRef("container", "input"), DefinitionField.flag("swing", false, new String[0]))),
    PLACE_FLUID("place_fluid", IndustrialStepCategory.BLOCK, List.of("place_liquid"), List.of(DefinitionField.text("fluid", "", "liquid"), DefinitionField.item(new String[0]), DefinitionField.positions(), DefinitionField.pointRef(), DefinitionField.flag("replace", false, new String[0]), DefinitionField.flag("consume", false, new String[0]), DefinitionField.containerRef("container", "input"), DefinitionField.flag("swing", false, new String[0]))),
    DESTROY_BLOCK("destroy_block", IndustrialStepCategory.BLOCK, List.of("break_block", "remove_block"), List.of(DefinitionField.positions(), DefinitionField.pointRef(), DefinitionField.flag("dropItems", false, "drop"), DefinitionField.flag("swing", false, new String[0]), DefinitionField.containerRef("container", "output"))),
    REQUIRE_BLOCK("require_block", IndustrialStepCategory.BLOCK, List.of("wait_for_block", "find_block", "check_block"), List.of(DefinitionField.text("block", "", new String[0]), DefinitionField.positions(), DefinitionField.pointRef(), DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0]), DefinitionField.text("statusText", "", new String[0]))),
    HARVEST_BLOCK_CLUSTERS("harvest_block_clusters", IndustrialStepCategory.BLOCK, List.of("harvest_blocks"), List.of(DefinitionField.text("targetBlockTag", "", "target_block_tag", "blockTag", "block_tag"), DefinitionField.text("attachedBlockTag", "", "attached_block_tag"), DefinitionField.text("supportBlockTag", "", "support_block_tag"), DefinitionField.integer("minAttachedBlocks", 0, 0, Integer.MAX_VALUE, "min_attached_blocks"), DefinitionField.integer("maxClusterBlocks", 96, 1, Integer.MAX_VALUE, "max_cluster_blocks"), DefinitionField.integer("maxCarryStacks", 18, 1, Integer.MAX_VALUE, "max_carry_stacks"), DefinitionField.integer("ticks", 1, 1, Integer.MAX_VALUE, new String[0]), IndustrialStepType.radius(6.0), DefinitionField.flag("swing", false, new String[0]), DefinitionField.text("plantItemTag", "", "plant_item_tag"), DefinitionField.containerRef("input", "container"), DefinitionField.item(new String[0]))),
    BREED_ENTITIES("breed_entities", IndustrialStepCategory.ENTITY, List.of("breed_animals"), List.of(DefinitionField.text("entityType", "", new String[0]), DefinitionField.pointRef(), IndustrialStepType.radius(6.0), DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0]), DefinitionField.flag("requireFood", true, "requiresFood"), DefinitionField.containerRef("container", "input"))),
    SLAUGHTER_ENTITIES("slaughter_entities", IndustrialStepCategory.ENTITY, List.of("slaughter_animals"), List.of(DefinitionField.text("entityType", "", new String[0]), DefinitionField.pointRef(), IndustrialStepType.radius(6.0), DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0]), DefinitionField.containerRef("container", "output"))),
    SHEAR_ENTITIES("shear_entities", IndustrialStepCategory.ENTITY, List.of("shear_sheep"), List.of(DefinitionField.text("entityType", "", new String[0]), DefinitionField.pointRef(), IndustrialStepType.radius(6.0), DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0]), DefinitionField.integer("ticks", 1, 1, Integer.MAX_VALUE, new String[0]), DefinitionField.containerRef("container", "output"))),
    REQUIRE_DROPS("require_drops", IndustrialStepCategory.ENTITY, List.of("require_drop_items", "has_drops"), List.of(DefinitionField.pointRef(), IndustrialStepType.radius(6.0), DefinitionField.item(new String[0]))),
    COLLECT_DROPS("collect_drops", IndustrialStepCategory.ENTITY, List.of(), List.of(DefinitionField.pointRef(), IndustrialStepType.radius(6.0), DefinitionField.item(new String[0]), DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0]), IndustrialStepType.range(1.5), DefinitionField.integer("maxCarryStacks", 18, 1, Integer.MAX_VALUE, "max_carry_stacks"), DefinitionField.containerRef("container", "output"))),
    REPEAT("repeat", IndustrialStepCategory.FLOW, List.of("loop"), List.of(DefinitionField.steps(), DefinitionField.positions(), DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0])));

    private final String canonical;
    private final IndustrialStepCategory category;
    private final List<String> aliases;
    private final List<DefinitionField> fields;
    public static final Set<String> ITEM_SPEC_KEYS;
    public static final Set<String> ITEM_LIST_KEYS;
    public static final Set<String> POSITION_KEYS;

    private IndustrialStepType(String canonical, IndustrialStepCategory category, List<String> aliases, List<DefinitionField> ownFields) {
        this.canonical = canonical;
        this.category = category;
        this.aliases = List.copyOf(aliases);
        ArrayList<DefinitionField> all = new ArrayList<DefinitionField>(ownFields);
        all.add(IndustrialCommonFields.TIMEOUT);
        all.add(IndustrialCommonFields.SKIP_ON_TIMEOUT);
        this.fields = List.copyOf(all);
    }

    public String canonical() {
        return this.canonical;
    }

    public IndustrialStepCategory category() {
        return this.category;
    }

    public List<String> aliases() {
        return this.aliases;
    }

    public List<DefinitionField> fields() {
        return this.fields;
    }

    public String translationKey() {
        return "gui.simupack.industrial.step." + this.canonical;
    }

    public boolean isRepeat() {
        return this == REPEAT;
    }

    public DefinitionField field(String key) {
        if (key == null) {
            return null;
        }
        for (DefinitionField field : this.fields) {
            if (!field.key().equals(key) && !field.aliases().contains(key)) continue;
            return field;
        }
        for (DefinitionField field : this.fields) {
            if (field.kind() == DefinitionField.Kind.ITEM && ITEM_SPEC_KEYS.contains(key)) {
                return field;
            }
            if (field.kind() != DefinitionField.Kind.ITEM_LIST || !ITEM_LIST_KEYS.contains(key)) continue;
            return field;
        }
        return null;
    }

    public Set<String> acceptedKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        keys.add("type");
        for (DefinitionField field : this.fields) {
            keys.add(field.key());
            keys.addAll(field.aliases());
            switch (field.kind()) {
                case ITEM: {
                    keys.addAll(ITEM_SPEC_KEYS);
                    break;
                }
                case ITEM_LIST: {
                    keys.addAll(ITEM_LIST_KEYS);
                    break;
                }
                case POSITIONS: {
                    keys.addAll(POSITION_KEYS);
                    break;
                }
            }
        }
        return keys;
    }

    public JsonObject newStepJson() {
        JsonObject step = new JsonObject();
        step.addProperty("type", this.canonical);
        if (this == REPEAT) {
            step.add("steps", (JsonElement)new JsonArray());
            step.addProperty("count", (Number)2);
        }
        return step;
    }

    public static IndustrialStepType byRawType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (IndustrialStepType type : IndustrialStepType.values()) {
            if (type.canonical.equals(normalized)) {
                return type;
            }
            for (String alias : type.aliases) {
                if (!alias.equalsIgnoreCase(normalized)) continue;
                return type;
            }
        }
        return null;
    }

    public static List<IndustrialStepType> palette() {
        ArrayList<IndustrialStepType> ordered = new ArrayList<IndustrialStepType>();
        for (IndustrialStepCategory category : IndustrialStepCategory.values()) {
            for (IndustrialStepType type : IndustrialStepType.values()) {
                if (type.category != category) continue;
                ordered.add(type);
            }
        }
        return List.copyOf(ordered);
    }

    public static List<IndustrialStepType> of(IndustrialStepCategory category) {
        ArrayList<IndustrialStepType> result = new ArrayList<IndustrialStepType>();
        for (IndustrialStepType type : IndustrialStepType.values()) {
            if (type.category != category) continue;
            result.add(type);
        }
        return List.copyOf(result);
    }

    private static DefinitionField range(double fallback) {
        return DefinitionField.decimal("range", fallback, 0.1, Double.NaN, new String[0]);
    }

    private static DefinitionField radius(double fallback) {
        return DefinitionField.decimal("radius", fallback, 0.5, Double.NaN, new String[0]);
    }

    static {
        ITEM_SPEC_KEYS = Set.of("item", "tag", "itemTag", "item_tag", "potion", "itemStack", "itemString", "stack", "customData", "nbt", "enchantments", "storedEnchantments");
        ITEM_LIST_KEYS = Set.of("items", "itemSpecs");
        POSITION_KEYS = Set.of("positions", "pos");
    }
}

