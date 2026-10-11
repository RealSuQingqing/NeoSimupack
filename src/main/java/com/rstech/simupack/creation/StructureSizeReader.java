package com.rstech.simupack.creation;

import com.rstech.simupack.SimuPackMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * 从结构 {@code .nbt} 里读尺寸。
 *
 * <p>尺寸是<b>结构文件的事实</b>，不该由用户手填：原版结构 NBT 的 {@code size} 是 [x,y,z]。
 * 界面把它按 {@code X x Y x Z} 的固定格式只读展示；捕获/导入时写入 {@code .sk} 的
 * {@code size} 只是为了让文件自洽，展示以结构文件为准。
 *
 * <p>三维预览需要的是整份结构而不是尺寸，所以这里额外开放了
 * {@link #readRootTag}/{@link #readRootTagFromZip}：让预览复用同一套「本地文件 / 包内 zip」
 * 与「gzip / 裸存」的读取策略，而不是各写一份。
 */
public final class StructureSizeReader {
    private StructureSizeReader() {
    }

    /** 读取工程目录里的 {@code <建筑id>.nbt}；不存在或读不出返回 null。 */
    public static int[] read(Path nbtPath) {
        return extract(readRootTag(nbtPath));
    }

    /**
     * 读出结构的根 NBT；不存在或读不出返回 null。
     *
     * <p>尺寸只是「整份结构」的一个投影，预览要的是同一份结构本身。
     * 所以两者共用这一条「先按 gzip 读、失败再按裸存读」的路径——
     * 否则会出现「尺寸显示得出来、预览却是空的」这种前后不一致，
     * 而那种不一致最难排查，因为两个功能看起来在读同一个文件。
     */
    public static CompoundTag readRootTag(Path nbtPath) {
        if (nbtPath == null || !Files.isRegularFile(nbtPath)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(nbtPath, NbtAccounter.unlimitedHeap());
        } catch (Exception compressedFailure) {
            try {
                return NbtIo.read(nbtPath);
            } catch (Exception plainFailure) {
                SimuPackMod.LOGGER.warn("无法读取结构 {}", nbtPath, plainFailure);
                return null;
            }
        }
    }

    /** 读取已安装包 zip 内 {@code buildings/<分类>/<建筑id>.nbt} 的尺寸。 */
    public static int[] readFromZip(Path zipPath, String category, String buildingId) {
        return extract(readRootTagFromZip(zipPath, category, buildingId));
    }

    /** 读出包内结构的根 NBT；条目不存在或读不出返回 null。 */
    public static CompoundTag readRootTagFromZip(Path zipPath, String category, String buildingId) {
        if (zipPath == null || category == null || buildingId == null) {
            return null;
        }
        String entryName = "buildings/" + category + "/" + buildingId + ".nbt";
        try (ZipFile zip = new ZipFile(zipPath.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            byte[] bytes;
            try (var input = zip.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }
            try {
                return NbtIo.readCompressed(new ByteArrayInputStream(bytes),
                        NbtAccounter.unlimitedHeap());
            } catch (Exception compressedFailure) {
                try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    return NbtIo.read(data, NbtAccounter.unlimitedHeap());
                }
            }
        } catch (Exception exception) {
            SimuPackMod.LOGGER.warn("无法读取包内结构 {} / {}", zipPath, entryName, exception);
            return null;
        }
    }

    /** 固定格式：{@code X x Y x Z}。 */
    public static String format(int[] size) {
        return StructureDimensions.format(size);
    }

    /**
     * 从已经读出来的结构根 NBT 里取尺寸。
     *
     * <p>与 {@link #read}(Path) 的区别：那个会去读文件，这个只在内存里取。
     * 界面同一帧里既要尺寸又要整份结构（预览）时，先读一次 {@link #readRootTag}，
     * 再从这里取尺寸即可 —— 否则同一个文件会被解析两遍以上，
     * 而每遍都要重建调色板并查方块注册表，正是大型建筑预览卡顿的来源。
     */
    public static int[] sizeOf(CompoundTag tag) {
        return extract(tag);
    }

    private static int[] extract(CompoundTag tag) {
        if (tag == null || !tag.contains("size", Tag.TAG_LIST)) {
            return null;
        }
        ListTag size = tag.getList("size", Tag.TAG_INT);
        List<Integer> raw = new ArrayList<>(size.size());
        for (int index = 0; index < size.size(); index++) {
            raw.add(size.getInt(index));
        }
        // 判断逻辑在 StructureDimensions（纯 Java，可单测）；这里只负责把 NBT 摊成整数列表。
        return StructureDimensions.of(raw);
    }
}
