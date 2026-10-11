package com.rstech.simupack.client.ui.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import org.appliedenergistics.yoga.YogaOverflow;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackMenuBar;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.creation.commercial.CommercialContainersModel;
import com.rstech.simupack.creation.commercial.CommercialOfferModel;
import com.rstech.simupack.creation.json.DefinitionField;
import com.rstech.simupack.creation.json.DefinitionIssue;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.TaffyPosition;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 商业建筑的编辑器面板：中间区提供「商业交易」编辑器，
 * 右栏在通用分类设定表单之后补一节入口按钮。
 *
 * <p>本类只服务商业（见 {@link #supports}）。通用部分（分类设定表单、只读判定、
 * 写回管线、控件工厂、校验面板骨架、问题码命名空间）都在
 * {@link DomainPanel} / {@link EditorPanel} 里。
 *
 * <p><b>数据层是 {@link CommercialOfferModel}，它包在与 {@code BuildingJsonDocument}
 * 同一个根对象上</b>（见 {@link #afterModelParsed}），所以右栏改「定义 id」与这里改
 * 「交易」动的是同一棵树 —— 这条不变量由结构保证。
 *
 * <p>界面分两栏：左边是交易列表（导航），右边是当前交易的详情。
 * 列表只做导航不承载编辑控件，避免交易一多就把详情挤没。
 *
 * <p><b>本阶段（阶段 2）的范围</b>：列表 + 详情骨架（id / 可见性 / 形态 / 只读摘要）。
 * 成本与产出的增删改（阶段 3）、库存编辑（阶段 4）、物品选择器（阶段 5）随后接入。
 */
@OnlyIn(Dist.CLIENT)
public final class CommercialTradePanel extends DomainPanel {

    private static final float TOOLBAR_HEIGHT = 16F;
    private static final float ROW_HEIGHT = EditorWidgets.ROW_HEIGHT;
    /** 小按钮（✕ / ▾）的边长。 */
    private static final float SMALL_BUTTON = EditorWidgets.SMALL_BUTTON;
    /** 左栏（交易列表）占中间区的宽度百分比 —— 用百分比而非固定像素，见 SimuPackLayout.pinWidthPercent。 */
    private static final float LIST_WIDTH_PERCENT = 34F;

    /** 写入 {@code id} 用的字段描述（别名归一与空值删除都由 JsonEditTransaction 负责）。 */
    private static final DefinitionField FIELD_ID = DefinitionField.text("id", "");
    /** 写入 {@code visibleTo} 用的字段描述；别名 {@code visible_to}/{@code visibility} 会被清掉。 */
    private static final DefinitionField FIELD_VISIBLE =
            DefinitionField.text("visibleTo", "player", "visible_to", "visibility");

    /** 可见性的三个规范值（顺序即下拉顺序）。 */
    private static final String[] VISIBILITIES = {"player", "npc", "mixed"};

    /**
     * 新建物品资源时用的初值。
     *
     * <p>必须是一个**已注册**的物品：{@code CommercialResource.valid()} 会查注册表，
     * 写一个不存在的 id 会让这条资源无效、进而整条交易被 NSUK 丢弃。
     * 圆石是最不会引起误会的中性选择；用户点图标就能换成想要的。
     */
    private static final String DEFAULT_ITEM = "minecraft:stone";

    /** 数值输入框宽度（放得下 5 位数与小数）。 */
    private static final float NUMBER_WIDTH = 46F;
    /** 数量输入框宽度（比数值更窄，数量就那么几位）。 */
    private static final float COUNT_WIDTH = 34F;
    /** 物品图标（ItemSlot）的边长；详情栏里的物品单元格用这个尺寸。 */
    private static final float ICON_SIZE = 18F;
    /**
     * 左栏列表里图标的边长。
     *
     * <p>比详情栏大一点：列表是「一眼扫过去」的地方，而且卡片本身有内边距
     * （见 {@link #CARD_PADDING}），图标太小会显得浮在一个空盒子里。
     */
    private static final float LIST_ICON_SIZE = 20F;
    /**
     * {@code ore.lss} 里 {@code .simupack_card} 的 {@code padding-all}。
     *
     * <p>把「想给图标多大」换算成「行高要给多少」时必须算上它，
     * 否则图标会被卡片内边距挤到，甚至被行的 {@code overflow(HIDDEN)} 裁掉。
     */
    private static final float CARD_PADDING = 6F;
    /**
     * 左栏列表行高 = 图标 + 卡片上下内边距，于是<b>图标正好填满卡片内侧</b>。
     *
     * <p>写成算式而不是写死一个数：改图标大小时行高自动跟着变，
     * 不会再出现「图标小小一个浮在大盒子里」。
     */
    private static final float LIST_ROW_HEIGHT = LIST_ICON_SIZE + CARD_PADDING * 2F;
    /**
     * 卡片内容相对分区标题的缩进量。
     *
     * <p>必须与 {@code ore.lss} 里 {@code .simupack_card} 的 {@code padding-all} 一致：
     * 同一个分区里，卡片里的按钮（在卡内）和卡外的按钮（如「新建容器」）要落在同一条竖线上，
     * 否则看起来差半格。改 LSS 那个值时要一起改这里。
     */
    private static final float CARD_INSET = 6F;
    /**
     * 资金的图标物品 —— NSUK 的金币。
     *
     * <p>照抄 NSUK 的做法（{@code new ItemStack(ModItems.GOLD_COIN.get())}）：
     * 用物品而不是自己画贴图，图标与悬浮提示都是白送的。
     * 用 id 字符串而不是直接引 {@code ModItems}，是因为 NSUK 对本模组是**软依赖**。
     */
    private static final String GOLD_COIN_ITEM = "simukraft:gold_coin";
    /** NSUK 未安装时的资金图标退路（金锭；留空会看起来像「没填」）。 */
    private static final String FALLBACK_MONEY_ITEM = "minecraft:gold_ingot";

    private CommercialOfferModel offers;
    /** 当前选中的交易 id；空串表示没有选中。 */
    private String selectedId = "";
    /** 容器声明（containers）模型；与 offers 共用同一个根对象，所以改的是同一棵 JSON。 */
    private CommercialContainersModel containers;

    public CommercialTradePanel(EditorHost host) {
        super(host);
    }

    @Override
    public boolean supports(String category) {
        return "commercial".equals(category);
    }

    @Override
    protected void resetViewState() {
        offers = null;
        selectedId = "";
    }

    @Override
    protected void afterModelParsed(List<JsonObject> recipes) {
        // 包在同一个根上：CommercialOfferModel 不自己 parse，避免出现第二份状态。
        offers = CommercialOfferModel.wrap(model.root());
        containers = CommercialContainersModel.wrap(model.root());
        List<JsonObject> all = offers.offers();
        selectedId = all.isEmpty() ? "" : offers.offerId(all.get(0));
    }

    @Override
    protected String editScope() {
        return selectedId;
    }

    // ── 校验 ───────────────────────────────────────────────────────────

    @Override
    public boolean hasValidation() {
        return offers != null;
    }

    @Override
    protected String issueNamespace() {
        return "commercial";
    }

    @Override
    public List<DefinitionIssue> validateForDisplay() {
        List<DefinitionIssue> issues = new ArrayList<>();
        if (offers != null) {
            issues.addAll(offers.validate());
        }
        // 容器声明的问题也要进同一个校验面板 —— 它和报价同属这份定义。
        if (containers != null) {
            issues.addAll(containers.validate());
        }
        return issues;
    }

    @Override
    public boolean canLocateIssue(DefinitionIssue issue) {
        return issue.location().startsWith(CommercialOfferModel.OFFERS + "[");
    }

    @Override
    public void locateIssue(DefinitionIssue issue) {
        int index = offerIndexFrom(issue.location());
        List<JsonObject> all = offers == null ? List.of() : offers.offers();
        if (index < 0 || index >= all.size()) {
            return;
        }
        selectedId = offers.offerId(all.get(index));
        host.refresh();
        host.openMiddleTab(MiddleTabKind.COMMERCIAL_TRADE);
    }

    /** 从 {@code offers[3].cost[1]} 这种位置里取出报价下标；解析不出来返回 -1。 */
    private static int offerIndexFrom(String location) {
        var matcher = java.util.regex.Pattern
                .compile(CommercialOfferModel.OFFERS + "\\[([0-9]+)]").matcher(location);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    // ── 右栏：入口 ─────────────────────────────────────────────────────

    @Override
    public void renderSettingsExtraInto(UIElement parent) {
        if (!"commercial".equals(building.category()) || offers == null) {
            return;
        }
        parent.addChild(sectionHeader("gui.simupack.commercial.trades"));

        UIElement row = SimuPackLayout.row(2);
        // 「打开页签」不是编辑动作，所以不受 editable() 限制：已安装包（只读）也要能打开，
        // 那正是作者来学官方报价设计的地方。页签里的字段与按钮由 editable() 各自置灰。
        row.addChild(textButton(
                Component.translatable("gui.simupack.commercial.edit_trades").getString(), 0F,
                () -> host.openMiddleTab(MiddleTabKind.COMMERCIAL_TRADE)));
        // 与容器卡片里的内容同一条竖线（卡片有 CARD_INSET 的内边距）。
        padLeft(row, CARD_INSET);
        row.addChild(SimuPackLayout.spacer(1F));
        parent.addChild(row);

        addContainersSection(parent);
    }

    // ── 中间区：交易编辑器 ─────────────────────────────────────────────

    @Override
    public void renderMiddleInto(UIElement container) {
        container.clearAllChildren();
        if (!prepare(container, View.OFFERS)) {
            return;
        }
        container.addChild(toolbar());

        // 两栏：左列表（固定百分比宽）+ 右详情（吃掉剩余宽度）。
        // 用 fillRow（占满剩余高度、子项纵向拉伸）；列宽用百分比而不是固定像素 ——
        // MC 的自动 GUI 缩放会让可用宽度掉到 320，固定像素列宽会直接溢出。
        UIElement body = SimuPackLayout.fillRow(2);
        // 这一层必须自己裁掉溢出。理由：两个子列都是纵向滚动区，纵向滚动条救不了
        // 横向溢出；一旦某一行的固定宽度之和超过列宽（窄窗口下很容易），
        // 内容就会画到列外、压到页签栏上。裁掉之后超出的部分由各列自己的滚动条处理，
        // 或者干脆被截断 —— 总之不会再「叠起来」。
        body.layout(layout -> layout.overflow(YogaOverflow.HIDDEN));
        // 左栏隐藏滚动条（用户要求），右栏保留 —— 详情是长表单，滚动条是有用的位置反馈。
        body.addChild(SimuPackLayout.pinWidthPercent(
                scrollingColumn(offerList(), false), LIST_WIDTH_PERCENT));
        body.addChild(SimuPackLayout.shrinkable(scrollingColumn(detailPanel(), true)));
        container.addChild(body);
    }

    /**
     * 把一列内容包成纵向滚动区。
     *
     * <p><b>必须用 {@code addScrollViewChild}，不能用 {@code addChild}。</b>
     * 这两者的区别不是风格问题：{@code addScrollViewChild(x)} 的实现就是
     * {@code viewContainer.addChild(x)}，而 {@code ScrollerView} <b>没有覆写</b>
     * {@code addChild} —— 所以 {@code addChild} 会把内容挂到 {@code ScrollerView} 自己身上，
     * 成为 {@code viewPort}/{@code viewContainer} 的<b>兄弟节点</b>：
     * 在视口之外，于是<b>永不裁剪、也永不参与滚动测量</b>。
     * 表现就是「内容画到容器外面、压在下方的页签栏上，而且永远不出滚动条」。
     *
     * <p>本仓库里正确的范例：{@code SingleplayerEditorScreen:1323}（右栏设定表单）、
     * {@code :1162}（建筑树）、{@code PackManagerScreen:603}。
     *
     * <p>另外显式设定 {@code verticalScrollDisplay}，并把横向滚动条关掉：
     * 纵向滚动区里出现横向滚动条说明某一行还在用固定像素宽度 —— 那是要修的 bug，
     * 不是要滚的内容。
     *
     * @param showScrollbar {@code false} 时<b>只隐藏滚动条，滚动本身照常</b>。
     *     这是 <em>显示</em> 开关而不是功能开关：滚轮处理是直接调
     *     {@code Scroller.scrollByWheel}，和这个设置无关；而 {@link ScrollDisplay#NEVER}
     *     走的是 {@code setDisplay(false)}，顺带把滚动条占的位置也收回来。
     */
    private static UIElement scrollingColumn(UIElement content, boolean showScrollbar) {
        var scroll = SimuPackLayout.scroller();
        scroll.scrollerStyle(style -> style
                .verticalScrollDisplay(showScrollbar ? ScrollDisplay.AUTO : ScrollDisplay.NEVER)
                .horizontalScrollDisplay(ScrollDisplay.NEVER));
        scroll.addScrollViewChild(content);
        return scroll;
    }

    private UIElement toolbar() {
        UIElement row = SimuPackLayout.row(3);
        row.layout(layout -> {
            layout.widthPercent(100);
            layout.height(TOOLBAR_HEIGHT);
            layout.minWidth(0);
            layout.flexShrink(0);
        });

        JsonObject offer = selectedOffer();
        String name = offer == null ? tr("gui.simupack.commercial.no_offer") : offers.offerId(offer);
        Button pick = textButton(name + " ▾", SimuPackLayout.textWidth(name + " ▾") + 10F, null);
        installDropdown(pick, this::offerEntries);
        pick.setActive(!offers.offers().isEmpty());
        row.addChild(pick);

        row.addChild(textButton("＋ " + tr("gui.simupack.commercial.new_trade"), 0F,
                this::newOffer, editable()));
        row.addChild(textButton("⧉ " + tr("gui.simupack.commercial.duplicate_trade"), 0F,
                this::duplicateSelected, editable() && offer != null));
        row.addChild(textButton("✕ " + tr("gui.simupack.commercial.delete_trade"), 0F,
                this::deleteSelected, editable() && offer != null));

        row.addChild(SimuPackLayout.spacer(1F));
        // 这里刻意不显示形态徽标：详情栏里已经有一行「交易界面里会出现在：…」，
        // 而在窄窗口下工具条把这四个按钮 + 下拉排完就没剩多少宽度了，
        // 再塞一个徽标会把它们挤出容器。
        // 徽标只统计「警告 + 错误」。
        //
        // 这是实测出来的：一个 22 条交易的正常工程会报 24 条问题，其中 22 条是 INFO
        // （每条库存规则都「不自动补货」—— 那是最常见的配置）。全算进去只会显示成
        // 吓人的 ⚠ 24，真正该看的两条被淹没。INFO 不是没问题，它只是不需要用户动手，
        // 留给校验面板逐条列出即可。
        int actionable = 0;
        for (var issue : offers.validate()) {
            if (issue.severity() != DefinitionIssue.Severity.INFO) {
                actionable++;
            }
        }
        row.addChild(SimuPackLayout.pinnedLabel(
                Component.literal(actionable == 0 ? "✓" : "⚠ " + actionable),
                actionable == 0 ? SimuPackTheme.TEXT_SUCCESS_COLOR : SimuPackTheme.TEXT_WARNING_COLOR));
        return row;
    }

    private List<SimuPackMenuBar.Entry> offerEntries() {
        List<SimuPackMenuBar.Entry> entries = new ArrayList<>();
        List<JsonObject> all = offers.offers();
        if (all.isEmpty()) {
            entries.add(SimuPackMenuBar.Entry.item("gui.simupack.commercial.trades_empty", "", () -> {
            }, () -> false));
            return entries;
        }
        for (JsonObject candidate : all) {
            String id = offers.offerId(candidate);
            String label = id + "  (" + shapeText(candidate) + ")";
            entries.add(SimuPackMenuBar.Entry.item(label, id.equals(selectedId) ? "✓" : "",
                    () -> selectOffer(id), () -> true));
        }
        return entries;
    }

    /** 左栏：交易列表。只做导航，编辑控件都在右侧详情里。 */
    private UIElement offerList() {
        UIElement column = SimuPackLayout.column(1);

        List<JsonObject> all = offers.offers();
        if (all.isEmpty()) {
            column.addChild(wrapped("gui.simupack.commercial.trades_empty",
                    SimuPackTheme.TEXT_MUTED_COLOR));
            return column;
        }
        for (JsonObject candidate : all) {
            String id = offers.offerId(candidate);
            boolean current = id.equals(selectedId);
            UIElement row = new UIElement().addClass(current ? "simupack_card_selected" : "simupack_card");
            row.layout(layout -> {
                layout.widthPercent(100);
                layout.height(LIST_ROW_HEIGHT);
                layout.minWidth(0);
                layout.flexShrink(0);
                layout.flexDirection(FlexDirection.ROW);
                layout.alignItems(AlignItems.CENTER);
                layout.gapAll(1);
                // 行高固定：内容超宽就裁掉，不许盖住下一行（图标上限 4+4，正常放得下）。
                layout.overflow(YogaOverflow.HIDDEN);
            });
            // 一行就是「成本 → 产出」的图标串。
            // 刻意不写「出售/收购/交换」，也不写物品名：名字在悬浮提示里，
            // 分类是游戏内 NSUK 自己算的（详情栏那行会告诉你结果），
            // 列表只需回答「哪条交易、拿什么换什么」。
            addResourceIcons(row, CommercialOfferModel.costArray(candidate, false));
            row.addChild(SimuPackLayout.pinnedLabel(Component.literal("→"),
                    SimuPackTheme.TEXT_MUTED_COLOR));
            addResourceIcons(row, CommercialOfferModel.resultArray(candidate, false));
            row.addChild(SimuPackLayout.spacer(1F));
            // 选中是纯界面状态变化（不动 JSON），但仍然会重建本页签的内容树，
            // 所以推迟到本帧事件派发结束之后再刷新 —— 与动作组画布的拖拽落点同一套理由。
            // 用 tell：execute 在渲染线程的事件回调里是当场执行的，等于没推迟。
            row.addEventListener(UIEvents.MOUSE_DOWN, event -> {
                if (event.button != 0 || current) {
                    return;
                }
                Minecraft.getInstance().tell(() -> selectOffer(id));
            });
            column.addChild(row);
        }
        return column;
    }

    /** 把一侧的资源逐个画成图标（NSUK 单侧上限 4，多出来的本来就无效，不画）。 */
    private void addResourceIcons(UIElement row, JsonArray resources) {
        if (resources == null) {
            return;
        }
        int shown = 0;
        for (JsonElement element : resources) {
            if (shown >= CommercialOfferModel.MAX_RESOURCES) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            row.addChild(resourceCell(element.getAsJsonObject()));
            shown++;
        }
    }

    /**
     * 一个资源格子：<b>图标 + 右下角角标</b>（物品是数量，资金是金额）。
     *
     * <p>几个跟着 NSUK 走的决定：
     * <ul>
     *   <li><b>资金的图标就是 {@code simukraft:gold_coin}</b> —— NSUK 自己也是
     *       {@code new ItemStack(ModItems.GOLD_COIN.get())} 来表示资金的。用物品而不是贴图文件，
     *       图标与悬浮提示都是白送的。</li>
     *   <li><b>角标用 {@code shortMoney}</b>：NSUK 那个方法的注释就是「格式化槽位角标资金金额」
     *       （去掉尾随 0）—— 角标位置很窄，{@code "3.20"} 会顶到相邻图标上。</li>
     *   <li><b>数量为 1 时不画角标</b>：与 MC 原版槽位一致，省掉一排没信息量的 "1"。</li>
     *   <li><b>金币自带的悬浮提示是「金币」而不是「资金」</b>，所以这里补一行明确写出
     *       「资金 + 金额」—— 用户要的就是一眼看出这一格代表钱。</li>
     * </ul>
     */
    private UIElement resourceCell(JsonObject resource) {
        UIElement cell = new UIElement();
        cell.layout(layout -> {
            layout.width(LIST_ICON_SIZE);
            layout.height(LIST_ICON_SIZE);
            layout.flexShrink(0);
            // 角标要能向右溢出（金额位数不定），所以这里不能裁。
            layout.overflow(org.appliedenergistics.yoga.YogaOverflow.VISIBLE);
        });

        boolean money = CommercialOfferModel.kindOf(resource) == CommercialOfferModel.ResourceKind.MONEY;
        ItemSlot icon = plainSlot(money
                ? moneyStack()
                : ItemRegistry.stackOf(CommercialOfferModel.itemOf(resource)), LIST_ICON_SIZE);
        if (money) {
            // 关掉 ItemSlot 自带的物品提示，再挂资金提示。
            //
            // style.tooltips(...) 是<b>追加</b>而不是替换：不关的话悬浮会出现两行
            // ——「NSUK-金币」（那枚硬币的物品名）和「资金 N」。用户看到的就是前者。
            // 这里只想要资金语义，金币只是它的图标。
            // 注意这与物品格相反：物品格的悬浮<b>就应该是</b>物品名（那是刻意的）。
            icon.slotStyle(style -> style.showItemTooltips(false));
            icon.style(style -> style.tooltips(Component.translatable(
                    "gui.simupack.commercial.money_tooltip",
                    CommercialOfferModel.shortMoney(CommercialOfferModel.moneyOf(resource)))));
        }
        cell.addChild(icon);

        int count = CommercialOfferModel.countOf(resource);
        String badge = money
                ? CommercialOfferModel.shortMoney(CommercialOfferModel.moneyOf(resource))
                : (count > 1 ? Integer.toString(count) : "");
        if (!badge.isEmpty()) {
            // 角标从**左**边开始、向右溢出，而不是右对齐再被削掉左边。
            //
            // 原来锚的是 right(0) + pinnedLabel（钉死宽度），数字一长就变成
            // 「..23」这种前置省略 —— 把最高位（也就是最重要的那几位）全吃掉了。
            // 改成 left(0) 且不钉宽度：文字原样铺开，多余部分向右伸出去。
            // 配合 cell 的 overflow(VISIBLE)，数字再长也不会被裁。
            Label label = SimuPackLayout.label(
                    Component.literal(badge), SimuPackTheme.TEXT_PRIMARY_COLOR);
            label.layout(layout -> layout
                    .positionType(TaffyPosition.ABSOLUTE)
                    .left(0)
                    .bottom(0));
            cell.addChild(label);
        }
        return cell;
    }

    /**
     * 一个「只有图标」的槽位。
     *
     * <p>LDLib2 的 {@code ItemSlot} 默认会画<b>两层底图</b>：外层槽位背景
     * （{@code backgroundTexture}）和物品叠层（{@code slotOverlay}）。
     * 我们要的只是图标本身，所以两层都清成 {@code IGuiTexture.EMPTY} ——
     * <b>这也是 NSUK 自己的写法</b>（{@code CommercialTradeUiRoot.displaySlot}：
     * {@code slot.style(s -> s.backgroundTexture(IGuiTexture.EMPTY))} 加
     * {@code slot.slotStyle(s -> s.slotOverlay(IGuiTexture.EMPTY).showItemTooltips(true))}）。
     *
     * <p>{@code showItemTooltips(true)} 必须显式写上：默认值本来就开着，
     * 但这里紧挨着两行「清空外观」，写出来才看得出<b>我们只清外观、没关提示</b>。
     */
    /**
     * 把选中的物品 id 写进某个 JSON 对象并重建页签。
     *
     * <p>三处调用点（成本 / 产出 / 库存物品 / 材料）原来各写一遍
     * 「pushHistory → 写 item → restructured」，这几步必须一起发生：
     * 少了历史就撤不回来，少了重建界面就与数据不一致。
     */
    private void writeItem(JsonObject target, String itemId) {
        if (!editable()) {
            return;
        }
        host.pushHistory();
        target.addProperty(CommercialOfferModel.ITEM, itemId);
        restructured();
    }

    /** 委托共用件：渲染实现只该有一份（见 EditorWidgets.plainSlot）。 */
    private static ItemSlot plainSlot(ItemStack stack, float size) {
        return EditorWidgets.plainSlot(stack, size);
    }

    /**
     * 资金图标：优先 NSUK 的金币。
     *
     * <p>退路是金锭而不是留空 —— 没装 NSUK 时商业功能本来就没意义，
     * 但编辑器不该让那一格看起来像「没填东西」（空格子在别处表示无效资源，会误导）。
     */
    private static ItemStack moneyStack() {
        ItemStack coin = ItemRegistry.stackOf(GOLD_COIN_ITEM);
        return coin.isEmpty() ? ItemRegistry.stackOf(FALLBACK_MONEY_ITEM) : coin;
    }

    /** 右栏：当前交易的详情。返回列本身，滚动由 {@link #scrollingColumn} 包。 */
    private UIElement detailPanel() {
        UIElement column = SimuPackLayout.column(2);

        JsonObject offer = selectedOffer();
        if (offer == null) {
            column.addChild(wrapped("gui.simupack.commercial.no_offer", SimuPackTheme.TEXT_MUTED_COLOR));
            return column;
        }

        column.addChild(textRow("gui.simupack.commercial.field.id", offers.offerId(offer),
                value -> edit("offer.id", offer, target -> offers.setOfferId(target, value))));
        column.addChild(visibilityRow(offer));
        column.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.commercial.shape_hit", shapeText(offer)),
                offers.offers().isEmpty() ? SimuPackTheme.TEXT_MUTED_COLOR
                        : CommercialOfferModel.isUnclassified(offer)
                                ? SimuPackTheme.TEXT_WARNING_COLOR : SimuPackTheme.TEXT_SUCCESS_COLOR));

        addResourceSection(column, offer, CommercialOfferModel.COST, "gui.simupack.commercial.cost");
        addResourceSection(column, offer, CommercialOfferModel.RESULT, "gui.simupack.commercial.result");
        addStockSection(column, offer);
        // 这里原本有一句「物品选择器在后续阶段接入」的 stage_note。
        // 选择器早就做好了，那句话是**错的** —— 界面上留着假话比没有话更糟，所以删掉。
        return column;
    }

    /** 可见性下拉：写规范键与规范值。 */
    private UIElement visibilityRow(JsonObject offer) {
        UIElement row = labelRow("gui.simupack.commercial.field.visibleTo");
        String current = offers.visibleTo(offer);
        String label = tr("gui.simupack.commercial.visible." + current) + " ▾";
        Button button = textButton(label, SimuPackLayout.textWidth(label) + 10F, null);
        List<SimuPackMenuBar.Entry> entries = new ArrayList<>();
        for (String value : VISIBILITIES) {
            entries.add(SimuPackMenuBar.Entry.item("gui.simupack.commercial.visible." + value,
                    value.equals(current) ? "✓" : "", () -> {
                        edit("offer.visibleTo", offer, target -> offers.setVisibleTo(target, value));
                        host.refresh();
                    }, () -> true));
        }
        installDropdown(button, () -> entries);
        if (readOnly) {
            button.setActive(false);
        }
        row.addChild(button);
        return row;
    }

    /**
     * 成本 / 产出的资源编辑区。
     *
     * <p><b>每条资源只有一个「形态」控件（资金 / 物品），而不是并列两个输入框。</b>
     * 这是刻意的：NSUK 判断资金用的是「元素里有没有 {@code money} 键」，两者并存时
     * {@code item} 会被静默忽略（见 {@code money_shadows_item}）。
     * 让「同时存在」在交互上<b>不可表达</b>，比事后告警可靠得多 ——
     * 而切换形态时 {@code writeMoney}/{@code writeItem} 会先清掉另一形态的键，
     * 所以磁盘上也不会留下两种拼写。
     */
    private void addResourceSection(UIElement parent, JsonObject offer, String key, String headerKey) {
        parent.addChild(sectionHeader(headerKey));
        boolean isCost = CommercialOfferModel.COST.equals(key);
        String[] aliases = isCost ? new String[]{"costs"} : new String[]{"results"};
        JsonArray array = CommercialOfferModel.arrayOf(offer, key, aliases, false);

        if (array == null || array.isEmpty()) {
            parent.addChild(wrapped(isCost
                    ? "gui.simupack.commercial.empty_cost"
                    : "gui.simupack.commercial.empty_result", SimuPackTheme.TEXT_ERROR_COLOR));
        } else {
            for (int index = 0; index < array.size(); index++) {
                JsonElement element = array.get(index);
                if (!element.isJsonObject()) {
                    parent.addChild(SimuPackLayout.wrappedLabel(
                            Component.translatable("gui.simupack.commercial.issue.invalid_resource"),
                            SimuPackTheme.TEXT_ERROR_COLOR));
                    continue;
                }
                parent.addChild(resourceRow(offer, key, aliases, index, element.getAsJsonObject()));
            }
        }

        // 上限 4（NSUK 会静默丢弃第 5 项），所以到上限就把「＋」禁用掉。
        int size = array == null ? 0 : array.size();
        boolean room = size < CommercialOfferModel.MAX_RESOURCES;
        UIElement actions = SimuPackLayout.row(2);
        actions.addChild(textButton("＋ " + tr("gui.simupack.commercial.add_money"), 0F,
                () -> addResource(offer, key, aliases, true), editable() && room));
        actions.addChild(textButton("＋ " + tr("gui.simupack.commercial.add_item"), 0F,
                () -> addResource(offer, key, aliases, false), editable() && room));
        if (!room) {
            actions.addChild(SimuPackLayout.pinnedLabel(
                    Component.translatable("gui.simupack.commercial.at_resource_limit",
                            CommercialOfferModel.MAX_RESOURCES),
                    SimuPackTheme.TEXT_WARNING_COLOR));
        }
        parent.addChild(actions);
    }

    /** 一行资源：形态控件 + 值输入 + 删除。 */
    private UIElement resourceRow(JsonObject offer, String key, String[] aliases,
                                  int index, JsonObject resource) {
        UIElement row = SimuPackLayout.row(2);
        row.layout(layout -> {
            layout.widthPercent(100);
            layout.height(ROW_HEIGHT);
            layout.minWidth(0);
            layout.flexShrink(0);
        });
        row.addChild(kindButton(resource));

        if (CommercialOfferModel.kindOf(resource) == CommercialOfferModel.ResourceKind.MONEY) {
            TextField amount = textField();
            amount.setNumbersOnlyDouble(0.0D, Double.MAX_VALUE);   // 金额
            amount.setText(rawString(resource, CommercialOfferModel.MONEY), false);
            amount.setTextResponder(value -> writeMoney(resource, value));
            amount.layout(layout -> {
                layout.width(56);
                layout.height(ROW_HEIGHT);
                layout.flexShrink(0);
            });
            row.addChild(amount);
        } else {
            row.addChild(widgets.itemCell(CommercialOfferModel.itemOf(resource), host::openItemPicker,
                    picked -> writeItem(resource, picked)));

            TextField count = textField();
            count.setNumbersOnlyInt(1, Integer.MAX_VALUE);   // 数量：0 个没有意义
            count.setText(Integer.toString(CommercialOfferModel.countOf(resource)), false);
            count.setTextResponder(value -> writeCount(resource, value));
            count.layout(layout -> {
                layout.width(COUNT_WIDTH);
                layout.height(ROW_HEIGHT);
                layout.flexShrink(0);
            });
            row.addChild(count);
        }

        row.addChild(SimuPackLayout.spacer(1F));
        row.addChild(textButton("✕", SMALL_BUTTON,
                () -> removeResource(offer, key, aliases, index), editable()));
        return row;
    }

    /** 形态单选：资金 ⇄ 物品。这是让「money 与 item 并存」不可表达的那道闸门。 */
    private Button kindButton(JsonObject resource) {
        boolean money = CommercialOfferModel.kindOf(resource) == CommercialOfferModel.ResourceKind.MONEY;
        String label = tr(money ? "gui.simupack.commercial.kind.money"
                : "gui.simupack.commercial.kind.item") + " ▾";
        Button button = textButton(label, SimuPackLayout.textWidth(label) + 10F, null);
        List<SimuPackMenuBar.Entry> entries = new ArrayList<>();
        entries.add(SimuPackMenuBar.Entry.item("gui.simupack.commercial.kind.money",
                money ? "✓" : "", () -> switchKind(resource, true), () -> true));
        entries.add(SimuPackMenuBar.Entry.item("gui.simupack.commercial.kind.item",
                money ? "" : "✓", () -> switchKind(resource, false), () -> true));
        installDropdown(button, () -> entries);
        if (readOnly) {
            button.setActive(false);
        }
        return button;
    }

    // ── 资源的结构操作（会重建页签，所以走 pushHistory + restructured）──────

    private void addResource(JsonObject offer, String key, String[] aliases, boolean money) {
        if (!editable()) {
            return;
        }
        JsonArray array = CommercialOfferModel.arrayOf(offer, key, aliases, true);
        if (array.size() >= CommercialOfferModel.MAX_RESOURCES) {
            return;
        }
        host.pushHistory();
        if (money) {
            CommercialOfferModel.addMoneyResource(array, 1.0D);
        } else {
            CommercialOfferModel.addItemResource(array, DEFAULT_ITEM, 1);
        }
        restructured();
    }

    private void removeResource(JsonObject offer, String key, String[] aliases, int index) {
        if (!editable()) {
            return;
        }
        JsonArray array = CommercialOfferModel.arrayOf(offer, key, aliases, false);
        if (array == null || index < 0 || index >= array.size()) {
            return;
        }
        host.pushHistory();
        array.remove(index);
        restructured();
    }

    /**
     * 切换资源形态。
     *
     * <p>会删掉旧形态的键、补上新形态的键，所以是结构性改动：
     * 走 {@code pushHistory} + {@code restructured()}（要重画这一行）。
     * 新形态给一个**可用**的初值，免得刚切过去就是一条无效资源。
     */
    private void switchKind(JsonObject resource, boolean toMoney) {
        if (!editable()) {
            return;
        }
        boolean alreadyMoney = CommercialOfferModel.kindOf(resource)
                == CommercialOfferModel.ResourceKind.MONEY;
        if (alreadyMoney == toMoney) {
            return;
        }
        host.pushHistory();
        if (toMoney) {
            CommercialOfferModel.writeMoney(resource, 1.0D);
        } else {
            CommercialOfferModel.writeItem(resource, DEFAULT_ITEM, 1);
        }
        restructured();
    }

    // ── 资源的标量写入（不重建页签，保住输入焦点；撤销可合并）────────────

    /**
     * 写资金数额。
     *
     * <p><b>刻意不用 {@code JsonEditTransaction.writeScalar} 的「空值即删键」语义</b>：
     * 资金键一旦被删掉，这个元素就变成物品形态了，界面会当场换成另一套控件
     * —— 用户只是想清空重打，却把形态改掉了。所以这里空值/非法值一律**不写**，
     * 脏输入留在框里，等用户改对。
     */
    private void writeMoney(JsonObject resource, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        double value;
        try {
            value = Double.parseDouble(text.trim());
        } catch (NumberFormatException exception) {
            return;
        }
        if (!Double.isFinite(value) || value < 0.0D) {
            return;
        }
        edit("resource.money", resource, target -> target.addProperty(CommercialOfferModel.MONEY, value));
    }

    /** 写数量；NSUK 会把小于 1 的静默改成 1，所以这里也拒绝写入并保留脏输入。 */
    private void writeCount(JsonObject resource, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int value;
        try {
            value = Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return;
        }
        if (value < 1) {
            return;
        }
        edit("resource.count", resource, target -> target.addProperty(CommercialOfferModel.COUNT, value));
    }

    /** 裸读一个键的文本（不做任何形态解释），供输入框回填。 */
    private static String rawString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    // ── 物品单元格：就一个图标，点开选择器 ────────────────────────────

    /**
     * 一个「物品」单元格：<b>只有一个图标</b>。
     *
     * <ul>
     *   <li><b>悬浮</b> → LDLib2 的 {@code ItemSlot} 自带物品提示，鼠标放上去就显示物品名
     *       （{@code SlotStyle.showItemTooltips()}），不需要自己挂 tooltip。</li>
     *   <li><b>点击</b> → 打开物品选择器浮层（搜索 + 图标网格），选中即写回。</li>
     *   <li>id 认不出来时额外画一个红色 ✗ —— 图标会是空的，不给提示就看不出是「没选」
     *       还是「拼错了」。</li>
     * </ul>
     *
     * <p><b>为什么彻底去掉手打 id</b>：拼错 id 会让那条资源无效 → NSUK 把整条交易
     * <b>静默丢弃</b>，是最难排查的一类坑。既然能让用户在注册表里点，就不该留一条
     * 能把 id 拼错的路。JSON 里存的仍然是 id 字符串，只是用户不再需要看到它。
     *
     * <p>写回走 {@code pushHistory + restructured()} 而不是 {@code edit()}：
     * 拾取是一次离散动作（不是连续输入），而且<b>图标必须立刻跟着变</b>，所以要重建。
     */

    /**
     * 写库存里的整数（上限 / 初始 / 补货量 / 补货间隔）。
     *
     * <p>与金额输入同样的策略：**空值与非法值一律不写**，脏输入留在框里。
     * 写规范键的同时清掉兼容拼写，免得文件里留下 {@code restock_amount} 这类历史写法。
     */
    private void writeStockInt(JsonObject stock, String key, String alias, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int value;
        try {
            value = Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return;
        }
        if (value < 0) {
            return;
        }
        edit("stock." + key, stock, target -> {
            if (alias != null) {
                target.remove(alias);
            }
            target.addProperty(key, value);
        });
    }

    /**
     * 库存编辑区。
     *
     * <p>库存是<b>可选键</b>（没有 stock 规则本身合法，只是该物品不受库存限制），
     * 所以没有规则时给一个「＋ 添加库存规则」，而不是画一堆空框。
     *
     * <p>三种模式与「materials 非空会禁用自动补货」都是 NSUK 的既有语义，
     * 这里把当前模式**实时写在标题下面**，并且当用户确实写了会被忽略的补货字段时，
     * 直接复用校验器那条文案就地提示 —— 面板说的和校验面板说的是同一句话。
     */
    private void addStockSection(UIElement parent, JsonObject offer) {
        parent.addChild(sectionHeader("gui.simupack.commercial.stock"));
        JsonObject stock = offers.stock(offer, false);
        CommercialOfferModel.SupplyMode mode = CommercialOfferModel.SupplyMode.of(stock);
        // 入库型（收购）的同一组字段含义与出库型相反，提示与文案都要按方向换。
        // 这里必须用库存里**真实写着**的物品：校验器就是拿它判方向的，
        // 用推断出来的物品会让面板报出校验面板不报的东西（那就成了各说各的）。
        boolean intake = CommercialOfferModel.stockFlow(offer, stock)
                == CommercialOfferModel.StockFlow.ENTERS;

        // 供给方式用下拉框，而不是「一堆字段 + 一个删除按钮」。
        //
        // 理由：NSUK 的库存是两层的（供给方式 → 是否补货），而「材料供给」一旦成立就会让
        // initial/restockAmount/restockInterval 全部失效。用一次选择决定「这组字段该不该出现」，
        // 用户就不可能填出「写了但被忽略」的字段。
        // 「不受限」也放进同一个下拉框（等于删除 stock 键），所以不再需要单独的删除按钮。
        UIElement modeRow = labelRow("gui.simupack.commercial.field.stock.mode");
        String modeText = tr(supplyModeKey(mode)) + " ▾";
        Button pick = textButton(modeText, SimuPackLayout.textWidth(modeText) + 10F, null, editable());
        installDropdown(pick, () -> supplyModeEntries(offer));
        modeRow.addChild(pick);
        parent.addChild(modeRow);

        if (mode == CommercialOfferModel.SupplyMode.UNLIMITED) {
            // 刻意不再补一句「不受库存限制」的说明：下拉框上写的就是这几个字。
            // 界面不重复它自己已经显示的东西。
            return;
        }

        JsonArray materials = CommercialOfferModel.materialsArray(stock, false);
        // 手工编辑过的文件仍可能出现「材料 + 补货字段并存」（本编辑器写不出这种状态），
        // 那就照旧复用校验器那条文案就地提示 —— 面板和校验面板说的是同一句话。
        // 这条是**警告**而不是说明，所以留在版面上，不塞进 tooltip。
        if (CommercialOfferModel.showsMaterials(mode) && hasRestockKeys(stock)) {
            parent.addChild(warningLabel("gui.simupack.commercial.issue.materials_disable_restock"));
        }
        if (intake) {
            // 收购上的两个硬故障，判据与校验器逐条对应（同一句话，不各说各的）：
            // 开局满仓 = 永远拒收；补货 = 把「已收」往上推、更早停收。
            int max = CommercialOfferModel.stockMax(stock);
            if (max > 0 && CommercialOfferModel.stockInitial(stock) >= max) {
                parent.addChild(warningLabel(
                        "gui.simupack.commercial.issue.intake_stock_full_at_start"));
            }
            if (CommercialOfferModel.restockEnabled(stock)) {
                parent.addChild(warningLabel(
                        "gui.simupack.commercial.issue.intake_restock_raises_stock"));
            }
            if (CommercialOfferModel.isMaterialBacked(stock)) {
                parent.addChild(warningLabel(
                        "gui.simupack.commercial.issue.intake_materials_ignored"));
            }
        }

        parent.addChild(stockItemRow(stock));
        // max 在材料模式下也有效（NSUK：min(rule.max(), 可用材料数)），所以两种模式都显示。
        parent.addChild(stockIntRow(stock, CommercialOfferModel.MAX, null,
                "gui.simupack.commercial.field.stock.max", false,
                intake ? "gui.simupack.commercial.field.stock.max.tip.intake" : null));
        if (CommercialOfferModel.showsInitial(mode)) {
            parent.addChild(stockIntRow(stock, CommercialOfferModel.INITIAL, null,
                    "gui.simupack.commercial.field.stock.initial", false,
                    intake ? "gui.simupack.commercial.field.stock.initial.tip.intake" : null));
        }
        if (CommercialOfferModel.showsRestock(mode)) {
            parent.addChild(stockIntRow(stock, CommercialOfferModel.RESTOCK_AMOUNT,
                    CommercialOfferModel.RESTOCK_AMOUNT_ALIAS,
                    "gui.simupack.commercial.field.stock.restockAmount", true));
            // 补货间隔的单位与「不受 /time set 影响」是真正看不出来的信息，
            // 所以放进 tooltip —— 需要的人悬停就有，不需要的人不被占版面。
            parent.addChild(stockIntRow(stock, CommercialOfferModel.RESTOCK_INTERVAL,
                    CommercialOfferModel.RESTOCK_INTERVAL_ALIAS,
                    "gui.simupack.commercial.field.stock.restockInterval", true,
                    "gui.simupack.commercial.restock_interval_tip"));
            // 刻意不写「当前：卖完即止 / 自动补货」：两个补货框空着还是填着，
            // 本身就已经说明了这件事，再写一行只是把同一件事说两遍。
        }
        if (CommercialOfferModel.showsMaterials(mode)) {
            addMaterialsSection(parent, stock, materials);
        }
    }

    /** 有没有写补货相关的键（含兼容拼写）。 */
    private static boolean hasRestockKeys(JsonObject stock) {
        return stock.has(CommercialOfferModel.RESTOCK_AMOUNT)
                || stock.has(CommercialOfferModel.RESTOCK_AMOUNT_ALIAS)
                || stock.has(CommercialOfferModel.RESTOCK_INTERVAL)
                || stock.has(CommercialOfferModel.RESTOCK_INTERVAL_ALIAS);
    }

    /**
     * 版面上的警告行（与校验面板同一批语言键，所以同一件事只会说一遍）。
     *
     * <p>警告是「需要处理的事」，按既定规则留在版面上；说明性文字才进 tooltip。
     */
    private static UIElement warningLabel(String key) {
        return SimuPackLayout.wrappedLabel(Component.translatable(key),
                SimuPackTheme.TEXT_WARNING_COLOR);
    }

    /**
     * 这条报价<b>打算</b>往哪个方向跟踪库存（出库=出售 / 入库=收购），供下拉框判断用。
     *
     * <p>与 {@code addStockSection} 里的判定不同：这里在库存物品还空着时用
     * {@link CommercialOfferModel#suggestStockItem(JsonObject)} 推断 —— 那时规则还没写下来，
     * 但用户接下来该看到哪些供给方式，取决于这条报价收的是哪一侧的物品。
     */
    private CommercialOfferModel.StockFlow intendedStockFlowOf(JsonObject offer) {
        String item = CommercialOfferModel.stockItem(offers.stock(offer, false));
        if (item.isBlank()) {
            item = CommercialOfferModel.suggestStockItem(offer);
        }
        return CommercialOfferModel.stockFlow(offer, item);
    }

    /** 供给方式下拉框的选项（收购报价上「现做现卖」会被拿掉，见下）。 */
    private List<SimuPackMenuBar.Entry> supplyModeEntries(JsonObject offer) {
        List<SimuPackMenuBar.Entry> entries = new ArrayList<>();
        JsonObject stock = offers.stock(offer, false);
        CommercialOfferModel.SupplyMode current = CommercialOfferModel.SupplyMode.of(stock);
        // 收购（入库型）上「材料供给」失效得很难看：NSUK 的 usesMaterialSupply() 要求
        // itemLeavesStock()，所以材料既不会被扣，而 materials 非空又让这条库存不再写 SQLite
        // → 上限静默消失 = 不限量收购。与其事后告警，不如让它不可选
        // （与「资金/物品单选」同一思路）；文件里已经这么写的仍要看得到、能改回来。
        boolean hideMaterials = intendedStockFlowOf(offer) == CommercialOfferModel.StockFlow.ENTERS
                && current != CommercialOfferModel.SupplyMode.MATERIALS;
        for (CommercialOfferModel.SupplyMode mode : CommercialOfferModel.SupplyMode.values()) {
            if (hideMaterials && mode == CommercialOfferModel.SupplyMode.MATERIALS) {
                continue;
            }
            // 第一个参数是**翻译键**（菜单栏自己翻译），不是已翻译好的 Component。
            // 快捷键位放 ✓ 标出当前模式 —— 与建筑分类下拉同一写法。
            entries.add(SimuPackMenuBar.Entry.item(
                    supplyModeKey(mode),
                    mode == current ? "✓" : "",
                    () -> switchSupplyMode(offer, mode),
                    () -> editable() && mode != current));
        }
        return entries;
    }

    private static String supplyModeKey(CommercialOfferModel.SupplyMode mode) {
        return switch (mode) {
            case UNLIMITED -> "gui.simupack.commercial.supply.unlimited";
            // 「卖完即止」与「自动补货」是同一个模式下「补货参数填没填」的区别，
            // 所以只有这一个键（面板下方会显示实际是哪种）。
            case STOCK -> "gui.simupack.commercial.supply.stock";
            case MATERIALS -> "gui.simupack.commercial.supply.materials";
        };
    }

    /**
     * 切换供给方式：把 {@code stock} 改造成该方式<b>只留真正生效的键</b>，
     * 选「不受限」则整个删掉 {@code stock}。
     */
    private void switchSupplyMode(JsonObject offer, CommercialOfferModel.SupplyMode mode) {
        if (!editable()) {
            return;
        }
        host.pushHistory();
        JsonObject stock = offers.stock(offer, true);
        if (CommercialOfferModel.applySupplyMode(stock, mode)) {
            offer.remove(CommercialOfferModel.STOCK);
        } else if (CommercialOfferModel.stockItem(stock).isBlank()) {
            // 新模式必须有一个库存物品，否则规则不成立（sqliteBacked 要求 item 非空）。
            // 优先用报价里真实出现过的物品：给收购报价塞一个默认物品，得到的是一条
            // 什么都不跟踪的死规则（stock_orphan_item），上限根本不会生效。
            String suggestion = CommercialOfferModel.suggestStockItem(offer);
            stock.addProperty(CommercialOfferModel.ITEM, suggestion.isBlank() ? DEFAULT_ITEM : suggestion);
        }
        restructured();
    }

    /** 库存物品：留空即删键（等于「不做库存跟踪」，是一个合法状态）。 */
    private UIElement stockItemRow(JsonObject stock) {
        UIElement row = labelRow("gui.simupack.commercial.field.stock.item");
        row.addChild(widgets.itemCell(CommercialOfferModel.stockItem(stock), host::openItemPicker,
                picked -> writeItem(stock, picked)));
        return row;
    }

    /** 库存里的一个整数字段（留空不删键）。 */
    private UIElement stockIntRow(JsonObject stock, String key, String alias, String labelKey) {
        return stockIntRow(stock, key, alias, labelKey, false, null);
    }

    /** 同上，{@code deleteWhenBlank} 见下。 */
    private UIElement stockIntRow(JsonObject stock, String key, String alias, String labelKey,
                                 boolean deleteWhenBlank) {
        return stockIntRow(stock, key, alias, labelKey, deleteWhenBlank, null);
    }

    /**
     * 库存里的一个整数字段。
     *
     * @param deleteWhenBlank 留空时是否<b>删掉这个键</b>。
     *     补货两个字段要用 {@code true}：它们的「空」有确切含义（关掉自动补货），
     *     而「不写」会让旧值留在文件里 —— 用户清空输入框却发现补货照旧，那就成了骗人。
     *     上限/初始仍然用 {@code false}：那里的空只是「还没填」，不该顺手删掉用户的值。
     * @param tooltipKey 输入框上的悬停说明；为 null 时不挂。
     *     单位的解释、边界说明这类文字放这里，不要占版面。
     */
    private UIElement stockIntRow(JsonObject stock, String key, String alias, String labelKey,
                                 boolean deleteWhenBlank, String tooltipKey) {
        UIElement row = labelRow(labelKey);
        TextField field = textField();
        field.setNumbersOnlyInt(0, Integer.MAX_VALUE);   // 库存整数
        field.setText(rawString(stock, key), false);
        field.setTextResponder(value -> {
            if (deleteWhenBlank) {
                writeStockIntOrClear(stock, key, alias, value);
            } else {
                writeStockInt(stock, key, alias, value);
            }
        });
        field.layout(layout -> {
            layout.width(NUMBER_WIDTH);
            layout.height(ROW_HEIGHT);
            layout.flexShrink(0);
        });
        if (tooltipKey != null) {
            field.style(style -> style.tooltips(Component.translatable(tooltipKey)));
        }
        row.addChild(field);
        row.addChild(SimuPackLayout.spacer(1F));
        return row;
    }

    /**
     * 写补货字段：<b>留空即删键</b>（= 关掉自动补货），非法值不写。
     *
     * <p>这是「卖完即止」与「自动补货」合并成一个模式之后必须有的语义：
     * 合并的前提就是「补货字段空着 = 卖完即止」，那么清空就必须真的把键去掉。
     */
    private void writeStockIntOrClear(JsonObject stock, String key, String alias, String text) {
        if (text == null || text.isBlank()) {
            edit("stock." + key, stock, target -> {
                target.remove(key);
                if (alias != null) {
                    target.remove(alias);
                }
            });
            return;
        }
        writeStockInt(stock, key, alias, text);
    }

    /** 材料列表：非空即「材料供给型」，最多 4 项。 */
    private void addMaterialsSection(UIElement parent, JsonObject stock, JsonArray materials) {
        // 「材料非空 = 材料供给型」在下拉框上已经说了；真正看不出来的是
        // 「材料从哪儿扣」，所以那句话放标题的 tooltip 里，不占版面。
        parent.addChild(sectionHeader("gui.simupack.commercial.materials")
                .style(style -> style.tooltips(
                        Component.translatable("gui.simupack.commercial.materials_tip"))));

        if (materials != null) {
            for (int index = 0; index < materials.size(); index++) {
                JsonElement element = materials.get(index);
                if (!element.isJsonObject()) {
                    continue;
                }
                parent.addChild(materialRow(stock, index, element.getAsJsonObject()));
            }
        }

        int size = materials == null ? 0 : materials.size();
        boolean room = size < CommercialOfferModel.MAX_RESOURCES;
        UIElement actions = SimuPackLayout.row(2);
        actions.addChild(textButton("＋ " + tr("gui.simupack.commercial.add_material"), 0F,
                () -> addMaterial(stock), editable() && room));
        if (!room) {
            actions.addChild(SimuPackLayout.pinnedLabel(
                    Component.translatable("gui.simupack.commercial.at_resource_limit",
                            CommercialOfferModel.MAX_RESOURCES),
                    SimuPackTheme.TEXT_WARNING_COLOR));
        }
        parent.addChild(actions);
    }

    /** 一行材料：物品 id + 数量 + 删除（材料永远是物品形态，所以不需要形态控件）。 */
    private UIElement materialRow(JsonObject stock, int index, JsonObject material) {
        UIElement row = SimuPackLayout.row(2);
        row.layout(layout -> {
            layout.widthPercent(100);
            layout.height(ROW_HEIGHT);
            layout.minWidth(0);
            layout.flexShrink(0);
        });

        String itemId = CommercialOfferModel.itemOf(material);
        row.addChild(widgets.itemCell(CommercialOfferModel.itemOf(material), host::openItemPicker,
                picked -> writeItem(material, picked)));

        TextField count = textField();
        count.setNumbersOnlyInt(1, Integer.MAX_VALUE);   // 数量：0 个没有意义
        count.setText(Integer.toString(CommercialOfferModel.countOf(material)), false);
        count.setTextResponder(value -> writeCount(material, value));
        count.layout(layout -> {
            layout.width(COUNT_WIDTH);
            layout.height(ROW_HEIGHT);
            layout.flexShrink(0);
        });
        row.addChild(count);

        row.addChild(SimuPackLayout.spacer(1F));
        row.addChild(textButton("✕", SMALL_BUTTON, () -> removeMaterial(stock, index), editable()));
        return row;
    }

    // ── 库存的结构操作 ─────────────────────────────────────────────────

    private void addMaterial(JsonObject stock) {
        if (!editable()) {
            return;
        }
        JsonArray materials = CommercialOfferModel.materialsArray(stock, true);
        if (materials.size() >= CommercialOfferModel.MAX_RESOURCES) {
            return;
        }
        host.pushHistory();
        CommercialOfferModel.addItemResource(materials, DEFAULT_ITEM, 1);
        restructured();
    }

    private void removeMaterial(JsonObject stock, int index) {
        if (!editable()) {
            return;
        }
        JsonArray materials = CommercialOfferModel.materialsArray(stock, false);
        if (materials == null || index < 0 || index >= materials.size()) {
            return;
        }
        host.pushHistory();
        materials.remove(index);
        restructured();
    }

    // ── 选择与结构操作 ─────────────────────────────────────────────────

    private JsonObject selectedOffer() {
        if (offers == null) {
            return null;
        }
        JsonObject found = offers.offerById(selectedId);
        if (found != null) {
            return found;
        }
        // 选中项被删掉/改名之后回落到第一条，避免详情栏空着。
        List<JsonObject> all = offers.offers();
        if (all.isEmpty()) {
            selectedId = "";
            return null;
        }
        selectedId = offers.offerId(all.get(0));
        return all.get(0);
    }

    private void selectOffer(String id) {
        selectedId = id;
        host.refresh();
    }

    private void newOffer() {
        if (!editable()) {
            return;
        }
        host.pushHistory();
        JsonObject created = offers.addOffer("trade");
        selectedId = offers.offerId(created);
        restructured();
    }

    private void duplicateSelected() {
        JsonObject offer = selectedOffer();
        int index = indexOfOffer(offer);
        if (!editable() || index < 0) {
            return;
        }
        host.pushHistory();
        JsonObject copy = offers.duplicateOffer(index);
        if (copy != null) {
            selectedId = offers.offerId(copy);
        }
        restructured();
    }

    private void deleteSelected() {
        JsonObject offer = selectedOffer();
        int index = indexOfOffer(offer);
        if (!editable() || index < 0) {
            return;
        }
        host.pushHistory();
        offers.removeOffer(index);
        List<JsonObject> remaining = offers.offers();
        selectedId = remaining.isEmpty() ? "" : offers.offerId(remaining.get(0));
        restructured();
    }

    /**
     * 报价在数组里的下标，按<b>引用</b>找。
     *
     * <p>不用 {@code List.indexOf}：它比的是 JSON 内容，两条内容相同（例如都没写 id）的
     * 报价会返回同一个下标，于是复制/删除会打错目标。
     */
    private int indexOfOffer(JsonObject target) {
        if (target == null) {
            return -1;
        }
        List<JsonObject> all = offers.offers();
        for (int index = 0; index < all.size(); index++) {
            if (all.get(index) == target) {
                return index;
            }
        }
        return -1;
    }

    // ── 显示文本 ───────────────────────────────────────────────────────

    /** 命中的 Tab 名（可能多个，用 / 连接）；一个都没命中时显示「未归类」。 */
    private static String shapeText(JsonObject offer) {
        if (offer == null) {
            return tr("gui.simupack.commercial.shape.unknown");
        }
        Set<CommercialOfferModel.TradeTab> tabs = CommercialOfferModel.tabsOf(offer);
        if (tabs.isEmpty()) {
            return tr("gui.simupack.commercial.shape.unknown");
        }
        StringBuilder builder = new StringBuilder();
        for (CommercialOfferModel.TradeTab tab : tabs) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(tr(tab.labelKey()));
        }
        return builder.toString();
    }

    /** 给一行内容加左内边距（与卡片内边距对齐用）。 */
    private static void padLeft(UIElement row, float inset) {
        row.layout(layout -> layout.paddingLeft(inset));
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    // ── 右栏：容器声明（物流端口） ──────────────────────────────────────

    /**
     * 容器声明一节。
     *
     * <p><b>它服务的是物流系统，不是材料供给</b>：{@code stock.materials} 的原材料由 NSUK
     * 扫描控制盒附近（XZ±5 / Y±2）的容器实时扣除，不需要也无法显式绑定；而 {@code containers}
     * 只被 {@code LogisticsAutoClientService} 消费，用来把这些箱子注册成该建筑的物流端口。
     * 所以这一节里不出现「材料」字样，免得用户以为在这里绑箱子就能给商店供货。
     */
    private void addContainersSection(UIElement parent) {
        if (containers == null) {
            return;
        }
        parent.addChild(sectionHeader("gui.simupack.commercial.containers"));

        List<String> ids = containers.ids();
        for (String id : ids) {
            parent.addChild(containerCard(id));
        }
        UIElement actions = SimuPackLayout.row(2);
        actions.addChild(textButton("＋ " + tr("gui.simupack.commercial.new_container"), 0F,
                this::newContainer, editable()));
        padLeft(actions, CARD_INSET);
        actions.addChild(SimuPackLayout.spacer(1F));
        parent.addChild(actions);
    }

    /** 一个容器：id 可改名，坐标只读（只能靠选点改），底部一个「选取点位」。 */
    private UIElement containerCard(String id) {
        UIElement card = SimuPackLayout.column(2);
        card.layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.flexShrink(0);
        });
        card.addClass("simupack_card");

        UIElement header = SimuPackLayout.row(2);
        TextField name = textField();
        name.setTextRegexValidator(
                com.rstech.simupack.client.ui.SimuPackDialog.ID_REGEX);   // 容器 id
        name.setText(id, false);
        // 逐字符尝试改名：成功就重建（列表跟着变），失败（空/重名）不写也不重建，
        // 把脏输入留在框里让用户改 —— 与资源行的标量写入同一策略。
        name.setTextResponder(value -> renameContainer(id, value));
        name.layout(layout -> {
            layout.flex(1);
            layout.height(ROW_HEIGHT);
            layout.minWidth(0);
        });
        header.addChild(name);
        header.addChild(textButton("✕", SMALL_BUTTON, () -> removeContainer(id), editable()));
        card.addChild(header);


        // 没有结构文件就没有可点的方块 —— 按钮置灰并说明原因，而不是让用户进去看空画布。
        boolean canPick = editable() && host.hasStructure();
        Button pick = textButton(tr("gui.simupack.commercial.pick_positions"), 0F,
                () -> pickPositions(id), canPick);
        if (!canPick) {
            pick.style(style -> style.tooltips(Component.translatable(editable()
                    ? "gui.simupack.editor.need_structure"
                    : "gui.simupack.editor.read_only")));
        }
        UIElement actions = SimuPackLayout.row(2);
        actions.addChild(pick);
        actions.addChild(SimuPackLayout.spacer(1F));
        card.addChild(actions);
        return card;
    }

    private void newContainer() {
        if (!editable() || containers == null) {
            return;
        }
        host.pushHistory();
        // 生成一个没被占用的默认名（container / container_2 / …），绝不覆盖已有条目。
        String id = "container";
        int suffix = 2;
        while (containers.ids().contains(id)) {
            id = "container_" + suffix++;
        }
        containers.addContainer(id, List.of());
        restructured();
    }

    private void renameContainer(String from, String to) {
        if (!editable() || containers == null) {
            return;
        }
        String target = to == null ? "" : to.trim();
        if (target.isEmpty() || target.equals(from) || containers.ids().contains(target)) {
            return;
        }
        host.pushHistory();
        containers.renameContainer(from, target);
        restructured();
    }

    private void removeContainer(String id) {
        if (!editable() || containers == null) {
            return;
        }
        host.pushHistory();
        containers.removeContainer(id);
        restructured();
    }

    /**
     * 打开选点会话，把选好的坐标<b>整体替换</b>成这个容器的坐标。
     *
     * <p>为什么是替换而不是追加：一个容器声明本来就代表「这一组箱子」，
     * 重选一遍的语义是「这批换掉了」。既然不再提供手填坐标，就不存在「在现有基础上补一个」
     * 这种操作 —— 两种语义混在一起最容易出现"以为加了其实覆盖了"。
     */
    private void pickPositions(String id) {
        if (!editable() || containers == null) {
            return;
        }
        // 数据层是纯 Gson（坐标是 int[3]），UI 侧用 BlockPos —— 边界上转两次。
        host.openPointPicker(toBlockPositions(containers.positions(id)), picked -> {
            if (containers == null) {
                return;
            }
            host.pushHistory();
            containers.setPositions(id, toIntArrays(picked));
            restructured();
        });
    }

    private static List<net.minecraft.core.BlockPos> toBlockPositions(List<int[]> positions) {
        List<net.minecraft.core.BlockPos> result = new ArrayList<>();
        for (int[] position : positions) {
            result.add(new net.minecraft.core.BlockPos(position[0], position[1], position[2]));
        }
        return result;
    }

    private static List<int[]> toIntArrays(List<net.minecraft.core.BlockPos> positions) {
        List<int[]> result = new ArrayList<>();
        for (net.minecraft.core.BlockPos position : positions) {
            result.add(new int[]{position.getX(), position.getY(), position.getZ()});
        }
        return result;
    }
}