package com.rstech.simupack.client.ui.editor;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackTheme;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.appliedenergistics.yoga.YogaOverflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 物品选择器：搜索框 + 图标网格（<b>虚拟化渲染</b>）。
 *
 * <p>存在的意义是让用户<b>永远不用手打物品 id</b>：JSON 里存的仍然是一个字符串，
 * 但界面上的交互是「点图标 → 搜 → 点图标」。这样拼错 id 这一类错误从根上消失
 * （NSUK 会因此把整条交易静默丢弃，是很难排查的坑）。
 *
 * <h2>为什么是虚拟化，而不是「异步加载」</h2>
 *
 * <p>先把一个常见误解说清楚：<b>UI 元素创建不能丢到后台线程</b>。
 * Minecraft / LDLib2 的 UI 不是线程安全的，元素必须在渲染线程上建造。
 * 所以「异步」在这里只有两种真实形态：
 *
 * <ol>
 *   <li><b>分摊到多帧</b>（每帧建一批）：弹窗能立刻出现，但总成本不变 ——
 *       之后每一帧仍要对全部元素做布局与绘制。是把卡顿摊薄，不是消除。</li>
 *   <li><b>根本不建</b>（虚拟化）：只建可视区那几十个格子，其余用上下占位撑出高度。
 *       元素总数与物品数<b>无关</b>，所以「显示全」这件事本身就变成免费的。</li>
 * </ol>
 *
 * <p>这里选 2。代价是需要自己维护「第几行到第几行可见」，并在滚动时重建那一小段；
 * 换来的是无论 100 个还是 5000 个物品，元素数量都在同一个量级。
 *
 * <h2>实现要点</h2>
 * <ul>
 *   <li><b>列数从布局里读回来</b>：第一版手算列数（按 {@code 0.78 × 窗口宽}）是错的，
 *       实测弹窗接近满宽。现在先让布局跑出真实宽度，再由 {@code width / CELL} 反推列数。</li>
 *   <li><b>行是一个个显式建的，不靠 {@code flexWrap}</b>：虚拟化必须知道「一共几行、
 *       现在在第几行」，自动折行会把这件事交给布局，我就算不出占位高度了。
 *       每行固定 {@code height = CELL}，于是
 *       {@code 上占位 + 可见行高 + 下占位} 恒等于 {@code 总行数 × CELL} ——
 *       <b>内容总高与可见窗口无关</b>，滚动条因此稳定，不会在滚动时抖动。</li>
 *   <li><b>列数宁可少算不可多算</b>：多算一列会让实际折行多出一行、总高对不上；
 *       少算只是右侧多留一点空白。所以减去一个滚动条宽度的余量。</li>
 *   <li><b>逐帧检查而不是监听滚动事件</b>：{@code UIEvents.TICK} 每帧来一次，
 *       读几个 float 比较一下就返回，代价可忽略；而滚动条拖动、滚轮、程序化跳转
 *       三种来源都会被它统一覆盖，不用分别接。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ItemPicker {

    /** 单个格子的边长。 */
    private static final float CELL = 18F;
    /** 网格区域的高度上下限（弹窗高度必须有限，否则它就不会滚）。 */
    private static final float MIN_GRID_HEIGHT = 90F;
    private static final float MAX_GRID_HEIGHT = 260F;
    /**
     * 纵向滚动条占的宽度余量 —— 列数从这里「少算」。
     *
     * <p>宁可少算一列（右边多留一点空白），也不能多算：多算会让实际折行比预期多一行，
     * 于是「上占位 + 可见行 + 下占位」不再等于总高，滚动条会来回抖。
     */
    private static final float SCROLLBAR_ALLOWANCE = 10F;
    /** 可视区上下各多建几行，避免每滚一像素就重建。 */
    private static final int BUFFER_ROWS = 2;

    /** 一个候选物品。{@code search} 是用于匹配的小写串（id + 本地化名）。 */
    private record Entry(String id, ItemStack stack, String search, boolean tag) {
    }

    private final Consumer<String> onPick;
    private final boolean blockMode;
    private final List<Entry> all;
    /** 当前关键词下的匹配结果（虚拟化只渲染它的可视段）。 */
    private List<Entry> matches;

    /**
     * 列表里是否含标签（= 构造时的 {@code allowTags}）。
     *
     * <p>用来把总行数<b>多算一行</b>：物品与标签交界处要换行（见 {@link #refreshWindow}），
     * 那一行会被浪费掉半截。多算一行只是底部多出一条空白滚动区，
     * 少算一行才会让最后的标签滚不到 —— 宁可多算。
     */
    private final boolean hasTags;

    private ScrollerView scroll;
    private UIElement topSpacer;
    private UIElement rows;
    private UIElement bottomSpacer;

    /** 已建窗口状态；{@code -1} 表示「需要重建」。 */
    private int builtColumns = -1;
    private int builtFirstRow = -1;
    private int builtLastRow = -1;

    public ItemPicker(Consumer<String> onPick) {
        this(onPick, false);
    }

    public static ItemPicker forBlocks(Consumer<String> onPick) {
        return new ItemPicker(onPick, false, true);
    }

    /**
     * @param allowTags 列表末尾是否追加 item tag。
     *
     * <p><b>由面板决定，不是用户选项。</b>NSUK 工业收标签（{@code "#minecraft:planks"}），
     * 商业不收（62 个商业类里没有一个用到 TagKey，{@code ResourceLocation.parse} 也过不了
     * {@code #}）—— 给商业用户选标签等于让他造出一笔永不成交的交易。
     * 证据与完整理由见 {@link ItemRegistry} 的标签那一节。
     */
    public ItemPicker(Consumer<String> onPick, boolean allowTags) {
        this(onPick, allowTags, false);
    }

    private ItemPicker(Consumer<String> onPick, boolean allowTags, boolean blockMode) {
        this.onPick = onPick;
        this.blockMode = blockMode;
        this.hasTags = allowTags || blockMode;
        // 每次打开都重新读一遍标签：标签集合会随数据包重载 / 启用禁用 / 切存档而变，
        // 而 ItemRegistry 里的标签缓存是静态的（见 invalidateTagCache 的注释）。
        // 放在这里而不是挂事件：标签只在这一刻之后被读，刷新的时机正好，且不依赖事件类名。
        ItemRegistry.invalidateTagCache();
        this.all = blockMode ? allBlockEntries() : allEntries(allowTags);
        this.matches = all;
    }

    /**
     * 构建选择器的内容（放进弹窗 body）。
     *
     * @param availableHeight 可用高度，用来算网格区高度
     */
    public UIElement build(float availableHeight) {
        float gridHeight = Math.max(MIN_GRID_HEIGHT,
                Math.min(MAX_GRID_HEIGHT, availableHeight * 0.45F));

        UIElement column = SimuPackLayout.column(2);

        TextField search = new TextField();
        search.setAnyString();
        search.textFieldStyle(style -> style
                .placeholder(Component.translatable("gui.simupack.item_picker.search"))
                .textColor(SimuPackTheme.TEXT_PRIMARY_COLOR));
        search.layout(layout -> {
            layout.widthPercent(100);
            layout.height(EditorWidgets.ROW_HEIGHT);
            layout.flexShrink(0);
        });

        UIElement holder = SimuPackLayout.column(0);
        holder.layout(layout -> layout.widthPercent(100));
        topSpacer = spacer();
        rows = SimuPackLayout.column(0);
        rows.layout(layout -> layout.widthPercent(100));
        bottomSpacer = spacer();
        holder.addChild(topSpacer);
        holder.addChild(rows);
        holder.addChild(bottomSpacer);

        // 网格必须放进滚动区，而且必须用 addScrollViewChild（见 CommercialTradePanel 里的同类注释）：
        // 用 addChild 会把内容挂到 ScrollerView 自己身上 —— 在视口之外，永不裁剪也永不滚动。
        scroll = SimuPackLayout.scroller();
        scroll.scrollerStyle(style -> style.horizontalScrollDisplay(ScrollDisplay.NEVER));
        scroll.layout(layout -> {
            layout.widthPercent(100);
            layout.height(gridHeight);
            layout.flexShrink(0);
        });
        scroll.addScrollViewChild(holder);

        search.setTextResponder(this::applyQuery);

        // 逐帧检查（理由见类注释）：宽度/滚动位置都只能在布局之后读到，
        // 而且滚动条拖动、滚轮、程序化跳转三种来源它都能覆盖。
        column.addEventListener(UIEvents.TICK, event -> refreshWindow());
        refreshWindow();

        column.addChild(search);
        column.addChild(scroll);
        return column;
    }

    /** 关键词变化：重算匹配集，回到顶部，然后立刻重建可视窗口。 */
    private void applyQuery(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            matches = all;
        } else {
            List<Entry> filtered = new ArrayList<>();
            for (Entry entry : all) {
                if (entry.search().contains(needle)) {
                    filtered.add(entry);
                }
            }
            matches = List.copyOf(filtered);
        }
        // 结果集变了，旧窗口没有意义；回到顶部再重建，避免停在越界的位置上。
        builtFirstRow = -1;
        builtLastRow = -1;
        builtColumns = -1;
        scroll.verticalScroller.setNormalizedValue(0F);
        refreshWindow();
    }

    /**
     * 按当前宽度与滚动位置，重建可视窗口。每帧被调用，所以必须便宜。
     *
     * <p>早期返回的条件是「宽度、列数、首行、末行都没变」——
     * 正常情况下每帧只是在比较几个 int。
     */
    private void refreshWindow() {
        float width = scroll.getSizeWidth() - SCROLLBAR_ALLOWANCE;
        if (width < CELL) {
            // 还没布局出宽度，下一帧再说。
            return;
        }
        int columns = Math.max(1, (int) (width / CELL));

        int total = matches.size();
        int totalRows = (total + columns - 1) / columns + (hasTags ? 1 : 0);
        float contentHeight = totalRows * CELL;
        float viewportHeight = scroll.getSizeHeight();
        float scrollable = Math.max(0F, contentHeight - viewportHeight);
        float offset = scroll.verticalScroller.getNormalizedValue() * scrollable;

        int firstRow = Math.max(0, (int) (offset / CELL) - BUFFER_ROWS);
        int visibleRows = (int) Math.ceil(Math.max(0F, viewportHeight) / CELL) + 1 + BUFFER_ROWS * 2;
        int lastRow = Math.min(totalRows - 1, firstRow + visibleRows - 1);

        if (columns == builtColumns && firstRow == builtFirstRow && lastRow == builtLastRow) {
            return;
        }
        builtColumns = columns;
        builtFirstRow = firstRow;
        builtLastRow = lastRow;

        // 上下占位撑出总高：三者的和恒等于 totalRows × CELL，与可见窗口无关。
        setHeight(topSpacer, firstRow * CELL);
        setHeight(bottomSpacer, Math.max(0, totalRows - lastRow - 1) * CELL);

        rows.clearAllChildren();
        if (lastRow < firstRow) {
            return;
        }
        int from = firstRow * columns;
        int to = Math.min(total, (lastRow + 1) * columns);
        // 第一个标签的下标。只在这里算一次（在早退之后），不放进每帧路径 ——
        // matches 有几千项，每帧扫一遍不值得。
        int tagStart = -1;
        for (int i = 0; i < total; i++) {
            if (matches.get(i).tag()) {
                tagStart = i;
                break;
            }
        }
        int index = from;
        while (index < to) {
            UIElement row = SimuPackLayout.row(0);
            row.layout(layout -> {
                layout.widthPercent(100);
                layout.height(CELL);
                layout.flexShrink(0);
                layout.overflow(YogaOverflow.HIDDEN);
            });
            for (int column = 0; column < columns && index < to; column++, index++) {
                // 物品与标签的交界处直接换行，让标签从新的一行开始。
                // 不这么做的话标签会紧跟在最后一个物品后面，看起来像"又一个物品"。
                if (index == tagStart && column > 0) {
                    break;
                }
                row.addChild(cell(matches.get(index)));
            }
            rows.addChild(row);
        }
    }

    private static UIElement spacer() {
        UIElement element = new UIElement();
        element.layout(layout -> {
            layout.widthPercent(100);
            layout.height(0F);
            layout.flexShrink(0);
        });
        return element;
    }

    private static void setHeight(UIElement element, float height) {
        element.layout(layout -> layout.height(Math.max(0F, height)));
    }

    /** 客户端注册表里的全部物品，按 id 排序（同模组的物品会挨在一起）。 */
    private static List<Entry> allItems() {
        List<Entry> entries = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            // 搜索同时认 id 与本地化名。本地化名跟随客户端语言，
            // 所以英文环境下它本来就是英文名 —— 不需要为「英文名」另开一路。
            String search = (id + " " + stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
            entries.add(new Entry(id.toString(), stack, search, false));
        }
        entries.sort(Comparator.comparing(Entry::id));
        return List.copyOf(entries);
    }

    /**
     * 选择栏的全部条目：物品在前，标签接在最后（{@code allowTags} 时）。
     *
     * <p>标签排在最后而不是混排：它们的数量是<b>几百</b>，混进物品里会把
     * 「按 id 排序、同模组挨在一起」这个物品区完全打散。
     *
     * <p>标签条目的 {@code stack} 为 null —— 它没有固定图标，
     * 由 {@link #cell} 每秒轮播一个成员物品，见那里的注释。
     */
    private static List<Entry> allBlockEntries() {
        List<Entry> entries = new ArrayList<>();
        for (var holder : BuiltInRegistries.BLOCK.holders().toList()) {
            var block = holder.value();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            // NSUK accepts every registered non-air block id, including technical
            // blocks without an item form. Do not drop those just because the picker
            // cannot render an item icon for them.
            if (id == null || id.equals(ResourceLocation.parse("minecraft:air"))) continue;
            ItemStack icon = block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
            entries.add(new Entry(id.toString(), icon, id.toString().toLowerCase(Locale.ROOT), false));
        }
        // Match the existing item picker: ordinary blocks first, block tags appended
        // as a separate section instead of being interleaved by global sorting.
        entries.sort(Comparator.comparing(Entry::id));
        List<Entry> tags = new ArrayList<>();
        BuiltInRegistries.BLOCK.getTagNames().sorted(Comparator.comparing(TagKey::location)).forEach(tag -> {
            String id = tag.location().toString();
            ItemStack icon = BuiltInRegistries.BLOCK.getTag(tag).flatMap(set -> set.stream().findFirst())
                    .map(holder -> holder.value().asItem()).filter(item -> item != Items.AIR)
                    .map(ItemStack::new).orElse(ItemStack.EMPTY);
            // Keep empty-icon tags too: tag existence, not the availability of an
            // item-form representative, determines whether NSUK can match them.
            tags.add(new Entry(id, icon, id.toLowerCase(Locale.ROOT), true));
        });
        entries.addAll(tags);
        return List.copyOf(entries);
    }

    private static List<Entry> allEntries(boolean allowTags) {
        if (!allowTags) {
            return allItems();
        }
        List<Entry> entries = new ArrayList<>(allItems());
        for (String tagRef : ItemRegistry.allTagRefs()) {
            entries.add(new Entry(tagRef, null, tagRef.toLowerCase(Locale.ROOT), true));
        }
        return List.copyOf(entries);
    }

    /** 一个格子：就一个 {@code ItemSlot}（它自带悬浮提示），点一下拾取。 */
    private UIElement cell(Entry entry) {
        ItemSlot slot = new ItemSlot();
        if (entry.tag()) {
            if (blockMode) installBlockCarousel(slot, entry.id());
            else installCarousel(slot, entry.id());
        } else {
            slot.setItem(entry.stack());
        }
        slot.layout(layout -> {
            layout.width(CELL);
            layout.height(CELL);
            layout.flexShrink(0);
        });
        // ItemSlot 没有 setOnClick（那是 Button 的），所以监听按下事件。
        slot.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button != 0) {
                return;
            }
            // 拾取会关掉浮层并重建面板内容 —— 那是在事件派发中拆元素树，
            // 推迟到本帧派发结束（与动作组画布落点、交易列表选中同一套理由）。
            // 用 tell：Minecraft.execute 在渲染线程的事件回调里是当场执行的，等于没推迟。
            // 配套：生效前要确认这个拾取框还是当前浮层，否则「先取消、后生效」
            // 会把物品写回一个已经关掉的框（见 Session.openItemPicker）。
            Minecraft.getInstance().tell(() -> onPick.accept(entry.id()));
        });
        return slot;
    }

    /** Block-tag cell: rotate member block items while retaining normal item tooltips. */
    private void installBlockCarousel(ItemSlot slot, String tagId) {
        // Block tags use the same presentation as item tags: the icon may rotate through
        // members, but the tooltip must identify the tag itself rather than the current member.
        slot.slotStyle(style -> style.showItemTooltips(false));
        slot.style(style -> style.tooltips(Component.translatable(
                "gui.simupack.block_picker.tag", tagId)));
        TagKey<net.minecraft.world.level.block.Block> tag = TagKey.create(
                net.minecraft.core.registries.Registries.BLOCK, ResourceLocation.parse(tagId));
        List<ItemStack> members = BuiltInRegistries.BLOCK.getTag(tag)
                .map(set -> set.stream().map(holder -> holder.value().asItem())
                        .filter(item -> item != Items.AIR).map(ItemStack::new).toList())
                .orElse(List.of());
        if (members.isEmpty()) return;
        int[] shown = {-1};
        slot.addEventListener(UIEvents.TICK, event -> {
            int index = (int) ((System.currentTimeMillis() / 1000L) % members.size());
            if (index == shown[0]) return;
            shown[0] = index;
            slot.setItem(members.get(index));
        });
        shown[0] = (int) ((System.currentTimeMillis() / 1000L) % members.size());
        slot.setItem(members.get(shown[0]));
    }

    /**
     * 标签格：图标每秒轮播该标签下的下一个物品。
     *
     * <p><b>为什么用轮播而不是加一个标记</b>：标签是一组物品，静态图标会让人以为它是单个物品。
     * 动起来这件事本身就是说明 —— 不需要额外文字或角标去解释「这是标签」。
     *
     * <p>两个刻意的做法：
     * <ul>
     *   <li><b>索引从墙上时钟推导，不自己累计。</b>{@code (秒数 / 1) % 成员数} ——
     *       没有计时器可漂移，所有标签格天然同步，滚动或切页签也不会错乱。</li>
     *   <li><b>只在索引真的变了才 {@code setItem}。</b>TICK 是每帧来的，
     *       每帧重设图标会让 {@code ItemSlot} 每帧重建渲染状态（也会让悬浮提示闪）。</li>
     * </ul>
     *
     * <p>监听挂在这个格子上而不是统一登记：格子随滚动被创建和销毁，
     * 监听跟着元素一起走，不需要额外的注销逻辑。
     */
    private void installCarousel(ItemSlot slot, String tagRef) {
        List<String> members = ItemRegistry.tagMembers(tagRef);
        if (members.isEmpty()) {
            // 空标签：不该出现在列表里（allTagRefs 已过滤），但真出现时不画错东西。
            return;
        }
        // 悬浮只说「这是个标签」。
        //
        // 两件事都要做，缺一不可：
        //   1. 关掉 ItemSlot 自带的物品提示 —— 它显示的是<b>当前轮播到的那一个成员</b>，
        //      不关的话就会和下面的类别提示并排成两行（用户看到的「甜浆果 / 类别：#c:foods」）。
        //      style.tooltips(...) 是追加而不是替换，所以必须显式关掉自带的那一条。
        //   2. 给标签自己的提示：类别 + 成员数。
        slot.slotStyle(style -> style.showItemTooltips(false));
        slot.style(style -> style.tooltips(Component.translatable(
                "gui.simupack.item_picker.tag", tagRef, members.size())));
        int[] shown = {-1};
        slot.addEventListener(UIEvents.TICK, event -> {
            int index = (int) ((System.currentTimeMillis() / 1000L) % members.size());
            if (index == shown[0]) {
                return;
            }
            shown[0] = index;
            slot.setItem(ItemRegistry.stackOf(members.get(index)));
        });
        // 首帧：TICK 之前先有个图标，否则会空一格。
        shown[0] = (int) ((System.currentTimeMillis() / 1000L) % members.size());
        slot.setItem(ItemRegistry.stackOf(members.get(shown[0])));
    }
}
