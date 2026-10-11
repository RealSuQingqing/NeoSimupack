package com.rstech.simupack.client.selection;

import com.rstech.simupack.SimuPackMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * 世界内建筑选点会话。
 *
 * <p>移植自旧版筑艺坊的 {@code BuildingSelectorScreen}（1.20.1）：
 * <ul>
 *   <li><b>刻意不是 Screen</b>——用事件监听实现，玩家可以自由移动与飞行；</li>
 *   <li>进入时保存玩家状态并临时开启 {@code noPhysics / invisible / flying}（自由相机）；</li>
 *   <li>左键设点 1、右键设点 2，Enter 完成、ESC 取消；</li>
 *   <li>世界内实时可视化：青色=准心目标、黄色=点 1、红色=点 2、灰色=选区、白色=结构原点。</li>
 * </ul>
 *
 * <p>点为「闭区间」：尺寸按 {@code |p2 - p1| + 1} 计算，与旧版一致。
 */
@OnlyIn(Dist.CLIENT)
public final class BuildingSelectionSession {
    /** 准心取点扫描距离（旧版为 5 格）。 */
    private static final double SCAN_DISTANCE = 5.0D;
    /** 选区边长允许范围。旧版来自配置；当前先用固定值，后续接入配置项。 */
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 128;

    private static BuildingSelectionSession instance;

    private Screen parent;
    private BlockPos point1;
    private BlockPos point2;
    private boolean active;
    /** 完成选择后由界面读取并清除。 */
    private static boolean finished;
    /** 完成后的结果（会话实例已被清空，因此结果单独保存）。 */
    private static BlockPos resultPos1;
    private static BlockPos resultPos2;

    // 玩家状态备份（自由相机模式）
    private boolean savedNoPhysics;
    private boolean savedInvisible;
    private boolean savedFlying;
    private boolean savedMayFly;
    private double savedX;
    private double savedY;
    private double savedZ;
    private float savedYaw;
    private float savedPitch;

    private BuildingSelectionSession() {
    }

    // ── 生命周期 ───────────────────────────────────────────────────────

    /** 进入选点模式；返回界面为 {@code parent}。 */
    public static void start(Screen parent) {
        stop();
        instance = new BuildingSelectionSession();
        instance.parent = parent;
        instance.begin();
    }

    public static boolean isActive() {
        return instance != null && instance.active;
    }

    /** 上一次选点是否已完成（供界面读取结果）。 */
    public static boolean consumeFinished() {
        if (!finished) {
            return false;
        }
        finished = false;
        return true;
    }

    public static BlockPos point1() {
        return resultPos1;
    }

    public static BlockPos point2() {
        return resultPos2;
    }

    public static void stop() {
        if (instance != null) {
            instance.end();
            instance = null;
        }
    }

    private void begin() {
        active = true;
        // 关闭当前界面，让玩家可以自由移动（旧版同样是 setScreen(null)）。
        Minecraft.getInstance().setScreen(null);
        NeoForge.EVENT_BUS.register(this);
        enterFreeCamera();
    }

    private void end() {
        if (!active) {
            return;
        }
        active = false;
        exitFreeCamera();
        NeoForge.EVENT_BUS.unregister(this);
        Minecraft.getInstance().setScreen(parent);
    }

    private void enterFreeCamera() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        savedNoPhysics = player.noPhysics;
        savedInvisible = player.isInvisible();
        savedFlying = player.getAbilities().flying;
        savedMayFly = player.getAbilities().mayfly;
        savedX = player.getX();
        savedY = player.getY();
        savedZ = player.getZ();
        savedYaw = player.getYRot();
        savedPitch = player.getXRot();

        player.noPhysics = true;
        player.setInvisible(true);
        player.getAbilities().flying = true;
        player.getAbilities().mayfly = true;
        player.onUpdateAbilities();
    }

    private void exitFreeCamera() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.noPhysics = savedNoPhysics;
        player.setInvisible(savedInvisible);
        player.getAbilities().flying = savedFlying;
        player.getAbilities().mayfly = savedMayFly;
        player.onUpdateAbilities();

        player.setPos(savedX, savedY, savedZ);
        player.setYRot(savedYaw);
        player.setXRot(savedPitch);
    }

    // ── 输入 ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!active || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        BlockPos hit = lookingAt();
        if (hit == null) {
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            point1 = hit;
            message("simupack.selection.point1", hit);
            event.setCanceled(true);
        } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            point2 = hit;
            message("simupack.selection.point2", hit);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onKey(InputEvent.Key event) {
        if (!active || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_ENTER || event.getKey() == GLFW.GLFW_KEY_KP_ENTER) {
            finish();
        } else if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            stop();
        }
    }

    /** 阻止选点期间打开其它界面（旧版同样拦截）。 */
    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        if (active && event.getNewScreen() != null) {
            event.setCanceled(true);
        }
    }

    // ── 取点 ───────────────────────────────────────────────────────────

    /**
     * 准心取点：先射线 5 格找实体方块；未命中则取前方 5 格处（允许选空气位置）。
     *
     * <p>与旧版 {@code getLookingAtPos()} 行为一致。
     */
    private BlockPos lookingAt() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 end = eye.add(look.scale(SCAN_DISTANCE));

        BlockHitResult hit = minecraft.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS) {
            return hit.getBlockPos();
        }
        return BlockPos.containing(end.x, end.y, end.z);
    }

    private void finish() {
        if (point1 == null || point2 == null) {
            message("simupack.selection.need_both", null);
            return;
        }
        int sizeX = Math.abs(point2.getX() - point1.getX()) + 1;
        int sizeY = Math.abs(point2.getY() - point1.getY()) + 1;
        int sizeZ = Math.abs(point2.getZ() - point1.getZ()) + 1;

        if (sizeX < MIN_SIZE || sizeY < MIN_SIZE || sizeZ < MIN_SIZE
                || sizeX > MAX_SIZE || sizeY > MAX_SIZE || sizeZ > MAX_SIZE) {
            message("simupack.selection.bad_size", null);
            return;
        }

        SimuPackMod.LOGGER.info("建筑选区完成: p1={} p2={} size={}x{}x{}",
                point1, point2, sizeX, sizeY, sizeZ);
        resultPos1 = point1;
        resultPos2 = point2;
        finished = true;
        stop();
    }

    private void message(String translationKey, BlockPos pos) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Component text = pos == null
                ? Component.translatable(translationKey)
                : Component.translatable(translationKey, pos.getX(), pos.getY(), pos.getZ());
        player.displayClientMessage(text, true);
    }

    // ── 渲染 ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!active) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        var poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        // 青色：准心当前指向的方块（实时反馈）
        BlockPos looking = lookingAt();
        if (looking != null) {
            drawBox(poseStack, looking, looking, 0.0F, 1.0F, 1.0F, 0.30F);
        }
        // 黄色：点 1；红色：点 2
        if (point1 != null) {
            drawBox(poseStack, point1, point1, 1.0F, 1.0F, 0.0F, 0.45F);
        }
        if (point2 != null) {
            drawBox(poseStack, point2, point2, 1.0F, 0.0F, 0.0F, 0.45F);
        }
        if (point1 != null && point2 != null) {
            // 灰色：整个选区；白色：结构原点（最小角），即 .nbt 的原点
            drawBox(poseStack, point1, point2, 0.6F, 0.6F, 0.6F, 0.22F);
            BlockPos origin = new BlockPos(
                    Math.min(point1.getX(), point2.getX()),
                    Math.min(point1.getY(), point2.getY()),
                    Math.min(point1.getZ(), point2.getZ()));
            drawBox(poseStack, origin, origin, 1.0F, 1.0F, 1.0F, 0.55F);
        }
        poseStack.popPose();
    }

    /** 画一个半透明体（含边框），坐标使用世界坐标。 */
    private static void drawBox(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                BlockPos from, BlockPos to,
                                float red, float green, float blue, float alpha) {
        int minX = Math.min(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX()) + 1;
        int maxY = Math.max(from.getY(), to.getY()) + 1;
        int maxZ = Math.max(from.getZ(), to.getZ()) + 1;

        var shape = net.minecraft.world.phys.shapes.Shapes.box(
                minX, minY, minZ, maxX, maxY, maxZ);
        var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        // 半透明填充（对应旧版的染色玻璃观感）
        var fillType = net.minecraft.client.renderer.RenderType.debugFilledBox();
        net.minecraft.client.renderer.LevelRenderer.renderVoxelShape(
                poseStack, bufferSource.getBuffer(fillType), shape,
                0.0D, 0.0D, 0.0D, red, green, blue, alpha, true);
        bufferSource.endBatch(fillType);

        // 边框（旧版只有面，这里额外描边，远看更清楚）
        var lineType = net.minecraft.client.renderer.RenderType.lines();
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(
                poseStack, bufferSource.getBuffer(lineType),
                minX, minY, minZ, maxX, maxY, maxZ,
                Math.min(1.0F, red + 0.2F), Math.min(1.0F, green + 0.2F), Math.min(1.0F, blue + 0.2F),
                Math.min(1.0F, alpha + 0.45F));
        bufferSource.endBatch(lineType);
    }
}
