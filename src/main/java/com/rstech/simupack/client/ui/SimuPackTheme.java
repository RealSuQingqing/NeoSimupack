package com.rstech.simupack.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.rstech.simupack.SimuPackMod;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 筑艺坊 UI 主题。
 *
 * <p>做法与 NSUK 的城市核心界面保持一致：先加载 LDLib2 内置的 ore 样式表，
 * 再叠加本模组在 {@code assets/simupack/lss/ore.lss} 中的同名扩展。
 * 这样按钮、Tab、滚动条、输入框等控件都能拿到 LDLib2 的标准外观，
 * 本模组只需要补充自己的面板、窗口、卡片与文字层级。
 */
@OnlyIn(Dist.CLIENT)
public final class SimuPackTheme {
    /** 基础样式表：LDLib2 内置 ore 主题。 */
    public static final ResourceLocation DEFAULT_STYLESHEET = StylesheetManager.ORE;

    public static final int WINDOW_BACKGROUND_COLOR = 0xFF2B2B2B;

    public static final int TEXT_PRIMARY_COLOR = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY_COLOR = 0xFFE6E6E6;
    public static final int TEXT_MUTED_COLOR = 0xFFBDBDBD;
    public static final int TEXT_SUCCESS_COLOR = 0xFF66FF66;
    public static final int TEXT_WARNING_COLOR = 0xFFFFFF55;
    public static final int TEXT_ERROR_COLOR = 0xFFFF7777;
    public static final int TEXT_INFO_COLOR = 0xFF66FFFF;

    /**
     * 状态指示灯「灭灯」的描边色。
     *
     * <p>灭灯画成<b>空心方框</b>而不是实心暗块：实测实心暗块无论取多暗都会和面板底色
     * 混在一起（面板本身是 #FF1F1F1F，树行区域实测约 (79,82,87)），用户会以为那里
     * 根本没有指示灯。空心框在任何底色上都能读出"这里有个灯，但它是灭的"。
     */
    public static final int INDICATOR_OFF_COLOR = 0xFF8C8C8C;

    private SimuPackTheme() {
    }

    /** 创建主题化 UI：LDLib2 ore 样式表 + 筑艺坊扩展。 */
    public static UI createUi(UIElement root) {
        return UI.of(root, stylesheets(DEFAULT_STYLESHEET));
    }

    /**
     * 创建带界面缩放的 UI。
     *
     * <p>只影响这一个界面：把「屏幕尺寸」映射成更小或更大的「布局尺寸」，
     * 由 LDLib2 负责把布局绘制到整个屏幕，因此不会改动 Minecraft 的全局 GUI 缩放。
     *
     * @param scale 大于 0 时生效；界面放大 {@code scale} 倍表示布局空间缩小到 {@code 1/scale}
     */
    public static UI createUi(UIElement root, float scale) {
        List<Stylesheet> sheets = stylesheets(DEFAULT_STYLESHEET);
        if (scale <= 0F) {
            return UI.of(root, sheets);
        }
        return UI.of(root, sheets, size -> com.lowdragmc.lowdraglib2.math.Size.of(
                Math.max(1, Math.round(size.width / scale)),
                Math.max(1, Math.round(size.height / scale))));
    }

    public static UI createUi(UIElement root, ResourceLocation stylesheet) {
        return UI.of(root, stylesheets(stylesheet));
    }

    public static Stylesheet stylesheet() {
        return stylesheet(DEFAULT_STYLESHEET);
    }

    public static Stylesheet stylesheet(ResourceLocation location) {
        return StylesheetManager.INSTANCE.getStylesheetSafe(normalize(location));
    }

    /** 全屏遮罩层：绝对定位铺满，只负责背景，不拦截鼠标事件。 */
    public static UIElement createShellPanel(int screenWidth, int screenHeight) {
        return new UIElement()
                .setAllowHitTest(false)
                .layout(layout -> {
                    layout.positionType(TaffyPosition.ABSOLUTE);
                    layout.left(0);
                    layout.top(0);
                    layout.width(Math.max(1, screenWidth));
                    layout.height(Math.max(1, screenHeight));
                })
                .addClass("simupack_shell_panel");
    }

    /** 分栏面板容器：带 ore 边框背景。 */
    public static UIElement panel() {
        return new UIElement().addClass("simupack_panel");
    }

    /** 列表卡片容器。 */
    public static UIElement card() {
        return new UIElement().addClass("simupack_card");
    }

    public static UIElement card(boolean error) {
        return new UIElement().addClass(error ? "simupack_card_error" : "simupack_card");
    }

    public static UIElement titleBar() {
        return new UIElement().addClass("simupack_title_bar");
    }

    public static UIElement toolbar() {
        return new UIElement().addClass("simupack_toolbar");
    }

    public static UIElement statusBar() {
        return new UIElement().addClass("simupack_status_bar");
    }

    private static List<Stylesheet> stylesheets(ResourceLocation stylesheet) {
        ResourceLocation base = normalize(stylesheet);
        ResourceLocation extension = ResourceLocation.fromNamespaceAndPath(SimuPackMod.MOD_ID, base.getPath());
        if (base.equals(extension)) {
            return List.of(stylesheet(base));
        }
        return List.of(stylesheet(base), stylesheet(extension));
    }

    private static ResourceLocation normalize(ResourceLocation location) {
        String path = location.getPath();
        if (path.endsWith(".lss")) {
            return location;
        }
        return ResourceLocation.fromNamespaceAndPath(location.getNamespace(), path + ".lss");
    }
}
