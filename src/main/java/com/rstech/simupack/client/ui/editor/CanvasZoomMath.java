package com.rstech.simupack.client.ui.editor;

/**
 * 画布缩放的锚点数学（纯函数，便于脱离客户端做回归测试）。
 *
 * <h2>画布的真实映射（用实测数据反推并验证过）</h2>
 *
 * <pre>
 *     R = zoom × v + offset + position
 * </pre>
 *
 * <ul>
 *   <li>{@code v}：内容层（children 布局空间）里的坐标；</li>
 *   <li>{@code R}：{@code getLocalMouse(...)} 返回的坐标（与 {@code offset} 同一空间）；</li>
 *   <li>{@code zoom}、{@code offset}：我们写进 {@code transform} 的值；</li>
 *   <li>{@code position}：<b>变换层自身</b>的布局位置
 *       （{@code Transform2D.pushPose} 里 {@code pivot} 用到 {@code element.getPositionX()}，
 *       pivot 为 (0,0) 时它就变成 {@code translate(px)·scale(z)·translate(-px)}，
 *       于是映射里恒有一个 {@code +position} 项）。</li>
 * </ul>
 *
 * <h2>锚点公式</h2>
 *
 * 光标停在 {@code R} 处，缩放前它下面的内容点满足
 * {@code v = (R - offset - position) / zoom}。要求缩放后该点仍在 {@code R}：
 * <pre>
 *     zoom' × v + offset' + position = R
 *     ⟹ offset' = u - (u - offset) × (zoom' / zoom)     其中 u = R - position
 * </pre>
 *
 * <p><b>注意</b>：不要照抄 LDLib2 {@code GraphView} 的 {@code offset += r/zoom - r/newZoom}——
 * 它的 {@code offset} 是<b>世界坐标</b>偏移（变换里写成 {@code translate(-offset*zoom)}），
 * 与这里的<b>屏幕像素</b>偏移差一个 {@code zoom} 因子，直接套用会随缩放持续漂移。
 */
final class CanvasZoomMath {
    private CanvasZoomMath() {}

    /**
     * 求以 {@code pointer} 为锚点时的新平移量。
     *
     * @param pointer  光标位置，必须与 {@code offset} 同一空间（即 {@code getLocalMouse} 的结果
     *                 再减去变换层的布局位置）
     * @param offset   当前平移量（屏幕像素）
     * @param oldZoom  当前缩放（&gt; 0）
     * @param newZoom  目标缩放（&gt; 0，且应已 clamp 过）
     */
    static float anchoredOffset(float pointer, float offset, float oldZoom, float newZoom) {
        if (!(oldZoom > 0F) || !(newZoom > 0F) || oldZoom == newZoom
                || !Float.isFinite(pointer) || !Float.isFinite(offset)) {
            return offset;
        }
        float next = pointer - (pointer - offset) * (newZoom / oldZoom);
        return Float.isFinite(next) ? next : offset;
    }

    /** 内容点 {@code local} 在屏幕（{@code getLocalMouse} 空间）上的位置，用于验证锚点不动。 */
    static float screenPosition(float local, float zoom, float offset, float position) {
        return zoom * local + offset + position;
    }
}
