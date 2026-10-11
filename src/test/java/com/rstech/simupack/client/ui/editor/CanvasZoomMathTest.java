package com.rstech.simupack.client.ui.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画布缩放锚点的回归测试。
 *
 * <p>用实测日志里的真实数字：光标 GUI (213.83, 112.5) 在 1.5 倍根缩放下对应
 * {@code getLocalMouse = (320.75, 168.75)}，变换层布局位置 180.41，缩放 1.0 → 0.9 时
 * 旧实现（多减了一次根元素位置）的残差是 (−12.03, −7.93) 像素。
 */
class CanvasZoomMathTest {
    /** getLocalMouse 空间下的光标位置（来自日志）。 */
    private static final float POINTER = 320.75F;
    /** 变换层的布局位置（来自日志的 rootPos / contentX）。 */
    private static final float POSITION = 180.41F;

    private static void assertAnchorKept(float pointer, float position, float zoom,
                                         float newZoom, float offset) {
        float local = (pointer - offset - position) / zoom;
        float before = CanvasZoomMath.screenPosition(local, zoom, offset, position);
        assertEquals(pointer, before, 1.0E-3F, "预置条件：光标处的内容点应当就在光标位置");

        float next = CanvasZoomMath.anchoredOffset(pointer - position, offset, zoom, newZoom);
        float after = CanvasZoomMath.screenPosition(local, newZoom, next, position);
        assertEquals(pointer, after, 1.0E-3F,
                "缩放后光标下的内容点必须停在同一位置（zoom " + zoom + " → " + newZoom + "）");
    }

    @Test
    void reproducesTheLoggedCaseExactly() {
        // 日志第一跳：zoom 1.0 → 0.9，offset 0，期望残差为 0。
        float next = CanvasZoomMath.anchoredOffset(POINTER - POSITION, 0F, 1F, 0.9F);
        assertEquals(14.034F, next, 1.0E-3F);
        float local = (POINTER - 0F - POSITION) / 1F;
        assertEquals(POINTER, CanvasZoomMath.screenPosition(local, 0.9F, next, POSITION), 1.0E-3F);
    }

    @Test
    void graphViewWorldOffsetFormulaWouldDriftByTheLoggedResidual() {
        // 旧实现：把 GraphView 的公式用在屏幕像素 offset 上，并且多减了一次根元素位置。
        float wrongPointer = POINTER - POSITION * 2F; // 180.41 被减了两次（实测行为）
        float wrong = wrongPointer - (wrongPointer - 0F) * 0.9F;
        float local = (POINTER - 0F - POSITION) / 1F;
        float actual = CanvasZoomMath.screenPosition(local, 0.9F, wrong, POSITION);
        // 日志实测 actual=201.81 / residual=-12.03（GUI 像素 = 本地像素 ÷ 1.5）。
        assertEquals(-12.03F, (actual - POINTER) / 1.5F, 0.05F);
    }

    @Test
    void anchorStaysPutAcrossRepeatedInAndOutZoom() {
        for (float position : new float[]{0F, 180.41F, -37.5F}) {
            for (float pointer : new float[]{320.75F, 5F, 1200F}) {
                float zoom = 1F;
                float offset = 0F;
                for (int i = 0; i < 12; i++) {
                    float next = Math.min(2F, zoom * 1.1F);
                    assertAnchorKept(pointer, position, zoom, next, offset);
                    offset = CanvasZoomMath.anchoredOffset(pointer - position, offset, zoom, next);
                    zoom = next;
                }
                for (int i = 0; i < 12; i++) {
                    float next = Math.max(0.5F, zoom / 1.1F);
                    assertAnchorKept(pointer, position, zoom, next, offset);
                    offset = CanvasZoomMath.anchoredOffset(pointer - position, offset, zoom, next);
                    zoom = next;
                }
            }
        }
    }

    @Test
    void zoomLimitReachedKeepsOffsetUnchanged() {
        // 已经到上下限时（newZoom == oldZoom）不能改 offset，否则画面会无端平移。
        assertEquals(42.5F, CanvasZoomMath.anchoredOffset(10F, 42.5F, 2F, 2F), 0F);
        assertEquals(42.5F, CanvasZoomMath.anchoredOffset(10F, 42.5F, 0F, 2F), 0F);
        assertEquals(42.5F, CanvasZoomMath.anchoredOffset(Float.NaN, 42.5F, 1F, 2F), 0F);
    }

    @Test
    void panningDoesNotDisturbTheAnchorInvariant() {
        // 先平移一段再缩放：锚点仍必须成立（offset 偏移不影响不变式）。
        float zoom = 1.4F;
        float offset = -260F;
        float next = zoom * 1.1F;
        assertAnchorKept(POINTER, POSITION, zoom, next, offset);
        float updated = CanvasZoomMath.anchoredOffset(POINTER - POSITION, offset, zoom, next);
        assertTrue(Math.abs(updated) < 1.0E4F);
    }
}
