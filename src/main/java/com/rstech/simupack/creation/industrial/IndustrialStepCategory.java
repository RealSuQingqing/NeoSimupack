/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.creation.industrial;

public enum IndustrialStepCategory {
    FLOW("flow", -4744674),
    MOVE("move", -12747592),
    HOLD("hold", -14119802),
    MATERIAL("material", -4099300),
    CONTAINER("container", -8762437),
    BLOCK("block", -13333943),
    ENTITY("entity", -4701069);

    private final String id;
    private final int color;

    private IndustrialStepCategory(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public String id() {
        return this.id;
    }

    public int color() {
        return this.color;
    }

    public int headerColor() {
        return IndustrialStepCategory.darken(this.color, 0.72f);
    }

    public String translationKey() {
        return "gui.simupack.industrial.group." + this.id;
    }

    public static IndustrialStepCategory byId(String id) {
        for (IndustrialStepCategory category : IndustrialStepCategory.values()) {
            if (!category.id.equals(id)) continue;
            return category;
        }
        return MOVE;
    }

    private static int darken(int argb, float factor) {
        int a = argb >>> 24 & 0xFF;
        int r = Math.round((float)(argb >> 16 & 0xFF) * factor);
        int g = Math.round((float)(argb >> 8 & 0xFF) * factor);
        int b = Math.round((float)(argb & 0xFF) * factor);
        return a << 24 | IndustrialStepCategory.clamp(r) << 16 | IndustrialStepCategory.clamp(g) << 8 | IndustrialStepCategory.clamp(b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}

