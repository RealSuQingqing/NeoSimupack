/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.client.ui.editor;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(value=Dist.CLIENT)
public enum MiddleTabKind {
    INDUSTRIAL_ACTION("gui.simupack.industrial.action_group"),
    INDUSTRIAL_SIMULATION("gui.simupack.industrial.simulation"),
    COMMERCIAL_TRADE("gui.simupack.commercial.trades_tab"),
    RESIDENTIAL_ARRAY("gui.simupack.residential.array");

    private final String labelKey;

    private MiddleTabKind(String labelKey) {
        this.labelKey = labelKey;
    }

    public String labelKey() {
        return this.labelKey;
    }
}

