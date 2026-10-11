package com.rstech.simupack.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * VS Code 风格的顶栏菜单与右键菜单。
 *
 * <p>菜单项布局参照 VS Code：左侧是操作文字，右侧是快捷键；不可用的操作置灰且不响应点击。
 * 顶栏只占一行文字宽度，操作集中在下拉面板中；下拉面板支持二级菜单
 * （例如「视图 → 界面缩放 → 1x/2x/…/自动」）。
 *
 * <p>坐标约定：{@code dropdownHost} 的局部坐标。右键菜单的调用方需要用
 * {@code 世界坐标 - dropdownHost.getPositionX()/getPositionY()} 换算
 * （内容区没有变换时 {@code worldToLocal} 是恒等的，直接用会整体偏移）。
 */
@OnlyIn(Dist.CLIENT)
public final class SimuPackMenuBar {
    public static final float TRIGGER_WIDTH = 32F;
    private static final float TRIGGER_HEIGHT = 14F;
    private static final float ROW_HEIGHT = 14F;
    private static final float ROW_GAP = 1F;
    private static final float PANEL_PADDING = 2F;
    /** 面板宽度上下限：太窄放不下文字，太宽会显得空。 */
    private static final float MIN_PANEL_WIDTH = 64F;
    private static final float MAX_PANEL_WIDTH = 150F;
    /** 菜单行内左右内边距（与 ore.lss 里 .simupack_menu_row 的 padding 保持一致）。 */
    private static final float ROW_PADDING = 5F;
    /** 快捷键列固定宽度（放得下 Ctrl+Shift+Z，并保证各项右对齐在同一竖线上）。 */
    private static final float SHORTCUT_MAX_WIDTH = 66F;

    /** 菜单项。{@code enabled} 在每次打开菜单时求值，因此可以反映实时状态。 */
    public record Entry(String labelKey, String shortcut, Runnable action,
                        List<Entry> children, BooleanSupplier enabled) {
        public static Entry item(String labelKey, Runnable action) {
            return new Entry(labelKey, "", action, List.of(), () -> true);
        }

        public static Entry item(String labelKey, String shortcut, Runnable action, BooleanSupplier enabled) {
            return new Entry(labelKey, shortcut, action, List.of(), enabled);
        }

        public static Entry submenu(String labelKey, List<Entry> children) {
            return new Entry(labelKey, "", null, List.copyOf(children), () -> true);
        }

        /** 分组分隔线。 */
        public static Entry separator() {
            return new Entry("", "", null, List.of(), () -> false);
        }

        boolean isSeparator() {
            return labelKey.isEmpty() && action == null && children.isEmpty();
        }

        boolean hasChildren() {
            return !children.isEmpty();
        }
    }

    private final UIElement bar;
    private final UIElement dropdownHost;
    private final float startX;

    private UIElement openPanel;
    private UIElement openSubPanel;
    /** 点击捕获层：点面板以外的区域才收起，避免误关闭导致「选不上」。 */
    private UIElement clickCatcher;
    private String openKey = "";
    private int menuCount;

    public SimuPackMenuBar(UIElement dropdownHost, float startX) {
        this.dropdownHost = dropdownHost;
        this.startX = startX;
        this.bar = SimuPackLayout.shrinkable(SimuPackLayout.buttonRow((int) ROW_GAP));
        this.bar.layout(layout -> layout.flexShrink(0));
    }

    public UIElement bar() {
        return bar;
    }

    /** 追加一个顶级菜单。 */
    public void addMenu(String key, String labelKey, List<Entry> entries) {
        float left = startX + menuCount * (TRIGGER_WIDTH + ROW_GAP);
        menuCount++;

        Button trigger = new Button().setText(Component.translatable(labelKey));
        trigger.addClass("simupack_menu_trigger");
        SimuPackLayout.pinWidth(trigger, TRIGGER_WIDTH);
        trigger.layout(layout -> layout.height(TRIGGER_HEIGHT));
        trigger.setOnClick(event -> {
            boolean wasOpen = key.equals(openKey);
            close();
            if (!wasOpen) {
                openKey = key;
                installClickCatcher();
                openPanel = buildPanel(left, 0F, entries);
                dropdownHost.addChild(openPanel);
            }
        });
        bar.addChild(trigger);
    }

    public void close() {
        closeSubPanel();
        if (openPanel != null) {
            dropdownHost.removeChild(openPanel);
            openPanel = null;
        }
        if (clickCatcher != null) {
            dropdownHost.removeChild(clickCatcher);
            clickCatcher = null;
        }
        openKey = "";
    }

    private void closeSubPanel() {
        if (openSubPanel != null) {
            dropdownHost.removeChild(openSubPanel);
            openSubPanel = null;
        }
    }

    /** 在指定位置弹出右键菜单（坐标为 {@code dropdownHost} 局部坐标）。 */
    public void openContextMenu(float left, float top, List<Entry> entries) {
        close();
        openKey = "context";
        installClickCatcher();
        openPanel = buildPanel(left, top, entries);
        dropdownHost.addChild(openPanel);
    }

    private void installClickCatcher() {
        if (clickCatcher != null) {
            return;
        }
        clickCatcher = new UIElement();
        clickCatcher.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(0);
            layout.top(0);
            layout.widthPercent(100);
            layout.heightPercent(100);
        });
        clickCatcher.style(style -> style.zIndex(250));
        clickCatcher.addEventListener(UIEvents.MOUSE_DOWN, event -> close());
        dropdownHost.addChild(clickCatcher);
    }

    // ── 面板构建 ───────────────────────────────────────────────────────

    /**
     * 二级菜单的横向位置。
     *
     * <p>默认贴在父面板右侧；若空间不足则翻到左侧，避免被窗口右边界裁切。
     */
    private float submenuLeft(float panelLeft, float parentWidth) {
        float right = panelLeft + parentWidth + MAX_PANEL_WIDTH;
        if (right > dropdownHost.getSizeWidth()) {
            return Math.max(2F, panelLeft - MAX_PANEL_WIDTH);
        }
        return panelLeft + parentWidth;
    }

    /**
     * 按内容算出面板宽度。
     *
     * <p>固定宽度会让「只有一项短文字」的菜单显得很空（例如右键的「新建建筑」），
     * 所以用字体实测每项宽度，再夹在上下限之间。
     */
    private static float measurePanelWidth(List<Entry> entries) {
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        int maxLabel = 0;
        int maxShortcut = 0;
        boolean anyShortcut = false;
        for (Entry entry : entries) {
            if (entry.isSeparator()) {
                continue;
            }
            int labelWidth = font.width(labelOf(entry));
            maxLabel = Math.max(maxLabel, labelWidth);
            if (!entry.shortcut().isEmpty()) {
                anyShortcut = true;
                maxShortcut = Math.max(maxShortcut, font.width(Component.literal(entry.shortcut())));
            }
        }
        float width = maxLabel + 2F * ROW_PADDING + 2F * PANEL_PADDING;
        if (anyShortcut) {
            width = Math.max(width, maxLabel + ROW_GAP + maxShortcut + 2F * ROW_PADDING + 2F * PANEL_PADDING);
        }
        return Math.max(MIN_PANEL_WIDTH, Math.min(MAX_PANEL_WIDTH, width));
    }

    /** 菜单项左侧文字（与 buildRow 里保持一致）。 */
    private static Component labelOf(Entry entry) {
        return Component.literal(Component.translatable(entry.labelKey()).getString()
                + (entry.hasChildren() ? "  >" : ""));
    }

    private UIElement buildPanel(float left, float top, List<Entry> entries) {
        float panelWidth = measurePanelWidth(entries);
        UIElement panel = new UIElement().addClass("simupack_menu");
        panel.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(left);
            layout.top(top);
            // 宽度按内容算好后钉死：绝对定位元素若宽度被当成不确定，
            // 子项的百分比会解析到内容区宽度，快捷键就会被推到面板外。
            layout.width(panelWidth);
            layout.minWidth(panelWidth);
            layout.maxWidth(panelWidth);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(ROW_GAP);
        });
        panel.style(style -> style.zIndex(300));

        float rowWidth = panelWidth - 2F * PANEL_PADDING;
        float cursorY = top + PANEL_PADDING;
        for (Entry entry : entries) {
            float rowHeight = entry.isSeparator() ? 5F : ROW_HEIGHT;
            panel.addChild(buildRow(entry, left, cursorY, rowHeight, rowWidth));
            cursorY += rowHeight + ROW_GAP;
        }
        return panel;
    }

    private UIElement buildRow(Entry entry, float panelLeft, float rowY, float rowHeight,
                               float rowWidth) {
        if (entry.isSeparator()) {
            UIElement line = new UIElement().addClass("simupack_menu_separator");
            line.layout(layout -> {
                layout.width(rowWidth);
                layout.minWidth(rowWidth);
                layout.maxWidth(rowWidth);
                layout.height(1);
                layout.flexShrink(0);
            });
            return line;
        }

        boolean enabled = entry.enabled() != null && entry.enabled().getAsBoolean();
        boolean hasShortcut = !entry.shortcut().isEmpty();
        UIElement row = new UIElement()
                .addClass(enabled ? "simupack_menu_row" : "simupack_menu_row_disabled");
        row.layout(layout -> {
            layout.width(rowWidth);
            layout.minWidth(rowWidth);
            layout.maxWidth(rowWidth);
            layout.height(rowHeight);
            layout.flexShrink(0);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
        });
        if (enabled) {
            // 手动处理 hover 高亮，避免依赖伪类支持差异。
            row.addEventListener(UIEvents.MOUSE_ENTER, event -> row.style(style -> style.background(
                    new com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture(0xFF3A5A7A))));
            row.addEventListener(UIEvents.MOUSE_LEAVE, event -> row.style(style -> style.background(
                    new com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture(0x00000000))));
        }

        // 行内的实际可用宽度要扣掉 ore.lss 给 .simupack_menu_row 设的左右内边距。
        float contentWidth = rowWidth - 2F * ROW_PADDING;
        // 快捷键／对号的宽度必须按实际文字量，不能一律扣 SHORTCUT_MAX_WIDTH：
        // 短标记（比如「✓」只有几个像素）会把标签宽度算成负数，标记就被挤出面板外。
        float shortcutWidth = hasShortcut
                ? Math.min(SHORTCUT_MAX_WIDTH,
                        Math.max(8F, SimuPackLayout.textWidth(entry.shortcut())))
                : 0F;
        UIElement label = SimuPackLayout.shrinkable(SimuPackLayout.label(
                labelOf(entry),
                enabled ? SimuPackTheme.TEXT_SECONDARY_COLOR : SimuPackTheme.TEXT_MUTED_COLOR));
        float labelWidth = hasShortcut
                ? Math.max(8F, contentWidth - shortcutWidth - ROW_GAP)
                : contentWidth;
        label.layout(layout -> {
            layout.width(labelWidth);
            layout.minWidth(labelWidth);
            layout.maxWidth(labelWidth);
            layout.flexShrink(0);
        });
        row.addChild(label);

        if (hasShortcut) {
            // 先在 Label 上设右对齐（shrinkable 返回的是 UIElement，拿不到 textStyle）。
            com.lowdragmc.lowdraglib2.gui.ui.elements.Label shortcutLabel = SimuPackLayout.label(
                    Component.literal(entry.shortcut()), SimuPackTheme.TEXT_MUTED_COLOR);
            shortcutLabel.textStyle(style -> style.textAlignHorizontal(
                    com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal.RIGHT));
            UIElement shortcut = SimuPackLayout.shrinkable(shortcutLabel);
            shortcut.layout(layout -> {
                layout.width(shortcutWidth);
                layout.minWidth(shortcutWidth);
                layout.maxWidth(shortcutWidth);
                layout.flexShrink(0);
            });
            row.addChild(shortcut);
        }

        if (!enabled) {
            // 置灰项不注册任何点击行为。
            return row;
        }

        if (entry.hasChildren()) {
            final float parentWidth = rowWidth + 2F * PANEL_PADDING;
            row.addEventListener(UIEvents.MOUSE_ENTER, event -> {
                closeSubPanel();
                openSubPanel = buildPanel(submenuLeft(panelLeft, parentWidth), rowY, entry.children());
                dropdownHost.addChild(openSubPanel);
            });
        } else {
            row.addEventListener(UIEvents.MOUSE_DOWN, event -> {
                if (event.button == 0) {
                    close();
                    if (entry.action() != null) {
                        entry.action().run();
                    }
                }
            });
        }
        return row;
    }
}
