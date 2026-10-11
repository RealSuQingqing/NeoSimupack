/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.creation.json;

import com.rstech.simupack.creation.json.CommercialDefinitionFields;
import com.rstech.simupack.creation.json.IndustrialDefinitionFields;
import com.rstech.simupack.creation.json.MedicalDefinitionFields;
import com.rstech.simupack.creation.json.SettingSection;
import java.util.List;
import java.util.Locale;

public final class BuildingSettingsSchema {
    private BuildingSettingsSchema() {
    }

    public static boolean hasJsonDefinition(String category) {
        return switch (BuildingSettingsSchema.normalize(category)) {
            case "industry", "commercial", "public" -> true;
            default -> false;
        };
    }

    public static List<SettingSection> sectionsFor(String category) {
        return switch (BuildingSettingsSchema.normalize(category)) {
            case "industry" -> IndustrialDefinitionFields.SECTIONS;
            case "commercial" -> CommercialDefinitionFields.SECTIONS;
            case "public" -> MedicalDefinitionFields.SECTIONS;
            default -> List.of();
        };
    }

    private static String normalize(String category) {
        if (category == null) {
            return "";
        }
        String value = category.trim().toLowerCase(Locale.ROOT);
        if ("commerce".equals(value)) {
            return "commercial";
        }
        return value.startsWith("medic") ? "public" : value;
    }
}

