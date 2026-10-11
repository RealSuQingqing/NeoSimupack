package com.rstech.simupack.client.ui.editor;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 领域专属弹窗的种类。
 *
 * <p>与 {@link MiddleTabKind} 同样的理由：弹窗走枚举，加分类不改 {@link EditorHost}。
 */
@OnlyIn(Dist.CLIENT)
public enum EditorDialog {
    /** 工业：「管理点位」弹窗（改名 / 删除 / 重新选取）。 */
    INDUSTRIAL_POINT_SETTINGS
}
