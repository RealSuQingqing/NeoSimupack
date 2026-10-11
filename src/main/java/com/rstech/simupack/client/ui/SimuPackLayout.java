package com.rstech.simupack.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 筑艺坊通用弹性布局工具。
 *
 * <p>布局约定与 NSUK 的城市核心界面保持一致，并额外处理了 flexbox 的一个关键陷阱：
 * <b>flex 子项默认 {@code min-width: auto}，其内容的最小宽度会压过显式设置的
 * {@code width}</b>。这会让固定宽度的分栏被内部长文本撑开，把整行挤出窗口。
 * 因此本类的固定尺寸辅助方法都会同时钉住 {@code width / minWidth / maxWidth}，
 * 弹性列则显式设置 {@code minWidth(0)} 以允许收缩。
 */
@OnlyIn(Dist.CLIENT)
public final class SimuPackLayout {
    private static final int MIN_SCREEN_WIDTH = 320;
    private static final int MIN_SCREEN_HEIGHT = 240;

    private SimuPackLayout() {
    }

    public static ScreenSize screenSize() {
        var window = Minecraft.getInstance().getWindow();
        return new ScreenSize(
                Math.max(MIN_SCREEN_WIDTH, window.getGuiScaledWidth()),
                Math.max(MIN_SCREEN_HEIGHT, window.getGuiScaledHeight())
        );
    }

    // ── 尺寸钉住 ───────────────────────────────────────────────────────

    /** 钉住元素宽度，防止 flex 内容最小宽度把它撑开。 */
    public static UIElement pinWidth(UIElement element, float width) {
        element.layout(layout -> {
            layout.width(width);
            layout.minWidth(width);
            layout.maxWidth(width);
            layout.flexShrink(0);
        });
        return element;
    }

    /**
     * 按父容器百分比钉住宽度。
     *
     * <p>分栏宽度必须用百分比而不是固定像素：Minecraft 的自动 GUI 缩放会让
     * 可用 GUI 宽度在小窗口下低至 495 甚至 320，固定像素列宽会直接溢出窗口。
     * 百分比列宽总和恒定，因此在任何缩放下都能放下。
     */
    public static UIElement pinWidthPercent(UIElement element, float percent) {
        element.layout(layout -> {
            layout.widthPercent(percent);
            layout.maxWidthPercent(percent);
            layout.minWidth(0);
            layout.flexShrink(0);
        });
        return element;
    }

    /** 允许元素宽度被压缩到 0（用于弹性列、可收缩文本）。 */
    public static UIElement shrinkable(UIElement element) {
        element.layout(layout -> {
            layout.minWidth(0);
            layout.minHeight(0);
        });
        return element;
    }

    /**
     * 钉住元素高度。
     *
     * <p>用于「顶部条」这类必须自报高度的区域：不钉的话 TabView 的内容容器会
     * {@code flex(1)} 把整块空间吃掉，把下面的画布挤没。
     */
    public static UIElement pinHeight(UIElement element, float height) {
        element.layout(layout -> {
            layout.height(height);
            layout.minHeight(height);
            layout.maxHeight(height);
            layout.flexShrink(0);
        });
        return element;
    }

    /** 单行不换行的横向容器：用在横向滚动条里（内容靠滚动查看，不折行）。 */
    public static UIElement hRow(int gap) {
        return new UIElement().layout(layout -> {
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(gap);
            // 刻意不设 flexWrap：默认就是 nowrap，内容才能超出视口触发横向滚动。
            layout.flexShrink(0);
        });
    }

    // ── 根与页面 ───────────────────────────────────────────────────────

    /** 根节点：铺满屏幕的列容器，自带全屏遮罩背景。 */
    public static UIElement root(ScreenSize screenSize) {
        UIElement root = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        });
        root.addChild(SimuPackTheme.createShellPanel(screenSize.width(), screenSize.height()));
        return root;
    }

    /**
     * 页面列：窗口内容区，纵向排列并带统一间距。
     *
     * <p>刻意不设置高度：{@link SimuPackWindowFrame} 已用 {@code flex(1)}
     * 把内容区限制在标题栏之下的剩余空间；若再写 {@code heightPercent(100)}，
     * 内容会比可用区域高出「标题栏 + 间距」，导致底部被裁切。
     */
    public static UIElement pageColumn(int gap) {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(gap);
        });
    }

    // ── 行与列 ─────────────────────────────────────────────────────────

    /** 顶部工具栏行：固定高度，不参与压缩。 */
    public static UIElement toolbarRow(int height, int gap) {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.height(height);
            layout.minWidth(0);
            layout.flexShrink(0);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(gap);
        });
    }

    /** 分栏行：占满 TabView 内容区的剩余高度，子项纵向拉伸。 */
    public static UIElement fillRow(int gap) {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flex(1);
            layout.flexShrink(1);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(gap);
        });
    }

    /** 固定宽度列：宽度、最小宽度、最大宽度一起钉住，确保不被内容撑开。 */
    public static UIElement fixedColumn(float width, int gap) {
        return pinWidth(new UIElement().layout(layout -> {
            layout.alignSelf(AlignItems.STRETCH);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(gap);
        }), width);
    }

    /** 弹性宽度列：占据行内剩余空间，允许收缩到 0。 */
    public static UIElement flexColumn(int gap) {
        return asFlexColumn(new UIElement(), gap);
    }

    /** 把固定宽度列布局应用到已有元素（例如主题面板）。 */
    public static UIElement asFixedColumn(UIElement element, float width, int gap) {
        element.layout(layout -> {
            layout.alignSelf(AlignItems.STRETCH);
            layout.minHeight(0);
            layout.flexShrink(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(gap);
        });
        return pinWidth(element, width);
    }

    /** 分栏分隔条宽度（同时决定可抓取范围）。 */
    public static final float SPLIT_BORDER = 3F;

    /**
     * 分栏面板：列布局 + 撑满高度，但<b>不设宽度</b>。
     *
     * <p>宽度交给 {@code SplitView} 统一管理。这里若再钉百分比宽度，会和分栏算出的
     * 宽度互相打架（表现为拖动后立刻回弹或内容溢出）。
     */
    public static UIElement asSplitPane(UIElement element, int gap) {
        element.layout(layout -> {
            layout.heightPercent(100);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(gap);
        });
        return element;
    }

    /**
     * 可拖拽横向分栏：{@code first} 占 {@code percentage}%，{@code second} 填满剩余。
     *
     * <p>拖拽是 LDLib2 {@code SplitView} 内建能力（含拖拽光标与鼠标捕获），无需自己实现。
     * 上下限放到 0/100，这样收起一格时可以真正收到底。
     */
    public static com.lowdragmc.lowdraglib2.gui.ui.elements.SplitView.Horizontal splitHorizontal(
            UIElement first, UIElement second, float percentage) {
        var split = new com.lowdragmc.lowdraglib2.gui.ui.elements.SplitView.Horizontal();
        split.setMinPercentage(0F);
        split.setMaxPercentage(100F);
        split.setBorderSize(SPLIT_BORDER);
        // 先装面板再设比例：setPercentage 依赖两个面板的尺寸。
        split.left(first).right(second);
        split.setPercentage(Math.max(0F, Math.min(100F, percentage)));
        split.layout(layout -> {
            layout.widthPercent(100);
            layout.flex(1);
            layout.flexShrink(1);
            layout.minWidth(0);
            layout.minHeight(0);
        });
        return split;
    }

    /** 把百分比宽度列布局应用到已有元素（例如主题面板）。 */
    public static UIElement asPercentColumn(UIElement element, float percent, int gap) {
        element.layout(layout -> {
            layout.alignSelf(AlignItems.STRETCH);
            layout.minHeight(0);
            layout.flexShrink(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(gap);
        });
        return pinWidthPercent(element, percent);
    }

    /** 把弹性宽度列布局应用到已有元素（例如主题面板）。 */
    public static UIElement asFlexColumn(UIElement element, int gap) {
        element.layout(layout -> {
            layout.flex(1);
            layout.flexShrink(1);
            layout.alignSelf(AlignItems.STRETCH);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(gap);
        });
        return element;
    }

    /** 纵向列容器（面板内部）。 */
    public static UIElement column(int gap) {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(gap);
        });
    }

    /** 横向行容器（面板内部）。 */
    public static UIElement row(int gap) {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(gap);
        });
    }

    /** 可换行的按钮组。 */
    public static UIElement buttonRow(int gap) {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.START);
            layout.flexWrap(FlexWrap.WRAP);
            layout.gapAll(gap);
        });
    }

    /** 子项在列内占满剩余高度，并允许压缩。 */
    public static UIElement fill(UIElement element) {
        element.layout(layout -> {
            layout.widthPercent(100);
            layout.flex(1);
            layout.flexShrink(1);
            layout.minWidth(0);
            layout.minHeight(0);
        });
        return element;
    }

    // ── 占位 ───────────────────────────────────────────────────────────

    /** 占据剩余空间的弹性空白。 */
    public static UIElement spacer(float flex) {
        return new UIElement().layout(layout -> {
            layout.flex(flex);
            layout.minWidth(0);
        });
    }

    /** 固定高度的空白。 */
    public static UIElement fixedSpacer(float height) {
        return new UIElement().layout(layout -> {
            layout.height(height);
            layout.flexShrink(0);
        });
    }

    public static UIElement absoluteRegion(int left, int top, int width, int height) {
        return new UIElement().layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(left);
            layout.top(top);
            layout.width(Math.max(1, width));
            layout.height(Math.max(1, height));
        });
    }

    // ── 文本 ───────────────────────────────────────────────────────────

    /** 使用 TextTexture 绘制文字，适合标题栏等需要精确控制的场合。 */
    public static UIElement text(Component text, int width, int color,
                                 TextTexture.TextType type, boolean dropShadow) {
        UIElement element = new UIElement().setAllowHitTest(false);
        element.layout(layout -> {
            layout.minWidth(0);
            layout.minHeight(0);
        });
        element.style(style -> style.backgroundTexture(new TextTexture(text.getString())
                .setWidth(width)
                .setType(type)
                .setColor(color)
                .setDropShadow(dropShadow)));
        return element;
    }

    /** 可翻译文本标签（可收缩，避免把容器撑开）。 */
    public static Label label(Component text, int color) {
        Label label = new Label();
        label.setValue(text);
        label.textStyle(style -> style.textColor(color).textShadow(true));
        label.layout(layout -> {
            layout.minWidth(0);
            layout.minHeight(0);
        });
        return label;
    }

    /**
     * 自动换行的说明文本标签。
     *
     * <p><b>{@code adaptiveHeight} 只管高度，不会触发换行</b>——真正的换行开关是
     * {@code textWrap(TextWrap.WRAP)}。少了它，长句会被拉成一行然后被容器裁掉
     * （表现为「文字被截断」）。默认值是 {@code NONE}，所以必须显式设。
     */
    public static Label wrappedLabel(Component text, int color) {
        Label label = new Label();
        label.setValue(text);
        label.textStyle(style -> style
                .textColor(color)
                .textShadow(true)
                .textWrap(TextWrap.WRAP)
                .adaptiveHeight(true));
        label.layout(layout -> {
            layout.widthPercent(100);
            layout.maxWidthPercent(100);
            layout.minWidth(0);
            layout.minHeight(0);
        });
        return label;
    }

    /**
     * 放在横向行里、占满剩余宽度并自动换行的文本。
     *
     * <p>横向行里的普通标签会被量成 0 宽，后面的控件就画到文字上；而 {@link #wrappedLabel}
     * 又写死了 {@code widthPercent(100)}，放进行里会把整行吃掉。这里取两者之间：
     * 用 {@code flex(1)} 占剩余宽度，同时打开真正的换行。
     */
    public static Label flexWrappedLabel(Component text, int color) {
        Label label = new Label();
        label.setValue(text);
        label.textStyle(style -> style
                .textColor(color)
                .textShadow(true)
                .textWrap(TextWrap.WRAP)
                .adaptiveHeight(true));
        label.layout(layout -> {
            layout.flex(1);
            layout.minWidth(0);
            layout.minHeight(0);
        });
        return label;
    }

    /**
     * 粗略估算文本像素宽度。实现见 {@link TextFitting#textWidth}（脱机可测）。
     */
    public static float textWidth(String text) {
        return TextFitting.textWidth(text);
    }

    /**
     * 把文本截断到 {@code maxWidth} 像素以内，超出部分用省略号代替。
     * 实现见 {@link TextFitting#fit}（脱机可测）。
     */
    public static String fitText(String text, float maxWidth) {
        return TextFitting.fit(text, maxWidth);
    }

    /**
     * 钉住宽度的标签：用于<b>横向排布</b>里必须自己占住位置的地方。
     *
     * <p>横向行里只写 {@code minWidth(0)} 时，标签在部分布局下会被量成 0 宽，
     * 后续控件就画到它头上（表现为文字重叠）。这里按文本长度钉死宽度并禁止收缩。
     */
    public static Label pinnedLabel(Component text, int color) {
        Label label = label(text, color);
        float width = textWidth(text.getString());
        label.layout(layout -> {
            layout.width(width);
            layout.minWidth(width);
            layout.maxWidth(width);
            layout.flexShrink(0);
        });
        return label;
    }

    // ── TabView ────────────────────────────────────────────────────────

    /**
     * 创建宽度与高度都受约束的 TabView。
     *
     * <p>必须显式约束 TabView 的内部容器：
     * <ul>
     *   <li>{@code tabContentContainer} 默认按内容计算尺寸——中文标签没有空格，
     *       最小内容宽度等于整行宽度，会让容器比屏幕还宽；同时高度不确定会让
     *       子元素的 {@code heightPercent(100)} 失效，面板被内容撑高、整页装不下，
     *       滚动区也因不溢出而不显示滚动条。这里用 {@code flex(1)} 让内容容器
     *       拿到确定高度。</li>
     * </ul>
     */
    public static com.lowdragmc.lowdraglib2.gui.ui.elements.TabView constrainedTabs() {
        var tabs = new com.lowdragmc.lowdraglib2.gui.ui.elements.TabView();
        tabs.layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
        });
        tabs.tabHeaderContainer(container -> container.layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.flexShrink(0);
        }));
        tabs.tabScroller(scroller -> scroller.layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.flexShrink(0);
        }));
        tabs.tabContentContainer(container -> container.layout(layout -> {
            layout.widthPercent(100);
            layout.flex(1);
            layout.flexShrink(1);
            layout.minWidth(0);
            layout.minHeight(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        }));
        return tabs;
    }

    // ── 滚动区域 ───────────────────────────────────────────────────────

    /**
     * 横向滚动区域：内容单行不换行，超出视口的部分靠横向滚动查看。
     *
     * <p>与 {@link #scroller()} 的关键差别：这里的 viewContainer <b>不能</b>设
     * {@code widthPercent(100)}，否则它会被钉在视口宽上、内容永远不溢出、滚动条也就不会出现。
     * 所以 viewContainer 按内容自适应宽度（{@code adaptiveWidth(true)}）。
     * 滚轮横向滚动是支持的：{@code ScrollerView} 的滚轮处理同时读 {@code deltaX} 与 {@code deltaY}。
     */
    public static ScrollerView hScroller() {
        ScrollerView scroller = new ScrollerView();
        scroller.getScrollerViewStyle().mode(ScrollerMode.HORIZONTAL);
        scroller.getScrollerViewStyle().adaptiveWidth(true);
        scroller.layout(layout -> {
            layout.widthPercent(100);
            layout.flex(1);
            layout.flexShrink(1);
            layout.minWidth(0);
            layout.minHeight(0);
        });
        scroller.viewContainer(container -> container.layout(layout -> {
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(3);
            layout.flexShrink(0);
        }));
        return scroller;
    }

    /**
     * 纵向滚动区域，内容按列排列。
     *
     * <p><b>必须锁死「只纵向滚动」。</b>{@code ScrollerView} 的 viewContainer 宽度是
     * {@code max(视口宽, 内容最大宽)}；里面的子元素若用 {@code widthPercent(100)}，
     * 就是按这个被撑开的宽度解析，于是子元素永远不换行 → 内容更宽 → 容器更宽，
     * 自我放大。表现出来就是「文字叠在一起、标签被压成 0 宽、积木标题栏冲出画布」。
     * 把滚动模式设成 {@link ScrollerMode#VERTICAL} 并关掉 {@code adaptiveWidth}，
     * viewContainer 的宽度就钉死在视口上，{@code widthPercent(100)} 才有确定含义、
     * 内部的可换行容器（{@code buttonRow} 等）才会真正换行。
     */
    public static ScrollerView scroller() {
        ScrollerView scroller = new ScrollerView();
        scroller.getScrollerViewStyle().mode(ScrollerMode.VERTICAL);
        scroller.getScrollerViewStyle().adaptiveWidth(false);
        scroller.layout(layout -> {
            layout.widthPercent(100);
            layout.flex(1);
            layout.flexShrink(1);
            layout.minWidth(0);
            layout.minHeight(0);
        });
        scroller.viewContainer(container -> container.layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.maxWidthPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(4);
        }));
        return scroller;
    }

    // ── 只读输入 ───────────────────────────────────────────────────────

    /**
     * 只读文本框：LDLib2 没有只读开关，但 {@code isEditable()} 是输入的统一闸门。
     *
     * <p>不要在输入框上用 {@code setActive(false)} 代替——那只管控件是否响应，
     * 管不住输入。按钮才用 {@code setActive(false)}。
     */
    public static final class ReadOnlyField extends TextField {
        @Override
        public boolean isEditable() {
            return false;
        }
    }

    /**
     * 只读多行文本框，闸门同样是 {@code isEditable()}。
     *
     * <p>{@link TextArea} 并不继承 {@link TextField}，所以需要单独一份。
     */
    public static final class ReadOnlyTextArea extends TextArea {
        @Override
        public boolean isEditable() {
            return false;
        }
    }

    public record ScreenSize(int width, int height) {
    }
}
