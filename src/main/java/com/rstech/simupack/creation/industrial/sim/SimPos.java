package com.rstech.simupack.creation.industrial.sim;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 结构局部坐标系里的整数格坐标。
 *
 * <p>仿真内核刻意不直接用 {@code net.minecraft.core.BlockPos}：数据层一旦沾上 Minecraft，
 * 就只能进游戏里才能验证，而这一块最容易算错。客户端在边界上做
 * {@code new BlockPos(pos.x(), pos.y(), pos.z())} 的转换即可。
 */
public record SimPos(int x, int y, int z) implements Comparable<SimPos> {

    public SimPos offset(int dx, int dy, int dz) {
        return new SimPos(x + dx, y + dy, z + dz);
    }

    public int manhattan(SimPos other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    /**
     * 稳定排序：先按 Y、再按 X、再按 Z。
     *
     * <p>寻路给出的一串目标点必须有序 —— 无序的话每次重算路径都会得到不同结果，
     * 表现就是「市民每帧乱跳」。
     */
    @Override
    public int compareTo(SimPos other) {
        int byY = Integer.compare(y, other.y);
        if (byY != 0) {
            return byY;
        }
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(z, other.z);
    }

    public static Set<SimPos> sortedCopy(Collection<SimPos> positions) {
        return new LinkedHashSet<>(sortedList(positions));
    }

    /** 去重并按 {@link #compareTo} 排序的有序列表。 */
    public static java.util.List<SimPos> sortedList(Collection<SimPos> positions) {
        java.util.List<SimPos> sorted = new java.util.ArrayList<>(new LinkedHashSet<>(positions));
        java.util.Collections.sort(sorted);
        return java.util.List.copyOf(sorted);
    }
}
