package com.rstech.simupack.creation.industrial.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * 寻路边界。
 *
 * <p>仿真内核只认「给我起点和终点，还我一串带落点的途径点」这一件事。真实实现是
 * {@code sim.nav.GridPathfinder} —— 它是 NSUK {@code HybridPathfinder} 的移植，
 * 跑在预览结构的方块数据上，因此会绕开墙、上台阶、跳缺口、下落，而不是穿墙走直线。
 *
 * <p>{@link #direct()} 只是<b>没有格数据时</b>的兜底（例如结构还没加载完），
 * 生成的是一条不看方块的直线，界面上会写明当前用的是哪一种。
 */
@FunctionalInterface
public interface SimNavigation {

    /** 单次寻路的返回点数上限。 */
    int MAX_POINTS_PER_LEG = 512;

    /**
     * 市民走路速度（格 / tick）。
     *
     * <p>0.2 格/tick ≈ 4 格/秒，与游戏里玩家的步行速度同量级。旧版是「1 tick 走 1 格」，
     * 比正常走路快五倍，看起来就是瞬移 —— 再真实的路径配上这个速度也不像人在走。
     */
    double WALK_BLOCKS_PER_TICK = 0.2D;

    /** 攀爬速度，取自 NSUK 的 {@code ActiveNavigation.CLIMB_VERTICAL_SPEED}。 */
    double CLIMB_BLOCKS_PER_TICK = 0.16D;

    /**
     * 从 {@code from} 走到 {@code to}。
     *
     * <p>约定：返回的列表<b>包含起点</b>。返回空表表示「走不到」，调用方据此把这一步
     * 标记成受阻，而不是假装走成功了。
     */
    List<SimWaypoint> findPath(SimPos from, SimPos to);

    /**
     * 「走到这个容器」时真正要站的落脚点。
     *
     * <p><b>不能直接走到容器方块本身</b> —— 箱子是实心方块，走不进去。NSUK 的
     * {@code move_to_container} 走的是 {@code IndustrialWorkService.containerStandTarget}：
     * 以容器为中心，按「半径 1，再半径 2」两圈、每圈 y 偏移从 0 到 -3 的顺序，
     * 找到第一个存在落脚点的圈，再在这一圈里取离市民最近的那个格子；
     * 只有两圈都找不到落脚点时，才退化成「容器本身」（{@code hasStandTarget = false}），
     * 由寻路自己的就近吸附去贴近它。
     *
     * @return 候选落脚点；返回空表表示「没有落脚点」，调用方应退回容器自身
     */
    default List<SimPos> standTargets(SimPos container) {
        return List.of();
    }

    /**
     * {@link #findPath} 返回空列表时，说清**是哪种失败**。
     *
     * <p>为什么要有它：一个空列表把三件完全不同的事混成了同一句「寻路失败」——
     * 「目标点附近根本没有落脚点」（容器埋在方块里 → 改配置或改建筑）、
     * 「起点和终点不在同一片连通区域」（建筑被墙/水分断）、
     * 「搜索超过上限」（建筑太大，其实可能走得到）。
     * 三种情况要改的东西完全不同，所以不能只说「走不到」。
     *
     * @return 一句话诊断；空串表示这条实现说不出更多
     */
    default String explainPathFailure(SimPos from, SimPos to) {
        return "";
    }

    /**
     * 兜底：确定性的直线格子路线，<b>不看方块</b>。
     *
     * <p>顺序固定为「先 X、再 Z、最后 Y」，每步曼哈顿距离减一，因此一定在有限步内收敛。
     * 它会在墙里穿过去 —— 所以只在拿不到格数据时使用，并且界面上必须说明。
     */
    static SimNavigation direct() {
        return (from, to) -> {
            if (from == null || to == null) {
                return List.of();
            }
            List<SimWaypoint> path = new ArrayList<>();
            SimPos current = from;
            path.add(SimWaypoint.of(current, current.y(), SimMotion.WALK));
            while (!current.equals(to) && path.size() <= MAX_POINTS_PER_LEG) {
                int dx = Integer.compare(to.x(), current.x());
                int dz = Integer.compare(to.z(), current.z());
                int dy = Integer.compare(to.y(), current.y());
                if (dx != 0) {
                    current = current.offset(dx, 0, 0);
                } else if (dz != 0) {
                    current = current.offset(0, 0, dz);
                } else if (dy != 0) {
                    current = current.offset(0, dy, 0);
                } else {
                    break;
                }
                path.add(SimWaypoint.of(current, current.y(), SimMotion.WALK));
            }
            return List.copyOf(path);
        };
    }

    /** 一条路线的总长度（含竖直落差）。 */
    static double length(List<SimWaypoint> path) {
        double total = 0;
        for (int i = 1; i < path.size(); i++) {
            total += path.get(i - 1).distanceTo(path.get(i));
        }
        return total;
    }

    /**
     * 按 NSUK 分运动方式的速度把路线长度换算成 tick。
     *
     * <p>攀爬 0.16 格/tick、「跳」至少 4 tick、「落」按 0.6 格/tick 计 ——
     * 这些数字来自 {@code ActiveNavigation.speedFor} 与 {@code CLIMB_VERTICAL_SPEED}，
     * 不是随手写的。全部按走路速度算的话，爬梯子会像坐电梯。
     */
    static int travelTicks(List<SimWaypoint> path) {
        double ticks = 0;
        for (int i = 1; i < path.size(); i++) {
            SimWaypoint from = path.get(i - 1);
            SimWaypoint to = path.get(i);
            double distance = from.distanceTo(to);
            ticks += switch (to.motion()) {
                case JUMP -> Math.max(4.0D, distance / WALK_BLOCKS_PER_TICK);
                case FALL -> Math.max(3.0D, Math.abs(to.y() - from.y()) / 0.6D);
                case CLIMB -> Math.max(4.0D, distance / CLIMB_BLOCKS_PER_TICK);
                case SWIM -> Math.max(2.0D, distance / 0.12D);
                case SWIM_EXIT -> Math.max(6.0D, distance / 0.15D);
                case WALK, RUN -> distance / WALK_BLOCKS_PER_TICK;
            };
        }
        return Math.max(1, (int) Math.ceil(ticks));
    }
}
