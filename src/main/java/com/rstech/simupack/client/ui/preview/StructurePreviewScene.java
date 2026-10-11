/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.client.ui.preview;

import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.utils.data.BlockPosFace;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.creation.industrial.sim.SimGesture;
import com.rstech.simupack.creation.industrial.sim.SimMotion;
import com.rstech.simupack.creation.industrial.sim.SimNavigation;
import com.rstech.simupack.creation.industrial.sim.SimPos;
import com.rstech.simupack.creation.industrial.sim.SimulationRunner;
import com.rstech.simupack.creation.industrial.sim.nav.GridPathfinder;
import com.rstech.simupack.creation.industrial.sim.nav.NavCell;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import com.rstech.simupack.client.ui.preview.ScenePickMath;
import com.rstech.simupack.client.ui.preview.StructurePreviewLoader;
import com.rstech.simupack.client.ui.preview.UnitBoxVisual;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL11;

@OnlyIn(value=Dist.CLIENT)
public final class StructurePreviewScene
extends Scene {
    private static final float CAMERA_YAW = -135.0f;
    private static final float CAMERA_PITCH = 25.0f;
    private static final float ZOOM_PADDING = 1.18f;
    private static final float EMPTY_ZOOM = 3.5f;
    private final TrackedDummyWorld previewWorld = new TrackedDummyWorld();
    private int blockCount;
    private List<BlockPos> core = List.of();
    private final Vector3f boundsMinimum = new Vector3f();
    private final Vector3f boundsMaximum = new Vector3f(1.0f);
    private Set<BlockPos> highlighted = Set.of();
    private List<BlockPos> simulationPath = List.of();
    private BlockPos simulationCitizen;
    private java.util.function.Consumer<BlockPos> hoverHandler = ignored -> { };
    private boolean hoverPicking;

    public void enableHoverPicking() {
        hoverPicking = true;
        pickDirty = true;
    }
    private PlayerModel<?> citizenModel;
    private ItemStack citizenHeldItem = ItemStack.EMPTY;

    public void setCitizenHeldItem(ItemStack stack) {
        citizenHeldItem = stack == null ? ItemStack.EMPTY : stack.copy();
    }
    /** 市民当前用的皮肤。每轮仿真开始时从 {@link SimulationSkins} 里随机挑一张。 */
    private ResourceLocation citizenSkin = SimulationSkins.defaultSkin();
    /** 本帧拾取射线是否打中市民。 */
    private boolean hoveredCitizen;

    public float frameMouseX() { return frameMouseX; }
    public float frameMouseY() { return frameMouseY; }

    /**
     * 鼠标在**本场景坐标系**里的位置（由框架在绘制时算好）。
     *
     * <p>{@code GUIContext.localMouseX/Y} 只为「鼠标真正悬停的那个元素」刷新 ——
     * 在别的元素（例如外层画布）的绘制回调里读它，拿到的其实是<b>本场景</b>的局部坐标。
     * 所以这个值必须由场景自己捕获并对外提供，外面想换算时也只能用它 +
     * 「本场景与外层元素的位置差」，不能拿绝对鼠标坐标去减绝对位置（那是两把尺子）。
     */
    public float localMouseX() { return localMouseX; }
    public float localMouseY() { return localMouseY; }

    public void setHoverHandler(java.util.function.Consumer<BlockPos> handler) {
        hoverHandler = handler == null ? ignored -> { } : handler;
    }

    /** Set the virtual citizen route and current position for simulation preview. */
    public void setSimulationPath(Collection<BlockPos> path, BlockPos citizen) {
        simulationPath = path == null ? List.of() : List.copyOf(path);
        if (citizen != null) {
            setSimulationCitizenPosition(citizen.getX() + 0.5D, citizen.getY(), citizen.getZ() + 0.5D);
        }
    }

    /**
     * 市民此刻的<b>连续</b>坐标。
     *
     * <p>旧版只有一个整数 {@code BlockPos}：市民要么在这一格、要么在那一格，走一秒会跳五格。
     * 现在坐标由 {@code SimulationSession} 沿寻路折线按速度插值给出，渲染层只负责画。
     */
    public void setSimulationCitizenPosition(double x, double y, double z) {
        citizenX = x;
        citizenY = y;
        citizenZ = z;
        citizenPlaced = true;
    }

    /**
     * 市民走路动画的相位（弧度）。
     *
     * <p>由<b>累计里程</b>换算，不是 tick：停下来时腿就该停，走快走慢摆腿频率要跟着变。
     * 旧版拿 {@code simulationPath.size()} 当相位，路径长度一变腿就乱抖。
     */
    public void setCitizenWalkPhase(float phase) {
        citizenWalkPhase = phase;
    }

    /**
     * 市民的朝向（Minecraft 的 bodyYaw：0 = 朝 +Z，90 = 朝 -X）。
     *
     * <p>旧版把朝向固定成 0，市民永远面向同一个方向，看起来像块会平移的贴图而不是「一个人」。
     */
    public void setCitizenFacing(float yawDegrees) {
        citizenYaw = yawDegrees;
    }

    /**
     * 当前动作与它已经进行了多少 tick，用来驱动挥手 / 开箱 / 作业的手臂动作。
     *
     * @see com.rstech.simupack.creation.industrial.sim.SimGesture
     */
    public void setCitizenGesture(SimGesture gesture, int gestureTicks) {
        citizenGesture = gesture == null ? SimGesture.IDLE : gesture;
        citizenGestureTicks = Math.max(0, gestureTicks);
    }

    /** 当前这一段路是走、是跳还是往下掉；下落 / 攀爬时不摆腿。 */
    public void setCitizenMotion(SimMotion motion) {
        citizenMotion = motion == null ? SimMotion.WALK : motion;
    }

    /**
     * {@code look_at} 要看向的点；{@code null} 表示不限制头部朝向。
     *
     * <p>NSUK 的 {@code look_at} / {@code look_at_container} 是真的转头，
     * 这里也照做：按目标点算头部相对身体的 yaw / pitch。
     */
    public void setCitizenLookAt(double x, double y, double z) {
        lookX = x;
        lookY = y;
        lookZ = z;
        hasLookTarget = true;
    }

    public void clearCitizenLookAt() {
        hasLookTarget = false;
    }

    private double citizenX;
    private double citizenY;
    private double citizenZ;
    private boolean citizenPlaced;
    private float citizenWalkPhase;
    private float citizenYaw;
    private SimGesture citizenGesture = SimGesture.IDLE;
    private SimMotion citizenMotion = SimMotion.WALK;
    private int citizenGestureTicks;
    private double lookX;
    private double lookY;
    private double lookZ;
    private boolean hasLookTarget;
    /** 平滑后的「正在走路」权重（0~1），让起步 / 停步不是瞬间切换。 */
    private float walkAmount;
    /** 玩家模型烘焙失败过一次之后就不再重试，直接走替身。 */
    private boolean citizenFallbackOnly;

    /** Reset the virtual citizen to the industrial control box before playback starts. */
    public void resetSimulationCitizen() {
        clearSimulationCitizen();
    }

    public void clearSimulationCitizen() {
        citizenPlaced = false;
        walkAmount = 0.0F;
        citizenGesture = SimGesture.IDLE;
        citizenGestureTicks = 0;
    }

    public BlockPos simulationStartPosition() {
        return findControlBoxPos();
    }
    private List<UnitBoxVisual> unitBoxes = List.of();
    private UIElement inputOverlay;
    private BiConsumer<BlockPos, Direction> pickHandler;
    private Runnable pickMissHandler;
    private BlockHitResult hoveredHit;
    private BlockPos pressedBlock;
    private boolean leftPressed;
    private boolean middleDragging;
    private float middleLastX;
    private float middleLastY;
    private boolean leftDragging;
    private float leftLastX;
    private float leftLastY;
    private float pressX;
    private float pressY;
    private float pressYaw;
    private float pressPitch;
    private static final float ROTATE_TOLERANCE = 2.0f;
    private static final float CLICK_MOVE_TOLERANCE = 4.0f;
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final int[] pickViewport = new int[4];
    private int guiWidth;
    private int guiHeight;
    private int framebufferWidth;
    private int framebufferHeight;
    private float frameMouseX;
    private float frameMouseY;
    private float localMouseX;
    private float localMouseY;
    private boolean renderingFrame;
    private boolean pickFrameValid;
    private boolean pickDirty = true;
    private float lastPickMouseX = Float.NaN;
    private float lastPickMouseY = Float.NaN;
    private boolean autoFitPending;
    private static final float PICKER_WHEEL_FACTOR = 0.62f;
    private static final float PICKER_MIN_ZOOM = 0.08f;
    private static final float PICKER_MAX_ZOOM = 256.0f;

    public StructurePreviewScene() {
        this(true, true);
    }

    public StructurePreviewScene(boolean cacheBuffers, boolean ortho) {
        this.createScene((Level)this.previewWorld).useOrtho(ortho).setTickWorld(false).setRenderFacing(false).setRenderSelect(false).setShowHoverBlockTips(false);
        // 这里**刻意不挂** setSceneEntityRenderHook 去推 walkAnimation。
        //
        // 上一版挂了，每帧 living.walkAnimation.update(0.5F, 0.4F) —— 结果是「动物一直在走，
        // 但原地不动」：假世界不 tick，实体位置永远不会变，可腿在摆，看起来就是卡在走路动画里。
        // 站着不动的动物本来就该腿不动，别再自己造一个更难看的假象。
        // （市民不在此列：它是手工画的玩家模型 renderCitizenModel，有自己的动画。）
        // 缓存开关由调用方定，而且**显式设定**（不设的话就是 LDLib 的默认值，靠猜）。
        //
        // 缓存（FBO）把整栋建筑烘进每层的 vertexBuffers，省掉每帧逐方块重画；代价是
        // 「改一个方块 ⇒ 整栋重编」。所以不能简单地说开或关：
        //   · 静态部分走缓存；
        //   · 会被仿真改动的方块由调用方声明出来（见 setDynamicBlocks），从缓存里切出去，
        //     交给一个不缓存的动态层每帧现画 —— 于是改方块不再触发重编。
        // 另外走缓存时**必须**配同步分片编译，否则 NEED 状态那一帧什么都不画（闪屏），理由见下。
        this.useCacheBuffer(cacheBuffers);
        this.configureCacheCompile(cacheBuffers);
        // **自己装方块白名单**，不再靠 setRenderedCore 去扩展它。
        //
        // 为什么要自己装：LDLib 的白名单是 `Scene.core::contains`，而 `setRenderedCore` 的实现是
        // 「先把 this.core 里全部方块从渲染器移除、再全量加回去」—— 每调一次整个结构就消失一瞬
        // 重编一次，那就是「放一个方块闪一次屏」。这里改成让过滤器同时认「结构本体」和
        // 「仿真放下的位置」，于是放下方块**不需要**碰渲染器的方块集合（改成增量登记一格）。
        this.installBlockFilter();
        this.addEventListener("mouseWheel", event -> {
            if (this.overlayTarget(event)) {
                return;
            }
            // 向下滚 = 拉远（Minecraft 的习惯）。原来这里是反的：向下滚是拉近，
            // 于是「想推进去看内饰」时走的是 ×1.22 那支，一路放大到 256 的上限卡死 ——
            // 现象就是「滚轮完全没反应，而且相机一直在墙外」。
            float factor = event.deltaY > 0.0f ? 1.22f : 0.82f;
            // 下限给到 0.01：相机绕的是结构包围盒中心，这个距离早已在室内。
            this.setZoom(Math.max(0.01f, Math.min(256.0f, this.getZoom() * factor)));
            this.pickDirty = true;
            event.stopPropagation();
        });
        this.addEventListener("mouseMove", event -> {
            if (this.middleDragging) {
                float sensitivity = 0.42f * Math.max(1.0f, (float)Math.sqrt(this.getZoom()));
                this.setCameraYawAndPitch(this.getRotationYaw() + (event.x - this.middleLastX) * sensitivity, this.getRotationPitch() + (event.y - this.middleLastY) * sensitivity);
                this.pickDirty = true;
                this.middleLastX = event.x;
                this.middleLastY = event.y;
                event.stopPropagation();
                return;
            }
            if (this.leftPressed) {
                float dx = event.x - this.pressX;
                float dy = event.y - this.pressY;
                if (!this.leftDragging && dx * dx + dy * dy > 16.0f) {
                    this.leftDragging = true;
                }
                if (this.leftDragging) {
                    this.panCamera(event.x - this.leftLastX, event.y - this.leftLastY);
                    this.pickDirty = true;
                    this.leftLastX = event.x;
                    this.leftLastY = event.y;
                    event.stopPropagation();
                }
            }
        });
        this.addEventListener("mouseUp", event -> {
            if (event.button == 2) {
                this.middleDragging = false;
                event.stopPropagation();
            }
        });
        this.showStructure(null);
    }

    public void setHighlighted(Collection<BlockPos> positions) {
        this.highlighted = Set.copyOf(positions);
    }

    public void setUnitBoxes(Collection<UnitBoxVisual> boxes) {
        this.unitBoxes = boxes == null ? List.of() : List.copyOf(boxes);
    }

    public void setInputOverlay(UIElement overlay) {
        this.inputOverlay = overlay;
    }

    private boolean overlayTarget(UIEvent event) {
        if (this.inputOverlay == null || event == null) {
            return false;
        }
        for (UIElement element = event.target; element != null; element = element.getParent()) {
            if (element != this.inputOverlay) continue;
            return true;
        }
        return false;
    }

    public StructurePreviewScene onPick(BiConsumer<BlockPos, Direction> handler) {
        this.pickHandler = handler;
        return this;
    }

    public StructurePreviewScene onPickMiss(Runnable handler) {
        this.pickMissHandler = handler;
        return this;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void drawBackgroundAdditional(GUIContext context) {
        if (this.isUseOrtho() && this.getRenderer() != null) {
            WorldSceneRenderer renderer = this.getRenderer();
            float halfWidth = this.getRange() * this.getZoom();
            float depth = ScenePickMath.orthographicDepthHalfExtent((Vector3fc)renderer.getLookAt(), (Vector3fc)this.boundsMinimum, (Vector3fc)this.boundsMaximum, renderer.getEyePos().distance((Vector3fc)renderer.getLookAt()));
            renderer.setCameraOrtho(halfWidth, halfWidth, depth);
        }
        this.frameMouseX = context.mouseX;
        this.frameMouseY = context.mouseY;
        this.localMouseX = context.localMouseX;
        this.localMouseY = context.localMouseY;
        this.guiWidth = context.surface.guiScaledWidth();
        this.guiHeight = context.surface.guiScaledHeight();
        this.framebufferWidth = context.surface.framebufferWidth();
        this.framebufferHeight = context.surface.framebufferHeight();
        this.pickFrameValid = false;
        this.renderingFrame = true;
        try {
            super.drawBackgroundAdditional(context);
        }
        finally {
            this.renderingFrame = false;
        }
    }

    public void renderBlockOverLay(WorldSceneRenderer renderer) {
        if (this.pickHandler == null && !hoverPicking) {
            super.renderBlockOverLay(renderer);
            this.renderUnitBoxes();
            return;
        }
        if (!this.isHover()) {
            this.hoveredHit = null;
            hoverHandler.accept(null);
        }
        if (this.renderingFrame) {
            GL11.glGetIntegerv((int)2978, (int[])this.pickViewport);
            this.inverseViewProjection.set((Matrix4fc)RenderSystem.getProjectionMatrix()).mul((Matrix4fc)RenderSystem.getModelViewMatrix());
            float determinant = this.inverseViewProjection.determinant();
            if (Float.isFinite(determinant) && Math.abs(determinant) > 1.0E-12f) {
                this.inverseViewProjection.invert();
                this.pickFrameValid = true;
                if (this.isHover() && (this.pickDirty || this.frameMouseX != this.lastPickMouseX || this.frameMouseY != this.lastPickMouseY)) {
                    this.hoveredHit = this.pickAt(this.frameMouseX, this.frameMouseY);
                    hoverHandler.accept(this.hoveredHit == null ? null : this.hoveredHit.getBlockPos().immutable());
                    this.lastPickMouseX = this.frameMouseX;
                    this.lastPickMouseY = this.frameMouseY;
                    this.pickDirty = false;
                }
            }
        }
        PoseStack pose = new PoseStack();
        for (BlockPos pos : this.highlighted) {
            this.outline(pose, pos, -16718337);
        }
        if (this.hoveredHit != null) {
            this.outline(pose, this.hoveredHit.getBlockPos(), -8395);
        }
        this.renderUnitBoxes();
        this.renderSimulationOverlay();
        if (this.afterWorldRender != null) {
            this.afterWorldRender.accept(this);
        }
    }

    private void renderSimulationOverlay() {
        BlockPos fallback = citizenPlaced ? null : fallbackCitizenPos();
        if (this.simulationPath.isEmpty() && !citizenPlaced && fallback == null) return;
        double cx = citizenPlaced ? citizenX : fallback.getX() + 0.5D;
        double cy = citizenPlaced ? citizenY : fallback.getY();
        double cz = citizenPlaced ? citizenZ : fallback.getZ() + 0.5D;

        PoseStack pose = new PoseStack();
        // 路线可能上百格，逐格画线框会把这帧的顶点数顶到没边。等距抽稀到 MAX_PATH_OUTLINE 个，
        // 并保证终点一定画出来 —— 看上去仍是一条连续的路线走廊。
        List<BlockPos> path = this.simulationPath;
        int stride = Math.max(1, path.size() / MAX_PATH_OUTLINE);
        for (int i = 0; i < path.size(); i += stride) {
            this.outline(pose, path.get(i), -16711936);
        }
        if (!path.isEmpty()) {
            this.outline(pose, path.get(path.size() - 1), -16711936);
        }
        if (citizenPlaced || fallback != null) {
            // 脚下那一格的黄色描边：坐标是连续的，位置得向下取整才对应到方块。
            this.outline(pose, BlockPos.containing(cx, cy, cz), -256);
            this.renderCitizenModel(cx, cy, cz);
        }
    }

    private BlockPos fallbackCitizenPos() {
        BlockPos control = findControlBoxPos();
        if (control != null) {
            return control;
        }
        return this.core.isEmpty() ? null : this.core.get(0).immutable();
    }

    private static final int MAX_PATH_OUTLINE = 128;

    /**
     * 工业控制盒上方一格。
     *
     * <p>结果缓存到结构变化为止：旧版在 {@code renderBlockOverLay} 里<b>每帧</b>遍历整个结构的
     * 方块并逐个做注册表查表，几万方块的结构每帧要跑几万次字符串拼接。
     */
    private BlockPos findControlBoxPos() {
        if (controlBoxResolved) {
            return controlBoxPos;
        }
        controlBoxResolved = true;
        controlBoxPos = null;
        for (BlockPos pos : this.core) {
            String id = BuiltInRegistries.BLOCK.getKey(this.previewWorld.getBlockState(pos).getBlock()).toString();
            if (id.contains("control_box")) {
                controlBoxPos = pos.above().immutable();
                break;
            }
        }
        return controlBoxPos;
    }

    private BlockPos controlBoxPos;
    private boolean controlBoxResolved;

    /** 结构对应的寻路网格与寻路实现；结构为空或过大时为 {@code null}，调用方回退直线。 */
    private WorldNavGrid navGrid;
    private SimNavigation navigation = SimNavigation.direct();
    /** 结构方块快照，用来查「这个位置是什么容器、能装多少」。 */
    private StructureBlockGetter structureBlocks;
    /**
     * 结构方块的可变副本（坐标 → 方块状态）。
     *
     * <p>寻路网格是从这张表建的，而「示意环境」（运行期在建筑外补的草地和树）也要进这张表，
     * 否则市民走不出建筑、也走不到树下。
     */

    /** 网格是否建成了。没建成时界面上要说明「当前是直线兜底」，不能假装是真寻路。 */
    public boolean hasNavGrid() {
        return navGrid != null;
    }

    public int navCellCount() {
        return navGrid == null ? 0 : navGrid.cellCount();
    }

    /** 当前可用的寻路实现（NSUK 规则寻路，或直线兜底）。 */
    public SimNavigation navigation() {
        return navigation;
    }

    // ── 容器开合 ───────────────────────────────────────────────────────

    private Set<BlockPos> openContainers = Set.of();
    private boolean lidAnimation = true;
    /** 仿真改动过的方块 → 改动前的原状态，用来还原（见 applyBlockEdits / clearBlockEdits）。 */
    private final Map<BlockPos, BlockState> blockEditBackup = new LinkedHashMap<>();

    /**
     * 结构本体占的坐标（渲染白名单的一半，见 {@link #installBlockFilter()}）。
     *
     * <p>用 Set 而不是拿 {@link #core} 那个 List 去 contains：过滤器会被频繁调用，
     * List 的 contains 是 O(n)，几千格的结构下这是每帧几十万次比较。
     */
    private final Set<BlockPos> coreSet = new HashSet<>();

    /**
     * **一整轮仿真可能改动的**坐标 —— 这些方块一律不进结构缓存，由动态层每帧现画。
     *
     * <p>为什么要提前把它们剔出缓存：LDLib2 的 FBO 缓存是**整层 all-or-nothing** 的。
     * 反编译 {@code WorldSceneRenderer.startAsyncCompile} 可见：所有条目一次编进每层的
     * {@code vertexBuffers}，而 {@code renderCacheBuffer} 在缓存状态为 {@code NEED} 时
     * **直接 return —— 那一帧什么都不画**。所以「改一个方块 ⇒ 整栋建筑重编 ⇒ 空一帧」，
     * 就是那个闪屏。把「会变的」和「不变的」分开之后：静态部分编一次再也不动，
     * 动态部分每帧现画，于是**播放全程一次重编都不需要**。
     *
     * <p>集合由调用方给（见 {@link #setDynamicBlocks}）：仿真跑完之后
     * {@code Result.blockEdits()} 里就是全部坐标，连成簇收获破坏的那一堆也在里面。
     */
    private final Set<BlockPos> dynamicBlocks = new LinkedHashSet<>();

    /**
     * 改动落在 {@link #dynamicBlocks} **之外**的位置。
     *
     * <p>正常不该有（调用方给的就是仿真会动的全部坐标）。真有就是有人没打招呼改了世界，
     * 那时只能退回「重编缓存」这条慢路 —— 用 {@link #splitMissed} 触发兜底，
     * 而不是让画面悄悄跟世界对不上。这些位置还要进白名单，
     * 否则写进去也读不出来（假世界对白名单外直接返回空气）。
     */
    private final Set<BlockPos> extraRendered = new LinkedHashSet<>();

    /** 静态条目用的坐标列表（渲染器以集合**实例**为键，得留着同一个实例才能删）。 */
    private List<BlockPos> staticRendered = List.of();
    /** 动态条目用的坐标列表。 */
    private List<BlockPos> dynamicRendered = List.of();

    /**
     * 动态层渲染器：**同一个假世界，但不走缓存**。
     *
     * <p>LDLib 的缓存开关是渲染器级别的（{@code useCache}），一个渲染器里没法一半缓存一半不缓存，
     * 所以动态层必须另起一个 {@code useCache=false} 的渲染器，在结构画完之后补画。
     * 它每帧从世界现取方块状态，所以放 / 破坏立刻可见，而且完全不需要重编。
     */
    private WorldSceneRenderer overlayRenderer;

    /** 方块改动动过世界、但还没重建渲染缓存。 */
    private boolean blockEditsDirty;
    /** 有改动落在动态集合之外，必须重编缓存兜底（见 {@link #extraRendered}）。 */
    private boolean splitMissed;

    /**
     * 给假世界装「结构本体 + 动态集合 + 兜底位置」这套白名单。
     *
     * <p>{@code TrackedDummyWorld.getBlockState} 对不在白名单里的位置直接返回空气，
     * 所以放下的方块必须能被过滤器认出来。这三个集合都是本类自己的字段，
     * 增删即时生效 —— 不需要通知渲染器，更不需要 {@code setRenderedCore}
     * （那是全量移除 + 全量加回，整个结构会消失一瞬）。
     */
    private void installBlockFilter() {
        TrackedDummyWorld world = this.getDummyWorld();
        if (world == null) {
            return;
        }
        world.setBlockFilter(pos -> this.coreSet.contains(pos)
                || this.dynamicBlocks.contains(pos)
                || this.extraRendered.contains(pos));
    }

    /**
     * 走缓存时把它调成「同步分片编译」。
     *
     * <p>反编译 {@code WorldSceneRenderer.renderCacheBuffer} 可见：默认的异步编译在启动线程后
     * **直接 return —— 那一帧什么都不画**；同步编译不 return，每帧只编一小块，而且
     * {@code renderUploadedBuffers} 只看 {@code vertexBuffersUsingMark}、不看编译状态，
     * 旧缓冲会一直画到这一层被新数据整体替换（{@code finalizeSyncLayer} 里一次 upload）——
     * 所以**看不见空帧**。
     *
     * <p>LDLib 的默认预算是 200 步 / 2ms：五千格的结构按层算几万步，重编一次要好几秒。
     * 这里放宽到 20000 步 / 4ms；真正的上限仍是每帧的时间预算，所以不会一帧卡死。
     */
    private void configureCacheCompile(boolean cacheBuffers) {
        WorldSceneRenderer renderer = this.getRenderer();
        if (!cacheBuffers || renderer == null) {
            return;
        }
        renderer.setSyncCompileMaxBlocksPerFrame(20000).setSyncCompileTimeBudgetNanos(4_000_000L);
        renderer.syncCompile(true);
    }

    /**
     * 应用一批「仿真对世界的方块改动」（放方块 / 等待方块）。
     *
     * <p>改动前的原状态按坐标记下来，{@link #clearBlockEdits()} 时还原 ——
     * 重播 / 重置 / 改输入重算都要还原，否则上一轮放下的方块会留在世界里。
     *
     * <p>认不出的方块 id 直接跳过：一个拼错的名字不该让整轮仿真崩掉。
     * 注意这些改动**不参与寻路**（不重建网格），所以放置的方块不改变后续走位。
     */
    public void applyBlockEdits(List<SimulationRunner.BlockEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (SimulationRunner.BlockEdit edit : edits) {
            ResourceLocation id = ResourceLocation.tryParse(edit.blockId());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                continue;
            }
            BlockPos pos = new BlockPos(edit.x(), edit.y(), edit.z());
            BlockState previous = this.previewWorld.getBlockState(pos);
            // replace=false 时只在原位是空气才放，与 NSUK 的 replace 语义一致。
            if (!edit.replace() && !previous.isAir()) {
                continue;
            }
            BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
            if (previous.equals(state)) {
                continue;
            }
            this.blockEditBackup.putIfAbsent(pos, previous);
            this.previewWorld.setBlockAndUpdate(pos, state);
            // 动过的方块在白名单里必须能被读出来。
            // 落在动态集合里的（正常情况下全部）由动态层每帧现画，这里什么都不用做；
            // 落在外面的说明调用方漏报了，记一笔让 flushBlockEdits 走重编兜底。
            if (!this.dynamicBlocks.contains(pos)) {
                this.splitMissed = true;
                if (!this.coreSet.contains(pos)) {
                    this.extraRendered.add(pos);
                }
            }
            changed = true;
        }
        if (changed) {
            this.blockEditsDirty = true;
        }
    }

    /**
     * 还原所有被仿真改动过的方块。
     *
     * <p>这里**不直接重建缓存**：调用方通常是「先全部还原、再按当前进度依次应用」，
     * 每次都重建的话一次换步要重编两遍。改完统一调 {@link #flushBlockEdits()}。
     */
    public void clearBlockEdits() {
        if (this.blockEditBackup.isEmpty() && this.extraRendered.isEmpty()) {
            return;
        }
        // 还原也可能落在动态集合之外（兜底位置），那同样得重编才对得上。
        for (BlockPos pos : this.blockEditBackup.keySet()) {
            if (!this.dynamicBlocks.contains(pos)) {
                this.splitMissed = true;
            }
        }
        this.blockEditBackup.forEach(this.previewWorld::setBlockAndUpdate);
        this.blockEditBackup.clear();
        // 兜底位置也撤出白名单：还原之后它们要么本来就是空气、要么本来就在结构里。
        this.extraRendered.clear();
        this.blockEditsDirty = true;
    }

    /**
     * 方块改动应用完之后调一次：世界真的变了才重建缓存，没变就是空操作。
     *
     * <p>仿真放下的那些方块**单独登记**（每格一个条目），不去动结构本体那个整体条目 ——
     * 一旦调 {@code setRenderedCore}，LDLib 会先把结构全部移除再全量加回，
     * 整个结构会消失一瞬（放一个方块闪一次屏）。
     */
    public void flushBlockEdits() {
        if (!this.blockEditsDirty) {
            return;
        }
        this.blockEditsDirty = false;
        if (this.splitMissed) {
            // 有改动落在动态集合之外（正常不该发生：调用方给的应是全部会动的坐标）。
            // 把那几格并进动态集合再切一次分层 —— 两种情况合成一条路，不维护第二套记账。
            this.dynamicBlocks.addAll(this.extraRendered);
            this.applyRenderedSplit();
        } else if (this.overlayRenderer == null) {
            // 没有分层（纯预览场景）又走缓存：只能重编。同步编译下旧缓冲一直画着，不会空帧。
            WorldSceneRenderer renderer = this.getRenderer();
            if (renderer != null && renderer.isUseCache()) {
                needCompileCache();
            }
        }
        // 分层生效且没有漏报时，这里什么都没做 —— 动态层每帧现画，改完立刻就是对的。
        this.pickDirty = true;
    }

    /**
     * 声明「这一轮仿真会动哪些方块」，用来把结构缓存切成静态 + 动态两层
     * （理由见 {@link #dynamicBlocks}）。
     *
     * <p>调用时机很关键：必须**在应用任何方块改动之前**。仿真要先跑完才知道坐标，
     * 所以这时结构已经显示出来了 —— 切分层会让缓存重编一次，但同步编译不会空帧，
     * 这一次重编是看不见的；换来的是整轮播放期间**零重编**。
     *
     * @param positions 会动的坐标；空集合表示「没有动态部分」，退回纯缓存
     */
    public void setDynamicBlocks(Collection<BlockPos> positions) {
        Set<BlockPos> next = positions == null || positions.isEmpty() ? Set.of() : Set.copyOf(positions);
        if (next.equals(this.dynamicBlocks)) {
            return;
        }
        this.dynamicBlocks.clear();
        this.dynamicBlocks.addAll(next);
        this.applyRenderedSplit();
    }

    /**
     * 按 {@link #dynamicBlocks} 把渲染器的条目切成「静态 + 动态」。
     *
     * <p>{@code Scene.core}（LDLib 自己的那个集合）**不动**：它管方块白名单和悬停高亮，
     * 被切出来的方块仍然要能悬停、能高亮，只是不再参与缓存烘焙。
     *
     * <p>条目用 {@code removeAllRenderedBlocks()} 全部重来，是因为 {@code renderedBlocksMap}
     * 是 {@code IdentityHashMap}（按**引用**找键）—— 拿不到旧列表实例就删不掉，
     * 而不清干净就会有两份条目同时画同一批方块。
     */
    private void applyRenderedSplit() {
        WorldSceneRenderer renderer = this.getRenderer();
        if (renderer == null) {
            return;
        }
        List<BlockPos> statics = new ArrayList<>(this.core.size());
        for (BlockPos pos : this.core) {
            if (!this.dynamicBlocks.contains(pos)) {
                statics.add(pos);
            }
        }
        this.staticRendered = List.copyOf(statics);
        this.dynamicRendered = List.copyOf(this.dynamicBlocks);
        renderer.removeAllRenderedBlocks();
        renderer.addRenderedBlocks(this.staticRendered, null);
        WorldSceneRenderer overlay = this.dynamicRendered.isEmpty() ? this.overlayRenderer : this.ensureOverlayRenderer();
        if (overlay != null) {
            overlay.removeAllRenderedBlocks();
            if (!this.dynamicRendered.isEmpty()) {
                overlay.addRenderedBlocks(this.dynamicRendered, null);
            }
        }
        // 「换分层」也算改了要画的东西，静态那一层得重编一次；同步编译下看不见空帧。
        renderer.needCompileCache();
        this.splitMissed = false;
        this.extraRendered.clear();
        this.installBlockFilter();
        this.pickDirty = true;
    }

    /**
     * 动态层渲染器（同一个假世界、不缓存），第一次用到时才建。
     *
     * <p>挂在 {@code afterWorldRender}：那是结构画完、深度与相机都还在的时候，
     * 补画的动态方块因此能和结构正确互相遮挡。
     *
     * <p><b>这个钩子 LDLib 自己也在用</b>（反编译 {@code Scene.createScene}：
     * {@code renderer.setAfterWorldRender(this::renderBlockOverLay)} —— 悬停高亮、路线、
     * 手工画的市民全在那里面）。所以**不能直接覆盖**，必须自己接过来再把原来那条跑一遍，
     * 而且顺序是「先补画方块，再跑原钩子」：方块要参与深度遮挡，高亮和市民得画在方块之上。
     */
    private WorldSceneRenderer ensureOverlayRenderer() {
        if (this.overlayRenderer == null) {
            this.overlayRenderer = new DynamicOverlayRenderer(this.getDummyWorld());
            WorldSceneRenderer renderer = this.getRenderer();
            if (renderer != null) {
                renderer.setAfterWorldRender(main -> {
                    this.drawDynamicLayer();
                    this.renderBlockOverLay(main);
                });
            }
        }
        return this.overlayRenderer;
    }

    /** 画动态层。没有动态方块时条目为空，等于空操作。 */
    private void drawDynamicLayer() {
        if (this.overlayRenderer instanceof DynamicOverlayRenderer overlay) {
            overlay.drawOverlay();
        }
    }

    /**
     * 只为了把 {@code protected drawWorld()} 暴露出来。
     *
     * <p>为什么借 LDLib 的路而不是自己画：它的 {@code renderBlocks} / {@code renderSingleBlock}
     * 都是 private，子类复用不了；自己重写就得连带处理模型、流体、TESR、染色和光照，
     * 那是一整片容易出错的地方。这里只借用它「不缓存」的那条现成路径。
     *
     * <p>代价：不缓存的分支里 {@code drawWorld()} 会顺手把假世界的实体也画一遍，
     * 于是预览里的动物这一帧被画两次。它们都是不透明模型，第二遍画出的是同样的像素，
     * 看起来没有区别 —— 用一点 GPU 换「不自己写渲染」，值。
     */
    private static final class DynamicOverlayRenderer extends WorldSceneRenderer {
        DynamicOverlayRenderer(Level world) {
            super(world);
        }

        void drawOverlay() {
            this.drawWorld();
        }
    }

    /**
     * 把若干容器的盖子开 / 关。
     *
     * <p>NSUK 在执行 {@code inspect_container} / {@code insert_item} /
     * {@code deposit_carried_items} 时会 {@code setContainersOpen(level, containers, true)}，
     * 做完再关回去。预览世界里箱子是真的方块，所以这件事也能如实做出来：
     * 改 {@code open} 方块状态再让场景重建一次缓存。
     *
     * <p>只在「开合集合真的变了」时才动，而且失败一次就永久关掉这个效果 ——
     * 重建预览缓存不便宜，不能每帧都触发。
     */
    public void setOpenContainers(Collection<BlockPos> positions) {
        if (!lidAnimation) {
            return;
        }
        Set<BlockPos> next = positions == null || positions.isEmpty() ? Set.of() : Set.copyOf(positions);
        if (next.equals(openContainers)) {
            return;
        }
        try {
            boolean changed = false;
            for (BlockPos pos : openContainers) {
                if (!next.contains(pos)) {
                    changed |= setLid(pos, false);
                }
            }
            for (BlockPos pos : next) {
                if (!openContainers.contains(pos)) {
                    changed |= setLid(pos, true);
                }
            }
            openContainers = next;
            if (changed) {
                needCompileCache();
            }
        } catch (RuntimeException exception) {
            lidAnimation = false;
            openContainers = Set.of();
            SimuPackMod.LOGGER.warn("预览里切换容器开合失败，已关闭该效果", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean setLid(BlockPos pos, boolean open) {
        BlockState state = previewWorld.getBlockState(pos);
        Property<Boolean> property = null;
        for (Property<?> candidate : state.getProperties()) {
            if ("open".equals(candidate.getName()) && candidate.getValueClass() == Boolean.class) {
                property = (Property<Boolean>) candidate;
                break;
            }
        }
        if (property == null || state.getValue(property) == open) {
            return false;
        }
        previewWorld.setBlockAndUpdate(pos, state.setValue(property, open));
        return true;
    }

    /** Minimal villager silhouette rendered in the preview world without spawning a server entity. */
    private void renderCitizenModel(double x, double y, double z) {
        // 只烘焙一次：失败过一次就不再重试，直接走替身。
        if (citizenModel == null && !citizenFallbackOnly) {
            try {
                citizenModel = new PlayerModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), true);
                // ★ 必须显式设成「成年」。
                //
                // EntityModel 里 `public boolean young = true;`（默认就是 true），而
                // AgeableListModel.renderToBuffer 会按它分支：young 为真时把头缩到
                // 1.5/2 = 0.75 倍并下移 1 格、把身体缩到 0.5 倍并下移 1.5 格 —— 那就是
                // 「市民怎么只有一格高、头还特别大」的真正原因。
                // 游戏里这个字段由 setupAnim/prepareMobModel 按 entity.isBaby() 写，
                // 我们手工画模型、不调 setupAnim，所以必须自己设。
                citizenModel.young = false;
            } catch (RuntimeException exception) {
                citizenModel = null;
                citizenFallbackOnly = true;
            }
        }
        if (citizenModel == null) {
            renderCitizenFallback(x, y, z);
            return;
        }
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();

        // 变换顺序必须与 LivingEntityRenderer.render + PlayerRenderer.scale 完全一致：
        //   translate(实体位置) → mulPose(180° - bodyYaw) → scale(entityScale)
        //   → scale(-1, -1, 1) → scale(0.9375) → translate(0, -1.501, 0) → 画模型
        //
        // 关键：模型空间**已经是方块单位**了 —— ModelPart.translateAndRotate 把部件偏移除了 16，
        // ModelPart.Cube.compile 也把顶点除了 16（腿在 y=12、脚底在 24，即 0.75~1.5 格）。
        // 旧版在这里又乘了一次 1/16，市民实际只有 0.11 格高（肉眼一个像素点），
        // 于是只剩它兜底画的那个半透明蓝盒子能被看见 —— 那就是「一片蓝色方块」的来源。
        //
        // 0.9375 则是对的，来自 PlayerRenderer.scale：模型本身 2.0 格高（8+12+12 像素），
        // 乘 0.9375 之后才是玩家的 1.8 格。少了它市民会有两格高。
        PoseStack pose = new PoseStack();
        pose.translate(x, y, z);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - citizenYaw));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.scale(0.9375F, 0.9375F, 0.9375F);
        pose.translate(0.0F, -1.501F, 0.0F);

        applyCitizenAnimation();

        // 玩家皮肤要走 entityTranslucent：帽子层与半透明像素靠它才正确，
        // entityCutoutNoCull（EntityModel.renderType 的默认值）会把它们画成硬边。
        RenderType skin = RenderType.entityTranslucent(this.citizenSkin);
        citizenModel.renderToBuffer(pose, buffers.getBuffer(skin), 15728880, OverlayTexture.NO_OVERLAY, -1);
        buffers.endBatch(skin);

        if (!citizenHeldItem.isEmpty()) {
            renderCitizenHeldItem(pose, buffers);
        }
    }

    /**
     * 把「走到哪、在做什么」翻译成骨架角度。
     *
     * <p>三件事分开叠加，互不干扰：
     * <ol>
     *   <li><b>走路</b>：摆腿相位直接用累计里程（{@code citizenWalkPhase}），幅度由 {@code walkAmount}
     *       平滑启停。相位跟着距离走，所以走得快摆得快、停下来腿就停；</li>
     *   <li><b>动作</b>：{@link SimGesture} 覆盖手臂。挥手 / 作业是往复摆动，开箱是伸手压身，
     *       数值对齐 {@code HumanoidModel.setupAnim} 里「攻击」用的 -1.5 附近（负值 = 抬臂向前）；</li>
     *   <li><b>注视</b>：{@code look_at} 时按目标点算头部相对身体的 yaw / pitch，
     *       公式与 {@code LivingEntityRenderer} 传 {@code netHeadYaw / headPitch} 的方式一致。</li>
     * </ol>
     */
    private void applyCitizenAnimation() {
        boolean walking = citizenGesture == SimGesture.WALK && !citizenMotion.vertical();
        walkAmount += ((walking ? 1.0F : 0.0F) - walkAmount) * 0.30F;

        float phase = citizenWalkPhase;
        float amplitude = walkAmount;
        float rightArm = (float) Math.sin(phase) * 0.35F * amplitude;
        float leftArm = (float) -Math.sin(phase) * 0.35F * amplitude;
        float rightLeg = (float) -Math.sin(phase) * 0.55F * amplitude;
        float leftLeg = (float) Math.sin(phase) * 0.55F * amplitude;
        float bodyPitch = 0.0F;
        float headPitchExtra = 0.0F;
        float ticks = citizenGestureTicks;

        switch (citizenGesture) {
            case SWING -> rightArm = -0.9F + (float) Math.sin(ticks * 0.7F) * 0.9F;
            case BREAK -> rightArm = -1.1F + (float) Math.sin(ticks * 0.8F) * 1.0F;
            case CRAFT -> rightArm = -0.75F + (float) Math.sin(ticks * 0.35F) * 0.6F;
            case MACHINE -> rightArm = -0.65F + (float) Math.sin(ticks * 0.25F) * 0.35F;
            case PLACE -> rightArm = -0.85F + (float) Math.sin(ticks * 0.5F) * 0.5F;
            case ENTITY -> rightArm = -0.6F + (float) Math.sin(ticks * 0.45F) * 0.4F;
            case OPEN_CONTAINER, INSERT, DEPOSIT -> {
                // 伸手到箱子上方并微微前倾 —— 就是 NSUK 那两步「打开容器」的样子。
                rightArm = -1.15F;
                bodyPitch = 0.22F;
                headPitchExtra = 0.18F;
            }
            default -> {
            }
        }

        citizenModel.rightArm.xRot = rightArm;
        citizenModel.leftArm.xRot = leftArm;
        citizenModel.rightArm.yRot = 0.0F;
        citizenModel.leftArm.yRot = 0.0F;
        citizenModel.rightLeg.xRot = rightLeg;
        citizenModel.leftLeg.xRot = leftLeg;
        citizenModel.body.xRot = bodyPitch;
        citizenModel.head.xRot = headPitchExtra;
        citizenModel.head.yRot = 0.0F;

        if (citizenGesture == SimGesture.LOOK && hasLookTarget) {
            double dx = lookX - citizenX;
            double dy = lookY - (citizenY + 1.62D);
            double dz = lookZ - citizenZ;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float absoluteYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float netHeadYaw = Mth.wrapDegrees(absoluteYaw - citizenYaw);
            citizenModel.head.yRot = (float) Math.toRadians(Mth.clamp(netHeadYaw, -75.0F, 75.0F));
            citizenModel.head.xRot = (float) Math.toRadians(Mth.clamp(
                    (float) -Math.toDegrees(Math.atan2(dy, horizontal)), -60.0F, 60.0F));
        }
        // 帽子层在模型树里是 head 的兄弟节点，不跟着 head 转，必须手动同步（与 HumanoidModel.setupAnim 一致）。
        citizenModel.hat.copyFrom(citizenModel.head);
        // 皮肤的第二层（袖子 / 裤腿 / 外套）在模型树里也是独立部件，同样要跟着动，
        // 否则手臂挥起来的时候袖子会留在原地 —— PlayerModel.setupAnim 做的就是这件事。
        citizenModel.leftSleeve.copyFrom(citizenModel.leftArm);
        citizenModel.rightSleeve.copyFrom(citizenModel.rightArm);
        citizenModel.leftPants.copyFrom(citizenModel.leftLeg);
        citizenModel.rightPants.copyFrom(citizenModel.rightLeg);
        citizenModel.jacket.copyFrom(citizenModel.body);
    }

    /**
     * 手上的物品。
     *
     * <p>挂在右臂部件自己的坐标系里（{@code ModelPart.translateAndRotate} 就是干这个的），
     * 而不是像旧版那样在已经翻转过的根空间里硬写一个 (-0.32, -0.72, -0.10) 偏移 ——
     * 那个偏移只在旧版的错误缩放下凑巧看着像手，缩放一改就飞到身体外面。
     *
     * <p>姿态序列逐句照抄原版 {@code ItemInHandLayer.renderArmWithItem}：
     * {@code translateToHand → X 轴 -90° → Y 轴 180° → 平移 (1/16, 0.125, -0.625)}。
     * 少了 Y 轴那 180° 会让物品正反面镜像，位移写错则会让它飘在手外面
     * （之前就是这个症状：手上那块泥土变成一片歪斜的蓝色板子）。
     */
    private void renderCitizenHeldItem(PoseStack pose, MultiBufferSource.BufferSource buffers) {
        pose.pushPose();
        // HumanoidModel.translateToHand(RIGHT) 就是这一句。
        citizenModel.rightArm.translateAndRotate(pose);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(1.0F / 16.0F, 0.125F, -0.625F);
        Minecraft.getInstance().getItemRenderer().renderStatic(citizenHeldItem,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                15728880, OverlayTexture.NO_OVERLAY, pose, buffers, Minecraft.getInstance().level, 0);
        pose.popPose();
    }

    /** 只在玩家模型烘焙失败时用的替身：实心，不会盖住任何东西（旧版是半透明大盒子，正好盖住模型）。 */
    private void renderCitizenFallback(double x, double y, double z) {
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        RenderType fill = RenderType.debugFilledBox();
        PoseStack pose = new PoseStack();
        LevelRenderer.renderVoxelShape(pose, buffers.getBuffer(fill),
                Shapes.box(x - 0.25D, y, z - 0.25D, x + 0.25D, y + 1.8D, z + 0.25D),
                0.0D, 0.0D, 0.0D, 0.15F, 0.75F, 0.95F, 1.0F, true);
        buffers.endBatch(fill);
    }

    private void renderUnitBoxes() {
        if (this.unitBoxes.isEmpty()) {
            return;
        }
        PoseStack pose = new PoseStack();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        for (UnitBoxVisual box : this.unitBoxes) {
            float maxX = (float)box.maxX() + 1.0f;
            float maxY = (float)box.maxY() + 1.0f;
            float maxZ = (float)box.maxZ() + 1.0f;
            if (box.filled() && box.alpha() > 0.0f) {
                RenderType fillType = RenderType.debugFilledBox();
                LevelRenderer.renderVoxelShape((PoseStack)pose, (VertexConsumer)bufferSource.getBuffer(fillType), (VoxelShape)Shapes.box((double)box.minX(), (double)box.minY(), (double)box.minZ(), (double)maxX, (double)maxY, (double)maxZ), (double)0.0, (double)0.0, (double)0.0, (float)box.red(), (float)box.green(), (float)box.blue(), (float)box.alpha(), (boolean)true);
                bufferSource.endBatch(fillType);
            }
            RenderType lineType = RenderType.lines();
            LevelRenderer.renderLineBox((PoseStack)pose, (VertexConsumer)bufferSource.getBuffer(lineType), (double)box.minX(), (double)box.minY(), (double)box.minZ(), (double)maxX, (double)maxY, (double)maxZ, (float)Math.min(1.0f, box.red() + 0.25f), (float)Math.min(1.0f, box.green() + 0.25f), (float)Math.min(1.0f, box.blue() + 0.25f), (float)Math.min(1.0f, box.alpha() + 0.55f));
            bufferSource.endBatch(lineType);
        }
    }

    private void outline(PoseStack pose, BlockPos pos, int color) {
        for (Direction face : Direction.values()) {
            this.drawFacingBorder(pose, new BlockPosFace(pos, face), color);
        }
    }

    private BlockHitResult pickAt(float mouseX, float mouseY) {
        if (!this.pickFrameValid || this.core.isEmpty()) {
            return null;
        }
        Vector2f mouse = ScenePickMath.framebufferMouse(mouseX, mouseY, this.guiWidth, this.guiHeight, this.framebufferWidth, this.framebufferHeight);
        if (mouse == null) {
            return null;
        }
        ScenePickMath.Ray ray = ScenePickMath.ray((Matrix4fc)this.inverseViewProjection, this.pickViewport, mouse.x, mouse.y);
        if (ray == null) {
            return null;
        }
        Vec3 from = new Vec3((double)ray.near().x, (double)ray.near().y, (double)ray.near().z);
        Vec3 to = new Vec3((double)ray.far().x, (double)ray.far().y, (double)ray.far().z);
        // 顺手判一下射线有没有打中市民：他不是方块，方块拾取是找不到他的。
        // 用一个略大的包围盒（0.35 半径、1.9 高），点起来别太费劲。
        this.hoveredCitizen = this.citizenPlaced
                && new AABB(this.citizenX - 0.35D, this.citizenY, this.citizenZ - 0.35D,
                        this.citizenX + 0.35D, this.citizenY + 1.9D, this.citizenZ + 0.35D)
                        .clip(from, to).isPresent();
        BlockHitResult closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos pos : this.core) {
            double distance;
            BlockHitResult hit;
            BlockState state = this.previewWorld.getBlockState(pos);
            VoxelShape shape = state.getShape((BlockGetter)this.previewWorld, pos);
            if (shape.isEmpty() && !state.getFluidState().isEmpty()) {
                shape = state.getFluidState().getShape((BlockGetter)this.previewWorld, pos);
            }
            if ((hit = shape.clip(from, to, pos)) == null || !((distance = hit.getLocation().distanceToSqr(from)) < closestDistance)) continue;
            closestDistance = distance;
            closest = hit;
        }
        return closest;
    }

    protected void onMouseDown(UIEvent event) {
        if (this.overlayTarget(event)) {
            return;
        }
        if (event.button == 2) {
            this.middleDragging = true;
            this.middleLastX = event.x;
            this.middleLastY = event.y;
            event.stopPropagation();
            return;
        }
        if (event.button == 0) {
            this.leftDragging = false;
            this.leftLastX = event.x;
            this.leftLastY = event.y;
            this.leftPressed = true;
            if (this.pickHandler != null) {
                BlockHitResult hit = this.pickAt(event.x, event.y);
                this.pressedBlock = hit == null ? null : hit.getBlockPos().immutable();
                this.pressYaw = this.getRotationYaw();
                this.pressPitch = this.getRotationPitch();
            }
            this.pressX = event.x;
            this.pressY = event.y;
        }
    }

    protected void onMouseUp(UIEvent event) {
        if (this.overlayTarget(event)) {
            return;
        }
        if (event.button == 2) {
            super.onMouseUp(event);
            return;
        }
        if (event.button == 0) {
            this.leftDragging = false;
        }
        if (event.button != 0 || !this.leftPressed) {
            return;
        }
        this.leftPressed = false;
        if (this.pickHandler == null) {
            return;
        }
        float dx = event.x - this.pressX;
        float dy = event.y - this.pressY;
        if (this.rotatedSincePress() || dx * dx + dy * dy > 16.0f) {
            this.pressedBlock = null;
            return;
        }
        BlockHitResult hit = this.pickAt(event.x, event.y);
        if (hit != null && hit.getBlockPos().equals((Object)this.pressedBlock)) {
            this.pickHandler.accept(hit.getBlockPos().immutable(), hit.getDirection());
        } else if (hit == null && this.pickMissHandler != null) {
            this.pickMissHandler.run();
        }
        this.pressedBlock = null;
    }

    private boolean rotatedSincePress() {
        return Math.abs(StructurePreviewScene.normalizeDegrees(this.getRotationYaw() - this.pressYaw)) > 2.0f || Math.abs(StructurePreviewScene.normalizeDegrees(this.getRotationPitch() - this.pressPitch)) > 2.0f;
    }

    private void panCamera(float screenDx, float screenDy) {
        float unitsPerPixelY;
        float unitsPerPixelX;
        WorldSceneRenderer renderer = this.getRenderer();
        if (renderer == null || this.getContentHeight() <= 0.0f) {
            return;
        }
        Vector3f lookAt = new Vector3f((Vector3fc)renderer.getLookAt());
        float distance = renderer.getEyePos().distance((Vector3fc)renderer.getLookAt());
        float contentWidth = this.getContentWidth();
        float contentHeight = this.getContentHeight();
        if (contentWidth <= 0.0f || contentHeight <= 0.0f) {
            return;
        }
        if (this.isUseOrtho()) {
            float halfWidth = this.getRange() * this.getZoom();
            unitsPerPixelX = Math.max(1.0E-4f, 2.0f * halfWidth / contentWidth);
            unitsPerPixelY = Math.max(1.0E-4f, 2.0f * halfWidth / contentHeight);
        } else {
            float unitsPerPixel;
            unitsPerPixelX = unitsPerPixel = Math.max(0.01f, distance / contentHeight) * 1.5f;
            unitsPerPixelY = unitsPerPixel;
        }
        double yaw = Math.toRadians(this.getRotationYaw());
        double pitch = Math.toRadians(this.getRotationPitch());
        double cameraX = Math.cos(yaw);
        double cameraY = Math.tan(pitch);
        double cameraZ = Math.sin(yaw);
        double length = Math.sqrt(cameraX * cameraX + cameraY * cameraY + cameraZ * cameraZ);
        double forwardX = -cameraX / length;
        double forwardY = -cameraY / length;
        double forwardZ = -cameraZ / length;
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
        double upX = -(rightZ /= rightLength) * forwardY;
        double upY = rightZ * forwardX - (rightX /= rightLength) * forwardZ;
        double upZ = rightX * forwardY;
        lookAt.add((float)((double)(-screenDx * unitsPerPixelX) * rightX + (double)(screenDy * unitsPerPixelY) * upX), (float)((double)(screenDy * unitsPerPixelY) * upY), (float)((double)(-screenDx * unitsPerPixelX) * rightZ + (double)(screenDy * unitsPerPixelY) * upZ));
        this.setCenter(lookAt);
        this.setZoom(this.getZoom());
    }

    private static float normalizeDegrees(float degrees) {
        float value = degrees % 360.0f;
        if (value > 180.0f) {
            value -= 360.0f;
        } else if (value < -180.0f) {
            value += 360.0f;
        }
        return value;
    }

    /**
     * 这个位置上的容器有**几格**；读不出来返回 -1。
     *
     * <p>**不硬编码方块表**：直接问方块自己。LDLib 的假世界在 {@code setBlock} 时
     * 会按 {@code BlockInfo.hasBlockEntity()} 建好方块实体并 {@code postEntity} 挂上
     * （见 {@code TrackedDummyWorld}），所以能力系统在这里是可用的：
     *
     * <ol>
     *   <li>先问 NeoForge 的物品能力（{@code Capabilities.ItemHandler.BLOCK}）——
     *       箱子 27、大箱子 54、漏斗 5、发射器 9、熔炉 3，**以及模组容器**，全是方块自己报的；</li>
     *   <li>不行再看方块实体本身是不是 {@link Container}；</li>
     *   <li>都认不出就返回 -1，调用方据此退回「不限量」，而不是猜一个数字。</li>
     * </ol>
     *
     * <p>（原来是按方块类型写死的一张表 —— 模组容器一律读不出来，而且原版加一个容器方块
     * 就得回来改这张表。）
     */
    public int containerCapacityAt(SimPos pos) {
        if (this.structureBlocks == null || pos == null) {
            return -1;
        }
        BlockPos blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
        // ① 方块自己的能力（NeoForge 注册的 handler：原版 + 模组都在这里）。
        net.neoforged.neoforge.items.IItemHandler handler = this.previewWorld
                .getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                        blockPos, null);
        if (handler != null) {
            return handler.getSlots();
        }
        // ② 退一步：方块实体自己就是容器（有些原版容器没有注册能力，但有 Container 实现）。
        net.minecraft.world.level.block.entity.BlockEntity entity = this.previewWorld.getBlockEntity(blockPos);
        if (entity instanceof net.minecraft.world.Container container) {
            return container.getContainerSize();
        }
        return -1;
    }

    /**
     * 这个容器**每一格自己的上限**（{@code IItemHandler.getSlotLimit(slot)}）；读不出来返回空数组。
     *
     * <p>和格数分开问是有原因的：一格能装多少 = {@code min(该格上限, 物品自己的堆叠上限)}。
     * 模组容器经常限制特定格位（机器输入格只收 1 个、流体罐格只收 16 个……），
     * 只看「物品上限 64 × 格数」会把容量算大。
     */
    public int[] containerSlotLimitsAt(SimPos pos) {
        if (this.structureBlocks == null || pos == null) {
            return new int[0];
        }
        BlockPos blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
        net.neoforged.neoforge.items.IItemHandler handler = this.previewWorld
                .getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                        blockPos, null);
        if (handler == null) {
            return new int[0];
        }
        int[] limits = new int[handler.getSlots()];
        for (int slot = 0; slot < limits.length; slot++) {
            limits[slot] = handler.getSlotLimit(slot);
        }
        return limits;
    }

    /** 换市民皮肤。每轮仿真开始时由面板调用（见 {@link SimulationSkins}）。 */
    public void setCitizenSkin(ResourceLocation skin) {
        this.citizenSkin = skin == null ? SimulationSkins.defaultSkin() : skin;
    }

    public ResourceLocation citizenSkin() {
        return this.citizenSkin;
    }

    /** 这一帧的拾取射线有没有打中市民（用来给他加 tooltip）。 */
    public boolean isCitizenHovered() {
        return this.hoveredCitizen;
    }

    // ── 仿真动物 ───────────────────────────────────────────────────────

    /**
     * 预览世界里的仿真动物，顺序即生成顺序。
     *
     * <p>不需要 {@code needCompileCache()}：实体每帧都画，不走渲染缓存，
     * 所以加 / 删动物不会让结构缓存作废（这跟 {@link #applyBlockEdits} 那类方块改动是两回事）。
     */
    private final List<Entity> previewAnimals = new ArrayList<>();

    /**
     * 在预览世界里某格的地面上放一只动物。
     *
     * <p>「放」这件事只做一半：把实体塞进假世界就够了。LDLib2 的
     * {@code WorldSceneRenderer.drawWorld()} 只要发现世界是 {@link TrackedDummyWorld}，
     * 就会遍历 {@link TrackedDummyWorld#getAllRenderedEntities()} 并用
     * {@code EntityRenderDispatcher.render(...)} 逐个画出来。自己再写一遍 dispatcher
     * 只会和 {@link #renderCitizenModel} 手工画的市民打架。
     *
     * @return 真正进世界的实体；实体类型不可用（{@code create} 返回 null，例如特性开关关着）时返回 null
     */
    public <T extends Entity> T spawnPreviewAnimal(EntityType<T> type, SimPos pos, float yawDegrees) {
        if (type == null || pos == null) {
            return null;
        }
        // 1.21.1 只有 create(Level) 这一个重载（没有 EntitySpawnReason 那一支），
        // 而且它内部要过 enabledFeatures() 的开关，关着就返回 null —— 必须判空。
        T entity = type.create(this.previewWorld);
        if (entity == null) {
            return null;
        }
        // 实体的 y 是**脚底**：站在 pos 这格顶上就是 y + 1；x/z 取格心。
        entity.setPos(pos.x() + 0.5D, pos.y() + 1.0D, pos.z() + 0.5D);
        applyPreviewFacing(entity, yawDegrees);
        // tickCount 保持 0 不是忘了推：LDLib2 的 renderEntities 只在 tickCount == 0 时
        // 把 xOld/yOld/zOld 对齐到当前位置。本项目 setTickWorld(false)，没人去 tick 它，
        // 于是它永远是 0 —— 这正是我们要的，否则 Mth.lerp 会把动物从 (0,0,0) 插值过来。
        entity.tickCount = 0;
        this.previewWorld.addEntity(entity);
        this.previewAnimals.add(entity);
        return entity;
    }

    /** 当前在预览世界里的仿真动物（只读视图，外部改不了这份列表）。 */
    public List<Entity> previewAnimals() {
        return Collections.unmodifiableList(this.previewAnimals);
    }

    /**
     * 预览世界本身。
     *
     * <p>给「按坐标读这个坐标上现在是什么方块」用 —— 破坏方块的掉落要靠它
     * （方块状态只有这里知道，而且播放过程中会变）。只读用途，别拿去改。
     */
    public TrackedDummyWorld previewWorld() {
        return this.previewWorld;
    }

    /**
     * 当前结构里所有方块的坐标。
     *
     * <p>给「按标签在工作区里找方块」用：工作区可能有几百格半径（几十万格），
     * 逐格问方块状态是不行的，而这张表里只有真正有方块的位置。
     *
     * <p>注意：仿真自己**放下的**方块不会出现在这里（它改的是世界、没进这张表），
     * 但那不影响成簇收获 —— 要收的是结构里本来就有的树。
     */
    public Iterable<BlockPos> structurePositions() {
        return this.core;
    }
    /** 结构包围盒最小角（取整）。没载入结构时返回 null。 */
    public BlockPos structureMinimum() {
        return this.core.isEmpty() ? null : new BlockPos(
                Mth.floor(this.boundsMinimum.x), Mth.floor(this.boundsMinimum.y), Mth.floor(this.boundsMinimum.z));
    }

    /** 结构包围盒最大角（取整）。没载入结构时返回 null。 */
    public BlockPos structureMaximum() {
        return this.core.isEmpty() ? null : new BlockPos(
                Mth.ceil(this.boundsMaximum.x) - 1, Mth.ceil(this.boundsMaximum.y) - 1,
                Mth.ceil(this.boundsMaximum.z) - 1);
    }

    /**
     * 这一格能不能站一只动物（{@code feet} 是**脚底所在的那一格**）。
     *
     * <p>判据对齐 NSUK 的 {@code IndustrialEntitySpawnService.isClear}：
     * **这一格是空气 + 上面一格也是空气（实体要占两格）+ 下面那格有碰撞**。
     * 优先问导航网格（它已经建模了「有地板 + 身体放得下」），网格里没这一格时才直接看方块。
     *
     * <p>为什么要检查：刷怪是围着市民铺一圈，那一圈可能落在栅栏里 / 半空 / 墙里 ——
     * 不查就会「动物浮着」或「动物卡在栅栏里」。
     */
    public boolean canStandAt(SimPos feet) {
        if (feet == null) {
            return false;
        }
        if (this.navGrid != null) {
            NavCell cell = this.navGrid.cell(feet.x(), feet.y(), feet.z());
            if (cell != null) {
                return cell.floorSupported() && this.navGrid.bodyPassage(feet.x(), feet.y(), feet.z());
            }
        }
        BlockPos pos = new BlockPos(feet.x(), feet.y(), feet.z());
        // 判据照 NSUK 的 isClear（IndustrialEntitySpawnService:90-92）：
        // 这一格空气 + 上面一格也空气 + **下面那格的碰撞形状非空**。
        // 最后一条不能写成「下面不是空气」—— 雪层、地毯、告示牌这类薄方块会被误判成能站。
        return this.previewWorld.getBlockState(pos).isAir()
                && this.previewWorld.getBlockState(pos.above()).isAir()
                && !this.previewWorld.getBlockState(pos.below())
                        .getCollisionShape(this.previewWorld, pos.below()).isEmpty();
    }

    /**
     * 清掉所有仿真动物。
     *
     * <p>列表与世界两边都要动：只清列表的话实体还留在假世界里，LDLib2 下一帧照样画；
     * 只从世界移除的话列表里会攒下一堆已经 setRemoved 的僵尸，后面的 contains 判断对不上号。
     */
    public void clearPreviewAnimals() {
        for (Entity entity : this.previewAnimals) {
            // 走世界的正规移除路径（内部 setRemoved → 区块回调解除），
            // 这样 getAllRenderedEntities() 立刻就不再有它。
            this.previewWorld.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        this.previewAnimals.clear();
    }

    /**
     * 应用一步对动物的改动：剪毛 → 羊变秃、屠宰 → 从世界里消失、繁殖 → 旁边多一只幼崽。
     *
     * <p>「找一只符合条件的动物」就是按顺序取第一只还没被处理过的：
     * 剪过毛的羊自己带着 {@code isSheared()} 状态，被宰的已经从列表里移除了，
     * 所以这里**不需要额外记账**。调用方每次都是从「刚刷出来的初始状态」重放过来的
     * （剪毛/屠宰不可逆，增量撤不掉 —— 和方块改动同一个理由）。
     */
    public void applyEntityEdits(List<SimulationRunner.EntityEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return;
        }
        for (SimulationRunner.EntityEdit edit : edits) {
            switch (edit.kind()) {
                case SHEAR -> {
                    Entity target = findAnimal(edit.entityType());
                    // 剪毛是 Sheep 自己的状态：设了之后模型真的会变秃。
                    if (target instanceof Sheep sheep && !sheep.isSheared()) {
                        sheep.setSheared(true);
                    }
                }
                case KILL -> {
                    Entity target = findAnimal(edit.entityType());
                    if (target != null) {
                        this.previewWorld.removeEntity(target.getId(), Entity.RemovalReason.KILLED);
                        this.previewAnimals.remove(target);
                    }
                }
                case SPAWN -> {
                    Entity parent = findAnimal(edit.entityType());
                    spawnBabyNear(parent == null ? null : parent.blockPosition(), edit.entityType());
                }
            }
        }
    }

    /** 按实体类型找一只还没被处理过的动物（剪过毛的羊会被跳过）。 */
    private Entity findAnimal(String entityType) {
        String wanted = normalizeEntityType(entityType);
        for (Entity entity : this.previewAnimals) {
            if (entity.isRemoved()) {
                continue;
            }
            if (!entityId(entity).equals(wanted)) {
                continue;
            }
            if (entity instanceof Sheep sheep && sheep.isSheared()) {
                continue;
            }
            return entity;
        }
        return null;
    }

    /** 在父代旁边找一格空的放幼崽；找不到空格就挨着放（宁可重叠也不要凭空消失）。 */
    /**
     * 在父代旁边找一格能站的地方放幼崽。
     *
     * <p>{@code origin} 是**父代脚底所在的那一格**，而 {@link #spawnPreviewAnimalById}
     * 收的是「脚下那一格」（它内部 +1 才是脚底）—— 直接拿 origin 传进去，幼崽会整整浮高一格。
     * 所以统一用 {@code origin.below()} 当参照，找同层的相邻格。
     */
    private void spawnBabyNear(BlockPos origin, String entityType) {
        if (origin == null) {
            return;
        }
        // 父代脚下那一格：幼崽应当和父代站在同一层。
        BlockPos support = origin.below();
        BlockPos spot = null;
        for (int[] offset : NEIGHBOUR_OFFSETS) {
            BlockPos candidate = support.offset(offset[0], 0, offset[1]);
            BlockPos feet = candidate.above();
            if (this.canStandAt(new SimPos(feet.getX(), feet.getY(), feet.getZ()))) {
                spot = candidate;
                break;
            }
        }
        if (spot == null) {
            // 旁边挤不下就贴着父代站 —— 宁可两个重叠，也不要浮在半空。
            spot = support;
        }
        Entity baby = spawnPreviewAnimalById(entityType, spot);
        // 幼崽：原版靠 AgeableMob 的年龄缩放模型，setBaby 之后体型和判定都会变小。
        if (baby instanceof AgeableMob mob) {
            mob.setBaby(true);
        }
    }

    /** 相邻 8 格，先看四个正的，再看四个斜的 —— 幼崽优先放在正方向。 */
    private static final int[][] NEIGHBOUR_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static String normalizeEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return "";
        }
        String value = entityType.trim();
        return value.indexOf(':') < 0 ? "minecraft:" + value : value;
    }

    /** 按 id 放一只动物（{@link #spawnPreviewAnimal} 需要一个 {@code EntityType} 泛型，这里是字符串入口）。 */
    private Entity spawnPreviewAnimalById(String entityType, BlockPos pos) {
        ResourceLocation id = ResourceLocation.tryParse(normalizeEntityType(entityType));
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        return spawnPreviewAnimal(type, new SimPos(pos.getX(), pos.getY(), pos.getZ()), 0.0F);
    }

    /**
     * 把实体摆成「朝 yawDegrees」。
     *
     * <p>只设 {@code yRot} 是不够的：{@code LivingEntityRenderer.render} 取的是
     * {@code yBodyRotO/yBodyRot}（身体）与 {@code yHeadRotO/yHeadRot}（头），
     * 三个角度里那个 {@code yRot} 它一眼都不看（那是 dispatcher 传进来的参数，只喂给少数图层）。
     * 而且它用的是 {@code rotLerp(partialTick, ...O, ...)}：起始值不一起设的话第一帧会从 0 插过来 ——
     * 动物先原地拧半圈脖子，再转回来。
     */
    private static void applyPreviewFacing(Entity entity, float yawDegrees) {
        entity.setYRot(yawDegrees);
        if (entity instanceof LivingEntity living) {
            living.setYBodyRot(yawDegrees);
            living.setYHeadRot(yawDegrees);
            living.yBodyRotO = yawDegrees;
            living.yHeadRotO = yawDegrees;
        }
        // 位置与 yRot 的「上一帧值」一起对齐，配合 tickCount == 0 让第一帧不插值。
        // setYBodyRot / setYHeadRot 不动那两个 *O 字段，所以上面必须手写。
        entity.setOldPosAndRot();
    }

    public void showStructure(CompoundTag structureTag) {
        float previousZoom = this.getZoom();
        float previousYaw = this.getRotationYaw();
        float previousPitch = this.getRotationPitch();
        boolean preserveCamera = !this.core.isEmpty() && Float.isFinite(previousZoom) && previousZoom > 0.0f;
        this.autoFitPending = !preserveCamera;
        StructurePreviewLoader.Snapshot snapshot = StructurePreviewLoader.snapshot(structureTag);
        List<StructurePreviewLoader.Placed> placed = snapshot.blocks();
        this.previewWorld.clear();
        // clear() 连实体一起清了（它内部对每个实体 setRemoved），列表必须同步清掉，
        // 否则里面留着一堆已经不在世界里的僵尸。
        // 这里再走一次 removeEntity 是安全的：实体已经从 id 表里摘掉了，
        // removeEntity 查不到就直接返回（不会重复触发区块回调，也不会刷警告）。
        clearPreviewAnimals();
        ArrayList<BlockPos> positions = new ArrayList<BlockPos>(placed.size());
        Map<BlockPos, BlockState> structureBlocks = new HashMap<>(Math.max(16, placed.size() * 2));
        int lowestY = Integer.MAX_VALUE;
        int highestY = Integer.MIN_VALUE;
        for (StructurePreviewLoader.Placed block : placed) {
            this.previewWorld.setBlockAndUpdate(block.pos(), block.state());
            positions.add(block.pos());
            structureBlocks.put(block.pos(), block.state());
            lowestY = Math.min(lowestY, block.pos().getY());
            highestY = Math.max(highestY, block.pos().getY());
        }
        this.core = List.copyOf(positions);
        // 白名单的一半：结构本体。过滤器读它（O(1)），见 installBlockFilter。
        this.coreSet.clear();
        this.coreSet.addAll(positions);
        // 换结构：上一栋建筑的分层与兜底位置全部作废。
        // 静态条目此刻就是整个结构（下面的 setRenderedCore 会登记它），动态层清空。
        this.dynamicBlocks.clear();
        this.extraRendered.clear();
        this.splitMissed = false;
        this.staticRendered = this.core;
        this.dynamicRendered = List.of();
        if (this.overlayRenderer != null) {
            this.overlayRenderer.removeAllRenderedBlocks();
        }
        this.blockCount = this.core.size();
        // 结构换了：控制盒位置、市民状态、容器开合全都要重来。
        this.controlBoxResolved = false;
        this.simulationCitizen = null;
        this.simulationPath = List.of();
        this.openContainers = Set.of();
        clearSimulationCitizen();
        // 寻路网格按结构建一次，之后所有步骤复用；建不成就回退直线。
        //
        // 几何来源必须是**这张结构快照表**，不能用 previewWorld：Scene 给假世界装了
        // `setBlockFilter(pos -> Scene.core.contains(pos))`，而 Scene.core 要到下面
        // setRenderedCore(...) 才会被填上 —— 在那之前读世界，除了上一栋建筑的方块全是空气，
        // 网格会静默建成 0~1 格（踩过一次）。
        StructureBlockGetter navigationBlocks = structureBlocks.isEmpty()
                ? null
                : new StructureBlockGetter(structureBlocks, lowestY, highestY);
        this.structureBlocks = navigationBlocks;
        this.navGrid = navigationBlocks == null ? null : WorldNavGrid.build(navigationBlocks, this.core);
        this.navigation = this.navGrid == null ? SimNavigation.direct() : new GridPathfinder(this.navGrid);
        this.boundsMinimum.set((Vector3fc)snapshot.minimum());
        this.boundsMaximum.set((Vector3fc)snapshot.maximum());
        if (!preserveCamera) {
            this.setCameraYawAndPitch(-135.0f, 25.0f);
        }
        this.pickFrameValid = false;
        this.pickDirty = true;
        this.lastPickMouseX = Float.NaN;
        this.lastPickMouseY = Float.NaN;
        this.hoveredHit = null;
        this.pressedBlock = null;
        this.leftPressed = false;
        this.highlighted = Set.of();
        this.unitBoxes = List.of();
        this.setCenter(new Vector3f((this.boundsMinimum.x + this.boundsMaximum.x) * 0.5f, (this.boundsMinimum.y + this.boundsMaximum.y) * 0.5f, (this.boundsMinimum.z + this.boundsMaximum.z) * 0.5f));
        this.setRenderedCore(this.core.isEmpty() ? List.of(BlockPos.ZERO) : this.core, null, false);
        // setRenderedCore 会动 Scene 自己的白名单集合；万一它顺手重置了过滤器，这里补装一次。
        this.installBlockFilter();
        if (preserveCamera) {
            this.setCameraYawAndPitch(previousYaw, previousPitch);
            this.setZoom(previousZoom);
        } else {
            this.applyFitZoom();
            this.autoFitPending = false;
        }
    }

    protected void onLayoutChanged() {
        super.onLayoutChanged();
        this.pickFrameValid = false;
        this.pickDirty = true;
        if (this.autoFitPending) {
            this.applyFitZoom();
            this.autoFitPending = false;
        }
    }

    private void applyFitZoom() {
        if (this.core.isEmpty()) {
            this.setZoom(3.5f);
            return;
        }
        if (!this.isUseOrtho()) {
            this.setZoom(StructurePreviewScene.fitPerspectiveDistance(this.boundsMinimum, this.boundsMaximum));
            return;
        }
        float width = this.getContentWidth();
        float height = this.getPaddingHeight();
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }
        this.setZoom(StructurePreviewScene.fitZoom(this.boundsMinimum, this.boundsMaximum, width, height));
    }

    private static float fitZoom(Vector3f minimum, Vector3f maximum, float viewportWidth, float viewportHeight) {
        float minX = minimum.x;
        float minY = minimum.y;
        float minZ = minimum.z;
        float maxX = maximum.x;
        float maxY = maximum.y;
        float maxZ = maximum.z;
        double yaw = Math.toRadians(-135.0);
        double pitch = Math.toRadians(25.0);
        double cameraX = Math.cos(yaw);
        double cameraY = Math.tan(pitch);
        double cameraZ = Math.sin(yaw);
        double cameraLength = Math.sqrt(cameraX * cameraX + cameraY * cameraY + cameraZ * cameraZ);
        double forwardX = -cameraX / cameraLength;
        double forwardY = -cameraY / cameraLength;
        double forwardZ = -cameraZ / cameraLength;
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
        double upX = -(rightZ /= rightLength) * forwardY;
        double upY = rightZ * forwardX - (rightX /= rightLength) * forwardZ;
        double upZ = rightX * forwardY;
        double minRight = Double.MAX_VALUE;
        double maxRight = -1.7976931348623157E308;
        double minUp = Double.MAX_VALUE;
        double maxUp = -1.7976931348623157E308;
        for (double x : new double[]{minX, maxX}) {
            for (double y : new double[]{minY, maxY}) {
                for (double z : new double[]{minZ, maxZ}) {
                    double projectedRight = x * rightX + y * 0.0 + z * rightZ;
                    double projectedUp = x * upX + y * upY + z * upZ;
                    minRight = Math.min(minRight, projectedRight);
                    maxRight = Math.max(maxRight, projectedRight);
                    minUp = Math.min(minUp, projectedUp);
                    maxUp = Math.max(maxUp, projectedUp);
                }
            }
        }
        float aspectRatio = viewportWidth / Math.max(1.0f, viewportHeight);
        float horizontalZoom = (float)((maxRight - minRight) * 0.5);
        float verticalZoom = (float)((maxUp - minUp) * (double)aspectRatio * 0.5);
        return Math.max(0.5f, Math.max(horizontalZoom, verticalZoom) * 1.18f);
    }

    private static float fitPerspectiveDistance(Vector3f minimum, Vector3f maximum) {
        double halfX = (double)(maximum.x - minimum.x) * 0.5;
        double halfY = (double)(maximum.y - minimum.y) * 0.5;
        double halfZ = (double)(maximum.z - minimum.z) * 0.5;
        double radius = Math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ);
        return Math.max(3.5f, (float)(radius / Math.sin(Math.toRadians(35.0)) * (double)1.18f));
    }

    public int blockCount() {
        return this.blockCount;
    }
}

