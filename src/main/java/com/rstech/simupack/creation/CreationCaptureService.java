package com.rstech.simupack.creation;

import com.rstech.simupack.SimuPackMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 结构捕获：把世界中的选区写成为 NUSK 可读取的 {@code .nbt} 结构文件。
 *
 * <p>移植自旧版筑艺坊的 {@code BuildingInfoScreen.saveStructureNBT()}：
 * <pre>
 * minPos = min(p1, p2)
 * size   = (|dx| + 1, |dy| + 1, |dz| + 1)   // 闭区间，与旧版一致
 * template.fillFromWorld(level, minPos, size, true, null)
 * NbtIo.writeCompressed(template.save(new CompoundTag()), nbtPath)
 * </pre>
 *
 * <p>两点与旧版的差异，都是为了正确性：
 * <ol>
 *   <li>在<b>集成服务端</b>执行，而不是客户端：{@code ClientLevel} 的方块实体数据不完整，
 *       会导致带容器的建筑捕获后丢失内容；</li>
 *   <li>写入工程目录中与 {@code .sk} 同主名的 {@code <建筑 id>.nbt}
 *       （旧版固定写 {@code structure.nbt}，而 NSUK 要求与 {@code .sk} 同主名）。</li>
 * </ol>
 *
 * <p>顺带统计选区内的方块构成，作为「材料统计」页签的数据来源。
 */
@OnlyIn(Dist.CLIENT)
public final class CreationCaptureService {
    private CreationCaptureService() {
    }

    /** 捕获结果，回到客户端线程后回调。 */
    public record Result(boolean success, String message, int blockCount) {
    }

    /**
     * 捕获选区到工程目录。
     *
     * @param onDone 在客户端线程回调，用于刷新界面
     */
    public static void capture(CreationProject project, CreationBuilding building, Consumer<Result> onDone) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            // 一定要打日志：这两处提前返回以前只弹提示，出问题时日志里什么都看不到，
            // 无法判断到底卡在哪一步。
            SimuPackMod.LOGGER.warn("捕获结构被拒绝：没有集成服务端（多人游戏不支持捕获）");
            onDone.accept(new Result(false, "捕获结构只能在单人游戏中进行", 0));
            return;
        }
        if (project == null || building == null || !building.hasRegion()) {
            SimuPackMod.LOGGER.warn("捕获结构被拒绝：project={} building={} hasRegion={}",
                    project == null ? "null" : project.id(),
                    building == null ? "null" : building.id(),
                    building != null && building.hasRegion());
            onDone.accept(new Result(false, "尚未选择建筑区域", 0));
            return;
        }

        BlockPos min = building.regionMin();
        Vec3i size = building.regionSize();
        UUID playerId = minecraft.player == null ? null : minecraft.player.getUUID();
        SimuPackMod.LOGGER.info("开始捕获结构 {}：origin={} size={}x{}x{}",
                building.id(), min, size.getX(), size.getY(), size.getZ());

        server.executeIfPossible(() -> {
            ServerPlayer player = playerId == null ? null : server.getPlayerList().getPlayer(playerId);
            ServerLevel level = player != null ? player.serverLevel() : server.overworld();
            try {
                StructureTemplate template = new StructureTemplate();
                // 忽略方块传 STRUCTURE_VOID（原版约定），不要传 null：
                // 传 null 依赖底层的空值检查，属于隐患。
                template.fillFromWorld(level, min, size, true,
                        net.minecraft.world.level.block.Blocks.STRUCTURE_VOID);
                CompoundTag tag = template.save(new CompoundTag());

                Path nbtPath = building.nbtPath(CreationWorkspace.projectDirectory(project.id()));
                Files.createDirectories(nbtPath.getParent());
                NbtIo.writeCompressed(tag, nbtPath);

                var materials = countMaterials(level, min, size);
                SimuPackMod.LOGGER.info("已捕获结构 {} -> {} ({}x{}x{}, {} 种方块)",
                        building.id(), nbtPath, size.getX(), size.getY(), size.getZ(), materials.size());

                minecraft.execute(() -> {
                    building.setHasStructure(true);
                    building.setMaterials(materials);
                    onDone.accept(new Result(true, "已捕获结构文件", materials.values().stream()
                            .mapToInt(Integer::intValue).sum()));
                });
            } catch (Exception exception) {
                SimuPackMod.LOGGER.error("捕获结构失败: {}", building.id(), exception);
                minecraft.execute(() -> onDone.accept(
                        new Result(false, "捕获失败: " + exception.getMessage(), 0)));
            }
        });
    }

    /** 统计选区内的方块构成（跳过空气），用于材料统计。 */
    private static TreeMap<String, Integer> countMaterials(ServerLevel level, BlockPos min, Vec3i size) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        BlockPos max = min.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            counts.merge(id, 1, Integer::sum);
        }
        return counts;
    }
}
