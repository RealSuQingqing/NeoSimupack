package com.rstech.simupack.client.ui.editor;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackMenuBar;
import com.rstech.simupack.client.ui.SimuPackTheme;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 编辑器通用的「控件工厂」：把「造一个已经配好的界面小部件」这件事集中在一处。
 *
 * <p>这些方法原本是 {@code IndustrialEditor} 的私有方法。它们的实现里<b>没有一行和「动作组」
 * 或任何具体分类有关</b>（表格行、按钮、分区标题、下拉、只读文本框），但因为是私有的，
 * 商业 / 公共的编辑器面板根本调不到。抽出本类就是为了让它们能被多个面板共用。
 *
 * <p><b>为什么需要 {@link Host} 而不是直接引用编辑器：</b>这些控件里有两个必须回到宿主才能做的事 ——
 * 一是「只读来源下不给出可编辑的文本框」（{@link #textField()}），二是「在下拉按钮被按下时
 * 把菜单弹在鼠标位置」（{@link #installDropdown}）。宿主只需提供这两件事，
 * 控件工厂因此不依赖任何具体编辑器类的状态。
 *
 * <p>约定（沿用重构前的既有做法，不要自行改动）：
 * <ul>
 *   <li>横向排布里的标签一律走 {@link SimuPackLayout#pinnedLabel} / {@code pinWidth} 钉死宽度 ——
 *       LDLib2 的 {@code Label} 在横向排布里会被量成 0 宽，文字会叠到相邻控件上；</li>
 *   <li>所有行高统一走 {@link #ROW_HEIGHT}，字段标签列宽统一走 {@link #LABEL_PERCENT}。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class EditorWidgets {

    /** 控件工厂需要宿主提供的两件事；见类注释。 */
    public interface Host {
        /** 当前建筑来自只读来源（拓展包内的定义）时为 true。 */
        boolean readOnly();

        /**
         * 在鼠标位置弹出下拉菜单。
         *
         * <p>传鼠标坐标而不是控件对象：控件可能位于带缩放／平移变换的画布里，
         * 用控件自身的布局坐标算出来的位置是错的。鼠标坐标本身就在 GUI 空间，
         * 宿主再换算到菜单宿主的局部坐标即可。
         */
        void openMenu(float mouseX, float mouseY, List<SimuPackMenuBar.Entry> entries);
    }

    /** 表单行的标准行高。 */
    public static final float ROW_HEIGHT = 14F;
    /** 字段标签列占父容器宽度的百分比。 */
    public static final float LABEL_PERCENT = 42F;
    /** 小按钮（✕ / ▾）的边长。 */
    public static final float SMALL_BUTTON = 14F;

    private final Host host;

    public EditorWidgets(Host host) {
        this.host = host;
    }

    // ── 基础控件 ───────────────────────────────────────────────────────

    /**
     * 按只读状态创建文本框：只读必须走 {@link SimuPackLayout.ReadOnlyField} 的
     * {@code isEditable()} 闸门，否则用户能在本该只读的拓展包上打字。
     */
    public TextField textField() {
        return host.readOnly() ? new SimuPackLayout.ReadOnlyField() : new CommitOnBlurField();
    }

    /**
     * 失焦也会提交的文本框。
     *
     * <p><b>为什么需要它</b>：LDLib2 的 {@code TextField} 会在值变化时触发
     * {@code setTextResponder}，但部分编辑器操作需要在用户直接点到别处时再做一次视图刷新。
     * 这里统一补上失焦提交和失焦刷新，避免输入中的控件被立即重建。
     *
     * <p><b>为什么做成子类而不是逐个补</b>：所有面板内联字段都出自
     * {@link #textField()} 这一个工厂，在这里挂一次就等于整套都改了；
     * 逐个调用点补的话，以后新增字段又会漏掉（这个会话里"两处只改了一处"已经栽过多次）。
     *
     * <p>注意对话框字段（{@code SimuPackDialog.field}）不走这个工厂 ——
     * 它们由「确定」按钮提交，语义不同，不做改动。
     */
    public static final class CommitOnBlurField extends TextField {
        private java.util.function.Consumer<String> responder;
        private String lastSubmittedText;
        private Runnable blurAction;

        public CommitOnBlurField() {
            addClass("simupack_editable_field");
            // 刷新面板会移除旧控件并触发 BLUR。只有文本真的变过才提交，
            // 避免旧控件在重建过程中把已经写入模型的新值再次覆盖掉。
            addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.BLUR,
                    event -> {
                        commitNow();
                        if (blurAction != null) {
                            blurAction.run();
                        }
                    });
        }

        /** Run a view update only after the user leaves this field. */
        public CommitOnBlurField setBlurAction(Runnable action) {
            this.blurAction = action;
            return this;
        }

        @Override
        public TextField setTextResponder(java.util.function.Consumer<String> value) {
            this.responder = value;
            TextField result = super.setTextResponder(text -> {
                lastSubmittedText = text;
                value.accept(text);
            });
            // 调用方通常先 setText，再安装 responder；此时建立初始基线。
            lastSubmittedText = getText();
            return result;
        }

        private void commitNow() {
            if (responder == null) {
                return;
            }
            String text = getText();
            if (text != null && !text.equals(lastSubmittedText)) {
                lastSubmittedText = text;
                responder.accept(text);
            }
        }
    }

    // ── 选物品（通用能力）──────────────────────────────────────────────

    /**
     * 「打开物品选择浮层」这个能力。
     *
     * <p><b>刻意抽成参数而不是加到 {@link Host}</b>：选物品和选点一样是通用能力
     * （商业报价、工业输入/产出、将来的住宅都可能用），控件不该因此依赖某个特定宿主接口。
     * 谁调用谁注入 —— 也正因为如此，{@code allowTags} 由<b>调用方</b>决定：
     * NSUK 工业收 {@code #tag}、商业不收，这是领域的差异，不是控件的差异。
     */
    @FunctionalInterface
    public interface PickOpener {
        void open(java.util.function.Consumer<String> onPick, boolean allowTags);
    }

    /** 物品图标的边长。 */
    public static final float ITEM_ICON_SIZE = 18F;

    /**
     * 物品格：一个图标（自带悬浮提示显示物品名）+ 一个「未注册」标记。
     *
     * <p>点图标打开选择浮层，选中后回调 id。
     *
     * @param itemId    当前值。空串表示未设置
     * @param opener    由调用方注入的选择浮层（见 {@link PickOpener}）
     * @param allowTags 列表里是否带 item tag
     * @param onPicked  选中后的落盘动作（历史、写入、重建都由调用方负责）
     */
    public UIElement itemCell(String itemId, PickOpener opener, boolean allowTags,
                             java.util.function.Consumer<String> onPicked) {
        UIElement cell = new UIElement();
        cell.layout(layout -> {
            layout.flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW);
            layout.alignItems(dev.vfyjxf.taffy.style.AlignItems.CENTER);
            layout.gapAll(1);
            layout.flexShrink(0);
        });

        // 「认不出来」的判断必须带领域：
        // 工业侧 "#minecraft:planks" 合法（NSUK 认），商业侧同样的字符串是<b>错</b>的
        // （商业完全没有标签分支），该报出来。所以用 allowTags 分派，而不是统一判一次。
        boolean unset = itemId == null || itemId.isBlank();
        boolean known = unset || (allowTags && ItemRegistry.isTagRef(itemId)
                ? ItemRegistry.isKnownTag(itemId)
                : ItemRegistry.isRegistered(itemId));

        UIElement iconBox = new UIElement();
        iconBox.layout(layout -> {
            layout.width(ITEM_ICON_SIZE);
            layout.height(ITEM_ICON_SIZE);
            layout.flexShrink(0);
            layout.justifyContent(org.appliedenergistics.yoga.YogaJustify.CENTER);
            layout.alignItems(dev.vfyjxf.taffy.style.AlignItems.CENTER);
        });
        UIElement child;
        if (known) {
            // 未设置时 stackOf 给空堆，画出来就是空槽 ——
            // 「还没选」和「选过了但认不出来」必须长得不一样。
            child = plainSlot(ItemRegistry.stackOf(itemId), ITEM_ICON_SIZE);
        } else {
            // 认不出来的画一个红色问号，画在图标框<b>里</b>，不是旁边。
            // 原来是空槽 + 旁边一个 ✗ + 原字符串：既把版面撑长，空槽又看起来像「还没设置」，
            // 两种完全不同的状态长得一样。原始字符串移进 tooltip —— 版面不留它，
            // 但排查时（例如文件里写了个拼错的 id）仍然读得到。
            child = SimuPackLayout.label(Component.literal("?"), SimuPackTheme.TEXT_ERROR_COLOR);
            iconBox.style(style -> style.tooltips(
                    Component.translatable("gui.simupack.item_picker.unknown", itemId)));
        }
        // 监听要挂在<b>框和子元素两处</b>：ItemSlot 自己会处理 MOUSE_DOWN（悬浮 / 拖拽），
        // 事件不一定冒泡到外层 —— 只挂框上时点图标毫无反应，表现为「选物品的弹窗打不开」。
        // 两处都挂则无论点在图标上还是框的空白处都能打开。
        installPick(iconBox, opener, allowTags, onPicked);
        installPick(child, opener, allowTags, onPicked);
        iconBox.addChild(child);
        cell.addChild(iconBox);
        return cell;
    }

    /** Block/tag cell with the same empty-slot shape as itemCell. */
    public UIElement blockCell(String blockId, PickOpener opener,
                               java.util.function.Consumer<String> onPicked) {
        UIElement cell = new UIElement();
        cell.layout(layout -> {
            layout.width(ITEM_ICON_SIZE); layout.height(ITEM_ICON_SIZE);
            layout.flexShrink(0); layout.justifyContent(org.appliedenergistics.yoga.YogaJustify.CENTER);
            layout.alignItems(dev.vfyjxf.taffy.style.AlignItems.CENTER);
        });
        List<ItemStack> tagMembers = blockTagMembers(blockId);
        UIElement child;
        if (!tagMembers.isEmpty()) {
            com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot slot = plainSlot(tagMembers.get(0), ITEM_ICON_SIZE);
            slot.slotStyle(style -> style.showItemTooltips(false));
            slot.style(style -> style.tooltips(Component.translatable("gui.simupack.block_picker.tag", blockId)));
            int[] shown = {0};
            slot.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.TICK, event -> {
                int index = (int) ((System.currentTimeMillis() / 1000L) % tagMembers.size());
                if (index != shown[0]) { shown[0] = index; slot.setItem(tagMembers.get(index)); }
            });
            child = slot;
        } else {
            ItemStack stack = blockStack(blockId);
            child = plainSlot(stack, ITEM_ICON_SIZE);
            if (blockId != null && !blockId.isBlank() && stack.isEmpty()) {
                child = SimuPackLayout.label(Component.literal("?"), SimuPackTheme.TEXT_ERROR_COLOR);
                cell.style(style -> style.tooltips(Component.translatable("gui.simupack.item_picker.unknown", blockId)));
            }
        }
        installPick(cell, opener, true, onPicked);
        installPick(child, opener, true, onPicked);
        cell.addChild(child);
        return cell;
    }

    private static List<ItemStack> blockTagMembers(String id) {
        if (id == null || id.isBlank()) return List.of();
        try {
            String location = id.startsWith("#") ? id.substring(1) : id;
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(location));
            return BuiltInRegistries.BLOCK.getTag(tag).map(set -> set.stream()
                    .map(holder -> holder.value().asItem())
                    .filter(item -> item != Items.AIR)
                    .map(ItemStack::new)
                    .toList()).orElse(List.of());
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static ItemStack blockStack(String id) {
        if (id == null || id.isBlank()) return ItemStack.EMPTY;
        try {
            if (id.startsWith("#")) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK,
                        ResourceLocation.parse(id.substring(1)));
                return BuiltInRegistries.BLOCK.getTag(tag).flatMap(set -> set.stream().findFirst())
                        .map(holder -> holder.value().asItem())
                        .filter(item -> item != Items.AIR)
                        .map(ItemStack::new).orElse(ItemStack.EMPTY);
            }
            Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).orElse(null);
            if (block != null && block.asItem() != Items.AIR) return new ItemStack(block.asItem());
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(id));
            return BuiltInRegistries.BLOCK.getTag(tag).flatMap(set -> set.stream().findFirst())
                    .map(holder -> holder.value().asItem())
                    .filter(item -> item != Items.AIR)
                    .map(ItemStack::new).orElse(ItemStack.EMPTY);
        } catch (RuntimeException exception) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 同上，但不列 item tag 的重载。
     *
     * <p>商业报价的物品字段就是这个情况：NSUK 商业侧完全不认标签，
     * 所以它不该在列表里看到标签。</p>
     */
    public UIElement itemCell(String itemId, PickOpener opener,
                             java.util.function.Consumer<String> onPicked) {
        return itemCell(itemId, opener, false, onPicked);
    }

    /**
     * 把「点一下打开选择浮层」挂到某个元素上。
     *
     * <p>抽成方法是因为{item@code itemCell} 要往<b>两个</b>元素上挂同一条逻辑
     * （外层框 + 里面的图标），原因见那里的注释：{@code ItemSlot} 会自己处理
     * {@code MOUSE_DOWN} 且不一定冒泡，只挂外层就会「点了没反应」。
     */
    private void installPick(UIElement element, PickOpener opener, boolean allowTags,
                            java.util.function.Consumer<String> onPicked) {
        element.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.MOUSE_DOWN, event -> {
            if (event.button != 0 || host.readOnly()) {
                return;
            }
            // 选择器是浮层，选中后会重建整个页签 —— 全程在事件派发之外做。
            opener.open(picked -> onPicked.accept(picked), allowTags);
        });
    }

    /**
     * 只显示图标、不带原版槽位外观的物品槽。
     *
     * <p>{@code showItemTooltips(true)} 必须显式写上：默认值本来就开着，
     * 但这里紧挨着两行「清空外观」，写出来才看得出<b>我们只清外观、没关提示</b>。
     */
    public static com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot plainSlot(
            net.minecraft.world.item.ItemStack stack, float size) {
        com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot slot =
                new com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot().setItem(stack);
        slot.layout(layout -> {
            layout.width(size);
            layout.height(size);
            layout.flexShrink(0);
        });
        slot.style(style -> style.backgroundTexture(
                com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY));
        slot.slotStyle(style -> style
                .slotOverlay(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY)
                .showItemTooltips(true));
        return slot;
    }

    /** 一行「标签 + 右侧控件」的骨架。 */
    public UIElement labelRow(String labelKey) {
        UIElement row = SimuPackLayout.row(2);
        row.layout(layout -> {
            layout.widthPercent(100);
            layout.height(ROW_HEIGHT);
            layout.minWidth(0);
            layout.flexShrink(0);
        });
        row.addChild(SimuPackLayout.pinWidthPercent(SimuPackLayout.shrinkable(
                SimuPackLayout.label(Component.translatable(labelKey), SimuPackTheme.TEXT_MUTED_COLOR)),
                LABEL_PERCENT));
        return row;
    }

    /** 「标签 + 文本框」的整行。 */
    public UIElement textRow(String labelKey, String value, Consumer<String> responder) {
        UIElement row = labelRow(labelKey);
        TextField text = textField();
        text.setAnyString();
        text.setText(value == null ? "" : value, false);
        text.setTextResponder(responder);
        text.layout(layout -> {
            layout.flex(1);
            layout.height(ROW_HEIGHT);
            layout.minWidth(0);
        });
        row.addChild(text);
        return row;
    }

    public Button textButton(String text, float width, Runnable action) {
        return textButton(text, width, action, true);
    }

    public Button textButton(String text, float width, Runnable action, boolean enabled) {
        Button button = new Button().setText(Component.literal(text));
        if (width > 0F) {
            SimuPackLayout.pinWidth(button, width);
        }
        button.layout(layout -> layout.height(ROW_HEIGHT));
        if (action != null) {
            button.setOnClick(event -> action.run());
        }
        if (!enabled) {
            button.setActive(false);
        }
        return button;
    }

    /** 换行的翻译标签。 */
    public UIElement wrapped(String key, int color) {
        return SimuPackLayout.wrappedLabel(Component.translatable(key), color);
    }

    /** 分区标题：比字段行亮一点，用来分隔「基本信息 / 职业 / 营业时间 / 交易」。 */
    public UIElement sectionHeader(String translationKey) {
        return SimuPackLayout.wrappedLabel(Component.translatable(translationKey),
                SimuPackTheme.TEXT_INFO_COLOR);
    }

    /**
     * 把一个按钮接成下拉菜单。
     *
     * <p><b>坐标必须取 {@code MOUSE_DOWN} 的 {@code event.x/y}，不能用 click 事件的。</b>
     * 点击是框架合成的，里面的坐标不可靠（实测留在 0），拿去换算菜单位置会落到窗口左上角，
     * 表现就是「下拉框点不动」。按下事件带的是真实 GUI 鼠标坐标，与画布滚轮缩放同源。
     */
    public void installDropdown(Button button, Supplier<List<SimuPackMenuBar.Entry>> supplier) {
        float[] press = new float[2];
        button.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button == 0) {
                press[0] = event.x;
                press[1] = event.y;
            }
        });
        button.setOnClick(event -> host.openMenu(press[0], press[1], supplier.get()));
    }
}
