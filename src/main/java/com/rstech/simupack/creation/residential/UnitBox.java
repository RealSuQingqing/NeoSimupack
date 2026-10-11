package com.rstech.simupack.creation.residential;

/**
 * 闭区间整数盒，纯几何、<b>不依赖任何 Minecraft 类型</b>。
 *
 * <p>刻意与 {@link CreationUnit} 分开：户型编辑要读写 {@code BlockPos}，
 * 但阵列的越界与重叠判定只是三轴区间比较。把这部分抽出来之后，
 * 可以在不加载 Minecraft 的单元测试里直接验证（见 {@code ResidentialArrayValidatorTest}）。
 */
public record UnitBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** 用两个任意角点构造，内部逐轴归一化（与 NSUK 的 Math.min/Math.max 一致）。 */
    public static UnitBox of(int ax, int ay, int az, int bx, int by, int bz) {
        return new UnitBox(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
    }

    public UnitBox translate(int dx, int dy, int dz) {
        return new UnitBox(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** 三维闭区间相交；共面贴合（{@code maxX == other.minX}）算重叠。 */
    public boolean overlaps(UnitBox other) {
        return other != null
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /**
     * 是否完全落在 {@code sizeX × sizeY × sizeZ} 的结构内。
     *
     * <p>尺寸任一非正表示「未知」，此时<b>跳过</b>判定并返回 true ——
     * 与 {@code StructureBounds.known()} 的约定一致。
     */
    public boolean within(int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            return true;
        }
        return minX >= 0 && minY >= 0 && minZ >= 0
                && maxX < sizeX && maxY < sizeY && maxZ < sizeZ;
    }

    public int extentX() {
        return maxX - minX + 1;
    }

    public int extentY() {
        return maxY - minY + 1;
    }

    public int extentZ() {
        return maxZ - minZ + 1;
    }
}
