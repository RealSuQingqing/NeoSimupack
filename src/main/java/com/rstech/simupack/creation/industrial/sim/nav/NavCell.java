package com.rstech.simupack.creation.industrial.sim.nav;

import com.rstech.simupack.creation.industrial.sim.SimPos;

/**
 * 寻路网格里的一格。
 *
 * <p>对应 NSUK 的 {@code PathCell}。{@code standY} 是脚下的实际站立高度（台阶、半砖、
 * 地毯都靠它区分），{@code cost} 是这一格的通行代价（普通地面 1.0、低矮空间 1.05、
 * 木门 3.2、水 5.0、可攀爬 8.0），全部照抄 NSUK 的 {@code PathSnapshotBuilder.classify}。
 */
public record NavCell(int x, int y, int z, double standY, boolean water, boolean climbable,
                      boolean woodenDoor, boolean floorSupported, double cost) {

    public long key() {
        return key(x, y, z);
    }

    public SimPos pos() {
        return new SimPos(x, y, z);
    }

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }
}
