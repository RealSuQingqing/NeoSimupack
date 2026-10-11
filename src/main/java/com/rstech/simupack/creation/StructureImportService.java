package com.rstech.simupack.creation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把外部结构文件转换为原版结构 NBT（即 NSUK 能直接读取的 {@code .nbt}）。
 *
 * <p>支持三种来源：
 * <ul>
 *   <li><b>原版结构 NBT</b>（含机械动力蓝图：Create 用的是同一套 {@code palette + blocks} 结构）；</li>
 *   <li><b>Sponge {@code .schem}</b>：{@code Palette} + varint 打包的 {@code BlockData}；</li>
 *   <li><b>Litematica {@code .litematic}</b>：每个区域 {@code BlockStatePalette} + 位压缩 {@code BlockStates}，
 *       多区域会被合并到同一包围盒。</li>
 * </ul>
 *
 * <p>刻意不支持 MCEdit 经典 {@code .schematic}：它用 1.12 之前的数字方块 ID，
 * 需要一张巨大的 ID→方块状态映射表，且不在需求范围内。遇到时明确报错，不静默产出空结构。
 *
 * <p>统一输出为原版格式，是为了让导出的包格式一致、材料统计与预览都能读。
 * 注意 NSUK 按文件名主干配对，所以目标文件名必须是 {@code <建筑id>.nbt}（由调用方决定）。
 */
public final class StructureImportService {
    private StructureImportService() {
    }

    /** 转换结果。 */
    public record Imported(int sizeX, int sizeY, int sizeZ, int blockCount,
                           Map<String, Integer> materials, String format) {
    }

    /** 转换中出现的可预期问题（用于给用户明确提示，而不是抛异常栈）。 */
    public static class ImportException extends IOException {
        public ImportException(String message) {
            super(message);
        }
    }

    /**
     * 读取 {@code source}，转换为原版结构 NBT 写入 {@code target}。
     */
    public static Imported convert(Path source, Path target) throws IOException {
        return convert(Files.readAllBytes(source), target);
    }

    /**
     * 同上，但源结构直接在内存里给（旧版拓展包转换时结构只存在于 ZIP 条目中，
     * 没必要为了转换先落一次盘）。
     */
    public static Imported convert(byte[] source, Path target) throws IOException {
        CompoundTag root = readAny(source);
        String format = detect(root);

        Map<Long, CompoundTag> placed = switch (format) {
            case "vanilla" -> decodeVanilla(root);
            case "sponge" -> decodeSponge(root);
            case "litematic" -> decodeLitematica(root);
            default -> throw new ImportException("无法识别的结构格式：" + format);
        };
        if (placed.isEmpty()) {
            throw new ImportException("结构里没有任何方块");
        }

        CompoundTag structure = buildVanillaStructure(placed);
        Files.createDirectories(target.getParent());
        NbtIo.writeCompressed(structure, target);

        ListTag size = structure.getList("size", Tag.TAG_INT);
        Map<String, Integer> materials = countMaterials(structure);
        return new Imported(size.getInt(0), size.getInt(1), size.getInt(2),
                placed.size(), materials, format);
    }

    // ── 读取与识别 ─────────────────────────────────────────────────────

    /** 建筑包里的结构可能是 gzip 也可能裸存，两种都试（与 NSUK 的读取策略一致）。 */
    private static CompoundTag readAny(Path source) throws IOException {
        return readAny(Files.readAllBytes(source));
    }

    /** 同上，内存版本。 */
    private static CompoundTag readAny(byte[] bytes) throws IOException {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (IOException compressedFailure) {
            try (var data = new java.io.DataInputStream(new ByteArrayInputStream(bytes))) {
                return NbtIo.read(data, NbtAccounter.unlimitedHeap());
            } catch (Exception plainFailure) {
                throw new ImportException("无法解析 NBT：" + plainFailure.getMessage());
            }
        }
    }

    private static String detect(CompoundTag root) {
        if (root.contains("Regions", Tag.TAG_COMPOUND)) {
            return "litematic";
        }
        if (root.contains("Palette", Tag.TAG_COMPOUND) && root.contains("BlockData", Tag.TAG_BYTE_ARRAY)) {
            return "sponge";
        }
        if (root.contains("palette", Tag.TAG_LIST) && root.contains("blocks", Tag.TAG_LIST)) {
            return "vanilla";
        }
        if (root.contains("Schematic", Tag.TAG_COMPOUND)) {
            return "mcedit";
        }
        return "unknown";
    }

    // ── 原版结构 NBT ───────────────────────────────────────────────────

    private static Map<Long, CompoundTag> decodeVanilla(CompoundTag root) {
        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        Map<Long, CompoundTag> placed = new LinkedHashMap<>();
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag block = blocks.getCompound(index);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() < 3) {
                continue;
            }
            int state = block.getInt("state");
            if (state < 0 || state >= palette.size()) {
                continue;
            }
            CompoundTag stateTag = palette.getCompound(state);
            if (isAir(stateTag)) {
                continue;
            }
            placed.put(key(pos.getInt(0), pos.getInt(1), pos.getInt(2)), stateTag.copy());
        }
        return placed;
    }

    // ── Sponge .schem ──────────────────────────────────────────────────

    private static Map<Long, CompoundTag> decodeSponge(CompoundTag root) throws ImportException {
        int width = root.getShort("Width");
        int height = root.getShort("Height");
        int length = root.getShort("Length");
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new ImportException("schem 尺寸无效：" + width + "x" + height + "x" + length);
        }
        CompoundTag paletteTag = root.getCompound("Palette");
        int paletteMax = root.contains("PaletteMax", Tag.TAG_INT)
                ? root.getInt("PaletteMax") : paletteTag.size();

        // palette 的 value 是索引；索引 0 在 Sponge 里固定是空气。
        List<CompoundTag> palette = new ArrayList<>();
        for (int index = 0; index < Math.max(paletteMax, 1); index++) {
            palette.add(null);
        }
        for (String stateString : paletteTag.getAllKeys()) {
            int index = paletteTag.getInt(stateString);
            if (index >= 0 && index < palette.size()) {
                palette.set(index, parseBlockStateString(stateString));
            }
        }

        byte[] data = root.getByteArray("BlockData");
        // varint 打包，按 y → z → x 顺序展开。
        Map<Long, CompoundTag> placed = new LinkedHashMap<>();
        int cursor = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int[] result = readVarInt(data, cursor);
                    cursor = result[1];
                    int index = result[0];
                    if (index == 0 || index >= palette.size()) {
                        continue;
                    }
                    CompoundTag state = palette.get(index);
                    if (state == null || isAir(state)) {
                        continue;
                    }
                    placed.put(key(x, y, z), state.copy());
                }
            }
        }
        return placed;
    }

    /** 从 {@code data[offset]} 起读一个 varint，返回 {值, 新偏移}。 */
    private static int[] readVarInt(byte[] data, int offset) {
        int value = 0;
        int position = 0;
        while (offset < data.length) {
            int current = data[offset++] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position > 35) {
                break;
            }
        }
        return new int[]{value, offset};
    }

    // ── Litematica .litematic ──────────────────────────────────────────

    private static Map<Long, CompoundTag> decodeLitematica(CompoundTag root) throws ImportException {
        CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty()) {
            throw new ImportException("投影里没有任何区域");
        }

        // 先解码每个区域到「区域坐标 → 方块状态」，再按 Position 平移到统一的绝对坐标，
        // 最后减去总包围盒的最小角。这样多区域投影也能合并。
        record Placed(int x, int y, int z, CompoundTag state) {
        }
        List<Placed> all = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (String regionName : regions.getAllKeys()) {
            CompoundTag region = regions.getCompound(regionName);
            CompoundTag posTag = region.getCompound("Position");
            CompoundTag sizeTag = region.getCompound("Size");
            ListTag palette = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            long[] states = region.getLongArray("BlockStates");
            if (palette.isEmpty() || states.length == 0) {
                continue;
            }

            int sizeX = sizeTag.getInt("x");
            int sizeY = sizeTag.getInt("y");
            int sizeZ = sizeTag.getInt("z");
            int originX = posTag.getInt("x");
            int originY = posTag.getInt("y");
            int originZ = posTag.getInt("z");

            int absX = Math.abs(sizeX);
            int absY = Math.abs(sizeY);
            int absZ = Math.abs(sizeZ);
            // 尺寸为负表示区域从 Position 向负方向延伸。
            int baseX = sizeX >= 0 ? originX : originX + sizeX + 1;
            int baseY = sizeY >= 0 ? originY : originY + sizeY + 1;
            int baseZ = sizeZ >= 0 ? originZ : originZ + sizeZ + 1;

            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long mask = (1L << bits) - 1L;

            for (int y = 0; y < absY; y++) {
                for (int z = 0; z < absZ; z++) {
                    for (int x = 0; x < absX; x++) {
                        // litematic 的索引顺序：x 最快，其次 z，最后 y。
                        int index = x + z * absX + y * absX * absZ;
                        long packed = readBits(states, index, bits, mask);
                        if (packed == 0 || packed >= palette.size()) {
                            continue;
                        }
                        CompoundTag state = palette.getCompound((int) packed);
                        if (isAir(state)) {
                            continue;
                        }
                        // 负尺寸时，索引 0 对应的是最大坐标那一端。
                        int worldX = sizeX >= 0 ? baseX + x : originX - x;
                        int worldY = sizeY >= 0 ? baseY + y : originY - y;
                        int worldZ = sizeZ >= 0 ? baseZ + z : originZ - z;
                        all.add(new Placed(worldX, worldY, worldZ, state.copy()));
                        minX = Math.min(minX, worldX);
                        minY = Math.min(minY, worldY);
                        minZ = Math.min(minZ, worldZ);
                        maxX = Math.max(maxX, worldX);
                        maxY = Math.max(maxY, worldY);
                        maxZ = Math.max(maxZ, worldZ);
                    }
                }
            }
        }
        if (all.isEmpty()) {
            throw new ImportException("投影里没有任何方块");
        }

        Map<Long, CompoundTag> placed = new LinkedHashMap<>();
        for (Placed entry : all) {
            placed.put(key(entry.x() - minX, entry.y() - minY, entry.z() - minZ), entry.state());
        }
        return placed;
    }

    /** 从位压缩长整型数组里取第 {@code index} 个 {@code bits} 位的值（跨 long 边界）。 */
    private static long readBits(long[] data, int index, int bits, long mask) {
        long startOffset = (long) index * bits;
        int startArray = (int) (startOffset >> 6);
        if (startArray >= data.length) {
            return 0L;
        }
        int startBit = (int) (startOffset & 0x3F);
        long value = data[startArray] >>> startBit;
        int endArray = (int) (((long) (index + 1) * bits - 1) >> 6);
        if (endArray != startArray && endArray < data.length) {
            value |= data[endArray] << (64 - startBit);
        }
        return value & mask;
    }

    // ── 输出原版结构 NBT ───────────────────────────────────────────────

    private static CompoundTag buildVanillaStructure(Map<Long, CompoundTag> placed) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long packed : placed.keySet()) {
            int x = (int) (packed >> 42);
            int y = (int) ((packed >> 21) & 0x1FFFFF);
            int z = (int) (packed & 0x1FFFFF);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        // palette：方块状态去重后按出现顺序编号，和原版结构格式一致。
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<CompoundTag> palette = new ArrayList<>();
        ListTag blocks = new ListTag();
        for (Map.Entry<Long, CompoundTag> entry : placed.entrySet()) {
            long packed = entry.getKey();
            CompoundTag state = entry.getValue();
            String stateKey = state.toString();
            Integer stateIndex = paletteIndex.get(stateKey);
            if (stateIndex == null) {
                stateIndex = palette.size();
                paletteIndex.put(stateKey, stateIndex);
                palette.add(state);
            }
            CompoundTag block = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(net.minecraft.nbt.IntTag.valueOf((int) (packed >> 42) - minX));
            pos.add(net.minecraft.nbt.IntTag.valueOf((int) ((packed >> 21) & 0x1FFFFF) - minY));
            pos.add(net.minecraft.nbt.IntTag.valueOf((int) (packed & 0x1FFFFF) - minZ));
            block.put("pos", pos);
            block.putInt("state", stateIndex);
            blocks.add(block);
        }

        CompoundTag structure = new CompoundTag();
        ListTag size = new ListTag();
        size.add(net.minecraft.nbt.IntTag.valueOf(sizeX));
        size.add(net.minecraft.nbt.IntTag.valueOf(sizeY));
        size.add(net.minecraft.nbt.IntTag.valueOf(sizeZ));
        structure.put("size", size);
        ListTag paletteList = new ListTag();
        palette.forEach(paletteList::add);
        structure.put("palette", paletteList);
        structure.put("blocks", blocks);
        structure.put("entities", new ListTag());
        return structure;
    }

    private static Map<String, Integer> countMaterials(CompoundTag structure) {
        Map<String, Integer> counts = new TreeMap<>();
        ListTag palette = structure.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = structure.getList("blocks", Tag.TAG_COMPOUND);
        for (int index = 0; index < blocks.size(); index++) {
            int state = blocks.getCompound(index).getInt("state");
            if (state < 0 || state >= palette.size()) {
                continue;
            }
            String name = palette.getCompound(state).getString("Name");
            if (!name.isEmpty()) {
                counts.merge(name, 1, Integer::sum);
            }
        }
        return counts;
    }

    // ── 方块状态解析 ───────────────────────────────────────────────────

    /** 把 {@code minecraft:oak_log[axis=y]} 这类字符串转成原版的 {@code {Name, Properties}}。 */
    private static CompoundTag parseBlockStateString(String raw) {
        CompoundTag tag = new CompoundTag();
        String text = raw.trim();
        int bracket = text.indexOf('[');
        if (bracket >= 0 && text.endsWith("]")) {
            tag.putString("Name", text.substring(0, bracket));
            CompoundTag properties = new CompoundTag();
            String inside = text.substring(bracket + 1, text.length() - 1);
            for (String pair : inside.split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    properties.putString(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
                }
            }
            if (!properties.isEmpty()) {
                tag.put("Properties", properties);
            }
        } else {
            tag.putString("Name", text);
        }
        return tag;
    }

    private static boolean isAir(CompoundTag state) {
        String name = state.getString("Name");
        return name.isEmpty() || "minecraft:air".equals(name) || "minecraft:cave_air".equals(name)
                || "minecraft:void_air".equals(name);
    }

    /** 把 (x,y,z) 打包成一个 long，各轴 21 位（±1,048,575，足够建筑用）。 */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
