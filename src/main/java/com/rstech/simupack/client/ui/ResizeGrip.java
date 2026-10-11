package com.rstech.simupack.client.ui;

import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.math.Size;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 可拖动改尺寸的边框条：悬停 / 拖动时在鼠标位置画一个双箭头。
 *
 * <p><b>这不是 OS 光标，而是 LDLib2 自己画的贴图</b> —— 分栏分隔条（{@code SplitView}）
 * 就是这么做的：{@code drawBackgroundAdditional} 里判断鼠标是否落在分隔条上，
 * 是则在 {@code postRendering} 阶段把 {@code Icons.ARROW_LEFT_RIGHT} /
 * {@code ARROW_UP_DOWN} 画在 {@code context.localMouseX/localMouseY} 上。
 *
 * <p>所以这里把同一套逻辑收成一个控件，仿真页签和住宅阵列页签共用：
 * 好处是观感与分栏完全一致、不需要自己换算坐标（框架给的 {@code localMouse*} 已经
 * 扣掉了所在分栏与画布的偏移），也不必去碰 GLFW 光标的创建 / 复位 / 泄漏。
 */
@OnlyIn(Dist.CLIENT)
public final class ResizeGrip extends UIElement {

    private final SpriteTexture icon;
    private boolean hovered;
    private boolean dragging;

    private ResizeGrip(SpriteTexture icon) {
        this.icon = icon;
        // 悬停状态由控件自己维护：两个面板各自再记一份的话，迟早会有一处忘了清。
        addEventListener(UIEvents.MOUSE_ENTER, event -> hovered = true);
        addEventListener(UIEvents.MOUSE_LEAVE, event -> hovered = false);
    }

    /** 左右双箭头：贴左边缘、改宽度。 */
    public static ResizeGrip horizontal() {
        return new ResizeGrip(Icons.ARROW_LEFT_RIGHT);
    }

    /** 上下双箭头：贴下边缘、改高度。 */
    public static ResizeGrip vertical() {
        return new ResizeGrip(Icons.ARROW_UP_DOWN);
    }

    /**
     * 拖动期间要一直显示箭头。
     *
     * <p>拖动时指针会离开这条 4px 宽的边（所以才把 MOUSE_MOVE/UP 挂在根容器上），
     * 只靠 hovered 的话箭头会中途消失。
     */
    public ResizeGrip setDragging(boolean value) {
        this.dragging = value;
        return this;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        if (!hovered && !dragging) {
            return;
        }
        Size size = this.icon.spriteSize;
        context.postRendering(ctx -> ctx.drawTexture(this.icon,
                ctx.localMouseX - size.width / 2.0F,
                ctx.localMouseY - size.height / 2.0F,
                size.width,
                size.height));
    }
}
