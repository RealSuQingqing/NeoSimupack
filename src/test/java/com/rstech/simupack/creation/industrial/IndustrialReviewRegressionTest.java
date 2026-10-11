package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.client.ui.editor.PickScope;
import com.google.gson.JsonObject;
import com.rstech.simupack.creation.json.BuildingJsonDocument;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Independent review regressions for the industrial editor changes. */
class IndustrialReviewRegressionTest {
    private static final BuildingJsonDocument.DefinitionKind POINT = BuildingJsonDocument.DefinitionKind.POINT;
    private static final BuildingJsonDocument.DefinitionKind CONTAINER = BuildingJsonDocument.DefinitionKind.CONTAINER;

    @Test void changingOneCraftContainerPreservesTheOtherSharedFallback() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[{"type":"craft_recipe","container":"shared","extension":42}]}]}
                """);
        JsonObject step = model.steps("r").get(0);
        var input = IndustrialStepType.CRAFT_RECIPE.field("input");
        var output = IndustrialStepType.CRAFT_RECIPE.field("output");
        BuildingJsonDocument.writeString(step, input, "source");
        assertEquals("source", BuildingJsonDocument.readString(step, input));
        assertEquals("shared", BuildingJsonDocument.readString(step, output));
        BuildingJsonDocument.writeString(step, output, "destination");
        assertEquals("source", BuildingJsonDocument.readString(step, input));
        assertEquals("destination", BuildingJsonDocument.readString(step, output));
        assertEquals(42, step.get("extension").getAsInt());
    }

    @Test void typedContainerRenameDoesNotRewriteIndependentPointWithSameName() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"points":{"shared":{"positions":[[1,2,3]]}},
                 "containers":{"shared":{"positions":[[4,5,6]]}},
                 "recipes":[{"id":"r","steps":[
                   {"type":"move_to","point":"shared"},
                   {"type":"craft_recipe","container":"shared"}]}]}
                """);
        assertTrue(model.renameDefinition("shared", "renamed", CONTAINER));
        assertTrue(model.hasDefinition("shared", POINT));
        assertFalse(model.hasDefinition("shared", CONTAINER));
        assertEquals("shared", model.steps("r").get(0).get("point").getAsString());
        JsonObject craft = model.steps("r").get(1);
        assertEquals("renamed", BuildingJsonDocument.readString(craft, IndustrialStepType.CRAFT_RECIPE.field("input")));
        assertEquals("renamed", BuildingJsonDocument.readString(craft, IndustrialStepType.CRAFT_RECIPE.field("output")));
    }

    @Test void reselectKeepsUnknownDefinitionFieldsAndIndependentSibling() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"points":{"p":{"pos":[1,2,3],"select":"ordered","future":{"data":[1,2]}}},
                 "containers":{"p":{"positions":[[8,8,8]]}},"recipes":[]}
                """);
        JsonObject sibling = model.definitionEntry("p", CONTAINER).deepCopy();
        assertTrue(model.setDefinitionPositions("p", POINT, List.of(new int[]{2,3,4}, new int[]{4,5,6}), "ordered"));
        assertEquals(2, model.definitionEntry("p", POINT).getAsJsonObject("future").getAsJsonArray("data").size());
        assertEquals(sibling, model.definitionEntry("p", CONTAINER));
    }

    @Test void unchangedReselectPreservesOriginalCoordinateSpellingAndMirrorLink() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"containers":{"box":{"pos":[1,2,3],"future":true}},"recipes":[]}
                """);
        assertTrue(model.ensurePointEntry("box"));
        String before = model.toJsonString();
        assertFalse(model.setDefinitionPositions("box", POINT, List.of(new int[]{1,2,3}), "nearest"));
        assertEquals(before, model.toJsonString());
        assertTrue(model.setDefinitionPositions("box", CONTAINER, List.of(new int[]{3,2,1}), "nearest"));
        assertEquals(3, BuildingJsonDocument.positionList(model.definitionEntry("box", POINT)).get(0).get(0).getAsInt());
    }

    @Test void unknownExtensionKeysDoNotBecomeIndustrialValidationRules() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"futureExtension":{"count":"dynamic","positions":"not industrial coordinates"},
                 "recipes":[{"id":"r","steps":[{"type":"set_status","statusText":"ready",
                   "customData":{"count":"arbitrary","pos":"payload"}}]}]}
                """);
        String before = model.toJsonString();
        var issues = assertDoesNotThrow(() -> model.validate(BuildingJsonDocument.StructureBounds.UNKNOWN));
        assertFalse(issues.stream().anyMatch(i -> i.location().contains("futureExtension")
                || i.location().contains("customData")), issues.toString());
        assertEquals(before, model.toJsonString());
    }

    @Test void malformedIndustrialPositionsProduceIssuesWithoutThrowing() {
        for (String position : List.of("[1,2]", "[1,2,3.5]", "[1,2,{}]", "null", "[1,2,2147483648]")) {
            BuildingJsonDocument model = BuildingJsonDocument.parse(
                    "{\"points\":{\"p\":{\"positions\":[" + position + "]}},\"recipes\":[{\"id\":\"r\",\"steps\":[{\"type\":\"look_at\",\"point\":\"p\"}]}]}");
            var issues = assertDoesNotThrow(() -> model.validate(BuildingJsonDocument.StructureBounds.UNKNOWN));
            assertTrue(issues.stream().anyMatch(i -> i.code().equals("invalid_position")), position);
        }
    }

    @Test void expandedStepBoundaryMatchesTheActualLoader() {
        for (int count : List.of(255, 256, Integer.MAX_VALUE)) {
            BuildingJsonDocument model = BuildingJsonDocument.parse(
                    "{\"recipes\":[{\"id\":\"r\",\"steps\":[{\"type\":\"repeat\",\"count\":" + count
                            + ",\"steps\":[{\"type\":\"set_status\",\"statusText\":\"ready\"}]}]}]}");
            var issues = assertDoesNotThrow(() -> model.validate(BuildingJsonDocument.StructureBounds.UNKNOWN));
            assertEquals(count >= 256, issues.stream().anyMatch(i -> i.code().equals("steps_exceeded")),
                    "Expanded count: " + count);
        }
    }

    @Test void missingDefinitionTypeUsesStructurePositionDefaultWithoutMutation() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"points":{"p":{"positions":[[0,0,0]]}},
                 "recipes":[{"id":"r","steps":[{"type":"look_at","point":"p"}]}]}
                """);
        String before = model.toJsonString();
        var issues = model.validate(BuildingJsonDocument.StructureBounds.UNKNOWN);
        assertFalse(issues.stream().anyMatch(i -> i.code().equals("point_bad_type")), issues.toString());
        assertEquals(before, model.toJsonString());
    }

    @Test void explicitEmptyCraftOverridesRemainDistinctFromMissingInheritedFields() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"craft_recipe"},
                  {"type":"craft_recipe","inputs":[],"outputs":[]}]}]}
                """);
        var restored = BuildingJsonDocument.parse(model.toJsonString());
        assertFalse(restored.steps("r").get(0).has("inputs"));
        assertFalse(restored.steps("r").get(0).has("outputs"));
        assertTrue(restored.steps("r").get(1).getAsJsonArray("inputs").isEmpty());
        assertTrue(restored.steps("r").get(1).getAsJsonArray("outputs").isEmpty());
    }

    @Test void pickScopeRejectsOtherBuildingRecipeOrChangedConfiguration() {
        Object building = new Object();
        // 选点作用域已抽成通用概念（原 IndustrialPickScope → PickScope）：
        // 工业的 subject 是配方 id，商业的是容器 id，判定逻辑同一个。
        var scope = new PickScope(building, "recipe", "original");
        assertTrue(scope.matches(building, "recipe", "original"));
        assertFalse(scope.matches(new Object(), "recipe", "original"));
        assertFalse(scope.matches(building, "other", "original"));
        assertFalse(scope.matches(building, "recipe", "changed"));
    }
}
