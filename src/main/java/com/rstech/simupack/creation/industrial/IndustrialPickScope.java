package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

import java.util.Objects;

/** Confirmation guard; scene navigation itself never mutates the source configuration. */
public record IndustrialPickScope(Object building, String recipeId, String configuration) {
    public boolean matches(Object currentBuilding, String currentRecipe, String currentConfiguration) {
        return building == currentBuilding && Objects.equals(recipeId, currentRecipe)
                && Objects.equals(configuration, currentConfiguration);
    }
}
