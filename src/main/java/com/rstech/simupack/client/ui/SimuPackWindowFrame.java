package com.rstech.simupack.client.ui;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 筑艺坊窗口框架：标题栏 + 关闭按钮 + 内容区。
 *
 * <p>结构与 NSUK 的 {@code SimuKraftWindowFrame} 一致，并借鉴 VS Code 的顶栏思路：
 * 操作按钮放在标题栏内、与标题同一行。这样可以省掉一整个工具栏行的高度——
 * 在自动 GUI 缩放只有 427x240 的环境里，这一行非常宝贵。
 */
@OnlyIn(Dist.CLIENT)
public final class SimuPackWindowFrame {
    public static final int TITLE_BAR_HEIGHT = 20;
    public static final int TITLE_BAR_PADDING_LEFT = 8;
    public static final int TITLE_BAR_GAP = 6;
    private static final int CLOSE_BUTTON_SIZE = 16;

    private SimuPackWindowFrame() {
    }

    /** 无顶栏操作区的窗口。 */
    public static UIElement create(SimuPackLayout.ScreenSize screenSize, Component title,
                                   UIElement content, Runnable closeAction) {
        return create(screenSize, title, null, null, content, closeAction);
    }

    /**
     * 带顶栏操作区的窗口。
     *
     * @param leftActions  紧跟标题的操作区（例如菜单栏）；可为 null
     * @param rightActions 关闭按钮之前的操作区（例如当前工程名）；可为 null
     */
    public static UIElement create(SimuPackLayout.ScreenSize screenSize, Component title,
                                   UIElement leftActions, UIElement rightActions,
                                   UIElement content, Runnable closeAction) {
        UIElement root = SimuPackLayout.root(screenSize);

        UIElement window = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        }).addClass("simupack_window");

        window.addChild(titleBar(title, leftActions, rightActions, closeAction));
        window.addChild(content.layout(layout -> {
            layout.flex(1);
            layout.flexShrink(1);
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        }));
        root.addChild(window);
        return root;
    }

    /**
     * 标题文本宽度：与 {@link #create} 中实际使用的宽度保持一致。
     *
     * <p>必须用客户端字体真实测量：按「每字符固定像素」估算会低估中文标题宽度，
     * 导致顶栏菜单项的实际位置比计算值更靠右，下拉面板与菜单项错位。
     */
    public static int titleWidthFor(Component title) {
        int measured = net.minecraft.client.Minecraft.getInstance().font.width(title);
        return Math.max(40, Math.min(200, measured + 6));
    }

    private static UIElement titleBar(Component title, UIElement leftActions,
                                      UIElement rightActions, Runnable closeAction) {
        UIElement bar = SimuPackTheme.titleBar().layout(layout -> {
            layout.widthPercent(100);
            layout.height(TITLE_BAR_HEIGHT);
            layout.minWidth(0);
            layout.flexShrink(0);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.SPACE_BETWEEN);
        });

        int titleWidth = titleWidthFor(title);
        UIElement titleText = SimuPackLayout.text(title, titleWidth,
                SimuPackTheme.TEXT_PRIMARY_COLOR, TextTexture.TextType.LEFT, true);
        titleText.layout(layout -> {
            layout.width(titleWidth);
            layout.height(13);
            layout.minWidth(0);
            layout.flexShrink(0);
        });
        bar.addChild(titleText);

        if (leftActions != null) {
            bar.addChild(leftActions);
        }
        bar.addChild(SimuPackLayout.spacer(1));
        if (rightActions != null) {
            bar.addChild(rightActions);
        }
        bar.addChild(closeButton(closeAction));
        return bar;
    }

    private static Button closeButton(Runnable closeAction) {
        Button button = new Button();
        button.setOnClick(event -> {
            if (event.button == 0) {
                closeAction.run();
                event.stopPropagation();
            }
        });
        button.noText().buttonStyle(style -> style
                .baseTexture(Icons.CLOSE)
                .hoverTexture(Icons.CLOSE.copy().setColor(ColorPattern.LIGHT_GRAY.color))
                .pressedTexture(Icons.CLOSE.copy().setColor(ColorPattern.GRAY.color)));
        button.layout(layout -> {
            layout.width(CLOSE_BUTTON_SIZE);
            layout.height(CLOSE_BUTTON_SIZE);
            layout.flexShrink(0);
        });
        return button;
    }
}
