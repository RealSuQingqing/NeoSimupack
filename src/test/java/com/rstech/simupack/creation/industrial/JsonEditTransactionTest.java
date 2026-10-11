package com.rstech.simupack.creation.industrial;

import com.google.gson.JsonObject;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import com.rstech.simupack.creation.json.DefinitionField;
import com.rstech.simupack.creation.json.JsonEditTransaction;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsonEditTransactionTest {
    @Test void lastKeystrokePersistsBeforeCommitWithoutReplacingLiveObject() {
        JsonObject step = new JsonObject(); step.addProperty("ticks", 1);
        List<String> order = new ArrayList<>();
        String[] saved = {step.toString()};
        assertTrue(JsonEditTransaction.apply(step, draft -> draft.addProperty("ticks", 20),
                () -> order.add("snapshot:" + step.get("ticks").getAsInt()),
                () -> { saved[0] = step.toString(); order.add("persist"); },
                () -> { assertEquals(step.toString(), saved[0]); order.add("commit"); }));
        assertEquals(List.of("snapshot:1", "persist", "commit"), order);
        assertEquals(20, step.get("ticks").getAsInt());
        assertEquals("{\"ticks\":20}", saved[0]);
    }
    @Test void incompleteInvalidAndNoOpInputsNeverSnapshotOrSave() {
        JsonObject step = new JsonObject(); step.addProperty("ticks", 1);
        DefinitionField field = DefinitionField.integer("ticks", 1, 1, Integer.MAX_VALUE);
        Runnable forbidden = () -> fail("Rejected/no-op edit must not create history or dirty state");
        for (String value : List.of("1", "1e", "NaN", "Infinity", "-1", "0.5", "999999999999")) {
            assertFalse(JsonEditTransaction.apply(step, draft -> JsonEditTransaction.writeScalar(draft, field, value),
                    forbidden, forbidden, forbidden), value);
        }
        assertEquals(1, step.get("ticks").getAsInt());
    }
    @Test void integerFieldsRoundTripAsIntegerTextAfterRebuild() {
        JsonObject step = new JsonObject();
        DefinitionField field = DefinitionField.integer("ticks", 1, 1, Integer.MAX_VALUE);

        JsonEditTransaction.writeScalar(step, field, "28");
        assertEquals("28", step.get("ticks").getAsString());
        assertEquals("28", BuildingJsonDocument.readString(step, field));

        // Compatibility with files written by the previous double-based path.
        step.addProperty("ticks", 28.0D);
        assertEquals("28", BuildingJsonDocument.readString(step, field));
    }

    @Test void booleanReferenceAndClearingSaveTheNewValue() {
        JsonObject step = new JsonObject();
        assertTrue(JsonEditTransaction.apply(step, draft ->
                BuildingJsonDocument.writeFlag(draft, DefinitionField.flag("swing", false), true),
                () -> assertFalse(step.has("swing")), () -> {
                    assertTrue(step.get("swing").getAsJsonPrimitive().isBoolean());
                    assertTrue(step.get("swing").getAsBoolean());
                }, () -> {}));
        assertTrue(JsonEditTransaction.apply(step, draft ->
                BuildingJsonDocument.writeString(draft, DefinitionField.pointRef(), "new"),
                () -> assertFalse(step.has("point")),
                () -> assertEquals("new", step.get("point").getAsString()), () -> {}));
        assertTrue(JsonEditTransaction.apply(step, draft -> draft.remove("point"), () -> {},
                () -> assertFalse(step.has("point")), () -> {}));
    }
}
