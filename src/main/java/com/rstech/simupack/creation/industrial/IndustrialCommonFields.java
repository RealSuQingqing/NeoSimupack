/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

public final class IndustrialCommonFields {
    public static final DefinitionField TIMEOUT = DefinitionField.integer("timeoutTicks", 12000, 1, Integer.MAX_VALUE, "timeout");
    public static final DefinitionField SKIP_ON_TIMEOUT = DefinitionField.flag("skipOnTimeout", false, "skip_on_timeout");
    public static final DefinitionField COUNT = DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE, new String[0]);

    private IndustrialCommonFields() {
    }
}

