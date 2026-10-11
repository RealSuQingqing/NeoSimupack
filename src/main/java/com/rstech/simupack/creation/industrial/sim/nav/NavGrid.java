package com.rstech.simupack.creation.industrial.sim.nav;

/**
 * 寻路网格的读取接口。
 *
 * <p>刻意只暴露三个查询，把「格数据从哪来」和「怎么找路」彻底分开：
 * 客户端用预览世界的方块实现它，单元测试用手搭的假网格实现它。
 * NSUK 把这两件事焊在一个包私有类里（{@code PathSnapshotBuilder} + {@code HybridPathfinder}），
 * 结果就是寻路规则完全没有测试覆盖 —— 这一层拆分就是为了不再重演。
 */
public interface NavGrid {

    /** 该格能不能站人；不能站返回 {@code null}。 */
    NavCell cell(int x, int y, int z);

    /** 该格是不是「身体能通过」的空腔（爬梯出口、竖直通道要用）。 */
    boolean bodyPassage(int x, int y, int z);

    /**
     * 从 {@code (fromX, fromY, fromZ)} 朝相邻的 {@code (toX, *, toZ)} 走，
     * 中间是不是被挡住（目前只有打开的地板门会形成这种边界）。
     */
    boolean blocksHorizontalBoundary(int fromX, int fromY, int fromZ, int toX, int toZ);

    default NavCell cell(NavCell reference, int dx, int dy, int dz) {
        return cell(reference.x() + dx, reference.y() + dy, reference.z() + dz);
    }

    /**
     * 搜索半径（格）。用来自动放大网格以覆盖起点与终点；
     * 返回 0 表示「网格就是全部范围，不需要扩」。
     */
    default int searchRadius() {
        return 96;
    }
}
