package com.rstech.simupack.creation.industrial.sim;

/**
 * 路径上的一个途径点。
 *
 * <p>结构照抄 NSUK 的 {@code PathWaypoint}：既有格子坐标（用于回链到方块、做高亮），
 * 也有**精确的落点**（{@code x/y/z} 是双精度），其中 {@code y} 是 {@code PathCell.standY}
 * —— 也就是脚下的实际站立高度。台阶、半砖、地毯都是靠这个值区分「站在 y 层」还是
 * 「站在 y+0.5」的，所以不能退回整数坐标，否则市民会浮空或陷进半砖。
 */
public record SimWaypoint(SimPos cell, double x, double y, double z, SimMotion motion) {

    public SimWaypoint {
        motion = motion == null ? SimMotion.WALK : motion;
    }

    public static SimWaypoint of(SimPos cell, double standY, SimMotion motion) {
        return new SimWaypoint(cell, cell.x() + 0.5D, standY, cell.z() + 0.5D, motion);
    }

    public double distanceTo(SimWaypoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 水平距离；判断「走了几格」用这个，竖直落差单独算。 */
    public double horizontalDistanceTo(SimWaypoint other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
