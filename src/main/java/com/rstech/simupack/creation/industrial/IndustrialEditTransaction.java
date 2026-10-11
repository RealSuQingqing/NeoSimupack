package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

import com.google.gson.JsonObject;
import java.util.function.Consumer;

/** UI-independent edit boundary: validate a draft, snapshot, mutate, persist, then notify. */
public final class IndustrialEditTransaction {
    private IndustrialEditTransaction() {}

    public static void writeScalar(JsonObject target, DefinitionField field, String value) {
        if (value == null || value.isBlank()) {
            IndustrialActionModel.clearField(target, field);
        } else if (!field.numeric()) {
            IndustrialActionModel.writeString(target, field, value);
        } else {
            double number = Double.parseDouble(value.trim());
            if (!Double.isFinite(number) || number < field.min()
                    || (!Double.isNaN(field.max()) && number > field.max())
                    || (field.kind() == DefinitionField.Kind.INTEGER && number != Math.rint(number))) {
                throw new IllegalArgumentException("Invalid numeric input");
            }
            IndustrialActionModel.writeNumber(target, field, number);
        }
    }

    /** Mutation must only modify its argument; it is first evaluated on an isolated draft. */
    public static boolean apply(JsonObject target, Consumer<JsonObject> mutation,
                                Runnable snapshot, Runnable persist, Runnable commit) {
        JsonObject draft = target.deepCopy();
        try {
            mutation.accept(draft);
        } catch (IllegalArgumentException exception) {
            return false; // Incomplete/invalid input stays in the text field, never in JSON/history.
        }
        if (draft.equals(target)) {
            return false;
        }
        snapshot.run();
        mutation.accept(target);
        persist.run();
        commit.run();
        return true;
    }
}
