package com.rstech.simupack.client.ui.preview;

import com.rstech.simupack.creation.industrial.sim.SimBlockMatcher;
import com.rstech.simupack.creation.industrial.sim.SimPos;
import com.rstech.simupack.creation.industrial.sim.SimWorkArea;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 按**预览世界**回答「这个坐标上是什么方块」。
 *
 * <p>成簇收获要在工作区里找「匹配 {@code #minecraft:logs} 的连通块」，所以需要两件事：
 * 某个坐标是不是某个标签的方块，以及工作区里哪些坐标有方块。
 *
 * <p>{@link #candidates} 走的是场景手里的**结构坐标集合**，不是逐格遍历 ——
 * 工作区半径可能是几百格（几十万格），逐格问一次方块状态是不行的。
 * 拿到的坐标再做一次空气过滤（模拟自己放下的方块也在这张表里）。
 */
@OnlyIn(Dist.CLIENT)
public final class PreviewWorldBlockMatcher implements SimBlockMatcher {

    private final Supplier<Level> world;
    private final Supplier<Iterable<BlockPos>> positions;

    public PreviewWorldBlockMatcher(Supplier<Level> world, Supplier<Iterable<BlockPos>> positions) {
        this.world = world;
        this.positions = positions;
    }

    @Override
    public Boolean matches(String selector, SimPos pos) {
        Level level = this.world == null ? null : this.world.get();
        if (level == null || selector == null || selector.isBlank() || pos == null) {
            return null;
        }
        BlockState state = level.getBlockState(new BlockPos(pos.x(), pos.y(), pos.z()));
        if (selector.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(selector.substring(1));
            if (tagId == null) {
                return null;
            }
            // 标签不存在时原版语义是「永不匹配」，不是报错 —— 照原样返回 false。
            return state.is(TagKey.create(Registries.BLOCK, tagId));
        }
        ResourceLocation id = ResourceLocation.tryParse(selector);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            // 认不出的方块 id：真实实现里这一步直接算无效（INVALID_STEP），所以回 null 而不是 false。
            return null;
        }
        return state.is(BuiltInRegistries.BLOCK.get(id));
    }

    @Override
    public List<SimPos> candidates(SimWorkArea area) {
        Level level = this.world == null ? null : this.world.get();
        if (level == null || area == null || !area.known() || this.positions == null) {
            return List.of();
        }
        Iterable<BlockPos> source = this.positions.get();
        if (source == null) {
            return List.of();
        }
        List<SimPos> result = new ArrayList<>();
        for (BlockPos pos : source) {
            SimPos simPos = new SimPos(pos.getX(), pos.getY(), pos.getZ());
            if (!area.contains(simPos)) {
                continue;
            }
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            result.add(simPos);
        }
        return result;
    }
}
