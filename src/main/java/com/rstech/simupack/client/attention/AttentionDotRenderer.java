package com.rstech.simupack.client.attention;

import com.lowdragmc.lowdraglib2.gui.texture.SDFRectTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.rstech.simupack.attention.AttentionSnapshot;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 用 LDLib2 的 {@link SDFRectTexture} 画一颗呼吸红点。
 *
 * <p>这是本项目的<b>第一处原版 {@code GuiGraphics} 渲染</b>：其余 UI 全部走 LDLib2 的控件树，
 * 而红点必须画在 P 按钮上、且在任何控件之后（见 {@link AttentionBadgeHook}）。
 *
 * <p><b>为什么用 SDF 而不是 5 次 {@code fill} 拼八边形</b>：{@code SDFRectTexture} 走 LDLib2
 * 自带的 SDF 着色器，{@code radius = 边长/2} 就是<b>任意 GUI 缩放下都抗锯齿的正圆</b>；
 * 而 {@code fill} 拼出来的多边形在 4x 缩放下会是明显的锯齿块。
 * LDLib2 是本模组的必需依赖，着色器必然已加载，没有额外成本。
 *
 * <p><b>动画用绝对时钟（{@link Util#getMillis()}）而不是 tick</b>：TitleScreen 上的控件
 * <b>不 tick</b>（{@code AbstractWidget} 没有 tick 回调），但 {@code render} 每帧都调，
 * 所以时钟驱动既最省事也最平滑。
 *
 * <p>几何（相对按钮，不写绝对坐标）：
 * <pre>
 * 按钮 (width - 28, 8, 20, 20)
 * 圆心 (x + w - 3, y + h - 3)
 * 呼吸：周期 1200 ms，直径 5.0 + 1.8 × sin²(π · phase)
 * 涟漪：周期 1600 ms（相位错开），直径 6.4 + 5.6 × phase，alpha 0x73 × (1 - phase)
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public final class AttentionDotRenderer {

    /** 红点核心色。 */
    private static final int CORE_COLOR = 0xFFE53935;
    /** 涟漪基色（alpha 在绘制时按相位缩放）。 */
    private static final int RIPPLE_RGB = 0x00E53935;

    private static final long BREATH_PERIOD_MS = 1200L;
    private static final long RIPPLE_PERIOD_MS = 1600L;

    private static final float CORE_MIN_DIAMETER = 5.0F;
    private static final float CORE_MAX_DIAMETER = 6.8F;
    private static final float RIPPLE_MIN_DIAMETER = 6.4F;
    private static final float RIPPLE_MAX_DIAMETER = 12.0F;
    private static final float RIPPLE_MAX_ALPHA = 0x73;

    /** 圆心距按钮右下角的距离。 */
    private static final float EDGE_INSET = 3.0F;

    private AttentionDotRenderer() {
    }

    /**
     * 在按钮矩形内画红点。
     *
     * @param graphics  原版绘制上下文
     * @param buttonX   按钮左上角 x（GUI 逻辑坐标）
     * @param buttonY   按钮左上角 y
     * @param buttonW   按钮宽度
     * @param buttonH   按钮高度
     * @param animate   {@code false} = 静态点（不读时钟）
     */
    public static void render(GuiGraphics graphics, int buttonX, int buttonY,
                              int buttonW, int buttonH, boolean animate) {
        if (graphics == null) {
            return;
        }
        float centerX = buttonX + buttonW - EDGE_INSET;
        float centerY = buttonY + buttonH - EDGE_INSET;

        float breathPhase = 0F;
        float ripplePhase = -1F;
        if (animate) {
            breathPhase = phase(Util.getMillis(), BREATH_PERIOD_MS);
            // 涟漪相位错开半个周期：不然两段动画会同时到顶点，看起来像一起闪。
            ripplePhase = phase(Util.getMillis() + RIPPLE_PERIOD_MS / 2L, RIPPLE_PERIOD_MS);
        }

        try {
            if (animate) {
                drawCircle(graphics, centerX, centerY, rippleDiameter(ripplePhase),
                        rippleColor(ripplePhase));
            }
            drawCircle(graphics, centerX, centerY, coreDiameter(breathPhase), CORE_COLOR);
        } finally {
            // SDFRectTexture 内部 enableBlend() + blendFuncSeparate(...) + disableDepthTest()
            // 且**不还原**（它只还原 pose/modelview）。虽然 Render.Post 之后本帧没有别的绘制，
            // 仍要显式收尾，避免状态跨帧残留把别的界面画坏。
            cleanup(graphics);
        }
    }

    /** 画一个正圆：{@code radius = 边长/2} 即为圆。 */
    private static void drawCircle(GuiGraphics graphics, float centerX, float centerY,
                                   float diameter, int color) {
        if (diameter <= 0F) {
            return;
        }
        float radius = diameter / 2F;
        // setRadius 的语义是"圆角半径"，等于边长一半时矩形退化成圆。
        SDFRectTexture.of(color).setRadius(radius)
                .draw(graphics, 0F, 0F, centerX - radius, centerY - radius, diameter, diameter, 0F);
    }

    /**
     * 渲染状态收尾。
     *
     * <p>{@code flush()} 先把红点自己的顶点提交掉，再恢复深度测试与默认混合模式，
     * 免得把 SDF 纹理改过的状态留给跨帧的其它绘制。
     */
    private static void cleanup(GuiGraphics graphics) {
        graphics.flush();
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
    }

    /** 周期内的相位 [0, 1)。 */
    static float phase(long millis, long periodMs) {
        if (periodMs <= 0L) {
            return 0F;
        }
        long remainder = Math.floorMod(millis, periodMs);
        return (float) remainder / (float) periodMs;
    }

    /** 呼吸直径：{@code 5.0 + 1.8 × sin²(π · phase)}。 */
    static float coreDiameter(float phase) {
        double sine = Math.sin(Math.PI * phase);
        return CORE_MIN_DIAMETER + (CORE_MAX_DIAMETER - CORE_MIN_DIAMETER) * (float) (sine * sine);
    }

    /** 涟漪直径：{@code 6.4 + 5.6 × phase}（向外扩散）。 */
    static float rippleDiameter(float phase) {
        float clamped = Math.max(0F, Math.min(1F, phase));
        return RIPPLE_MIN_DIAMETER + (RIPPLE_MAX_DIAMETER - RIPPLE_MIN_DIAMETER) * clamped;
    }

    /** 涟漪颜色：alpha 由 {@code 0x73 → 0x00} 淡出。 */
    static int rippleColor(float phase) {
        float clamped = Math.max(0F, Math.min(1F, phase));
        int alpha = Math.round(RIPPLE_MAX_ALPHA * (1F - clamped));
        return (alpha << 24) | RIPPLE_RGB;
    }

    /**
     * 快照是否该让红点亮起。
     *
     * <p>这是渲染路径上唯一的判断，O(1) 且零 IO：算快照要读盘、读 ZIP、反射问 NSUK，
     * 那些一律在 {@code AttentionCenter.refreshAsync()} 的后台线程里做。
     */
    public static boolean shouldLight(AttentionSnapshot snapshot) {
        return snapshot != null && !snapshot.isEmpty();
    }
}
