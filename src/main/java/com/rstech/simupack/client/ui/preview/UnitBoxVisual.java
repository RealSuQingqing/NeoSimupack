package com.rstech.simupack.client.ui.preview;

/**
 * 预览里要画出来的一个户型立方体。
 *
 * <p>坐标是<b>结构局部闭区间</b>，与 {@code unit:} 行完全同源：{@code min} 与 {@code max}
 * 都指向真实方块，渲染时上界会 +1 变成单位立方体的外沿
 * （与 {@code BuildingSelectionSession.drawBox} 的约定一致）。
 *
 * <p>刻意只放 int 与颜色，不放 {@code BlockPos}：调用方（选点会话、阵列面板）
 * 各有自己的数据形态，这里只负责「要画成什么样」。
 */
public record UnitBoxVisual(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                            float red, float green, float blue, float alpha, boolean filled) {

    /**
     * 用两个任意角点构造，逐轴归一化。
     *
     * <p>必须归一化：用户在画布上先点右上、再点左下是常态，
     * 拿反的两个点直接去画会得到负尺寸的盒子。
     */
    public static UnitBoxVisual boxed(int ax, int ay, int az, int bx, int by, int bz,
                                      float red, float green, float blue, float alpha, boolean filled) {
        return new UnitBoxVisual(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz),
                red, green, blue, alpha, filled);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }
}
