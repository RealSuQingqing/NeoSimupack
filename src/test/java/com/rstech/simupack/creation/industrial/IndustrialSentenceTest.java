package com.rstech.simupack.creation.industrial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import com.rstech.simupack.creation.json.JsonEditTransaction;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class IndustrialSentenceTest {
    /**
     * 不是 JSON 字段、而是运行时按配置拼出来的占位符。
     *
     * <p>{@code rangeDetail} 会换成「建筑结构范围」或「{point} 的 {radius} 格范围内」，
     * {@code outputTail} 会换成「，产物到 {output}」或空串——换完还会解析出真正的字段占位符，
     * 所以这里放过它们，但其余占位符仍然必须是该 type 的真实字段。
     */
    private static final Set<String> VIRTUAL_TOKENS = Set.of("rangeDetail", "outputTail");

    private JsonObject language(String locale) throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/simupack/lang/" + locale + ".json"))).getAsJsonObject();
    }
    @Test void allTwentyEightTypesHaveMatchingValidLocalizedFieldTokens() throws Exception {
        JsonObject zh = language("zh_cn"), en = language("en_us");
        assertEquals(zh.keySet(), en.keySet());
        assertEquals(28, IndustrialStepType.values().length);
        for (var type : IndustrialStepType.values()) {
            String key = "gui.simupack.industrial.sentence." + type.canonical();
            assertTrue(zh.has(key), key); assertTrue(en.has(key), key);
            Set<String> first = new HashSet<>();
            for (var part : IndustrialSentence.parse(zh.get(key).getAsString())) if (part.field() != null) {
                assertTrue(VIRTUAL_TOKENS.contains(part.field()) || type.field(part.field()) != null,
                        key + ":" + part.field());
                assertTrue(first.add(part.field()), "Duplicate sentence field");
            }
            Set<String> second = new HashSet<>();
            for (var part : IndustrialSentence.parse(en.get(key).getAsString())) if (part.field() != null) second.add(part.field());
            assertEquals(first, second, key);
            Set<String> all = new HashSet<>();
            for (var field : IndustrialSentence.fields(type)) assertTrue(all.add(field.key()));
            for (var field : type.fields()) assertTrue(all.contains(field.key()));
        }
    }
    @Test void renderingMetadataNeverMutatesUnknownOrAliasedJson() throws Exception {
        var model = BuildingJsonDocument.parse("{\"recipes\":[{\"id\":\"r\",\"steps\":[{\"type\":\"craft_all_recipe\",\"container\":\"box\",\"customData\":{\"count\":\"future\"}}]}]}");
        String before = model.toJsonString();
        var zh = language("zh_cn");
        IndustrialSentence.fields(IndustrialStepType.CRAFT_AVAILABLE_RECIPE);
        IndustrialSentence.parse(zh.get("gui.simupack.industrial.sentence.craft_available_recipe").getAsString());
        assertEquals(before, model.toJsonString());
        assertEquals(1, IndustrialSentence.fields(IndustrialStepType.REAL_MACHINE_RECIPE).stream().filter(f -> f.key().equals("timeoutTicks")).count());
    }
    @Test void capturedInputOwnerSurvivesRecipeRenameWithoutReplacingLiveRecipe() {
        var model = BuildingJsonDocument.createDefault("test", "test");
        JsonObject recipe = model.addRecipe("original");
        var owner = BuildingJsonDocument.InputOwner.ofRecipe("original");
        assertTrue(model.renameRecipe("original", "renamed"));
        assertSame(recipe, model.ownerObject(owner));
        assertEquals("renamed", model.selectedRecipeId());
        model.addInputItem(owner, null);
        assertEquals(1, recipe.getAsJsonArray("inputs").size());
        assertFalse(model.renameRecipe("renamed", "renamed"));
    }
    @Test void removingOverrideThroughTransactionPreservesOtherFieldsAndSavesImmediately() {
        JsonObject step = JsonParser.parseString("{\"type\":\"craft_recipe\",\"inputs\":[],\"customData\":{\"count\":\"future\"}}").getAsJsonObject();
        assertTrue(JsonEditTransaction.apply(step, draft -> draft.remove("inputs"),
                () -> assertTrue(step.has("inputs")),
                () -> { assertFalse(step.has("inputs")); assertTrue(step.has("customData")); }, () -> {}));
    }
}
