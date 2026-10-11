package com.rstech.simupack.client.ui.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * 只读的结构方块表，用来给寻路当几何来源。
 *
 * <p><b>为什么不用预览场景里的那个 {@code TrackedDummyWorld}：</b>
 * LDLib2 的 {@code Scene} 在构造时会给假世界装一条过滤器 ——
 * {@code dummyWorld.setBlockFilter(pos -> this.core.contains(pos))}，
 * 而 {@code Scene.core} 是 {@code private final Set}，只有在 {@code setRenderedCore}
 * 被调用时才会被 {@code clear() + addAll()} 填上。
 * 于是在 {@code setRenderedCore} 之前读世界，**除了上一栋建筑的方块之外全是空气**：
 * 寻路网格会静默地建成 0~1 格，寻路必然失败（这个坑真的踩过一次）。
 *
 * <p>换成这张表之后，寻路的几何就等于「结构快照里有什么」，与渲染器的缓存、
 * 过滤器、以及调用顺序彻底解耦；顺带也让「打开箱盖」这类对世界的改动不会影响通行性
 * （NSUK 的快照同样是建一次就不再变）。
 */
@OnlyIn(Dist.CLIENT)
final class StructureBlockGetter implements BlockGetter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Map<BlockPos, BlockState> blocks;
    private final int minY;
    private final int maxY;

    StructureBlockGetter(Map<BlockPos, BlockState> blocks, int minY, int maxY) {
        this.blocks = blocks;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = blocks.get(pos);
        return state == null ? AIR : state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getLightEmission(BlockPos pos) {
        return 0;
    }

    @Override
    public int getMaxLightLevel() {
        return 15;
    }

    @Override
    public int getMinBuildHeight() {
        return minY;
    }

    @Override
    public int getHeight() {
        return Math.max(1, maxY - minY + 1);
    }
}
