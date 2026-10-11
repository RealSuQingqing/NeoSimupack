/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.creation.json;

import java.util.ArrayList;
import java.util.List;

public record DefinitionField(String key, Kind kind, List<String> aliases, String fallback, double min, double max, List<String> choices) {
    public DefinitionField {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        fallback = fallback == null ? "" : fallback;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public static DefinitionField text(String key, String fallback, String ... aliases) {
        return new DefinitionField(key, Kind.TEXT, List.of(aliases), fallback, 0.0, Double.NaN, List.of());
    }

    public static DefinitionField integer(String key, int fallback, int min, int max, String ... aliases) {
        return new DefinitionField(key, Kind.INTEGER, List.of(aliases), Integer.toString(fallback), min, max, List.of());
    }

    public static DefinitionField decimal(String key, double fallback, double min, double max, String ... aliases) {
        return new DefinitionField(key, Kind.DECIMAL, List.of(aliases), DefinitionField.trimNumber(fallback), min, max, List.of());
    }

    public static DefinitionField flag(String key, boolean fallback, String ... aliases) {
        return new DefinitionField(key, Kind.FLAG, List.of(aliases), Boolean.toString(fallback), 0.0, Double.NaN, List.of());
    }

    public static DefinitionField choice(String key, String fallback, String ... choices) {
        return new DefinitionField(key, Kind.CHOICE, List.of(), fallback, 0.0, Double.NaN, List.of(choices));
    }

    public static DefinitionField item(String ... aliases) {
        return new DefinitionField("item", Kind.ITEM, List.of(aliases), "", 0.0, Double.NaN, List.of());
    }

    public static DefinitionField itemList(String ... aliases) {
        String[] stringArray;
        if (aliases.length == 0) {
            String[] stringArray2 = new String[1];
            stringArray = stringArray2;
            stringArray2[0] = "itemSpecs";
        } else {
            stringArray = aliases;
        }
        return new DefinitionField("items", Kind.ITEM_LIST, List.of(stringArray), "", 0.0, Double.NaN, List.of());
    }

    public static DefinitionField positions() {
        return new DefinitionField("positions", Kind.POSITIONS, List.of("pos"), "", 0.0, Double.NaN, List.of());
    }

    public static DefinitionField pointRef() {
        return new DefinitionField("point", Kind.POINT_REF, List.of(), "", 0.0, Double.NaN, List.of());
    }

    public static DefinitionField containerRef(String key, String ... aliases) {
        return new DefinitionField(key, Kind.CONTAINER_REF, List.of(aliases), "", 0.0, Double.NaN, List.of());
    }

    public static DefinitionField inputs() {
        return new DefinitionField("inputs", Kind.INPUTS, List.of(), "", 0.0, Double.NaN, List.of());
    }

    public static DefinitionField outputs() {
        return new DefinitionField("outputs", Kind.OUTPUTS, List.of(), "", 0.0, Double.NaN, List.of());
    }

    public static DefinitionField steps() {
        return new DefinitionField("steps", Kind.STEPS, List.of(), "", 0.0, Double.NaN, List.of());
    }

    public List<String> acceptedKeys() {
        ArrayList<String> keys = new ArrayList<String>(this.aliases.size() + 1);
        keys.add(this.key);
        keys.addAll(this.aliases);
        return List.copyOf(keys);
    }

    public boolean numeric() {
        return this.kind == Kind.INTEGER || this.kind == Kind.DECIMAL;
    }

    public boolean hasMax() {
        return !Double.isNaN(this.max);
    }

    public String translationKey() {
        return "gui.simupack.industrial.field." + this.key;
    }

    private static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long)value);
        }
        return Double.toString(value);
    }

    public static enum Kind {
        TEXT,
        INTEGER,
        DECIMAL,
        FLAG,
        CHOICE,
        ITEM,
        ITEM_LIST,
        POSITIONS,
        POINT_REF,
        CONTAINER_REF,
        INPUTS,
        OUTPUTS,
        STEPS;

    }
}

