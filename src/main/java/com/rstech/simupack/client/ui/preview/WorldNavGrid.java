package com.rstech.simupack.client.ui.preview;

import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.creation.industrial.sim.nav.NavCell;
import com.rstech.simupack.creation.industrial.sim.nav.NavGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 把预览结构翻译成寻路网格。
 *
 * <p><b>这是 NSUK {@code PathSnapshotBuilder} 的移植</b>：站立高度怎么取（碰撞盒在这个格子里
 * 的最高顶面，还要压得住 NPC 的 0.62×0.62 落脚面）、什么算「身体过得去」（身体切片
 * 0.19~0.81 与碰撞盒不相交）、哪些方块算危险（岩浆 / 火 / 仙人掌 / 甜浆果 / 凋灵玫瑰）、
 * 什么算可攀爬、木门给什么代价、打开的地板门怎么形成横向屏障 —— 常量与判据全部照抄。
 *
 * <p>三个实现上的差别，都是为了让它在编辑器里真的能用：
 * <ol>
 *   <li>直接问 {@link BlockGetter}，不预先把整片区域的 BlockState/VoxelShape 存两张 map
 *       （原版那样做是因为它要在服务端线程之外算）；</li>
 *   <li>加了快速排除：脚下、身体、头顶都是空气的格子必然是「可通行空腔」，
 *       一条判断就出结果。预览结构里绝大多数格子是这种，否则 40 万格的网格每次
 *       打开页签都要卡一下；</li>
 *   <li>「身体能通过」用 {@link BitSet} 按体积线性索引存，而不是 {@code Set<Long>}：
 *       开阔空气里几乎所有格子都满足这个条件，装箱的 Long 集合会吃掉几十 MB。</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class WorldNavGrid implements NavGrid {

    /** 水平留边：给「绕出建筑再走回来」留出空间。 */
    private static final int HORIZONTAL_PADDING = 12;
    /** 竖直留边。 */
    private static final int VERTICAL_PADDING = 8;
    /** 网格总体积上限。超了就先缩留边，再超就放弃（回退直线），绝不硬算到卡死。 */
    private static final int MAX_VOLUME = 1_500_000;
    /** NPC 身体半宽 / 高度 / 落脚面判据，与 NSUK 一致。 */
    private static final double NPC_HALF_WIDTH = 0.31D;
    private static final double NPC_HEIGHT = 1.8D;
    private static final double FLOOR_TOP_EPSILON = 0.0626D;
    private static final double BODY_SLICE_MIN = 0.19D;
    private static final double BODY_SLICE_MAX = 0.81D;

    private final Map<Long, NavCell> cells;
    private final BitSet bodyPassages;
    private final Map<Long, Byte> horizontalBarriers;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    private WorldNavGrid(Map<Long, NavCell> cells, BitSet bodyPassages, Map<Long, Byte> horizontalBarriers,
                         int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        this.cells = cells;
        this.bodyPassages = bodyPassages;
        this.horizontalBarriers = horizontalBarriers;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    /**
     * 用结构包围盒建网格。
     *
     * @param core 结构里实际存在的方块位置（用来算包围盒）
     * @return 建成返回网格；体积过大或结构为空时返回 {@code null}（调用方回退到直线）
     */
    public static WorldNavGrid build(BlockGetter level, Iterable<BlockPos> core) {
        if (level == null || core == null) {
            return null;
        }
        int cMinX = Integer.MAX_VALUE;
        int cMinY = Integer.MAX_VALUE;
        int cMinZ = Integer.MAX_VALUE;
        int cMaxX = Integer.MIN_VALUE;
        int cMaxY = Integer.MIN_VALUE;
        int cMaxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (BlockPos pos : core) {
            if (pos == null) {
                continue;
            }
            any = true;
            cMinX = Math.min(cMinX, pos.getX());
            cMinY = Math.min(cMinY, pos.getY());
            cMinZ = Math.min(cMinZ, pos.getZ());
            cMaxX = Math.max(cMaxX, pos.getX());
            cMaxY = Math.max(cMaxY, pos.getY());
            cMaxZ = Math.max(cMaxZ, pos.getZ());
        }
        if (!any) {
            return null;
        }

        int horizontal = HORIZONTAL_PADDING;
        int vertical = VERTICAL_PADDING;
        while (volume(cMinX, cMaxX, cMinY, cMaxY, cMinZ, cMaxZ, horizontal, vertical) > MAX_VOLUME) {
            if (horizontal > 2) {
                horizontal = 2;
            } else if (vertical > 2) {
                vertical = 2;
            } else {
                SimuPackMod.LOGGER.warn("结构太大（{}x{}x{}），放弃构建寻路网格，仿真将回退为直线路线",
                        cMaxX - cMinX + 1, cMaxY - cMinY + 1, cMaxZ - cMinZ + 1);
                return null;
            }
        }

        int minX = cMinX - horizontal;
        int minY = cMinY - vertical;
        int minZ = cMinZ - horizontal;
        int sizeX = (cMaxX + horizontal) - minX + 1;
        int sizeY = (cMaxY + vertical) - minY + 1;
        int sizeZ = (cMaxZ + horizontal) - minZ + 1;

        Map<Long, NavCell> cells = new HashMap<>();
        BitSet bodyPassages = new BitSet(sizeX * sizeY * sizeZ);
        Map<Long, Byte> barriers = new HashMap<>();
        Classifier classifier = new Classifier(level, cells, bodyPassages, minX, minY, minZ, sizeX, sizeY, sizeZ);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = minX; x < minX + sizeX; x++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                for (int y = minY; y < minY + sizeY; y++) {
                    mutable.set(x, y, z);
                    classifier.classify(mutable);
                }
            }
        }
        for (int x = minX; x < minX + sizeX; x++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                for (int y = minY - 1; y <= minY + sizeY; y++) {
                    mutable.set(x, y, z);
                    classifier.addOpenTrapdoorBarrier(mutable, barriers, minY, minY + sizeY - 1);
                }
            }
        }
        // 自检：网格几乎为空一定是「几何来源给不出方块」，而不是「这栋楼真没地方站」。
        // 静默失败过一次（Scene 的 blockFilter 让整个世界读成空气），所以这里必须吵。
        long volume = (long) sizeX * sizeY * sizeZ;
        if (cells.isEmpty()) {
            SimuPackMod.LOGGER.warn("寻路网格为空：范围 x[{}..{}] y[{}..{}] z[{}..{}]，体积 {}，"
                            + "非空气下方 {}，危险 {}，支撑不匹配 {}，身体空间不足 {}。"
                            + "这通常意味着几何来源读不到方块，仿真会退化为直线路线。",
                    minX, minX + sizeX - 1, minY, minY + sizeY - 1, minZ, minZ + sizeZ - 1, volume,
                    classifier.statSolidBelow, classifier.statDangerous,
                    classifier.statBadSupport, classifier.statNoClearance);
        } else {
            SimuPackMod.LOGGER.info("寻路网格：范围 x[{}..{}] y[{}..{}] z[{}..{}]，体积 {}，可站 {} 格，可通行腔 {} 格",
                    minX, minX + sizeX - 1, minY, minY + sizeY - 1, minZ, minZ + sizeZ - 1, volume,
                    cells.size(), bodyPassages.cardinality());
        }
        return new WorldNavGrid(cells, bodyPassages, barriers, minX, minY, minZ, sizeX, sizeY, sizeZ);
    }

    private static long volume(int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                               int horizontal, int vertical) {
        long sx = (long) (maxX - minX + 1) + horizontal * 2L;
        long sy = (long) (maxY - minY + 1) + vertical * 2L;
        long sz = (long) (maxZ - minZ + 1) + horizontal * 2L;
        return sx * sy * sz;
    }

    // ── NavGrid ───────────────────────────────────────────────────────

    @Override
    public NavCell cell(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return null;
        }
        return cells.get(NavCell.key(x, y, z));
    }

    @Override
    public boolean bodyPassage(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return false;
        }
        return bodyPassages.get(index(x, y, z));
    }

    @Override
    public boolean blocksHorizontalBoundary(int fromX, int fromY, int fromZ, int toX, int toZ) {
        int dx = Integer.compare(toX - fromX, 0);
        int dz = Integer.compare(toZ - fromZ, 0);
        if (Math.abs(toX - fromX) + Math.abs(toZ - fromZ) != 1) {
            return false;
        }
        byte forward = mask(dx, dz);
        byte backward = mask(-dx, -dz);
        Byte from = horizontalBarriers.get(NavCell.key(fromX, fromY, fromZ));
        Byte to = horizontalBarriers.get(NavCell.key(toX, fromY, toZ));
        return from != null && (from & forward) != 0 || to != null && (to & backward) != 0;
    }

    /** 网格里能站人的格子数，用于界面提示与自检。 */
    public int cellCount() {
        return cells.size();
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= minX && x < minX + sizeX
                && y >= minY && y < minY + sizeY
                && z >= minZ && z < minZ + sizeZ;
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    private static byte mask(int dx, int dz) {
        if (dx == 0 && dz < 0) {
            return 1;
        }
        if (dx == 0 && dz > 0) {
            return 2;
        }
        if (dx < 0 && dz == 0) {
            return 4;
        }
        if (dx > 0 && dz == 0) {
            return 8;
        }
        return 0;
    }

    // ── 分类 ───────────────────────────────────────────────────────────

    private static final class Classifier {
        private final BlockGetter level;
        private final Map<Long, NavCell> cells;
        private final BitSet bodyPassages;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        /** 自检计数：网格为空时用来判断是哪一道判定把整片都挡掉了。 */
        private int statSolidBelow;
        private int statDangerous;
        private int statBadSupport;
        private int statNoClearance;

        private Classifier(BlockGetter level, Map<Long, NavCell> cells, BitSet bodyPassages,
                           int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.level = level;
            this.cells = cells;
            this.bodyPassages = bodyPassages;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        private void classify(BlockPos.MutableBlockPos pos) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            BlockState foot = level.getBlockState(pos);
            BlockState head = level.getBlockState(pos.above());
            BlockState below = level.getBlockState(pos.below());

            // 快速排除 + 快速通过：脚下、身体、头顶都是空气时，
            // 「能站」必然为假（没有落脚点），而「身体能通过」必然为真
            // （NSUK 的 hasBodyPassage 在身体盒 y-1..y+1 全空时就是这个结果）。
            if (foot.isAir() && head.isAir() && below.isAir()) {
                markBodyPassage(x, y, z);
                return;
            }
            if (!below.isAir()) {
                statSolidBelow++;
            }
            if (dangerous(foot) || dangerous(head) || dangerous(below)) {
                statDangerous++;
                return;
            }

            boolean water = foot.getFluidState().is(FluidTags.WATER) || head.getFluidState().is(FluidTags.WATER);
            boolean climbable = isClimbable(foot) || isClimbable(head);

            if (footPassable(pos, foot) && headPassable(pos.above(), head) && hasNpcClearance(pos, y)) {
                markBodyPassage(x, y, z);
            }

            if (water) {
                if (!footPassable(pos, foot) || !headPassable(pos.above(), head)) {
                    return;
                }
                cells.put(NavCell.key(x, y, z), new NavCell(x, y, z, y, true, climbable, false, false, 5.0D));
                return;
            }
            if (climbable && footPassable(pos, foot) && headPassable(pos.above(), head)) {
                boolean supported = isGridFloorSupport(y, supportTop(pos.below(), below));
                cells.put(NavCell.key(x, y, z), new NavCell(x, y, z, y, false, true, false, supported, 8.0D));
                return;
            }
            if (isClosedWoodenLowerDoor(foot) && isMatchingWoodenDoorHead(head)) {
                double doorStandY = supportTop(pos.below(), below);
                if (isGridFloorSupport(y, doorStandY) && hasNpcClearance(pos, doorStandY)) {
                    cells.put(NavCell.key(x, y, z),
                            new NavCell(x, y, z, doorStandY, false, false, true, true, 3.2D));
                    return;
                }
            }
            if (!footPassable(pos, foot) || !headPassable(pos.above(), head)) {
                double low = lowStandY(pos, foot);
                if (!Double.isNaN(low) && headPassable(pos.above(), head, low - y) && hasNpcClearance(pos, low)) {
                    cells.put(NavCell.key(x, y, z),
                            new NavCell(x, y, z, low, false, false, false, true, 1.05D));
                }
                return;
            }
            double standY = supportTop(pos.below(), below);
            if (!isGridFloorSupport(y, standY)) {
                statBadSupport++;
                return;
            }
            if (!hasNpcClearance(pos, standY)) {
                statNoClearance++;
                return;
            }
            cells.put(NavCell.key(x, y, z), new NavCell(x, y, z, standY, false, false, false, true, 1.0D));
        }

        private void markBodyPassage(int x, int y, int z) {
            if (x < minX || x >= minX + sizeX || y < minY || y >= minY + sizeY || z < minZ || z >= minZ + sizeZ) {
                return;
            }
            bodyPassages.set(((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX));
        }

        /** 打开的地板门会在它朝向的那一侧形成一道横向屏障（NSUK 的 addOpenTrapdoorBarriers）。 */
        private void addOpenTrapdoorBarrier(BlockPos pos, Map<Long, Byte> barriers, int minY, int maxY) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof TrapDoorBlock)
                    || !state.hasProperty(TrapDoorBlock.OPEN)
                    || !state.getValue(TrapDoorBlock.OPEN)
                    || !state.hasProperty(TrapDoorBlock.FACING)) {
                return;
            }
            Direction edge = state.getValue(TrapDoorBlock.FACING).getOpposite();
            byte mask = mask(edge.getStepX(), edge.getStepZ());
            if (mask == 0) {
                return;
            }
            for (int dy = 0; dy >= -1; dy--) {
                int y = pos.getY() + dy;
                if (y < minY || y > maxY) {
                    continue;
                }
                barriers.merge(NavCell.key(pos.getX(), y, pos.getZ()), mask, (a, b) -> (byte) (a | b));
            }
        }

        private boolean footPassable(BlockPos pos, BlockState state) {
            return bodyPassable(pos, state, 0.0D, 1.0D);
        }

        private boolean headPassable(BlockPos pos, BlockState state) {
            return bodyPassable(pos, state, 0.0D, 0.8D);
        }

        private boolean headPassable(BlockPos pos, BlockState state, double standOffset) {
            double minY = Math.max(0.0D, standOffset - 1.0D);
            double maxY = Math.max(minY, standOffset + NPC_HEIGHT - 1.0D);
            return bodyPassable(pos, state, minY, maxY);
        }

        private boolean bodyPassable(BlockPos pos, BlockState state, double localMinY, double localMaxY) {
            Block block = state.getBlock();
            if (isDoorLike(block)) {
                return state.isPathfindable(PathComputationType.LAND)
                        || clearsNpcBodySlice(shape(pos, state), localMinY, localMaxY);
            }
            return state.isAir()
                    || state.getFluidState().is(FluidTags.WATER)
                    || isClimbable(state)
                    || clearsNpcBodySlice(shape(pos, state), localMinY, localMaxY);
        }

        private VoxelShape shape(BlockPos pos, BlockState state) {
            if (state.isAir()) {
                return Shapes.empty();
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            return shape == null ? Shapes.empty() : shape;
        }

        private double supportTop(BlockPos supportPos, BlockState state) {
            VoxelShape shape = shape(supportPos, state);
            if (shape.isEmpty()) {
                return Double.NaN;
            }
            double top = Double.NEGATIVE_INFINITY;
            for (AABB box : shape.toAabbs()) {
                if (!touchesNpcSupportFootprint(box)) {
                    continue;
                }
                top = Math.max(top, supportPos.getY() + box.maxY);
            }
            return Double.isFinite(top) ? top : Double.NaN;
        }

        private double lowStandY(BlockPos pos, BlockState state) {
            double standY = supportTop(pos, state);
            if (Double.isNaN(standY)) {
                return Double.NaN;
            }
            double offset = standY - pos.getY();
            return offset > 0.0D && offset <= 0.75D ? standY : Double.NaN;
        }

        /** NPC 的 0.62×0.62×1.8 身体盒是否与周围碰撞盒全部不相交。 */
        private boolean hasNpcClearance(BlockPos feet, double standY) {
            double centerX = feet.getX() + 0.5D;
            double centerZ = feet.getZ() + 0.5D;
            AABB npc = new AABB(centerX - NPC_HALF_WIDTH, standY, centerZ - NPC_HALF_WIDTH,
                    centerX + NPC_HALF_WIDTH, standY + NPC_HEIGHT, centerZ + NPC_HALF_WIDTH);
            int minX = (int) Math.floor(npc.minX);
            int minY = (int) Math.floor(npc.minY) - 1;
            int minZ = (int) Math.floor(npc.minZ);
            int maxX = (int) Math.floor(npc.maxX);
            int maxY = (int) Math.floor(npc.maxY);
            int maxZ = (int) Math.floor(npc.maxZ);
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        mutable.set(x, y, z);
                        BlockState state = level.getBlockState(mutable);
                        if (state.isAir()) {
                            continue;
                        }
                        VoxelShape shape = shape(mutable, state);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        for (AABB box : shape.toAabbs()) {
                            if (box.maxX + x > npc.minX && box.minX + x < npc.maxX
                                    && box.maxY + y > npc.minY && box.minY + y < npc.maxY
                                    && box.maxZ + z > npc.minZ && box.minZ + z < npc.maxZ) {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        }
    }

    private static boolean isGridFloorSupport(int y, double standY) {
        return !Double.isNaN(standY) && Math.abs(standY - y) <= FLOOR_TOP_EPSILON;
    }

    /** 身体切片（0.19~0.81 的方形柱）与碰撞盒不相交。 */
    static boolean clearsNpcBodySlice(VoxelShape shape, double localMinY, double localMaxY) {
        if (shape == null || shape.isEmpty()) {
            return true;
        }
        for (AABB box : shape.toAabbs()) {
            if (box.maxX > BODY_SLICE_MIN && box.minX < BODY_SLICE_MAX
                    && box.maxY > localMinY && box.minY < localMaxY
                    && box.maxZ > BODY_SLICE_MIN && box.minZ < BODY_SLICE_MAX) {
                return false;
            }
        }
        return true;
    }

    private static boolean touchesNpcSupportFootprint(AABB box) {
        return box.maxX > BODY_SLICE_MIN && box.minX < BODY_SLICE_MAX
                && box.maxZ > BODY_SLICE_MIN && box.minZ < BODY_SLICE_MAX;
    }

    private static boolean isDoorLike(Block block) {
        return block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock;
    }

    private static boolean isClosedWoodenLowerDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && DoorBlock.isWoodenDoor(state)
                && state.hasProperty(DoorBlock.OPEN)
                && !state.getValue(DoorBlock.OPEN)
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    private static boolean isMatchingWoodenDoorHead(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && DoorBlock.isWoodenDoor(state)
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    private static boolean isClimbable(BlockState state) {
        return state.is(Blocks.LADDER) || state.is(BlockTags.CLIMBABLE) || state.is(Blocks.SCAFFOLDING);
    }

    private static boolean dangerous(BlockState state) {
        Block block = state.getBlock();
        return state.getFluidState().is(FluidTags.LAVA)
                || block == Blocks.LAVA
                || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.WITHER_ROSE;
    }
}
