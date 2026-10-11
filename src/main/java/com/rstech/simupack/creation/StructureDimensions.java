package com.rstech.simupack.creation;

import java.util.List;

/**
 * 结构尺寸的纯逻辑：把「三段整数」校验并归一成 {@code [x,y,z]}。
 *
 * <p><b>为什么单独一个类</b>：测试源码集看不到 Minecraft 类，所以
 * {@code StructureSizeReaderTest} 里连 {@code CompoundTag} 都 import 不了。
 * 而这段逻辑恰恰是「改错了不会崩、只会静默显示错尺寸」的那种——最值得有测试的正是它，
 * 所以把读 NBT 的那一层（{@link StructureSizeReader}）与判断这一层拆开：
 * 前者碰 Minecraft，后者纯 Java、可测。
 *
 * <p>规则与 {@code .sk} 里的 {@code size} 无关：结构尺寸的<b>事实来源是结构文件本身</b>
 * （原版结构 NBT 的 {@code size} 是 [x,y,z]）。{@code .sk} 里那行只是让文件自洽，
 * 官方包里甚至有写成 {@code size:见预览} 的。
 */
public final class StructureDimensions {

    private StructureDimensions() {
    }

    /**
     * 校验并归一尺寸；不合法（不足三段 / 任一段 ≤ 0）时返回 {@code null}。
     *
     * <p>返回 null 而不是 {@code {0,0,0}}：调用方要能区分「没有结构」与「尺寸是 0」——
     * 前者显示占位提示，后者会让包围盒算出一个退化到看不见的取景。
     */
    public static int[] of(List<Integer> raw) {
        if (raw == null || raw.size() < 3) {
            return null;
        }
        int x = raw.get(0) == null ? 0 : raw.get(0);
        int y = raw.get(1) == null ? 0 : raw.get(1);
        int z = raw.get(2) == null ? 0 : raw.get(2);
        if (x <= 0 || y <= 0 || z <= 0) {
            return null;
        }
        return new int[]{x, y, z};
    }

    /** 固定展示格式：{@code X x Y x Z}，与 {@code .sk} 的写法一致。 */
    public static String format(int[] size) {
        if (size == null || size.length < 3) {
            return "";
        }
        return size[0] + " x " + size[1] + " x " + size[2];
    }
}
