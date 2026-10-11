package com.rstech.simupack.client.ui.editor;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.rstech.simupack.client.ui.ResizeGrip;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackMenuBar;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.client.ui.preview.StructurePreviewScene;
import com.rstech.simupack.client.ui.preview.UnitBoxVisual;
import com.rstech.simupack.creation.CreationBuilding;
import com.rstech.simupack.creation.residential.CreationUnit;
import com.rstech.simupack.creation.residential.ResidentialArrayValidator;
import com.rstech.simupack.creation.residential.UnitArraySpec;
import com.rstech.simupack.creation.residential.UnitBox;
import com.rstech.simupack.creation.residential.UnitLabelTemplate;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 住宅户型阵列页签。
 *
 * <p>与「中间区其它领域页签」同构：内容跟着当前建筑的户型列表走，
 * 参数是<b>页签私有状态</b>（不属于工程数据，不进 {@code .sk}）。
 *
 * <p>三条与既有实现一致的约定：
 * <ul>
 *   <li><b>不改 {@code jsonConfig}。</b>生成只动 {@code building.units()}，
 *       住宅没有 per-building JSON，走 {@code persist()} 会凭空造出 NSUK 不读的文件；</li>
 *   <li><b>一次生成一次入栈。</b>{@code pushHistory()} 只调一次，撤销一步回到生成前；</li>
 *   <li><b>只追加、不重排。</b>NSUK 归户是「前面的 {@code unit:} 行赢」，
 *       新户型追加在末尾，源户型保持原有优先级。</li>
 * </ul>
 *
 * <p><b>为什么预览区单独局部刷新</b>：文本框的 responder 里如果调
 * {@code host.refresh()}，会重建整个面板、销毁正在输入的那个控件，
 * 表现就是「打一个字就丢焦点」。所以参数框只改字段 + 重画预览与按钮状态。
 */
@OnlyIn(Dist.CLIENT)
public final class ResidentialArrayPanel extends DomainPanel {

    /** 源户型的多选集合；用 LinkedHashSet 保证生成顺序稳定。 */
    private final Set<String> selectedIds = new LinkedHashSet<>();

    private int offsetX;
    private int offsetY = 4;
    private int offsetZ;
    /** <b>新增</b>数量，不含源户型（见 {@link UnitArraySpec#newCount}）。 */
    private int newCount = 2;
    /** 名称模板；{@code {n}} 是新增序号通配符，见 {@link UnitLabelTemplate}。 */
    private String nameTemplate = UnitLabelTemplate.DEFAULT_TEMPLATE;

    /** 名称输入框；占位符说明挂在它的 tooltip 上，见 {@link #nameRow()}。 */
    private TextField nameField;

    /** 预览/校验宿主，参数变化时只重画这一个元素。 */
    private UIElement previewHost;
    /** 确认按钮：可用性随校验结果变化。 */
    private Button confirmButton;

    /**
     * 页签里的 3D 画布。
     *
     * <p><b>由面板自己创建一次、永不重建。</b>表单每次刷新都会被
     * {@code clearAllChildren()} 重建（见 {@link #renderMiddleInto}），画布若跟着重建，
     * 相机（缩放/旋转/平移）就会在每次点击后复位 —— 这与
     * {@code IndustrialActionPanel.requestFitView} 记录的是同一个教训：
     * 「取景/相机状态不能被 renderMiddleInto 反复重置」。
     */
    private final StructurePreviewScene scene = new StructurePreviewScene();
    /**
     * 画布宿主：铺满整个中间区，同时是悬浮卡片的定位祖先。
     *
     * <p>{@code positionType(RELATIVE)} 不能省：参数卡是 ABSOLUTE 定位，
     * 需要一个「已定位」的祖先作为包含块；静态定位的话卡片会锚到更外层
     * （{@code IndustrialActionPanel} 的 stage 上有同样的注释，那边踩过）。
     */
    private final UIElement canvasHost = new UIElement();
    /**
     * 悬浮卡片的<b>外框</b>：位置与尺寸都在它身上，拖边框改的就是它。
     *
     * <p>刻意不在这里加 {@code simupack_card}：卡片样式带 {@code padding-all: 6}，
     * 而绝对定位的子元素是相对<b>内边距盒</b>定位的 —— 拖拽边贴上去会内缩 6px，
     * 看起来像卡片里多了一条莫名其妙的条纹。所以外框只负责尺寸，
     * 视觉与内边距交给里面填满的 {@link #cardBody}。
     */
    private final UIElement cardHost = new UIElement();
    /** 视觉卡片本体：背景 + 内边距 + 纵向排布，填满 {@link #cardHost}。 */
    private final UIElement cardBody = new UIElement();
    /** 表单内容的宿主；它在卡片的滚动区里，每次刷新只重建它的内容。 */
    private final UIElement formHost = new UIElement();
    /**
     * 面板的根容器，也是稳定对象。
     *
     * <p>不每次刷新新建：那样会把 {@link #canvasHost} 换到新的父节点下，
     * 而 {@code Scene} 换父时是否会释放渲染资源无法离线确认（LDLib2 没有文档说明）。
     * 稳定根节点把「换父」这件事彻底排除掉，只有 {@code root} 自己被重新挂到宿主上 ——
     * 这与 {@code actionHost} 的既有用法一致。
     */
    private final UIElement root = SimuPackLayout.column(3);

    /** 悬浮卡片的宽度；参数行的标签列按它配（见 {@link #pinnedLabel}）。 */
    private static final float CARD_WIDTH = 216F;
    /** 卡片四周留出的边距，让画布在边缘仍可见、也便于看出它是浮层。 */
    private static final float CARD_MARGIN = 6F;
    /** 卡片初始高度（占画布高度的百分比）。 */
    private static final float CARD_HEIGHT_PERCENT = 92F;
    /** 拖拽边框的可调范围。太窄装不下参数行，太高会顶出画布。 */
    private static final float CARD_MIN_WIDTH = 150F;
    private static final float CARD_MIN_HEIGHT_PERCENT = 20F;
    private static final float CARD_MAX_HEIGHT_PERCENT = 100F;
    /** 拖拽边的厚度。 */
    private static final float GRIP = 4F;

    private float cardWidth = CARD_WIDTH;
    private float cardHeightPercent = CARD_HEIGHT_PERCENT;

    /** 是否正在拖边框改尺寸。 */
    private boolean resizing;
    private float resizeStartX;
    private float resizeStartY;
    private float resizeStartWidth;
    private float resizeStartPercent;
    /** 当前悬停 / 正在拖拽的是哪条边，用来决定鼠标指针形状。 */
    private ResizeGrip activeGrip;

    /** 已经装进画布的结构属于哪个建筑，避免每次刷新都重建假世界与 VBO。 */
    private CreationBuilding sceneBuilding;
    private CompoundTag sceneTag;

    /** 已定义但未选中的户型：淡蓝，只用来标出邻居边界。 */
    private static final float CONTEXT_RED = 0.45F;
    private static final float CONTEXT_GREEN = 0.85F;
    private static final float CONTEXT_BLUE = 1.0F;
    private static final float CONTEXT_ALPHA = 0.10F;
    /** 被选为阵列源的户型：青色，比参考盒子亮。 */
    private static final float SOURCE_RED = 0.20F;
    private static final float SOURCE_GREEN = 0.95F;
    private static final float SOURCE_BLUE = 1.0F;
    private static final float SOURCE_ALPHA = 0.22F;
    /** 本次将生成的副本：绿色（有冲突时改画红色，见 {@link #boxes}）。 */
    private static final float PLAN_RED = 0.35F;
    private static final float PLAN_GREEN = 0.95F;
    private static final float PLAN_BLUE = 0.35F;
    private static final float PLAN_ALPHA = 0.18F;
    /** 冲突时的副本颜色。 */
    private static final float CONFLICT_RED = 1.0F;
    private static final float CONFLICT_GREEN = 0.30F;
    private static final float CONFLICT_BLUE = 0.30F;

    public ResidentialArrayPanel(EditorHost host) {
        super(host);
        scene.addClass("simupack_preview_canvas");
        // 左键点方块 = 选中/取消选中该方块所属的户型（见 pickUnitAt）。
        // 命中面（face）用不上：户型的归属只看坐标落在哪个范围内。
        scene.onPick((pos, face) -> pickUnitAt(pos));
        scene.onPickMiss(() -> host.notify(
                Component.translatable("gui.simupack.residential.array.miss").getString()));
        SimuPackLayout.fill(scene);
        // 卡片是画布的兄弟节点且画在上面，但仍要显式告诉场景「指针在卡片里时别响应」：
        // 滚轮事件的派发路径不保证只给最上层元素。
        scene.setInputOverlay(cardHost);

        canvasHost.layout(layout -> {
            layout.widthPercent(100F);
            layout.flex(1F);
            layout.flexShrink(1F);
            layout.minWidth(0F);
            layout.minHeight(0F);
            layout.positionType(TaffyPosition.RELATIVE);
        });
        canvasHost.addChild(scene);
        // 后加 = 画在上面。
        canvasHost.addChild(cardHost);

        buildCard();

        SimuPackLayout.fill(root);
        // 拖拽的「移动 / 抬起」挂在根容器上，而不是拖拽边自己身上：
        // 指针拖快了会离开那 4px 宽的边，事件就收不到了（工业面板的画布平移同理）。
        root.addEventListener(UIEvents.MOUSE_MOVE, this::updateResize);
        root.addEventListener(UIEvents.MOUSE_UP, event -> endResize());
        root.addEventListener(UIEvents.MOUSE_LEAVE, event -> endResize());
        root.addChild(canvasHost);
    }

    /**
     * 搭出悬浮参数卡（只搭一次，之后 {@link #renderForm} 只换里面的内容）。
     *
     * <p>结构：外框 {@link #cardHost}（定尺寸）+ 本体 {@link #cardBody}（背景与内边距）
     * + 左/下两条拖拽边。拖动这两条边就是「拖窗口边框改大小」，
     * 上边与右边固定在 {@link #CARD_MARGIN} 处不动。
     *
     * <p>「确认生成」放在滚动区<b>外</b>：参数行有十来行，放滚动区里会被挤出可视范围
     * （上一版就出现过按钮落在卡片外面看不见）。
     */
    private void buildCard() {
        cardHost.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.top(CARD_MARGIN);
            layout.right(CARD_MARGIN);
            layout.width(cardWidth);
            // 高度按百分比给：卡片要随窗口缩放，固定像素在大窗口下会显得很矮。
            layout.heightPercent(cardHeightPercent);
        });

        cardBody.addClass("simupack_card");
        cardBody.layout(layout -> {
            layout.widthPercent(100F);
            layout.heightPercent(100F);
            layout.minWidth(0F);
            layout.minHeight(0F);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(3);
        });
        cardBody.addChild(sectionHeader("gui.simupack.residential.array"));

        formHost.layout(layout -> {
            layout.widthPercent(100F);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView scroll = SimuPackLayout.scroller();
        scroll.addScrollViewChild(formHost);
        cardBody.addChild(scroll);

        confirmButton = textButton(Component.translatable("gui.simupack.residential.array.confirm").getString(),
                0F, this::generate, false);
        UIElement actions = SimuPackLayout.buttonRow(4);
        actions.layout(layout -> layout.flexShrink(0F));
        actions.addChild(confirmButton);
        cardBody.addChild(actions);

        cardHost.addChild(cardBody);
        // 拖拽边在外框上、贴在边框内侧：本体有 6px 内边距，4px 的边只压到内边距，不挡内容。
        cardHost.addChild(edgeGrip(true));
        cardHost.addChild(edgeGrip(false));
    }

    /**
     * 一条拖拽边。
     *
     * @param vertical true = 贴在左边缘（改宽度）；false = 贴在下边缘（改高度）
     */
    private UIElement edgeGrip(boolean vertical) {
        ResizeGrip grip = vertical ? ResizeGrip.horizontal() : ResizeGrip.vertical();
        grip.addClass("simupack_resize_grip");
        grip.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(0F);
            if (vertical) {
                layout.top(0F);
                layout.width(GRIP);
                layout.heightPercent(100F);
            } else {
                layout.bottom(0F);
                layout.height(GRIP);
                layout.widthPercent(100F);
            }
        });
        grip.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button != 0) {
                return;
            }
            resizing = true;
            activeGrip = grip;
            grip.setDragging(true);
            resizeStartX = event.x;
            resizeStartY = event.y;
            resizeStartWidth = cardWidth;
            resizeStartPercent = cardHeightPercent;
            event.stopPropagation();
        });
        return grip;
    }

    private void endResize() {
        resizing = false;
        if (activeGrip != null) {
            activeGrip.setDragging(false);
            activeGrip = null;
        }
    }

    /** 拖拽中：改外框尺寸。上/右边缘固定在原位，所以向左拖变宽、向下拖变高。 */
    private void updateResize(UIEvent event) {
        if (!resizing) {
            return;
        }
        float dx = event.x - resizeStartX;
        float dy = event.y - resizeStartY;
        float canvasHeight = Math.max(1F, canvasHost.getContentHeight());
        cardWidth = clamp(resizeStartWidth - dx, CARD_MIN_WIDTH, maxCardWidth());
        cardHeightPercent = clamp(resizeStartPercent + dy / canvasHeight * 100F,
                CARD_MIN_HEIGHT_PERCENT, CARD_MAX_HEIGHT_PERCENT);
        applyCardSize();
        event.stopPropagation();
    }

    private void applyCardSize() {
        cardHost.layout(layout -> {
            layout.width(cardWidth);
            layout.heightPercent(cardHeightPercent);
        });
    }

    /** 卡片最多占满画布宽度（两侧各留一个 {@link #CARD_MARGIN}）。 */
    private float maxCardWidth() {
        float canvasWidth = canvasHost.getContentWidth();
        if (canvasWidth <= 0F) {
            return CARD_WIDTH * 4F; // 还没布局，先给一个宽松上限
        }
        return Math.max(CARD_MIN_WIDTH, canvasWidth - CARD_MARGIN * 2F);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected boolean editable() {
        return building != null && !readOnly;
    }

    @Override
    public void renderMiddleInto(UIElement container) {
        container.clearAllChildren();
        // root / canvasHost / formHost 都是构造期建好的稳定对象：这里只把自己挂回宿主，
        // 再重建表单内容。画布所在的子树从头到尾没被动过，相机状态因此不会丢。
        container.addChild(root);
        renderForm();
        syncScene();
    }

    /** 重建表单区；画布不在这一层，见 {@link #renderMiddleInto}。 */
    private void renderForm() {
        formHost.clearAllChildren();
        if (building == null) {
            formHost.addChild(wrapped("gui.simupack.editor.no_building", SimuPackTheme.TEXT_MUTED_COLOR));
            return;
        }
        if (building.units().isEmpty()) {
            formHost.addChild(wrapped("gui.simupack.residential.array.no_source", SimuPackTheme.TEXT_WARNING_COLOR));
            return;
        }

        UIElement root = SimuPackLayout.column(3);
        SimuPackLayout.fill(root);

        // 整张表单放进滚动区：户型可能有几十条，参数行也有十来行，
        // 标题、操作提示与「确认生成」都在卡片的固定区域里（见 buildCard），
        // 这里只放会随数据变化的内容。
        UIElement form = SimuPackLayout.column(4);
        form.layout(layout -> {
            layout.widthPercent(100F);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        form.addChild(sectionHeader("gui.simupack.residential.array.sources"));
        form.addChild(sourceList());
        form.addChild(selectionActions());
        form.addChild(sectionHeader("gui.simupack.residential.array.parameters"));
        form.addChild(numberRow("gui.simupack.residential.array.offset_x", offsetX, value -> offsetX = value));
        form.addChild(numberRow("gui.simupack.residential.array.offset_y", offsetY, value -> offsetY = value));
        form.addChild(numberRow("gui.simupack.residential.array.offset_z", offsetZ, value -> offsetZ = value));
        form.addChild(numberRow("gui.simupack.residential.array.new_count", newCount, value -> newCount = value));
        form.addChild(nameRow());
        // 名称语法提示、名称示例、将新增几个、冲突列表全都放进 previewHost：
        // 它们都会随参数变化，必须由 refreshPreview() 统一重建。
        // （早先把「名称示例」直接加在 form 上，结果改数量/模板后它一直不动 —— 典型的
        //  「输入框失焦了但界面没更新」，因为那段静态文本根本不在刷新范围内。）
        previewHost = SimuPackLayout.column(2);
        previewHost.layout(layout -> {
            layout.widthPercent(100F);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        form.addChild(previewHost);

        formHost.addChild(form);
        refreshPreview();
    }

    // ── 画布 ───────────────────────────────────────────────────────────

    /**
     * 把结构与户型盒子同步到画布。
     *
     * <p>只在<b>建筑换了或结构内容变了</b>时才调 {@code showStructure}：
     * 它会重建假世界和渲染缓冲，而且会把相机自动取景一次。
     * 每次刷新都调的话，用户点一下户型就被拉回默认视角。
     */
    private void syncScene() {
        if (building == null) {
            return;
        }
        CompoundTag tag = host.structureTag();
        // EditorHost 返回的是按结构来源缓存的同一个根对象；用身份比较避免每次刷新深遍历 NBT。
        if (sceneBuilding != building || sceneTag != tag) {
            scene.showStructure(tag);
            sceneBuilding = building;
            sceneTag = tag;
        }
        scene.setUnitBoxes(boxes());
    }

    /**
     * 画布上要画的盒子。
     *
     * <p>三层含义用颜色区分，都画出来才能一眼看出「选了什么、会生成到哪、会不会撞」：
     * <ul>
     *   <li>未选中的已有户型 —— 淡蓝参考；</li>
     *   <li>选中的源户型 —— 青色；</li>
     *   <li>本次将生成的副本 —— 绿色；一旦有 ERROR 就整体改红，
     *       用户在点「确认生成」之前就知道撞在哪。</li>
     * </ul>
     */
    private List<UnitBoxVisual> boxes() {
        List<UnitBoxVisual> boxes = new ArrayList<>();
        if (building == null) {
            return boxes;
        }
        boolean conflict = ResidentialArrayValidator.hasError(currentIssues());
        for (CreationUnit unit : building.units()) {
            if (!unit.hasRange()) {
                continue;
            }
            boolean selected = selectedIds.contains(unit.id());
            boxes.add(visual(unit,
                    selected ? SOURCE_RED : CONTEXT_RED,
                    selected ? SOURCE_GREEN : CONTEXT_GREEN,
                    selected ? SOURCE_BLUE : CONTEXT_BLUE,
                    selected ? SOURCE_ALPHA : CONTEXT_ALPHA));
        }
        for (CreationUnit copy : plannedCopies()) {
            boxes.add(visual(copy,
                    conflict ? CONFLICT_RED : PLAN_RED,
                    conflict ? CONFLICT_GREEN : PLAN_GREEN,
                    conflict ? CONFLICT_BLUE : PLAN_BLUE,
                    PLAN_ALPHA));
        }
        return boxes;
    }

    private static UnitBoxVisual visual(CreationUnit unit, float red, float green, float blue, float alpha) {
        return UnitBoxVisual.boxed(
                unit.min().getX(), unit.min().getY(), unit.min().getZ(),
                unit.max().getX(), unit.max().getY(), unit.max().getZ(),
                red, green, blue, alpha, true);
    }

    /**
     * 画布点选：把点中的方块所属户型加入或移出源选择。
     *
     * <p>点空气走 {@code onPickMiss}（画布只在有方块的坐标上命中），所以这里不用处理。
     *
     * <p><b>重叠时选哪个</b>：NSUK 的归户是「第一个命中的 {@code unit:} 行赢」
     * （方案 §2.3），所以这里选<b>行序最前</b>的那个 —— 它才是游戏里真正拿到床位的户型。
     * 同时明确告诉用户存在重叠，而不是静默替他挑一个。
     */
    private void pickUnitAt(BlockPos pos) {
        if (building == null || pos == null) {
            return;
        }
        List<CreationUnit> hits = new ArrayList<>();
        for (CreationUnit unit : building.units()) {
            if (unit.hasRange() && unit.contains(pos)) {
                hits.add(unit);
            }
        }
        if (hits.isEmpty()) {
            host.notify(Component.translatable("gui.simupack.residential.array.pick_outside").getString());
            return;
        }
        CreationUnit target = hits.get(0);
        if (!selectedIds.remove(target.id())) {
            selectedIds.add(target.id());
        }
        if (hits.size() > 1) {
            host.notify(Component.translatable("gui.simupack.residential.array.pick_overlap",
                    hits.size(), target.label()).getString());
        } else {
            host.notify(Component.translatable(
                    selectedIds.contains(target.id())
                            ? "gui.simupack.residential.array.picked"
                            : "gui.simupack.residential.array.unpicked",
                    target.label()).getString());
        }
        host.refresh();
    }

    // ── 源户型多选 ─────────────────────────────────────────────────────

    private UIElement sourceList() {
        UIElement list = SimuPackLayout.column(2);
        list.layout(layout -> {
            layout.widthPercent(100F);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        for (CreationUnit unit : building.units()) {
            list.addChild(sourceRow(unit));
        }
        return list;
    }

    private UIElement sourceRow(CreationUnit unit) {
        UIElement row = SimuPackLayout.row(3);
        row.layout(layout -> {
            layout.widthPercent(100F);
            layout.height(EditorWidgets.ROW_HEIGHT);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        boolean selected = selectedIds.contains(unit.id());
        row.addChild(textButton(selected ? "✔" : "○", EditorWidgets.SMALL_BUTTON,
                () -> toggleSelection(unit), true));
        String range = unit.hasRange()
                ? format(unit.min()) + " ~ " + format(unit.max())
                : Component.translatable("gui.simupack.residential.no_range").getString();
        row.addChild(SimuPackLayout.shrinkable(SimuPackLayout.label(
                Component.literal(unit.label() + "   " + range),
                unit.hasRange() ? SimuPackTheme.TEXT_SECONDARY_COLOR : SimuPackTheme.TEXT_WARNING_COLOR)));
        return row;
    }

    private UIElement selectionActions() {
        UIElement row = SimuPackLayout.buttonRow(4);
        row.addChild(textButton(Component.translatable("gui.simupack.residential.array.select_all").getString(),
                0F, this::selectAll, true));
        row.addChild(textButton(Component.translatable("gui.simupack.residential.array.select_none").getString(),
                0F, this::selectNone, true));
        return row;
    }

    private void toggleSelection(CreationUnit unit) {
        if (unit == null) {
            return;
        }
        if (!selectedIds.remove(unit.id())) {
            selectedIds.add(unit.id());
        }
        // 勾选记号是按钮文字，只有重建表单才会更新；而点击不是连续输入，
        // 重建不会打断用户（参数文本框才必须避免重建，见 numberRow 的注释）。
        host.refresh();
    }

    private void selectAll() {
        for (CreationUnit unit : building.units()) {
            if (unit.hasRange()) {
                selectedIds.add(unit.id());
            }
        }
        host.refresh();
    }

    private void selectNone() {
        selectedIds.clear();
        host.refresh();
    }

    // ── 参数行 ─────────────────────────────────────────────────────────

    /** 第一个有范围、且被选中作为源的户型；没有则返回 null（名称示例用）。 */
    private CreationUnit firstSelectedWithRange() {
        for (CreationUnit unit : building.units()) {
            if (selectedIds.contains(unit.id()) && unit.hasRange()) {
                return unit;
            }
        }
        return null;
    }

    /**
     * 名称示例：按当前模板生成前几个新增户型的名字，例如 {@code 101_1、101_2、101_3}。
     *
     * <p>这就是「让用户知道可以用 {@code {n}} 通配」的方式 —— 不用写帮助文档，
     * 改模板就能立刻看见结果。没有选中源户型时退回用第一个有范围的户型当样例，
     * 这样即使还没勾选也能看出模板效果。
     */
    private String namePreview() {
        CreationUnit sample = firstSelectedWithRange();
        if (sample == null) {
            for (CreationUnit unit : building.units()) {
                if (unit.hasRange()) {
                    sample = unit;
                    break;
                }
            }
        }
        if (sample == null) {
            return "-";
        }
        // 最多展示 3 个：再多会把卡片撑宽、也超出「看一眼就知道规则」的目的。
        int shown = Math.max(1, Math.min(3, newCount));
        StringBuilder builder = new StringBuilder();
        for (int n = 1; n <= shown; n++) {
            if (n > 1) {
                builder.append("、");
            }
            builder.append(UnitLabelTemplate.label(nameTemplate, sample.label(), n));
        }
        if (newCount > shown) {
            builder.append(" …");
        }
        return builder.toString();
    }

    private UIElement pinnedLabel(String key) {
        // 标签列宽按卡片宽度定：卡片只有 216 宽，110 会把输入框挤到只剩几十像素。
        return SimuPackLayout.pinWidth(SimuPackLayout.label(Component.translatable(key),
                SimuPackTheme.TEXT_SECONDARY_COLOR), 84F);
    }

    /**
     * 整数参数行。
     *
     * <p>responder 里<b>不</b>调 {@code host.refresh()}：那会重建面板并销毁正在输入的控件。
     * 只改字段，然后重画预览（校验会在确认时再跑一次，保证不会用过期结果生成）。
     */
    private UIElement numberRow(String key, int value, java.util.function.IntConsumer setter) {
        UIElement row = SimuPackLayout.row(3);
        row.layout(layout -> {
            layout.widthPercent(100F);
            layout.height(EditorWidgets.ROW_HEIGHT);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        row.addChild(pinnedLabel(key));
        TextField field = textField();
        field.setText(Integer.toString(value), false);
        field.setTextRegexValidator("-?\\d+");
        field.setTextResponder(text -> {
            try {
                setter.accept(Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                // 正则已经挡掉了非数字；真解析不了就保持原值，不要清成 0。
            }
            refreshPreview();
        });
        field.layout(layout -> {
            layout.flex(1F);
            layout.minWidth(0F);
            layout.height(EditorWidgets.ROW_HEIGHT);
        });
        row.addChild(field);
        return row;
    }

    /**
     * 名称模板行。
     *
     * <p>支持的占位符写法放在输入框的 <b>tooltip</b> 里，而不是占一行文字：
     * 那句话在 216px 宽的卡片里会折成三行还溢出、压到下一行上；
     * 而 tooltip 悬浮才出现，既不占版面也不打断阅读。
     */
    private UIElement nameRow() {
        UIElement row = SimuPackLayout.row(3);
        row.layout(layout -> {
            layout.widthPercent(100F);
            layout.height(EditorWidgets.ROW_HEIGHT);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        row.addChild(pinnedLabel("gui.simupack.residential.array.template"));
        nameField = textField();
        nameField.setAnyString();
        nameField.setText(nameTemplate == null ? "" : nameTemplate, false);
        nameField.setTextResponder(text -> {
            nameTemplate = text == null ? "" : text;
            refreshPreview();
        });
        nameField.style(style -> style.tooltips(
                Component.translatable("gui.simupack.residential.array.name_tooltip")));
        nameField.layout(layout -> {
            layout.flex(1F);
            layout.minWidth(0F);
            layout.height(EditorWidgets.ROW_HEIGHT);
        });
        row.addChild(nameField);
        return row;
    }

    // ── 预览与校验 ─────────────────────────────────────────────────────

    private UnitArraySpec spec() {
        return new UnitArraySpec(offsetX, offsetY, offsetZ, newCount, nameTemplate);
    }

    /** 本次将新增的户型（保序：按源户型在列表中的顺序，各自 1..newCount）。 */
    private List<CreationUnit> plannedCopies() {
        List<CreationUnit> result = new ArrayList<>();
        if (building == null) {
            return result;
        }
        UnitArraySpec spec = spec();
        for (CreationUnit unit : building.units()) {
            if (selectedIds.contains(unit.id())) {
                result.addAll(spec.expand(unit));
            }
        }
        return result;
    }

    private List<ResidentialArrayValidator.Issue> currentIssues() {
        List<ResidentialArrayValidator.Issue> issues = new ArrayList<>();
        if (building == null) {
            return issues;
        }
        if (selectedIds.isEmpty()) {
            return issues;
        }
        for (CreationUnit unit : building.units()) {
            if (selectedIds.contains(unit.id()) && !unit.hasRange()) {
                issues.add(ResidentialArrayValidator.Issue.error("source_no_range", unit.label()));
            }
        }
        if (!spec().valid()) {
            issues.add(ResidentialArrayValidator.Issue.error("invalid_spec", ""));
            return issues;
        }
        List<ResidentialArrayValidator.Placed> generated = new ArrayList<>();
        for (CreationUnit copy : plannedCopies()) {
            generated.add(placed(copy));
        }
        List<ResidentialArrayValidator.Placed> existing = new ArrayList<>();
        for (CreationUnit unit : building.units()) {
            if (unit.hasRange()) {
                existing.add(placed(unit));
            }
        }
        int sizeX = bounds == null ? 0 : bounds.sizeX();
        int sizeY = bounds == null ? 0 : bounds.sizeY();
        int sizeZ = bounds == null ? 0 : bounds.sizeZ();
        issues.addAll(ResidentialArrayValidator.validate(generated, existing, sizeX, sizeY, sizeZ));
        return issues;
    }

    private static ResidentialArrayValidator.Placed placed(CreationUnit unit) {
        return new ResidentialArrayValidator.Placed(
                UnitBox.of(unit.min().getX(), unit.min().getY(), unit.min().getZ(),
                        unit.max().getX(), unit.max().getY(), unit.max().getZ()),
                unit.label());
    }

    /**
     * 只重画预览区与确认按钮，不重建整张表单（重建会销毁正在输入的控件、丢焦点）。
     *
     * <p>这里必须把「会随参数变化的东西」全部覆盖到，否则就会出现
     * 「输入框失焦提交了、界面却没变」：
     * <ul>
     *   <li>名称语法提示 + 名称示例（模板、新增数量都会影响它）；</li>
     *   <li>将新增几个 / 冲突列表；</li>
     *   <li><b>画布上的盒子</b> —— 改了偏移却不重画，绿色预览框会停在旧位置。</li>
     * </ul>
     */
    private void refreshPreview() {
        if (previewHost == null) {
            return;
        }
        List<ResidentialArrayValidator.Issue> issues = currentIssues();
        int count = plannedCopies().size();
        previewHost.clearAllChildren();
        previewHost.addChild(SimuPackLayout.wrappedLabel(Component.translatable(
                        "gui.simupack.residential.array.name_preview", namePreview()),
                SimuPackTheme.TEXT_MUTED_COLOR));

        if (selectedIds.isEmpty()) {
            previewHost.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.residential.array.select_source"),
                    SimuPackTheme.TEXT_WARNING_COLOR));
        } else {
            previewHost.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.residential.array.preview_count", count),
                    count > 0 ? SimuPackTheme.TEXT_SECONDARY_COLOR : SimuPackTheme.TEXT_WARNING_COLOR));
        }

        for (ResidentialArrayValidator.Issue issue : issues) {
            boolean error = issue.severity() == ResidentialArrayValidator.Severity.ERROR;
            previewHost.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable(issue.translationKey(),
                            issue.detail().isEmpty() ? "-" : issue.detail()),
                    error ? SimuPackTheme.TEXT_ERROR_COLOR : SimuPackTheme.TEXT_WARNING_COLOR));
        }
        if (confirmButton != null) {
            confirmButton.setActive(canGenerate());
        }
        // 画布上的盒子也跟着参数走（未选中的淡蓝、选中的青色、待生成的绿/红）。
        scene.setUnitBoxes(boxes());
    }

    private boolean canGenerate() {
        return editable() && !selectedIds.isEmpty() && !plannedCopies().isEmpty()
                && !ResidentialArrayValidator.hasError(currentIssues());
    }

    // ── 生成 ───────────────────────────────────────────────────────────

    private void generate() {
        if (!editable()) {
            return;
        }
        // 确认时重新校验一次：文本框是失焦提交的，按钮的可用状态可能落后于字段。
        List<ResidentialArrayValidator.Issue> issues = currentIssues();
        if (ResidentialArrayValidator.hasError(issues)) {
            host.notify(Component.translatable("gui.simupack.residential.array.blocked").getString());
            refreshPreview();
            return;
        }
        List<CreationUnit> copies = plannedCopies();
        if (copies.isEmpty()) {
            return;
        }
        host.pushHistory();
        building.units().addAll(copies);
        host.commit();
        host.refresh();
        host.notify(Component.translatable("gui.simupack.residential.array.generated", copies.size()).getString());
    }

    // ── 文案键 ─────────────────────────────────────────────────────────

    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
