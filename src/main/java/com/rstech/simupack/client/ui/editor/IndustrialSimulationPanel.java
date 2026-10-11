/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonObject
 *  com.lowdragmc.lowdraglib2.gui.ui.UIElement
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.Button
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.Label
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.TextField
 *  com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent
 *  com.rstech.simupack.client.ui.ResizeGrip
 *  com.rstech.simupack.client.ui.SimuPackLayout
 *  com.rstech.simupack.client.ui.SimuPackMenuBar$Entry
 *  com.rstech.simupack.client.ui.preview.StructurePreviewScene
 *  com.rstech.simupack.creation.CreationBuilding
 *  com.rstech.simupack.creation.industrial.IndustrialStepType
 *  com.rstech.simupack.creation.industrial.sim.SimGesture
 *  com.rstech.simupack.creation.industrial.sim.SimInventory
 *  com.rstech.simupack.creation.industrial.sim.SimNavigation
 *  com.rstech.simupack.creation.industrial.sim.SimPos
 *  com.rstech.simupack.creation.industrial.sim.SimStepSpec
 *  com.rstech.simupack.creation.industrial.sim.SimWaypoint
 *  com.rstech.simupack.creation.industrial.sim.SimulationPlan
 *  com.rstech.simupack.creation.industrial.sim.SimulationPlan$Entry
 *  com.rstech.simupack.creation.industrial.sim.SimulationRunner
 *  com.rstech.simupack.creation.industrial.sim.SimulationRunner$Focus
 *  com.rstech.simupack.creation.industrial.sim.SimulationRunner$Result
 *  com.rstech.simupack.creation.industrial.sim.SimulationRunner$Status
 *  com.rstech.simupack.creation.industrial.sim.SimulationRunner$StepOutcome
 *  com.rstech.simupack.creation.industrial.sim.SimulationSession
 *  com.rstech.simupack.creation.industrial.sim.SimulationSession$Pose
 *  com.rstech.simupack.creation.json.BuildingJsonDocument
 *  com.rstech.simupack.creation.json.BuildingJsonDocument$DefinitionKind
 *  dev.vfyjxf.taffy.style.TaffyPosition
 *  net.minecraft.core.BlockPos
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.network.chat.Component
 *  net.minecraft.world.item.ItemStack
 *  net.neoforged.api.distmarker.Dist
 *  net.neoforged.api.distmarker.OnlyIn
 */
package com.rstech.simupack.client.ui.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.utils.ModularUITooltipComponent;
import com.rstech.simupack.client.ui.ResizeGrip;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackMenuBar;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.client.ui.editor.EditorDialog;
import com.rstech.simupack.client.ui.editor.EditorHost;
import com.rstech.simupack.client.ui.editor.EditorWidgets;
import com.rstech.simupack.client.ui.editor.ItemRegistry;
import com.rstech.simupack.client.ui.editor.MiddleTabKind;
import com.rstech.simupack.client.ui.preview.StructurePreviewScene;
import com.rstech.simupack.creation.CreationBuilding;
import com.rstech.simupack.creation.industrial.IndustrialStepType;
import com.rstech.simupack.creation.industrial.sim.SimBackpack;
import com.rstech.simupack.creation.industrial.sim.SimGesture;
import com.rstech.simupack.creation.industrial.sim.SimInventory;
import com.rstech.simupack.creation.industrial.sim.SimNavigation;
import com.rstech.simupack.creation.industrial.sim.SimPos;
import com.rstech.simupack.creation.industrial.sim.SimStepSpec;
import com.rstech.simupack.creation.industrial.sim.SimWaypoint;
import com.rstech.simupack.creation.industrial.sim.SimulationPlan;
import com.rstech.simupack.creation.industrial.sim.SimBackpack;
import com.rstech.simupack.creation.industrial.sim.SimulationRunner;
import com.rstech.simupack.creation.industrial.sim.SimulationSession;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;

@OnlyIn(value=Dist.CLIENT)
public final class IndustrialSimulationPanel {
    private static final float CARD_WIDTH = 292.0f;
    /**
     * 悬浮参数卡的默认高度。
     *
     * <p>原来给 92%（几乎占满画布），而卡片刚出现时只有「配方 + 控制按钮 + 一句提示」，
     * 下面一大片是空的 —— 看着像界面坏了。时间轴本来就在 ScrollerView 里，
     * 内容多了自己滚动，所以默认给一半就够了，需要更大可以拖下边缘。
     */
    private static final float CARD_HEIGHT_PERCENT = 52.0f;
    private static final float CARD_MARGIN = 6.0f;
    private static final float CARD_MIN_WIDTH = 180.0f;
    private static final float CARD_MIN_HEIGHT_PERCENT = 20.0f;
    private static final float CARD_MAX_HEIGHT_PERCENT = 100.0f;
    private static final float GRIP = 4.0f;
    /** 容器库存浮层的宽度与「离鼠标多远」。 */
    private static final float POPUP_WIDTH = 186.0f;
    /** 浮层边缘离光标多少像素 —— 要「紧贴」，所以只有 8。 */
    private static final float POPUP_EDGE = 8.0f;
    /** 离开容器后延迟多少 tick 才关浮层（给鼠标挪到浮层上留出时间）。 */
    private static final int POPUP_CLOSE_GRACE = 8;
    /** 浮层的内边距、单行高度、行距 —— 用来算出一个确定的内容高度。 */
    private static final float POPUP_PADDING = 4.0f;
    private static final float POPUP_ROW_HEIGHT = 14.0f;
    private static final float POPUP_GAP = 2.0f;
    /** 播放速度倍率；0.5 靠下面 tick() 里的预算累加实现（advance 最少走 1 tick，没法直接传 0.5）。 */
    private static final double[] SPEEDS = new double[]{0.5, 1.0, 2.0, 4.0, 8.0};
    private static final int NO_INDEX = -1;
    private static final long PREVIEW_SEED = 20261008L;
    private static final List<String> CONTAINER_KEYS = List.of("container", "input", "output");
    private final EditorHost host;
    private final EditorWidgets widgets;
    /**
     * 场景用**透视**投影（第 2 个参数 false）。
     *
     * <p>默认构造是 {@code this(true, true)} —— 正交。正交下滚轮只是「平面缩放」，
     * 相机与结构的距离固定，**永远进不到内部**，看内饰时始终被外墙挡着。
     * 透视下滚轮是推拉相机，推进去就穿进墙里看到室内了 —— 这正是仿真看内饰需要的。
     * （第 1 个参数保留 true：结构静态，缓存省重绘；开箱盖时 needCompileCache() 会按需重建。）
     */
    // 第一个参数是**渲染缓存**：静态结构走缓存（省掉每帧逐方块重画），
    // 而仿真会改动的方块由 setDynamicBlocks 从缓存里切出去、交给不缓存的动态层每帧现画 ——
    // 于是播放全程一次缓存重编都不需要（重编就是那个闪屏）。
    private final StructurePreviewScene scene = new StructurePreviewScene(true, false);

    /**
     * 产出源：动物掉落 / 剪毛 / 繁殖食物，以及**破坏方块**的掉落，都走真实数据。
     *
     * <p>绑在场景上而不是做成单例：破坏方块要按坐标读预览世界里「现在是什么方块」，
     * 那只有场景知道。用 {@code Supplier} 而不是存引用，是为了建筑切换后还能拿到新的那个世界。
     */
    private final com.rstech.simupack.creation.industrial.sim.SimLootProvider lootProvider =
            new com.rstech.simupack.client.ui.preview.IntegratedServerLootProvider(this.scene::previewWorld);

    /**
     * 按坐标查方块 —— 成簇收获要找「匹配 {@code #minecraft:logs} 的连通块」。
     *
     * <p>候选坐标取自场景手里的结构方块表，而不是逐格遍历工作区（后者可能有几十万格）。
     */
    private final com.rstech.simupack.creation.industrial.sim.SimBlockMatcher blockMatcher =
            new com.rstech.simupack.client.ui.preview.PreviewWorldBlockMatcher(
                    this.scene::previewWorld, this.scene::structurePositions);

    private final SimulationRunner.Focus focusResolver = new SimulationRunner.Focus(){

        public List<SimPos> positionsOf(SimStepSpec step) {
            return IndustrialSimulationPanel.this.focusOf(step);
        }

        public boolean knowsContainer(String name) {
            // NSUK 的 resolveContainerPositions 除了「名字存在」，还要求 type == "structure_pos"
            // 且坐标能解析出来（IndustrialControlBoxService:231-240）—— 缺任何一条都返回空表，
            // 步骤随即 WAITING_RETRY。这里照此校验，免得「写错类型的容器」在仿真里看不出问题。
            if (IndustrialSimulationPanel.this.document == null
                    || !IndustrialSimulationPanel.this.document.isContainerPoint(name)) {
                return false;
            }
            JsonObject definition = IndustrialSimulationPanel.this.document
                    .definitionEntry(name, BuildingJsonDocument.DefinitionKind.CONTAINER);
            if (definition == null) {
                return false;
            }
            String type = IndustrialSimulationPanel.string(definition, "type");
            if (!type.isBlank() && !"structure_pos".equalsIgnoreCase(type)) {
                return false;
            }
            return !IndustrialSimulationPanel.this
                    .definitionPositions(name, BuildingJsonDocument.DefinitionKind.CONTAINER)
                    .isEmpty();
        }

        public int entityCount(String entityType) {
            return IndustrialSimulationPanel.this.countPreviewAnimals(entityType);
        }
    };

    /**
     * 预览里现在有多少只这种动物。
     *
     * <p>剪毛 / 屠宰要用它判成败：真实实现是「在半径内找实体，找不到就重试」，
     * 所以没刷怪就不该成功 —— 这也让建筑的「刷怪」设置真的能被验证。
     */
    /**
     * 这个位置在不在建筑外框（膨胀 {@code inflate} 格）之内。
     *
     * <p>NSUK 数「同类实体有几个」时用的是 {@code buildingBounds(building).inflate(2.0)}
     * （{@code IndustrialEntitySpawnService:94-97}）—— 不加这个过滤会把建筑外很远处的
     * 同类实体也数进来，于是「动物不够」永远触发不了。
     */
    private boolean inBuildingBounds(BlockPos pos, int inflate) {
        BlockPos min = this.scene.structureMinimum();
        BlockPos max = this.scene.structureMaximum();
        if (min == null || max == null) {
            return true;
        }
        return pos.getX() >= min.getX() - inflate && pos.getX() <= max.getX() + inflate
                && pos.getZ() >= min.getZ() - inflate && pos.getZ() <= max.getZ() + inflate;
    }

    private int countPreviewAnimals(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return 0;
        }
        String wanted = entityType.trim();
        if (wanted.indexOf(':') < 0) {
            wanted = "minecraft:" + wanted;
        }
        int count = 0;
        for (net.minecraft.world.entity.Entity animal : this.scene.previewAnimals()) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString().equals(wanted)
                    && inBuildingBounds(animal.blockPosition(), 2)) {
                count++;
            }
        }
        return count;
    }
    private final UIElement root = new UIElement();
    /**
     * 画布。这里特意用了匿名子类，只为在绘制时抓一次**画布局部坐标下的鼠标位置**。
     *
     * <p>为什么不用绝对坐标自己换算：换算是错的来源。「鼠标绝对坐标 − 元素绝对位置」
     * 只要差一层（padding、分栏偏移、缩放），浮层就会被甩到几百像素外，
     * 而且和命中判定用的是两把尺子，表现就是「看着在那儿、鼠标碰不到」。
     * {@code context.localMouseX/localMouseY} 是框架算好的、就在本元素坐标系里的值 ——
     * 分栏分隔条的 {@code SplitView} 和拖动边框的 {@code ResizeGrip} 都靠它，
     * 是这套 UI 里唯一被证明「位置一定对」的来源。
     */
    private final UIElement canvasHost = new UIElement() {
        @Override
        public void drawBackgroundAdditional(com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext context) {
            super.drawBackgroundAdditional(context);
            refreshPopupMouse();
            if (popupNeedsAnchor && popupHost.isDisplayed()) {
                anchorPopupToMouse();
            }
        }
    };

    /**
     * 把鼠标换算到**画布坐标系**。
     *
     * <p>不能用 {@code context.localMouseX} 在这里直接读：那个值只为「鼠标悬停的那个元素」
     * 刷新，在本元素（画布）的绘制回调里读到的其实是**场景**的局部坐标。
     * 也不能拿绝对鼠标坐标去减绝对位置 —— 那是两把尺子，差一层就甩出几百像素
     * （前面几轮就是栽在这里）。
     *
     * <p>正确做法是取**差值**：{@code 场景.localMouse + (场景绝对位置 − 画布绝对位置)}。
     * 两个绝对位置都用同一条递归规则算出来，相减会把共同的基准偏差抵消掉，
     * 剩下的偏移量是精确的。
     */
    private void refreshPopupMouse() {
        // 依据（诊断截图 m=299,208，而光标画布局部约 (289,152)）：scene.localMouseX/Y 给出的
        // **就是绝对 GUI 坐标**，与 frameMouseX/Y 同源。所以画布局部坐标 = 它 − 画布绝对位置。
        this.popupMouseX = this.scene.localMouseX() - this.canvasHost.getPositionX()
                - this.canvasHost.getContentX();
        this.popupMouseY = this.scene.localMouseY() - this.canvasHost.getPositionY()
                - this.canvasHost.getContentY();
    }
    private final UIElement cardHost = new UIElement();
    private final UIElement cardBody = SimuPackLayout.column((int)2);
    private final ScrollerView cardScroll = SimuPackLayout.scroller();
    private final UIElement cardContent = SimuPackLayout.column((int)3);
    private final UIElement timelineHost = SimuPackLayout.column((int)1);
    private final Label detailLabel = SimuPackLayout.wrappedLabel((Component)Component.literal((String)""), (int)-1644826);
    private final Label pathNote = SimuPackLayout.wrappedLabel((Component)Component.literal((String)""), (int)-4342339);
    private final Button playButton = new Button();
    private final Button speedButton = new Button();
    private final List<Label> timelineLabels = new ArrayList<Label>();
    /** 时间轴每行的第二行（耗时 + 结果）。 */
    private final SimulationSession session = new SimulationSession();
    private final SimInventory initialInventory = new SimInventory();
    private CreationBuilding building;
    private String syncedJson = "";
    private BuildingJsonDocument document;
    private String selectedRecipeId = "";
    private List<SimulationPlan.Entry> plan = List.of();
    private List<BlockPos> currentRoute = List.of();
    private int renderedIndex = -1;
    private int speedIndex;
    private boolean mounted;
    private boolean cardDirty = true;
    private boolean planDirty = true;
    private String structureSource = "";
    private String hoveredContainer = "";

    /**
     * 悬浮在容器上时贴着鼠标显示的库存浮层。
     *
     * <p>挂在 {@code canvasHost} 上（与 {@code scene} 同一个坐标系），位置用
     * {@code canvasHost.worldToLocal(绝对鼠标)} 换算 —— 旧版直接拿 GUI 绝对坐标当布局坐标用，
     * 位置从一开始就是错的，这也是当初把浮层整个删掉的原因。
     */
    private final UIElement popupHost = new UIElement();
    private final UIElement popupContent = SimuPackLayout.column((int)2);
    /** 本轮是否已经算过账（点过播放 / 单步 / 重播）。没算过就还没有时间轴，也不需要显示市民。 */
    private boolean computed;
    /** 挑市民皮肤用的随机源（每轮仿真在 {@code recompute()} 里取一次）。 */
    private final net.minecraft.util.RandomSource skinRandom = net.minecraft.util.RandomSource.create();
    /** 当前有没有给市民挂着 tooltip，用来避免每 tick 都去改样式。 */
    private boolean citizenTooltipShown;
    /** 动物改动已经应用到第几步（-1 = 还没应用过）。用于「前进时只补新走的那几步」。 */
    private int appliedEntityIndex = -1;
    /** 已经在加号框里选好、但还没按「添加」的物品。 */
    private String pendingAddItem = "";
    /** 浮层最后一行那个数量框当前输入的内容（空 = 1）；见 {@link #pendingAddAmount()}。 */
    private String pendingAddCount = "";
    /** 0.5x 用的余量：每帧加一次倍率，够了才真的走一格。 */
    private double speedBudget;
    /** 浮层位置还没定下来（绘制回调里布局尺寸还不可用），等下一次绘制补上。 */
    private boolean popupNeedsAnchor;
    /** 鼠标是否正压在浮层（或它的子控件）上 —— 由浮层自己的 mouseEnter/mouseLeave 维护。 */
    private boolean popupHoveredFlag;
    /** 离开容器后延迟关闭的剩余 tick 数；-1 表示没有在倒计时。 */
    private int popupCloseDelay = -1;
    /** 绘制时抓到的画布局部鼠标坐标，以及据此定下的浮层矩形（定位与命中判定共用）。 */
    private float popupMouseX;
    private float popupMouseY;
    private float popupLeft;
    private float popupTop;
    private float popupAppliedWidth;
    private float popupAppliedHeight;
    /** 上一次套用卡片尺寸时画布的宽度，用来发现「画布尺寸变了」。 */
    private float appliedCanvasWidth = -1.0f;
    /** 用户是否自己拖过卡片边框。拖过之后就不再按可用空间自动收敛宽度。 */
    private boolean sizeCustomized;
    // 注意：这两个初始值必须是字面量。这个文件是从 class 反编译恢复的，
    // CFR 把 CARD_WIDTH / CARD_HEIGHT_PERCENT 内联成了字面量，
    // 只改上面的常量是改不动的（改默认高度那次就白改了）。
    private float cardWidth = 292.0f;
    private float cardHeightPercent = 52.0f;
    private boolean resizing;
    private float resizeStartX;
    private float resizeStartY;
    private float resizeStartWidth;
    private float resizeStartPercent;
    private ResizeGrip activeGrip;

    public IndustrialSimulationPanel(EditorHost host) {
        this.host = host == null ? new SilentHost() : host;
        this.widgets = new EditorWidgets(new EditorWidgets.Host(){

            @Override
            public boolean readOnly() {
                return false;
            }

            @Override
            public void openMenu(float mouseX, float mouseY, List<SimuPackMenuBar.Entry> entries) {
                IndustrialSimulationPanel.this.host.openMenu(mouseX, mouseY, entries);
            }
        });
        this.root.addEventListener("tick", event -> this.tick());
        this.playButton.setOnClick(event -> {
            // 没算过就先算：进页面不自动开跑，避免一进来就铺满「前一步受阻，未执行」。
            this.ensureComputed();
            if (!this.session.hasResult()) {
                return;
            }
            if (this.session.finished()) {
                // 跑完 → 这个按钮是「重置」：停下、回到第一步、回到可编辑。
                this.resetRun();
                return;
            }
            this.session.toggle();
            this.refreshTransport();
            this.refreshContainerPopup();
        });
        this.speedButton.setOnClick(event -> {
            this.speedIndex = (this.speedIndex + 1) % SPEEDS.length;
            this.refreshTransport();
        });
        this.root.addEventListener("mouseMove", this::updateResize);
        this.root.addEventListener("mouseUp", event -> this.endResize());
        this.root.addEventListener("mouseLeave", event -> this.endResize());
    }

    public UIElement root() {
        return this.root;
    }

    public void sync(CreationBuilding target) {
        String json = target == null ? "" : target.jsonConfig();
        boolean switchedBuilding = target != this.building;
        boolean changed = switchedBuilding || !json.equals(this.syncedJson);
        this.building = target;
        if (!changed) {
            return;
        }
        this.syncedJson = json;
        if (switchedBuilding) {
            this.initialInventory.restore(new SimInventory());
            this.selectedRecipeId = "";
            this.hoveredContainer = "";
            this.pendingAddCount = "";
        }
        this.document = null;
        if (target != null && "industry".equals(target.category())) {
            try {
                this.document = BuildingJsonDocument.parse((String)json);
            }
            catch (RuntimeException ignored) {
                this.document = null;
            }
        }
        if (this.document != null && (this.selectedRecipeId.isBlank() || this.document.recipe(this.selectedRecipeId) == null)) {
            this.selectedRecipeId = this.document.selectedRecipeId();
        }
        this.cardDirty = true;
        this.planDirty = true;
    }

    public void setStructure(String sourceKey, CompoundTag tag) {
        String key;
        String string = key = sourceKey == null ? "" : sourceKey;
        if (key.equals(this.structureSource)) {
            return;
        }
        this.structureSource = key;
        this.scene.showStructure(tag);
        this.currentRoute = List.of();
        // 新结构 = 新方块，容量要重新读。
        this.applyContainerMetadata();
        this.applyPopupHeight();
    }

    /**
     * 进页面时的准备：**只清场，不算账**。
     *
     * <p>点了播放 / 单步 / 重播才真的跑一遍（{@link #ensureComputed()}）。
     * 之前是一进页签就整轮算完，于是时间轴立刻铺满十几行「前一步受阻，未执行」——
     * 用户还没开始，界面上已经一片红。
     */
    private void prepareRun() {
        this.computed = false;
        // 容量跟着结构走：换建筑 / 换结构之后重新读一遍方块。
        this.applyContainerMetadata();
        // 上一轮的方块改动（放方块 / 等待方块）要还原，否则会留在世界里。
        this.applyBlockEditsUpTo(-1);
        // 刷出来的动物也一并收掉；下一轮开始时会按「刷怪」设置重新生成。
        this.scene.clearPreviewAnimals();
        this.session.pause();
        this.plan = List.of();
        this.renderedIndex = -1;
        this.appliedEntityIndex = -1;
        this.currentRoute = List.of();
        this.timelineHost.clearAllChildren();
        this.timelineLabels.clear();
        this.scene.clearSimulationCitizen();
        this.scene.setSimulationPath(List.of(), null);
        this.scene.setOpenContainers(Set.of());
        this.refreshPathNote();
        this.refreshHighlight();
        this.refreshDetail();
        this.refreshTransport();
        this.refreshContainerPopup();
    }

    /** 需要的话先算一遍；已经算过就直接用。 */
    private void ensureComputed() {
        if (!this.computed) {
            this.recompute();
        }
    }

    public void renderInto(UIElement host) {
        if (host == null) {
            return;
        }
        if (!this.mounted) {
            this.mount();
            this.mounted = true;
        }
        host.clearAllChildren();
        host.addChild(this.root);
        if (this.cardDirty) {
            this.cardDirty = false;
            this.rebuildCard();
        }
        if (this.planDirty) {
            this.planDirty = false;
            // 只清场，不算账 —— 等用户点播放 / 单步 / 重播才真的跑。
            this.prepareRun();
        } else if (this.computed && this.session.hasResult()) {
            this.rebuildTimeline();
            this.refreshTransport();
            this.refreshDetail();
            this.refreshScene();
        }
    }

    private void mount() {
        this.scene.addClass("simupack_preview_canvas");
        SimuPackLayout.fill((UIElement)this.root);
        this.canvasHost.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.heightPercent(100.0f);
            layout.positionType(TaffyPosition.RELATIVE);
        });
        SimuPackLayout.fill((UIElement)this.scene);
        this.canvasHost.addChild((UIElement)this.scene);
        this.cardHost.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.top(6.0f);
            layout.right(6.0f);
            layout.width(this.cardWidth);
            layout.heightPercent(this.cardHeightPercent);
        });
        this.cardBody.addClass("simupack_card");
        SimuPackLayout.fill((UIElement)this.cardBody);
        this.cardScroll.addScrollViewChild(this.cardContent);
        this.cardBody.addChild((UIElement)this.cardScroll);
        this.cardHost.addChild(this.cardBody);
        this.cardHost.addChild(this.edgeGrip(true));
        this.cardHost.addChild(this.edgeGrip(false));
        this.canvasHost.addChild(this.cardHost);
        this.scene.setInputOverlay(this.cardHost);
        this.scene.enableHoverPicking();
        this.scene.setHoverHandler(this::onSceneHover);

        // 库存浮层：与场景同一个坐标系，加在最后 → 命中测试时排在场景前面，
        // 所以浮层上的按钮能先拿到点击；再显式停一次冒泡，免得同时触发场景的转视角拖拽。
        this.popupHost.addClass("simupack_container_popup");
        this.popupHost.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(0.0f);
            layout.top(0.0f);
            layout.width(POPUP_WIDTH);
        });
        this.popupHost.setDisplay(false);
        this.popupHost.addEventListener("mouseDown", event -> event.stopPropagation());
        // 自己维护一份「鼠标在不在浮层上」：isHover() 是框架状态、可能滞后一帧，
        // 而 mouseEnter/mouseLeave 是事件，进入的当下就能拿到（ResizeGrip 就是这么判悬停的）。
        this.popupHost.addEventListener("mouseEnter", event -> this.popupHoveredFlag = true);
        this.popupHost.addEventListener("mouseLeave", event -> this.popupHoveredFlag = false);
        this.popupHost.addChild(this.popupContent);
        this.canvasHost.addChild(this.popupHost);

        // 悬停市民时，把**工人背包**挂进 tooltip（就在文字「作者：…」的下一行）。
        //
        // 走的是 LDLib2 的 hoverTooltips 事件：它允许返回一个 HoverTooltips，其中带一个
        // vanilla 的 TooltipComponent；ModularUITooltipComponent 能把任意 UIElement 塞进去。
        // 这是**唯一**能出物品图标的入口 —— style 的 tooltips(Component...) 只能出文字，
        // 而 Tooltips 那个 record 里只有 Component[]，塞不进物品槽。
        this.scene.addEventListener("hoverTooltips", event -> {
            if (!this.scene.isCitizenHovered()) {
                return;   // 没悬停市民：交给 style 那条（有文字就画文字）
            }
            String name = com.rstech.simupack.client.ui.preview.SimulationSkins
                    .displayName(this.scene.citizenSkin());
            HoverTooltips tips = HoverTooltips.empty().append(Component.literal(name));
            SimulationRunner.Result result = this.session.result();
            java.util.List<SimBackpack.Slot> slots = result == null
                    ? java.util.List.of()
                    : result.backpackAt(this.session.index());
            if (!slots.isEmpty()) {
                tips = tips.tooltipComponent(new ModularUITooltipComponent(this.backpackRow(slots)));
            }
            event.hoverTooltips = tips;
        });

        this.root.addChild(this.canvasHost);
    }

    private UIElement edgeGrip(boolean vertical) {
        ResizeGrip grip = vertical ? ResizeGrip.horizontal() : ResizeGrip.vertical();
        grip.addClass("simupack_resize_grip");
        grip.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(0.0f);
            if (vertical) {
                layout.top(0.0f);
                layout.width(4.0f);
                layout.heightPercent(100.0f);
            } else {
                layout.bottom(0.0f);
                layout.height(4.0f);
                layout.widthPercent(100.0f);
            }
        });
        grip.addEventListener("mouseDown", event -> {
            if (event.button != 0) {
                return;
            }
            this.resizing = true;
            this.sizeCustomized = true;
            this.activeGrip = grip;
            grip.setDragging(true);
            this.resizeStartX = event.x;
            this.resizeStartY = event.y;
            this.resizeStartWidth = this.cardWidth;
            this.resizeStartPercent = this.cardHeightPercent;
            event.stopPropagation();
        });
        return grip;
    }

    private void endResize() {
        this.resizing = false;
        if (this.activeGrip != null) {
            this.activeGrip.setDragging(false);
            this.activeGrip = null;
        }
    }

    private void updateResize(UIEvent event) {
        if (!this.resizing) {
            return;
        }
        float dx = event.x - this.resizeStartX;
        float dy = event.y - this.resizeStartY;
        float canvasHeight = Math.max(1.0f, this.canvasHost.getContentHeight());
        this.cardWidth = IndustrialSimulationPanel.clamp(this.resizeStartWidth - dx, 180.0f, this.maxCardWidth());
        this.cardHeightPercent = IndustrialSimulationPanel.clamp(this.resizeStartPercent + dy / canvasHeight * 100.0f, 20.0f, 100.0f);
        this.applyCardSize();
        event.stopPropagation();
    }

    private void applyCardSize() {
        // 卡片必须**装得下**：默认宽 292 是 GUI 像素，而 GUI scale 调大时整个窗口可能只有
        // 六百多 GUI 单位宽，中栏就更窄（实测 4 倍缩放下不足 250）。这时候右对齐 + 292 宽
        // 会直接向左溢出，压到左边那栏上 —— 表现就是「卡片跑到栏外面去了」。
        this.appliedCanvasWidth = this.canvasHost.getContentWidth();
        float width = Math.min(this.desiredCardWidth(), this.fitCardWidth());
        this.cardHost.layout(layout -> {
            layout.width(width);
            layout.heightPercent(this.cardHeightPercent);
        });
    }

    /**
     * 卡片的默认宽度。
     *
     * <p>**不写死像素**：固定值只在设计时那个 GUI 缩放下合适，缩放一变大就占掉半屏、
     * 一变小又窄得放不下字。所以默认取「可用宽度的一个比例」，再夹到
     * [{@link #CARD_MIN_WIDTH}, {@link #CARD_WIDTH}] 之间 ——
     * 上界保证大屏上不会拉得过宽，下界保证小缩放 / 小窗口下仍然能读。
     *
     * <p>用户一旦自己拖过边框，就完全按他的来（{@link #sizeCustomized}），
     * 否则每次窗口变化都会把他的调整冲掉。
     */
    private float desiredCardWidth() {
        if (this.sizeCustomized) {
            return this.cardWidth;
        }
        float canvasWidth = this.canvasHost.getContentWidth();
        if (canvasWidth <= 0.0f) {
            return CARD_WIDTH;
        }
        return Math.max(170.0f, Math.min(CARD_WIDTH, canvasWidth * 0.34f));
    }

    /** 画布装得下的宽度。比 {@link #maxCardWidth()} 更宽松一点，只用于「不要溢出」。 */
    private float fitCardWidth() {
        float canvasWidth = this.canvasHost.getContentWidth();
        if (canvasWidth <= 0.0f) {
            return this.cardWidth;
        }
        return Math.max(120.0f, canvasWidth - 12.0f);
    }

    private float maxCardWidth() {
        float canvasWidth = this.canvasHost.getContentWidth();
        if (canvasWidth <= 0.0f) {
            return 1168.0f;
        }
        return Math.max(180.0f, canvasWidth - 12.0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void rebuildCard() {
        this.cardContent.clearAllChildren();
        this.cardContent.addChild((UIElement)SimuPackLayout.label((Component)Component.translatable((String)"gui.simupack.industrial.simulation.citizen"), (int)-1));
        this.cardContent.addChild((UIElement)this.pathNote);
        this.refreshPathNote();
        if (this.building == null || !"industry".equals(this.building.category())) {
            this.cardContent.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.industrial.simulation_only_industry"), (int)-171));
            return;
        }
        if (this.document == null) {
            this.cardContent.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.industrial.simulation_bad_json"), (int)-34953));
            return;
        }
        if (this.document.recipes().isEmpty()) {
            this.cardContent.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.industrial.simulation_no_recipe"), (int)-171));
            return;
        }
        Button recipeButton = new Button().setText((Component)Component.translatable((String)"gui.simupack.industrial.simulation.recipe", (Object[])new Object[]{this.recipeName()}));
        this.widgets.installDropdown(recipeButton, this::recipeEntries);
        this.cardContent.addChild((UIElement)recipeButton);
        UIElement controls = SimuPackLayout.buttonRow((int)2);
        controls.addChild((UIElement)this.playButton);
        controls.addChild((UIElement)this.textButton("gui.simupack.industrial.simulation.step", this::stepOnce));
        controls.addChild((UIElement)this.textButton("gui.simupack.industrial.simulation.replay", this::restart));
        controls.addChild((UIElement)this.speedButton);
        this.cardContent.addChild(controls);
        this.cardContent.addChild((UIElement)this.detailLabel);
        this.cardContent.addChild((UIElement)SimuPackLayout.label((Component)Component.translatable((String)"gui.simupack.industrial.simulation.timeline"), (int)-1));
        this.cardContent.addChild(this.timelineHost);
    }

    private void recompute() {
        if (this.document == null) {
            return;
        }
        JsonObject recipe = this.document.recipe(this.selectedRecipeId);
        if (recipe == null) {
            JsonObject jsonObject = recipe = this.document.recipes().isEmpty() ? null : (JsonObject)this.document.recipes().get(0);
        }
        if (recipe == null) {
            return;
        }
        this.plan = SimulationPlan.of((JsonObject)recipe);
        BlockPos start = this.scene.simulationStartPosition();
        // 刷怪必须**在跑之前**：MOVE_TO_ENTITY 的候选目标就是从这些动物身上取的
        // （见 focusOf）。放在 run 之后的话，整轮已经算完了，走到实体那步还是没目标。
        this.spawnConfiguredAnimals();
        SimulationRunner.Result result = SimulationRunner.run((JsonObject)recipe, this.plan, (SimulationRunner.Focus)this.focusResolver, (SimInventory)this.initialInventory, (SimPos)(start == null ? null : IndustrialSimulationPanel.toSimPos(start)), (SimNavigation)this.scene.navigation(), (long)20261008L, (com.rstech.simupack.creation.industrial.sim.SimEnvironment)new com.rstech.simupack.creation.industrial.sim.SimEnvironment(this.lootProvider, this.blockMatcher, this.currentWorkArea()));
        this.session.load(result);
        // 界面上那一行放不下长消息（会被省略号截断，诊断刚好就是长的那种），
        // 所以每一步的**完整**原文也写进日志：跑完在 run/logs/latest.log 里搜「[仿真]」。
        logOutcomes(result);
        // 把「这一轮会动的方块」先交给场景：它会把这些位置从结构缓存里切出去，交给动态层每帧现画。
        // 必须赶在 applyBlockEditsUpTo 之前 —— 切完再改，播放全程就一次缓存重编都不会发生。
        this.scene.setDynamicBlocks(editedPositions(result));
        this.computed = true;
        // 每启动一轮仿真换一张皮肤（库里目前只有一张，随机就是它本身）。
        this.scene.setCitizenSkin(com.rstech.simupack.client.ui.preview.SimulationSkins.random(this.skinRandom));
        this.renderedIndex = -1;
        this.appliedEntityIndex = -1;
        this.currentRoute = List.of();
        // 新一轮开始：先还原上一轮放下的方块，再按进度（第 0 步）应用这一轮的。
        this.applyBlockEditsUpTo(this.session.index());
        this.refreshPathNote();
        this.rebuildTimeline();
        this.refreshTransport();
        this.refreshHighlight();
        this.refreshRoute();
        this.refreshDetail();
        this.refreshScene();
    }

    /**
     * 这一步的「目标坐标」——**必须分类型**，不能把 positions / point / container 并成一份。
     *
     * <p>NSUK 的优先级（审计见 `SIMUPACK_SIMULATION_REVIEW.md` 23.5）：
     * <ul>
     *   <li>方块类（放/破坏/等待）：**positions 优先**（{@code repeat} 的循环位置就是注入到这里的），
     *       {@code point} 只作兜底（{@code IndustrialBlockActionService:177-189}）；</li>
     *   <li>{@code move_to} / {@code look_at}：**只认 point**，为空即 {@code missing_point} + 重试
     *       （{@code IndustrialWorkService:554-559}）；</li>
     *   <li>容器类：**容器自己的坐标**（{@code IndustrialWorkService:572-579}）。</li>
     * </ul>
     *
     * <p>并集的后果是真实踩过的：循环体里的 {@code move_to_container} 被注入的循环坐标
     * （奶池格子）带偏，引擎把奶池当成容器，整步「寻路失败」。
     */
    /** 两个格子的**欧氏**距离是否在半径内（NSUK 用 distanceToSqr 比）。 */
    private static boolean within(SimPos anchor, SimPos pos, double radius) {
        double dx = anchor.x() - pos.x();
        double dy = anchor.y() - pos.y();
        double dz = anchor.z() - pos.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private List<SimPos> focusOf(SimStepSpec step) {
        switch (step.type()) {
            case MOVE_TO_CONTAINER, LOOK_AT_CONTAINER, INSPECT_CONTAINER, INSERT_ITEM,
                 REQUIRE_INPUTS, REQUIRE_OUTPUT_SPACE, DEPOSIT_CARRIED_ITEMS, CRAFT_RECIPE,
                 CRAFT_AVAILABLE_RECIPE, REAL_MACHINE_RECIPE, FILL_ITEM -> {
                // 容器类：只取容器定义里的坐标（dropItems 之类步骤的 container 只是产物去向，
                // 那些步骤不在这一组里 —— 见 default 分支）。
                LinkedHashSet<SimPos> containers = new LinkedHashSet<>();
                for (String key : CONTAINER_KEYS) {
                    String name = IndustrialSimulationPanel.string(step.json(), key);
                    if (name.isBlank()) {
                        continue;
                    }
                    containers.addAll(this.definitionPositions(name,
                            BuildingJsonDocument.DefinitionKind.CONTAINER));
                }
                return SimPos.sortedList(containers);
            }
            case MOVE_TO, LOOK_AT -> {
                // 只认 point：point 为空就是「没有目标点位」，由内核报受阻（与 NSUK 一致）。
                return SimPos.sortedList(this.definitionPositions(step.point(),
                        BuildingJsonDocument.DefinitionKind.POINT));
            }
            case MOVE_TO_ENTITY -> {
                // 走到实体：候选是**刷怪生成出来的动物**所在格子，谁近由内核挑。
                //
                // 但候选要先被半径筛一遍：NSUK 的候选实体受
                // `AABB(point).inflate(max(0.5, step.radius())) ∩ buildingBounds` 限制
                // （IndustrialEntityActionService:187-195）。没有这一步时，
                // 建筑另一头的羊也会被当成「走得到的目标」，走位和耗时都不对。
                Set<SimPos> points = this.definitionPositions(step.point(),
                        BuildingJsonDocument.DefinitionKind.POINT);
                double radius = Math.max(0.5D, step.range());
                LinkedHashSet<SimPos> animals = new LinkedHashSet<>();
                for (net.minecraft.world.entity.Entity animal : this.scene.previewAnimals()) {
                    BlockPos cell = animal.blockPosition();
                    SimPos pos = new SimPos(cell.getX(), cell.getY(), cell.getZ());
                    if (!points.isEmpty()
                            && points.stream().noneMatch(anchor -> within(anchor, pos, radius))) {
                        continue;
                    }
                    animals.add(pos);
                }
                if (animals.isEmpty()) {
                    // 一只都没有（或都不在半径内）= MISSING_ENTITIES（NSUK 会重试），
                    // 不该退回点位继续走。
                    return List.of();
                }
                return SimPos.sortedList(animals);
            }
            default -> {
                // 方块类与其余：positions 优先（循环位置注入在这里），point 兜底。
                if (!step.positions().isEmpty()) {
                    return SimPos.sortedList(step.positions());
                }
                return SimPos.sortedList(this.definitionPositions(step.point(),
                        BuildingJsonDocument.DefinitionKind.POINT));
            }
        }
    }

    /**
     * 把这一轮每一步的**完整**消息写进游戏日志。
     *
     * <p>为什么需要：时间轴上那一行是给一眼看的，长消息（尤其寻路失败的诊断）会被截断成
     * 「起点和终点不在同一片连…」，而最关键的数字正好在省略号后面。日志里是完整的。
     * 读法：跑完退出游戏（日志要退出才刷盘），
     * {@code Select-String -Path run\logs\latest.log -Pattern '\[仿真\]'}。
     *
     * <p>受阻的那一步用 warn，其余用 info，所以日志里一眼能看出问题在哪一步。
     */
    private void logOutcomes(SimulationRunner.Result result) {
        java.util.List<SimulationRunner.StepOutcome> outcomes = result.outcomes();
        for (int index = 0; index < outcomes.size(); index++) {
            SimulationRunner.StepOutcome outcome = outcomes.get(index);
            String line = "[仿真] 第 " + (index + 1) + "/" + outcomes.size() + " 步 "
                    + outcome.typeId() + " " + outcome.ticks() + "t —— " + outcome.message();
            if (outcome.status() == SimulationRunner.Status.BLOCKED) {
                com.rstech.simupack.SimuPackMod.LOGGER.warn(line);
            } else {
                com.rstech.simupack.SimuPackMod.LOGGER.info(line);
            }
        }
        if (result.blockedAt() >= 0) {
            com.rstech.simupack.SimuPackMod.LOGGER.warn(
                    "[仿真] 第 {} 步受阻，后面不会再走；完整原因见上一行", result.blockedAt() + 1);
        }
    }

    private void refreshPathNote() {
        // 正常情况不占地方：用户不需要每次都被提醒「这是按 NSUK 规则算的」。
        // 只有真的退化（网格建不出来 / 几乎为空）时才冒出来，那时候不说清楚才会误导。
        boolean fallback = !this.scene.hasNavGrid();
        boolean weak = !fallback && this.scene.navCellCount() < 4;
        this.pathNote.setDisplay(fallback || weak);
        if (fallback) {
            this.pathNote.setValue((Component)Component.translatable((String)"gui.simupack.industrial.simulation.path_fallback"));
            this.pathNote.textStyle(style -> style.textColor(-171).textShadow(true));
        } else if (weak) {
            this.pathNote.setValue((Component)Component.translatable((String)"gui.simupack.industrial.simulation.path_weak", (Object[])new Object[]{this.scene.navCellCount()}));
            this.pathNote.textStyle(style -> style.textColor(-34953).textShadow(true));
        }
    }

    private Set<SimPos> definitionPositions(String name, BuildingJsonDocument.DefinitionKind kind) {
        LinkedHashSet<SimPos> positions = new LinkedHashSet<SimPos>();
        if (this.document == null || name == null || name.isBlank()) {
            return positions;
        }
        JsonObject definition = this.document.definitionEntry(name, kind);
        if (definition == null) {
            return positions;
        }
        for (JsonArray array : BuildingJsonDocument.positionList((JsonObject)definition)) {
            if (array.size() != 3) continue;
            try {
                positions.add(new SimPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()));
            }
            catch (RuntimeException runtimeException) {}
        }
        return positions;
    }

    private void tick() {
        // 鼠标停在市民身上时挂一个 tooltip，内容是皮肤名（直接用文件名）。
        // 放在 tick 里而不是悬停回调里：市民不是方块，走到他身上时方块悬停未必会变，
        // 回调可能根本不触发。
        boolean overCitizen = this.scene.isCitizenHovered();
        if (overCitizen != this.citizenTooltipShown) {
            this.citizenTooltipShown = overCitizen;
            if (overCitizen) {
                String name = com.rstech.simupack.client.ui.preview.SimulationSkins.displayName(this.scene.citizenSkin());
                this.scene.style(style -> style.tooltips((Component)Component.literal((String)name)));
            } else {
                this.scene.style(style -> style.tooltips(new Component[0]));
            }
        }
        // 浮层的「宽限期」倒计时：宽限内鼠标已经在浮层上就撤销，否则到期关闭。
        if (this.popupCloseDelay > 0) {
            if (this.popupHeld()) {
                this.popupCloseDelay = -1;
            } else if (--this.popupCloseDelay <= 0) {
                this.popupCloseDelay = -1;
                this.hoveredContainer = "";
                this.popupHost.setDisplay(false);
            }
        }
        // 画布尺寸变了（窗口缩放 / 首次布局完成）就重新收敛一次卡片宽度。
        // 首次布局时 getContentWidth() 还是 0，只能等这里。
        float canvasWidth = this.canvasHost.getContentWidth();
        if (canvasWidth > 0.0f && Math.abs(canvasWidth - this.appliedCanvasWidth) > 0.5f) {
            this.applyCardSize();
        }
        // 浮层定位在 canvasHost 的绘制回调里做（那里才有画布局部鼠标坐标）。
        if (!(this.mounted && this.session.playing() && this.session.hasResult())) {
            return;
        }
        // 倍速换算成「攒够 1 tick 才走一步」：0.5x 就是每两帧走一格，
        // 而 2x/4x/8x 与直接 advance(倍数) 等价（advance 内部本来就是循环）。
        this.speedBudget += SPEEDS[this.speedIndex];
        boolean changed = false;
        while (this.speedBudget >= 1.0) {
            this.speedBudget -= 1.0;
            if (this.session.advance(1)) {
                changed = true;
            }
            if (!this.session.playing()) {
                break;
            }
        }
        if (changed) {
            this.refreshIndexDependentUi();
        }
    }

    private void stepOnce() {
        this.ensureComputed();
        if (!this.session.hasResult()) {
            return;
        }
        this.session.pause();
        this.session.skipCurrentStep();
        this.refreshIndexDependentUi();
        this.refreshTransport();
    }

    private void restart() {
        this.ensureComputed();
        if (!this.session.hasResult()) {
            return;
        }
        this.session.replay();
        this.renderedIndex = -1;
        this.appliedEntityIndex = -1;
        this.currentRoute = List.of();
        this.refreshIndexDependentUi();
        this.refreshTransport();
        this.refreshContainerPopup();
    }

    private void invalidate() {
        this.planDirty = true;
        this.host.refresh();
    }

    /**
     * 一整轮仿真会改动的**全部**坐标（含成簇收获破坏掉的那些）。
     *
     * <p>从 {@code Result.blockEdits()} 汇总，而不是去猜步骤上的点位：
     * 成簇收获破坏的范围是运行时按连通簇算出来的，光看步骤坐标推不出来。
     * 好在仿真是先跑完再切分层，所以这里拿到的是真值 —— 一个都不会漏。
     */
    private static Set<BlockPos> editedPositions(SimulationRunner.Result result) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        if (result == null) {
            return positions;
        }
        for (List<SimulationRunner.BlockEdit> edits : result.blockEdits().values()) {
            if (edits == null) {
                continue;
            }
            for (SimulationRunner.BlockEdit edit : edits) {
                positions.add(new BlockPos(edit.x(), edit.y(), edit.z()));
            }
        }
        return positions;
    }

    /**
     * 把「已经走到的所有步骤」对世界的方块改动应用到预览世界。
     *
     * <p>每次都**从零重放**（先还原、再按进度依次应用），不做增量：用户会点时间轴往回跳，
     * 增量的话已经放下的方块撤不掉。方块改动只有几条，重放很便宜；
     * 而且世界没变时 {@code flushBlockEdits()} 是空操作，不会白重建渲染缓存。
     */
    private void applyBlockEditsUpTo(int index) {
        this.scene.clearBlockEdits();
        SimulationRunner.Result result = this.session.result();
        if (this.computed && result != null) {
            for (int i = 0; i <= index; i++) {
                List<SimulationRunner.BlockEdit> edits = result.blockEdits().get(i);
                if (edits != null) {
                    this.scene.applyBlockEdits(edits);
                }
            }
        }
        this.scene.flushBlockEdits();
    }

    /**
     * 把「已经走到的所有步骤」对**动物**的改动应用到预览世界。
     *
     * <p>同样从零重放，而且要从「刚刷出来的初始状态」开始：剪毛和屠宰都不可逆
     * （羊秃了、羊没了），往回拖时间轴时增量是撤不回来的。所以先按刷怪配置重新生成，
     * 再把 0..index 的改动依次应用一遍。
     */
    private void applyEntityEditsUpTo(int index) {
        SimulationRunner.Result result = this.session.result();
        // **前进时只补新走的几步**：不清场、不重刷。
        // 之前每一步都「清场重刷 + 从 0 重放」，而刷怪用的是不固定种子 ——
        // 鸡每次都被撒到别的位置，内核那批 EntityEdit 带的是**旧坐标**，
        // 于是「屠宰/繁殖」在画面上落到空处（看起来完全没生效）。
        // 现在同一轮里始终是**同一批实体**，位置也对得上。
        if (this.appliedEntityIndex >= 0 && index >= this.appliedEntityIndex && this.computed
                && result != null) {
            for (int i = this.appliedEntityIndex + 1; i <= index; i++) {
                List<SimulationRunner.EntityEdit> edits = result.entityEdits().get(i);
                if (edits != null) {
                    this.scene.applyEntityEdits(edits);
                }
            }
            this.appliedEntityIndex = index;
            return;
        }
        // 往回拖（或第一次应用）：只能重来 —— 剪毛和屠宰不可逆，增量撤不回来。
        // 位置靠固定种子保证和上一遍一致，所以重放出来的还是同一批。
        this.spawnConfiguredAnimals();
        if (this.computed && result != null) {
            for (int i = 0; i <= index; i++) {
                List<SimulationRunner.EntityEdit> edits = result.entityEdits().get(i);
                if (edits != null) {
                    this.scene.applyEntityEdits(edits);
                }
            }
        }
        this.appliedEntityIndex = index;
    }

    /**
     * 按建筑定义算出成簇收获的**作业范围**。
     *
     * <p>照 NSUK {@code IndustrialWorkAreaService}：{@code workArea.radius <= 0} 时直接用建筑外框
     * （注意这种情况在真实的成簇收获里**永远扫不到东西** —— 游标从第 1 环起步，
     * 而 {@code ring <= 0} 立刻不成立），否则按 radius 向外扩、上下界再加
     * {@code minYOffset} / {@code maxYOffset}。
     *
     * <p>同时记下建筑自己的外框：真实实现会「簇里任一方块落在建筑内 ⇒ 整簇否决」。
     */
    private com.rstech.simupack.creation.industrial.sim.SimWorkArea currentWorkArea() {
        if (this.document == null) {
            return com.rstech.simupack.creation.industrial.sim.SimWorkArea.UNKNOWN;
        }
        BlockPos min = this.scene.structureMinimum();
        BlockPos max = this.scene.structureMaximum();
        if (min == null || max == null) {
            return com.rstech.simupack.creation.industrial.sim.SimWorkArea.UNKNOWN;
        }
        JsonObject root = this.document.root();
        JsonObject workArea = root.has("workArea") && root.get("workArea").isJsonObject()
                ? root.getAsJsonObject("workArea") : new JsonObject();
        int radius = Math.max(0, intField(workArea, 0, "radius"));
        // 扫描环是 [max(1, startOffset), radius]：贴着建筑的前 startOffset-1 圈**永远不扫**
        // （IndustrialBlockClusterHarvestService:952/:960-965/:987）。以前完全没读这个字段。
        int startOffset = Math.max(0, intField(workArea, 0, "startOffset", "start_offset"));
        int minYOffset = intField(workArea, -8, "minYOffset", "min_y_offset");
        int maxYOffset = intField(workArea, 24, "maxYOffset", "max_y_offset");
        SimPos areaMin = new SimPos(min.getX() - radius,
                radius > 0 ? min.getY() + minYOffset : min.getY(), min.getZ() - radius);
        SimPos areaMax = new SimPos(max.getX() + radius,
                radius > 0 ? max.getY() + maxYOffset : max.getY(), max.getZ() + radius);
        // `excludeBuilding` 在 NSUK 里是**真的被读的**（:980：x/z 落在建筑投影内的整列跳过，
        // 只看 XZ 不看 Y）—— 我早先断言它是惰性字段，那是错的，已改回来。
        // 它配 `radius: 0` 时的含义就是「不砍建筑自己」；关掉它才允许扫建筑所在的列。
        boolean excludeBuilding = flagField(workArea, true, "excludeBuilding", "exclude_building");
        SimPos buildingMin = new SimPos(min.getX(), min.getY(), min.getZ());
        SimPos buildingMax = new SimPos(max.getX(), max.getY(), max.getZ());
        return new com.rstech.simupack.creation.industrial.sim.SimWorkArea(areaMin, areaMax,
                buildingMin, buildingMax,
                excludeBuilding ? buildingMin : null, excludeBuilding ? buildingMax : null,
                startOffset, radius);
    }

    /** 读一个布尔配置；键不存在或不是布尔就取默认值（别名按顺序试）。 */
    private static boolean flagField(JsonObject object, boolean fallback, String... keys) {
        for (String key : keys) {
            try {
                if (object.has(key)) {
                    return object.get(key).getAsBoolean();
                }
            } catch (RuntimeException ignored) {
                // 值不是布尔 —— 当作没写，继续试下一个别名。
            }
        }
        return fallback;
    }

    /** 读一个整数配置；键不存在或不是数字就取默认值（别名按顺序试）。 */
    private static int intField(JsonObject object, int fallback, String... keys) {
        for (String key : keys) {
            try {
                if (object.has(key)) {
                    return object.get(key).getAsInt();
                }
            } catch (RuntimeException ignored) {
                // 值不是数字 —— 当作没写，继续试下一个别名。
            }
        }
        return fallback;
    }

    /**
     * 按建筑定义里的「刷怪」（{@code spawnEntity}）一节，在仿真开始时把动物放进预览世界。
     *
     * <p>实体类动作组（剪羊毛 / 屠宰 / 繁殖 / 收掉落物）在干跑里本来**没有作用对象**，
     * 于是整轮只能标注「需要真实世界」。建筑定义里本来就有刷怪这一节，照它把动物生成出来，
     * 那些步骤才有东西可操作 —— 这也是「第一次开始仿真会刷动物，模拟就能继续推进」。
     *
     * <p><b>位置规则照 NSUK 的 {@code IndustrialEntitySpawnService}</b>：
     * 在建筑外框内均匀随机取 {@code (x, z)}、{@code y = 建筑最低角 + 1}，
     * 每只最多试 24 次，挑到「这一格空 + 上面也空 + 下面能站」才生成；
     * 24 次都挑不到就**这一只不生成**（不换地方重试）。
     *
     * <p>注意它**不看控制箱**：NSUK 那边虽然把控制箱坐标传进了 {@code spawnOne(...)}，
     * 但那个参数从头到尾没被用过。我们一度改成「围着控制盒铺环」，那是自造行为，已经改回来了。
     *
     * <p>**位置不固定种子**（NSUK 用的是每次都随机的 {@code level.random}，这里同理）：
     * 每次跑都撒在不同的地方，正好当作对「能不能站」这套判定的随机测试 ——
     * 要是某个位置会摆出问题（浮空 / 卡墙），多跑几次就能撞出来。
     * （掉落的随机数仍然用内核的固定种子，那是另一回事：同一轮重播的账必须一样。）
     */
    private void spawnConfiguredAnimals() {
        this.scene.clearPreviewAnimals();
        if (this.document == null) {
            return;
        }
        JsonObject root = this.document.root();
        if (!root.has("spawnEntity") || !root.get("spawnEntity").isJsonObject()) {
            return;
        }
        JsonObject spawn = root.getAsJsonObject("spawnEntity");
        if (!spawnFlag(spawn, "enabled")) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(string(spawn, "type"));
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return;
        }
        int count = spawnCount(spawn);
        BlockPos min = this.scene.structureMinimum();
        BlockPos max = this.scene.structureMaximum();
        if (count <= 0 || min == null || max == null) {
            return;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        // NSUK：baseY = 建筑最低角 + 1，X/Z 在外框内均匀随机。
        int baseY = min.getY() + 1;
        int spanX = Math.max(1, max.getX() - min.getX() + 1);
        int spanZ = Math.max(1, max.getZ() - min.getZ() + 1);
        // **固定种子**（按建筑定义派生）：同一栋建筑每轮撒在同一批位置。
        // 以前不固定种子 —— 那样一旦重放（回拖时间轴）位置就全变，
        // 而内核那批 EntityEdit 带的是**坐标**，会落到空处，屠宰/繁殖看起来完全没生效。
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(
                this.document.root().toString().hashCode() * 31L + 20261008L);
        Set<Long> taken = new LinkedHashSet<>();
        for (int spawned = 0; spawned < count; spawned++) {
            boolean placed = false;
            for (int attempt = 0; attempt < SPAWN_ATTEMPTS && !placed; attempt++) {
                int x = min.getX() + random.nextInt(spanX);
                int z = min.getZ() + random.nextInt(spanZ);
                SimPos feet = new SimPos(x, baseY, z);
                if (!this.scene.canStandAt(feet)) {
                    continue;
                }
                // NSUK 那边靠 noCollision 挡实体重叠；假世界不做实体碰撞，所以自己记格子。
                if (!taken.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) {
                    continue;
                }
                // spawnPreviewAnimal 收的是「脚下那一格」（它内部 +1 才是脚底），
                // 而 NSUK 是直接把脚底放在选中的那一格 —— 所以传 feet.below()。
                this.scene.spawnPreviewAnimal(type, new SimPos(x, baseY - 1, z),
                        random.nextFloat() * 360.0F);
                placed = true;
            }
            if (!placed) {
                // NSUK：24 次都挑不到就让这一只空着，不无限重试。
                break;
            }
        }
    }

    /** 每只动物最多试几次 —— 和 NSUK 的 24 一致。 */
    private static final int SPAWN_ATTEMPTS = 24;


    private static boolean spawnFlag(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int spawnCount(JsonObject object) {
        try {
            return object.has("count") ? Math.max(0, object.get("count").getAsInt()) : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * 改完一条容器内容之后**就地**刷新，不走宿主的整体重建。
     *
     * <p>走 {@code host.refresh()} 的话，宿主会 {@code clearAllChildren()} 再把整棵界面挂回去，
     * 场景元素被摘下来又装回去，3D 缓冲被迫重编译 —— 表现就是「每改一个数字预览图刷一下」。
     * 改初始库存只影响本面板自己的状态和这个浮层，直接就地做即可。
     */
    private void afterInventoryEdit() {
        this.planDirty = false;
        this.prepareRun();
        this.refreshContainerPopup();
    }

    private void refreshIndexDependentUi() {
        int index = this.session.index();
        if (index != this.renderedIndex) {
            if (this.renderedIndex != -1) {
                this.refreshTimelineRow(this.renderedIndex);
            }
            this.renderedIndex = index;
            this.refreshTimelineRow(index);
            this.refreshHighlight();
            this.refreshRoute();
            this.refreshDetail();
            // 放方块 / 等待方块在仿真里的可见效果：按当前进度把方块改动应用到预览世界。
            this.applyBlockEditsUpTo(index);
            // 动物那边的可见效果：剪毛变秃、屠宰消失、繁殖多一只。必须在方块改动之后 ——
            // 它会重新刷一遍动物（从初始状态重放），这也是唯一会动动物列表的地方。
            this.applyEntityEditsUpTo(index);
            // 浮层里的数量是「当前播放时刻」的，步骤一变就得重画。
            if (!this.hoveredContainer.isBlank() && this.popupHost.isDisplayed()) {
                this.refreshContainerPopup();
            }
        }
        this.refreshScene();
    }

    private void refreshTransport() {
        // 三态：跑完了是「重置」，正在跑是「暂停」，其余是「播放」。
        String key;
        if (this.session.finished()) {
            key = "gui.simupack.industrial.simulation.reset_run";
        } else if (this.session.playing()) {
            key = "gui.simupack.industrial.simulation.pause";
        } else {
            key = "gui.simupack.industrial.simulation.play";
        }
        this.playButton.setText((Component)Component.translatable((String)key));
        this.speedButton.setText((Component)Component.translatable((String)"gui.simupack.industrial.simulation.speed", (Object[])new Object[]{this.speedLabel()}));
        this.refreshDetail();
    }

    /**
     * 「重置」：停下当前这轮，回到第一步，并回到**可编辑**状态。
     *
     * <p>实现上直接走 {@link #prepareRun()} —— 它本来就是「清场、不算账」，
     * 也就是「还没开始仿真」那个状态，而 {@code editable()} 正是以它为判据的。
     * 不需要额外维护一套「已重置」的标志位。
     */
    private void resetRun() {
        this.session.pause();
        this.session.replay();
        this.session.pause();
        this.prepareRun();
        this.refreshTransport();
    }

    /** 一轮里有多少步是按「假定成功」计的（只给内部诊断用，界面上不再显示）。 */
    private static int assumedCount(SimulationRunner.Result result) {
        int count = 0;
        for (SimulationRunner.StepOutcome outcome : result.outcomes()) {
            if (!outcome.assumed()) continue;
            ++count;
        }
        return count;
    }

    private void refreshDetail() {
        SimulationRunner.Result result = this.session.result();
        if (!this.computed || result == null) {
            // 进页面还没开跑：只提示一句怎么开始，不显示上一轮的残留状态。
            this.detailLabel.setValue((Component)Component.literal((String)Component.translatable((String)"gui.simupack.industrial.simulation.not_started").getString()));
            this.detailLabel.textStyle(style -> style.textColor(-4342339).textShadow(true));
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(Component.translatable((String)"gui.simupack.industrial.simulation.summary", (Object[])new Object[]{this.session.index() + 1, result.outcomes().size(), this.session.elapsedTicks(), result.totalTicks()}).getString());
        if (result.blockedAt() >= 0) {
            text.append('\n').append(Component.translatable((String)"gui.simupack.industrial.simulation.blocked", (Object[])new Object[]{result.blockedAt() + 1}).getString());
        }
        // 跑完不再补一句「其中 N 步是按假定成功计的」：那是给作者看的统计，不是他这一刻要的信息，
        // 而且每次跑完都占一行。单步的「需要世界」标注本来就在各自那一行上，够看清了。
        SimulationRunner.StepOutcome outcome = this.session.current();
        if (outcome != null) {
            text.append('\n').append(this.stepDetailLine(this.session.index()).trim());
        }
        this.detailLabel.setValue((Component)Component.literal((String)text.toString()));
        this.detailLabel.textStyle(style -> style.textColor(outcome == null ? -1644826 : this.detailColor(outcome)).textShadow(true));
    }

    private int detailColor(SimulationRunner.StepOutcome outcome) {
        return switch (outcome.status()) {
            default -> throw new MatchException(null, null);
            case SimulationRunner.Status.BLOCKED -> -34953;
            case SimulationRunner.Status.SKIPPED_TIMEOUT, SimulationRunner.Status.NEEDS_WORLD, SimulationRunner.Status.INVALID -> -171;
            case SimulationRunner.Status.NOT_REACHED -> -4342339;
            case SimulationRunner.Status.DONE -> -1644826;
        };
    }

    private void rebuildTimeline() {
        this.timelineHost.clearAllChildren();
        this.timelineLabels.clear();
        this.renderedIndex = -1;
        this.appliedEntityIndex = -1;
        SimulationRunner.Result result = this.session.result();
        if (result == null) {
            return;
        }
        int index = 0;
        while (index < result.outcomes().size()) {
            UIElement row = SimuPackLayout.row((int)2);
            int target = index++;
            row.addEventListener("mouseDown", event -> {
                this.session.pause();
                this.session.seek(target);
                this.refreshIndexDependentUi();
                this.refreshDetail();
                this.refreshTransport();
            });
            // 一行搞定：两行版在 ScrollerView 里会把两行画重叠（flexWrappedLabel 在 column 里
            // 高度塌成 0），所以还是单行 + 压短文字。
            Label label = SimuPackLayout.flexWrappedLabel((Component)Component.literal((String)""), (int)-4342339);
            this.timelineLabels.add(label);
            row.addChild((UIElement)label);
            this.timelineHost.addChild(row);
        }
        for (index = 0; index < this.timelineLabels.size(); ++index) {
            this.refreshTimelineRow(index);
        }
    }

    /** 时间轴每行：只有「第几步 + 做什么」，状态靠颜色区分（成功绿、卡住红、警告黄）。 */
    private String stepLine(int index) {
        SimulationRunner.StepOutcome outcome;
        SimulationRunner.Result result = this.session.result();
        SimulationRunner.StepOutcome stepOutcome = outcome = result == null ? null : result.outcome(index);
        if (outcome == null) {
            return "";
        }
        String marker = index == this.session.index() ? "\u25b6 " : "  ";
        return marker + (index + 1) + ". " + this.stepTitle(outcome);
    }

    /** 详情区用的那一行：时间轴的标题 + 耗时 + 结果说明（时间轴上不写这些，太挤）。 */
    private String stepDetailLine(int index) {
        SimulationRunner.StepOutcome outcome;
        SimulationRunner.Result result = this.session.result();
        SimulationRunner.StepOutcome stepOutcome = outcome = result == null ? null : result.outcome(index);
        if (outcome == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(this.stepLine(index));
        if (outcome.ticks() > 0) {
            text.append(" \u00b7 ").append(outcome.ticks()).append('t');
        }
        String message = IndustrialSimulationPanel.compact(outcome.message());
        if (!message.isEmpty()) {
            text.append(" \u00b7 ").append(message);
        }
        return text.toString();
    }

    /**
     * 压短消息：去掉 {@code minecraft:} 这类命名空间、合并空白、超长截断。
     *
     * <p>卡片宽度只有两百多像素，不压的话「手持 minecraft:packed_mud」这种消息会折成两行，
     * 把整个时间轴撑得很长。
     */
    private static String compact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replaceAll("\\b[a-z0-9_.-]+:", "").replaceAll("\\s+", " ").trim();
        return value.length() <= 30 ? value : value.substring(0, 29) + "\u2026";
    }

    private String stepTitle(SimulationRunner.StepOutcome outcome) {
        String key = outcome.labelKey();
        if (key == null || key.isBlank()) {
            return outcome.typeId().isBlank() ? "?" : outcome.typeId();
        }
        return Component.translatable((String)key).getString();
    }

    private void refreshTimelineRow(int index) {
        SimulationRunner.StepOutcome outcome;
        if (index < 0 || index >= this.timelineLabels.size()) {
            return;
        }
        SimulationRunner.Result result = this.session.result();
        SimulationRunner.StepOutcome stepOutcome = outcome = result == null ? null : result.outcome(index);
        if (outcome == null) {
            return;
        }
        Label label = this.timelineLabels.get(index);
        label.setValue((Component)Component.literal((String)this.stepLine(index)));
        label.textStyle(style -> style.textColor(this.rowColor(outcome, index == this.session.index())).textShadow(true));
    }

    private int rowColor(SimulationRunner.StepOutcome outcome, boolean current) {
        if (current) {
            return -10027009;
        }
        return switch (outcome.status()) {
            default -> throw new MatchException(null, null);
            case SimulationRunner.Status.BLOCKED -> -34953;
            case SimulationRunner.Status.SKIPPED_TIMEOUT, SimulationRunner.Status.NEEDS_WORLD, SimulationRunner.Status.INVALID -> -171;
            case SimulationRunner.Status.NOT_REACHED -> -4342339;
            // 成功不再靠「· 完成」这几个字，直接整行染成绿色。
            case SimulationRunner.Status.DONE -> SimuPackTheme.TEXT_SUCCESS_COLOR;
        };
    }

    /** 「×0.5 / ×1 / ×2 …」里那个数字：整数不显示小数点。 */
    private String speedLabel() {
        double speed = SPEEDS[this.speedIndex];
        return speed == Math.rint(speed) ? Integer.toString((int)speed) : Double.toString(speed);
    }

    private String capacityText(String name) {
        int capacity = this.initialInventory.capacity(name);
        return capacity == Integer.MAX_VALUE ? "" : Integer.toString(capacity);
    }

    private void openItemPicker(Consumer<String> onPick, boolean allowTags) {
        this.host.openItemPicker(onPick, allowTags);
    }

    private Button textButton(String key, Runnable action) {
        Button button = new Button().setText((Component)Component.translatable((String)key));
        button.setOnClick(event -> action.run());
        return button;
    }

    private void refreshScene() {
        if (!this.computed) {
            // 还没开跑：把上一次的市民收掉，场景里不留残影。
            this.scene.clearSimulationCitizen();
            return;
        }
        SimulationSession.Pose pose = this.session.pose();
        if (pose == null) {
            this.scene.clearSimulationCitizen();
        } else {
            this.scene.setSimulationCitizenPosition(pose.x(), pose.y(), pose.z());
            this.scene.setCitizenFacing(pose.yaw());
            this.scene.setCitizenWalkPhase((float)(pose.walkedDistance() * 1.5707963267948966));
            this.scene.setCitizenMotion(pose.motion());
            this.scene.setCitizenGesture(pose.gesture(), this.session.tickInStep());
            this.applyLookTarget(pose);
        }
        String held = this.session.heldItem();
        this.scene.setCitizenHeldItem(held.isBlank() ? ItemStack.EMPTY : ItemRegistry.stackOf(held));
        this.scene.setOpenContainers(this.openContainersForCurrentStep());
    }

    private void applyLookTarget(SimulationSession.Pose pose) {
        if (pose.gesture() != SimGesture.LOOK) {
            this.scene.clearCitizenLookAt();
            return;
        }
        Iterator iterator = this.session.focus().iterator();
        if (iterator.hasNext()) {
            SimPos pos = (SimPos)iterator.next();
            this.scene.setCitizenLookAt((double)pos.x() + 0.5, (double)pos.y() + 0.5, (double)pos.z() + 0.5);
            return;
        }
        this.scene.clearCitizenLookAt();
    }

    private Set<BlockPos> openContainersForCurrentStep() {
        SimulationRunner.StepOutcome outcome = this.session.current();
        if (outcome == null || !outcome.gesture().reaching() || outcome.status() != SimulationRunner.Status.DONE) {
            return Set.of();
        }
        JsonObject step = this.stepJsonAt(this.session.index());
        if (step == null) {
            return Set.of();
        }
        LinkedHashSet<BlockPos> result = new LinkedHashSet<BlockPos>();
        for (String key : CONTAINER_KEYS) {
            String name = IndustrialSimulationPanel.string(step, key);
            if (name.isBlank()) continue;
            for (SimPos pos : this.definitionPositions(name, BuildingJsonDocument.DefinitionKind.CONTAINER)) {
                result.add(IndustrialSimulationPanel.toBlockPos(pos));
            }
        }
        return result;
    }

    private JsonObject stepJsonAt(int index) {
        if (index < 0 || index >= this.plan.size()) {
            return null;
        }
        return this.plan.get(index).spec().json();
    }

    private void refreshRoute() {
        SimulationRunner.StepOutcome outcome = this.session.current();
        ArrayList<BlockPos> route = new ArrayList<BlockPos>();
        if (outcome != null) {
            for (SimWaypoint waypoint : outcome.route()) {
                route.add(IndustrialSimulationPanel.toBlockPos(waypoint.cell()));
            }
        }
        if (!route.equals(this.currentRoute)) {
            this.currentRoute = List.copyOf(route);
            this.scene.setSimulationPath(this.currentRoute, null);
        }
    }

    private void refreshHighlight() {
        LinkedHashSet<BlockPos> highlight = new LinkedHashSet<BlockPos>();
        for (SimPos pos : this.session.focus()) {
            highlight.add(IndustrialSimulationPanel.toBlockPos(pos));
        }
        if (!this.hoveredContainer.isBlank()) {
            for (SimPos pos : this.definitionPositions(this.hoveredContainer, BuildingJsonDocument.DefinitionKind.CONTAINER)) {
                highlight.add(IndustrialSimulationPanel.toBlockPos(pos));
            }
        }
        this.scene.setHighlighted(highlight);
    }

    private void onSceneHover(BlockPos pos) {
        String container;
        String string = container = pos == null ? "" : this.containerAt(pos);
        if (container.isEmpty()) {
            // 鼠标离开了容器。只有当它也不在浮层上时才关 —— 否则鼠标从容器走向浮层的路上
            // 浮层就没了，永远点不到。
            //
            // 但**不能立刻关**：鼠标从紧贴光标的位置挪到浮层上要一两帧，而框架的 hover 状态
            // 也是滞后一帧才更新。在这一两帧的空隙里 isHover() 还是 false，浮层就会被误关
            // （实测就是这样：位置已经贴上了，鼠标一移过去它仍然消失）。
            // 所以先挂一个几 tick 的宽限，宽限内发现鼠标已经在浮层上就撤销。
            if (this.hoveredContainer.isEmpty() || this.popupHeld()) {
                this.popupCloseDelay = -1;
                return;
            }
            if (this.popupCloseDelay < 0) {
                this.popupCloseDelay = POPUP_CLOSE_GRACE;
            }
            return;
        }
        this.popupCloseDelay = -1;
        if (container.equals(this.hoveredContainer)) {
            return;
        }
        this.hoveredContainer = container;
        this.pendingAddCount = "";
        this.refreshHighlight();
        this.refreshDetail();
        // 换了容器 = 重新贴一次鼠标；同一个容器内移动鼠标不动它（动了就追不上）。
        this.refreshContainerPopup(true);
    }

    // ── 容器库存浮层 ───────────────────────────────────────────────────

    /**
     * 贴着鼠标显示当前容器的库存。
     *
     * <p>显示的是**当前播放时刻**的库存：材料被扣掉、产物加上去，播放到哪一步就是哪一步的账
     * （数据来自 {@link SimulationRunner.Result#inventoryAt(int)}）。
     * 可编辑的只有「初始数量」与容量，而且只在跑到最后一步之后才解锁 ——
     * 仿真过程是确定性的，能改的输入本来就只有初始状态。
     */
    private void refreshContainerPopup() {
        this.refreshContainerPopup(false);
    }

    /**
     * @param reanchor 是否重新贴到鼠标位置。
     *                 <b>只在「刚悬浮到某个容器上」时为 true</b>：浮层一旦出现就不再跟着鼠标跑，
     *                 否则鼠标永远追不上它（一靠近它就跟着挪），那就点不到里面的东西了。
     */
    private void refreshContainerPopup(boolean reanchor) {
        this.popupContent.clearAllChildren();
        if (this.hoveredContainer.isBlank() || this.document == null) {
            this.popupHost.setDisplay(false);
            return;
        }
        if (reanchor || !this.popupHost.isDisplayed()) {
            // 真正的定位发生在 canvasHost 的绘制回调里 —— 只有那里才拿得到
            // 「画布局部坐标下的鼠标位置」。这里只是挂个标记。
            this.popupNeedsAnchor = true;
        }
        this.popupHost.setDisplay(true);
        this.buildPopupRows();
        this.applyPopupHeight();
    }

    /**
     * 把浮层放到鼠标右下方，越界就翻到另一侧。
     *
    /**
     * 把浮层贴在鼠标旁边。**不做任何坐标换算** —— 用的是绘制时抓到的画布局部鼠标坐标，
     * 与 left/top 同一个坐标系，所以「贴 8 像素」就是真的 8 像素。
     *
     * @return 布局尺寸还不可用时返回 false，等下一帧再放。
     */
    /**
     * 把浮层固定在画布**左下角**。
     *
     * <p>这里刻意**不做任何鼠标坐标换算**。前前后后试过四种来源
     * （绝对鼠标 − 元素绝对位置、{@code worldToLocal}、{@code context.localMouseX}、
     * 场景局部鼠标 + 相对偏移），每一种都还是差一大块 —— 说明我始终没拿到
     * 「和 left/top 同一个坐标系」的那个鼠标值，而这些换算只要差一层就是几百像素。
     *
     * <p>与其继续猜，不如把不确定性从公式里去掉：位置写死，用户永远知道它在哪里、
     * 鼠标一定够得到（这也正是「碰不到浮层」这个问题的本质）。
     * 左上角是卡片，左下角是空的，所以放左下。
     *
     * @return 布局尺寸还不可用（画布尺寸为 0）时返回 false，下一帧再放。
     */
    private boolean anchorPopupToMouse() {
        float canvasWidth = this.canvasHost.getSizeWidth();
        float canvasHeight = this.canvasHost.getSizeHeight();
        if (canvasWidth < 8.0f || canvasHeight < 8.0f) {
            return false;
        }
        float width = Math.max(120.0f, Math.min(POPUP_WIDTH, canvasWidth * 0.42f));
        float height = this.popupHeight();
        // popupMouseX/Y 是画布局部坐标（实测与光标一致），left/top 也在同一个坐标系里，
        // 所以「贴 8 像素」就是真的 8 像素。右下放不下就翻到左上（同样紧贴），
        // 两侧都放不下（画布太窄）才以光标为中心 —— 至少保证光标落在框里点得到。
        float left;
        if (this.popupMouseX + POPUP_EDGE + width <= canvasWidth) {
            left = this.popupMouseX + POPUP_EDGE;
        } else if (this.popupMouseX - POPUP_EDGE - width >= 0.0f) {
            left = this.popupMouseX - POPUP_EDGE - width;
        } else {
            left = this.popupMouseX - width * 0.5f;
        }
        float top;
        if (this.popupMouseY + POPUP_EDGE + height <= canvasHeight) {
            top = this.popupMouseY + POPUP_EDGE;
        } else if (this.popupMouseY - POPUP_EDGE - height >= 0.0f) {
            top = this.popupMouseY - POPUP_EDGE - height;
        } else {
            top = this.popupMouseY - height * 0.5f;
        }
        this.popupLeft = Math.max(2.0f, Math.min(left, Math.max(2.0f, canvasWidth - width - 2.0f)));
        this.popupTop = Math.max(2.0f, Math.min(top, Math.max(2.0f, canvasHeight - height - 2.0f)));
        this.popupAppliedWidth = width;
        this.popupAppliedHeight = height;
        final float finalLeft = this.popupLeft;
        final float finalTop = this.popupTop;
        this.popupHost.layout(layout -> {
            layout.left(finalLeft);
            layout.top(finalTop);
            layout.width(width);
            layout.height(height);
        });
        this.popupNeedsAnchor = false;
        return true;
    }

    /** 浮层内容高度：标题 + 实时内容（图标 18 高）+ 可编辑时才有新增行。 */
    private float popupHeight() {
        float height = POPUP_PADDING * 2 + POPUP_ROW_HEIGHT + 22.0f;
        if (this.editable()) {
            height += POPUP_ROW_HEIGHT + POPUP_GAP;
        }
        return height;
    }

    private void applyPopupHeight() {
        float height = this.popupHeight();
        // 高度变了也要更新记录，否则 popupHeld() 用的还是旧矩形。
        this.popupAppliedHeight = height;
        this.popupHost.layout(layout -> layout.height(height));
    }

    /** 鼠标是不是正压在浮层上（与定位共用同一套画布局部坐标，不会出现「两把尺子」）。 */
    private boolean mouseOverPopup() {
        if (!this.popupHost.isDisplayed()) {
            return false;
        }
        return this.popupMouseX >= this.popupLeft && this.popupMouseY >= this.popupTop
                && this.popupMouseX <= this.popupLeft + this.popupAppliedWidth
                && this.popupMouseY <= this.popupTop + this.popupAppliedHeight;
    }

    /**
     * 鼠标是不是在浮层上（含浮层里的子控件）。
     *
     * <p>用 {@code isSelfOrChildHover()}，**不是** {@code isHover()}：
     * 后者的实现是 {@code getLastHoveredElement() == this} —— 只对「最后一个被悬停的元素」为真，
     * 鼠标落在浮层里的按钮 / 输入框上时它是 false。我前面拿它判断，永远得不到真，
     * 于是鼠标一离开容器浮层就被关掉，表现就是「碰不到」。这是查 LDLib2 源码才看清的。
     *
     * <p>{@code isSelfOrChildHover()} 会从 last hovered 元素沿父链往上找，
     * 鼠标在浮层内部任何位置都返回 true。
     *
     * <p>另外叠一个自己算的矩形判定（鼠标值已被实机验证是正确的），
     * 万一框架的 hover 状态还没刷新，也不会误关。
     */
    private boolean popupHeld() {
        if (this.popupHost.isSelfOrChildHover()) {
            return true;
        }
        return this.popupMouseX >= this.popupLeft
                && this.popupMouseX <= this.popupLeft + this.popupAppliedWidth
                && this.popupMouseY >= this.popupTop
                && this.popupMouseY <= this.popupTop + this.popupAppliedHeight;
    }

    private void buildPopupRows() {
        SimulationRunner.Result result = this.session.result();
        boolean editable = this.editable();
        // 标题：容器名 + 容量。容量是读出来的，不是让人填的。
        this.popupContent.addChild((UIElement)SimuPackLayout.label(
                (Component)Component.literal((String)(this.hoveredContainer + " (" + this.capacityLabel(this.hoveredContainer) + ")")),
                (int)(this.document.isContainerPoint(this.hoveredContainer) ? -1 : -171)));

        // 上半部**只做显示**：箱子里现在有什么。图标 + 右下角数量，跟原版物品栏一个样子。
        // 跑起来之前显示已设好的初始内容，跑起来之后显示当前播放时刻的实时内容。
        this.popupContent.addChild(this.liveContentsRow());

        // 下半部：选物品 → 填数量 → 按「添加」。**仿真中整行都不显示** ——
        // 那时改不了任何东西，摆一排灰控件只会占地方、还容易误以为能点。
        if (editable) {
            this.popupContent.addChild(this.popupAddRow(editable));
        }
    }

    /**
     * 工人背包里现在有什么（一格一个图标，数量交给原版角标渲染）。
     *
     * <p>这个元素不是放到界面上，而是作为 **tooltip 的内容**塞进去的
     * （见 {@code hoverTooltips} 监听器）：悬停市民时它出现在文字「作者：…」的**下一行**。
     * 数量用 {@code ItemSlot} 的原版角标（{@code renderItemDecorations}），不是自己贴的字。
     */
    private UIElement backpackRow(List<SimBackpack.Slot> slots) {
        UIElement row = SimuPackLayout.row((int)1);
        row.layout(layout -> layout.flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP));
        for (SimBackpack.Slot slot : slots) {
            row.addChild(this.liveItemCell(slot.item(), slot.count(), false));
        }
        return row;
    }

    /** 当前要显示的内容：算过账就用当前时刻的实时值，否则就是已经设好的初始内容。 */
    private Map<String, Integer> shownContents() {
        SimulationRunner.Result result = this.session.result();
        if (this.computed && result != null) {
            return result.inventoryAt(this.session.index()).getOrDefault(this.hoveredContainer, Map.of());
        }
        return this.initialInventory.contents(this.hoveredContainer);
    }


    private UIElement liveContentsRow() {
        UIElement row = SimuPackLayout.row((int)1);
        // 物品种类多时换行，别往右溢出到浮层外面。
        row.layout(layout -> layout.flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP));
        // **一格一个图标**：1728 个水瓶在真实容器里是 27 格 × 64，不是一格写着 1728。
        // 摊格规则和容量判定同源（SimInventory.slotLayout），所以界面显示的格数
        // 就是判定用的格数，不会出现「看着放得下、判定说放不下」。
        for (SimInventory.Slot slot : this.shownSlotLayout()) {
            row.addChild(this.liveItemCell(slot.item(), slot.count(), true));
        }
        return row;
    }

    /** 当前该显示的**逐格**内容：算过账就用当前时刻的实时快照，否则用已设好的初始内容。 */
    private java.util.List<SimInventory.Slot> shownSlotLayout() {
        SimulationRunner.Result result = this.session.result();
        if (this.computed && result != null) {
            return this.initialInventory.slotLayout(this.hoveredContainer,
                    result.inventoryAt(this.session.index()).getOrDefault(this.hoveredContainer, Map.of()));
        }
        return this.initialInventory.slotLayout(this.hoveredContainer);
    }

    /**
     * 一格物品：图标 + 右下角数量，全部交给 {@code ItemSlot} 的原版装饰渲染。
     *
     * <p>**仿真没跑的时候右键这一格 = 清掉这一格**（清的量 = 这一格显示的量，
     * 不是这种物品的全部）：手动加进去的东西总得能拿掉，否则只能清空整个容器重来。
     */
    private UIElement liveItemCell(String item, int count, boolean clearable) {
        ItemStack stack = ItemRegistry.stackOf(item);
        if (stack.isEmpty()) {
            return (UIElement)SimuPackLayout.label((Component)Component.literal((String)item), (int)-4342339);
        }
        // 数量交给 ItemSlot 自己的渲染：查 LDLib2 源码，
        // ItemSlot.drawItemStack -> DrawerHelper.drawItemStack 里就是
        // GuiGraphics.renderItem(...) 跟着 GuiGraphics.renderItemDecorations(...)，
        // 也就是原版那套数量装饰，角标位置和物品栏一模一样。
        // （先前把数量设成 1 再自己贴 Label，等于把已经正确的装饰盖掉了。）
        stack.setCount(Math.max(1, Math.min(9999, count)));
        UIElement cell = EditorWidgets.plainSlot(stack, 18.0F);
        if (this.editable()) {
            // GLFW 的右键是 button == 1：右键清掉这一格。不额外贴提示，免得挡着物品图标。
            cell.addEventListener("mouseDown", event -> {
                if (event.button != 1) {
                    return;
                }
                event.stopPropagation();
                this.initialInventory.take(this.hoveredContainer, item, Math.max(1, count));
                this.refreshContainerPopup(false);
            });
        }
        return cell;
    }

    /**
     * 新增一行：选物品 → 填数量 → 按「添加」。三步都做完才真的写进箱子。
     *
     * <p>运行时整行不可用（不可编辑），上半部的实时内容继续更新。
     */
    private UIElement popupAddRow(boolean editable) {
        UIElement row = SimuPackLayout.row((int)2);
        String container = this.hoveredContainer;
        // ① 选物品：18×18 的框，没选时画「+」（和 EditorWidgets 画「?」同一套做法），
        //    选了就把那个物品的图标显示在这里。
        UIElement addSlot = new UIElement();
        addSlot.layout(layout -> {
            layout.width(18.0f);
            layout.height(18.0f);
            layout.flexShrink(0.0f);
            layout.justifyContent(org.appliedenergistics.yoga.YogaJustify.CENTER);
            layout.alignItems(dev.vfyjxf.taffy.style.AlignItems.CENTER);
        });
        if (this.pendingAddItem.isBlank()) {
            addSlot.addChild((UIElement)SimuPackLayout.label((Component)Component.literal((String)"+"), SimuPackTheme.TEXT_SUCCESS_COLOR));
        } else {
            addSlot.addChild(this.widgets.itemCell(this.pendingAddItem, this::openItemPicker, false, ignored -> {}));
        }
        if (editable) {
            addSlot.addEventListener("mouseDown", event -> {
                event.stopPropagation();
                this.host.openItemPicker(picked -> {
                    if (picked == null || picked.isBlank()) {
                        return;
                    }
                    // 只记下选择，不立刻写进箱子 —— 数量填完再按「添加」。
                    this.pendingAddItem = (String)picked;
                    this.refreshContainerPopup();
                    // allowTags = false：箱子装的是**具体物品**。
                    // 标签（#minecraft:xxx）是配方级的写法，放进容器内容里既没法计数也没法扣料。
                }, false);
            });
        } else {
            this.greyWhenLocked(addSlot, false);
        }
        row.addChild(addSlot);

        // ② 数量（留空按 1 计）。运行时用只读文本，看得见但改不了。
        if (editable) {
            TextField amount = new TextField().setText(this.pendingAddCount);
            SimuPackLayout.pinWidth((UIElement)amount, 28.0f);
            amount.setTextResponder(value -> this.pendingAddCount = value == null ? "" : value.trim());
            row.addChild((UIElement)amount);
        } else {
            row.addChild((UIElement)SimuPackLayout.label((Component)Component.literal((String)this.pendingAddCount), (int)-4342339));
        }

        // ③ 添加：只有选了物品才能按。
        Button confirm = new Button().setText((Component)Component.translatable((String)"gui.simupack.industrial.simulation.popup_add"));
        boolean ready = editable && !this.pendingAddItem.isBlank();
        if (ready) {
            confirm.setOnClick(event -> {
                this.initialInventory.add(container, this.pendingAddItem, this.pendingAddAmount());
                this.pendingAddItem = "";
                this.pendingAddCount = "";
                this.afterInventoryEdit();
            });
        } else {
            confirm.style(style -> style.opacity(0.45f));
        }
        row.addChild((UIElement)confirm);
        return row;
    }

    private int pendingAddAmount() {
        if (this.pendingAddCount.isEmpty()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(this.pendingAddCount));
        }
        catch (NumberFormatException numberFormatException) {
            return 1;
        }
    }

    /** 不能编辑时把控件置灰并停掉交互 —— 不再另外写一行「去跑完才能改」的提示。 */
    private void greyWhenLocked(UIElement element, boolean editable) {
        if (editable) {
            return;
        }
        element.setActive(false);
        element.style(style -> style.opacity(0.45f));
    }


    private void applyCapacity(String container, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            this.initialInventory.setCapacity(container, Integer.MAX_VALUE);
        } else {
            try {
                this.initialInventory.setCapacity(container, Integer.parseInt(text));
            }
            catch (NumberFormatException numberFormatException) {
                return;
            }
        }
        this.invalidate();
    }

    /**
     * 把每个容器的容量按**结构里的方块**填好。
     *
     * <p>容量是方块的属性（箱子 27 格、漏斗 5 格、发射器 9 格……），
     * 让玩家手输既多余又容易填错。认不出来的方块保持不限量。
     */
    /**
     * 把容器名与方块对应起来：**指向同一批方块的多个名字共用一本账**，容量则从方块读出来。
     *
     * <p>最常见的情况就是 input 和 output 其实是同一个箱子 —— 不连起来的话，
     * 「往 input 里放、从 output 看」会看到两个互不相干的箱子，产物永远不出现。
     */
    private void applyContainerMetadata() {
        if (this.document == null) {
            return;
        }
        Map<String, String> groupOf = new LinkedHashMap<String, String>();
        for (String name : this.document.containerKeys()) {
            List<SimPos> positions = SimPos.sortedList(this.definitionPositions(name, BuildingJsonDocument.DefinitionKind.CONTAINER));
            if (positions.isEmpty()) {
                continue;
            }
            // 位置集合相同 = 同一批方块。名字不同但坐标一样，必须共用一本账。
            String key = positions.toString();
            String canonical = groupOf.get(key);
            if (canonical != null) {
                this.initialInventory.link(name, canonical);
                continue;
            }
            groupOf.put(key, name);
            int capacity = this.scene.containerCapacityAt(positions.get(0));
            if (capacity > 0) {
                this.initialInventory.setCapacity(name, capacity);
            }
            // 每格自己的上限也一起读：一格能装多少 = min(该格上限, 物品堆叠上限)，
            // 模组容器常常限制特定格位，只按「格数 × 64」算会把容量算大。
            this.initialInventory.setSlotLimits(name, this.scene.containerSlotLimitsAt(positions.get(0)));
        }
    }

    /**
     * 浮层上显示的容量文案。
     *
     * <p>**只写格数**：一格到底能装多少，取决于该格上限和物品自己的堆叠上限，
     * 是两者取小的结果 —— 列在标题里既占地方又容易让人以为「27 × 64 = 1728 件」，
     * 所以容量判定照旧（内核按逐格 + 物品上限算），标题不展开。
     */
    private String capacityLabel(String name) {
        int capacity = this.initialInventory.capacity(name);
        return capacity == Integer.MAX_VALUE
                ? Component.translatable((String)"gui.simupack.industrial.simulation.popup_unlimited").getString()
                : Integer.toString(capacity);
    }

    /** 浮层里宽度紧张，去掉命名空间。 */
    private static String shortItemName(String item) {
        if (item == null) {
            return "";
        }
        int colon = item.indexOf(58);
        return colon < 0 ? item : item.substring(colon + 1);
    }

    /**
     * 现在能不能改容器的初始库存。
     *
     * <p>规则是「**没有开始仿真**的时候即可编辑」：还没算过账（刚进页面 / 刚改过配方）能改；
     * 或者停在第一步且没有在播放（点一下时间轴第 1 行、或重播之后）也能改。
     * 一旦跑起来了就只读，输入框置灰。
     */
    private boolean editable() {
        return !this.computed || (!this.session.playing() && this.session.index() == 0);
    }

    private String containerAt(BlockPos pos) {
        if (this.document == null || pos == null) {
            return "";
        }
        for (String name : this.document.containerKeys()) {
            for (SimPos candidate : this.definitionPositions(name, BuildingJsonDocument.DefinitionKind.CONTAINER)) {
                if (!IndustrialSimulationPanel.toBlockPos(candidate).equals((Object)pos)) continue;
                return name;
            }
        }
        return "";
    }

    private static BlockPos toBlockPos(SimPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    private static SimPos toSimPos(BlockPos pos) {
        return new SimPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private String recipeName() {
        if (this.document == null) {
            return this.selectedRecipeId;
        }
        JsonObject recipe = this.document.recipe(this.selectedRecipeId);
        return recipe == null ? this.selectedRecipeId : this.document.recipeDisplayName(recipe);
    }

    private List<SimuPackMenuBar.Entry> recipeEntries() {
        ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
        if (this.document == null) {
            return entries;
        }
        List recipes = this.document.recipes();
        for (int index = 0; index < recipes.size(); ++index) {
            JsonObject recipe = (JsonObject)recipes.get(index);
            Object id = IndustrialSimulationPanel.string(recipe, "id");
            if (((String)id).isBlank()) {
                id = "recipe_" + index;
            }
            String name = this.document.recipeDisplayName(recipe);
            Object targetId = id;
            entries.add(SimuPackMenuBar.Entry.item((String)name, (String)(((String)id).equals(this.selectedRecipeId) ? "\u2713" : ""), () -> this.lambda$recipeEntries$24((String)targetId), () -> true));
        }
        return entries;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        }
        catch (RuntimeException ignored) {
            return "";
        }
    }

    private /* synthetic */ void lambda$recipeEntries$24(String targetId) {
        this.selectedRecipeId = targetId;
        this.cardDirty = true;
        this.planDirty = true;
        this.host.refresh();
    }

    private static final class SilentHost
    implements EditorHost {
        private SilentHost() {
        }

        @Override
        public void openMenu(float mouseX, float mouseY, List<SimuPackMenuBar.Entry> entries) {
        }

        @Override
        public void pushHistory() {
        }

        @Override
        public void recordEdit(String tag) {
        }

        @Override
        public void commit() {
        }

        @Override
        public void refresh() {
        }

        @Override
        public void notify(String message) {
        }

        @Override
        public void openMiddleTab(MiddleTabKind kind) {
        }

        @Override
        public void openPickTab() {
        }

        @Override
        public void openDialog(EditorDialog dialog) {
        }

        @Override
        public void openItemPicker(Consumer<String> onPick, boolean allowTags) {
        }

        @Override
        public void openBlockPicker(Consumer<String> onPick) {
        }

        @Override
        public boolean hasStructure() {
            return false;
        }

        @Override
        public void openPointPicker(List<BlockPos> initial, Consumer<List<BlockPos>> onPicked) {
        }
    }
}

