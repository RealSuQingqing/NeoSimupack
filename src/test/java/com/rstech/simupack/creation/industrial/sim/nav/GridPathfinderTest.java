package com.rstech.simupack.creation.industrial.sim.nav;

import com.rstech.simupack.creation.industrial.sim.SimNavigation;
import com.rstech.simupack.creation.industrial.sim.SimPos;
import com.rstech.simupack.creation.industrial.sim.SimWaypoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寻路规则的测试。
 *
 * <p>这一组正是 NSUK 那边做不到的事：它的 {@code HybridPathfinder} 是包私有的，
 * 几何来自一个同样包私有的快照类，规则完全没有测试覆盖。这里把几何抽成
 * {@link NavGrid}，于是「绕开墙」「上台阶」「不走斜角缝」「不穿脚下没路的空档」
 * 这些最容易被写错的判定第一次可以被断言。
 */
class GridPathfinderTest {

    /**
     * 手搭的假网格：给一组「能站的格子」，其余一律不可站。
     *
     * <p>{@code blockedEdges} 用来模拟「两格相邻但走不过去」——例如它们之间隔着半砖或栏杆。
     */
    private static final class FakeGrid implements NavGrid {
        private final java.util.Set<Long> standable = new java.util.HashSet<>();
        private final java.util.Set<Long> bodyPassage = new java.util.HashSet<>();
        private final java.util.Set<Long> blockedEdges = new java.util.HashSet<>();

        FakeGrid stand(int x, int y, int z) {
            standable.add(NavCell.key(x, y, z));
            bodyPassage.add(NavCell.key(x, y, z));
            bodyPassage.add(NavCell.key(x, y + 1, z));
            return this;
        }

        /** 删除一个格子的「身体能通过」，用来切断竖直通道。 */
        FakeGrid seal(int x, int y, int z) {
            bodyPassage.remove(NavCell.key(x, y, z));
            standable.remove(NavCell.key(x, y, z));
            return this;
        }

        FakeGrid blockEdge(int fromX, int fromY, int fromZ, int toX, int toZ) {
            blockedEdges.add(edgeKey(fromX, fromY, fromZ, toX, toZ));
            blockedEdges.add(edgeKey(toX, fromY, toZ, fromX, fromZ));
            return this;
        }

        private static long edgeKey(int fromX, int fromY, int fromZ, int toX, int toZ) {
            return NavCell.key(fromX, fromY, fromZ) * 31L + NavCell.key(toX, 0, toZ);
        }

        @Override
        public NavCell cell(int x, int y, int z) {
            if (!standable.contains(NavCell.key(x, y, z))) {
                return null;
            }
            return new NavCell(x, y, z, y, false, false, false, true, 1.0D);
        }

        @Override
        public boolean bodyPassage(int x, int y, int z) {
            return bodyPassage.contains(NavCell.key(x, y, z));
        }

        @Override
        public boolean blocksHorizontalBoundary(int fromX, int fromY, int fromZ, int toX, int toZ) {
            return blockedEdges.contains(edgeKey(fromX, fromY, fromZ, toX, toZ));
        }

        @Override
        public int searchRadius() {
            return 0;
        }
    }

    /** 把途径点还原成格子串，便于断言「走了哪条路」。 */
    private static List<SimPos> cells(List<SimWaypoint> path) {
        return path.stream().map(SimWaypoint::cell).toList();
    }

    @Test
    void 空地直走时会被抽稀成两个端点() {
        FakeGrid grid = new FakeGrid();
        for (int x = 0; x <= 10; x++) {
            grid.stand(x, 0, 0);
        }
        List<SimWaypoint> path = new GridPathfinder(grid).findPath(new SimPos(0, 0, 0), new SimPos(10, 0, 0));

        assertFalse(path.isEmpty());
        // string pulling 会把中间的一串拐点压掉，只留首尾（这正是 NSUK 的 smooth 行为）。
        assertEquals(2, path.size());
        assertEquals(new SimPos(0, 0, 0), path.get(0).cell());
        assertEquals(new SimPos(10, 0, 0), path.get(path.size() - 1).cell());
    }

    @Test
    void 绕开缺口而不是直接穿过去() {
        FakeGrid grid = new FakeGrid();
        // 一条 0..10 的走廊，中间 z=0 的 x=5 是缺口；绕行 z=1。
        for (int x = 0; x <= 10; x++) {
            if (x != 5) {
                grid.stand(x, 0, 0);
            }
            grid.stand(x, 0, 1);
        }
        List<SimWaypoint> path = new GridPathfinder(grid).findPath(new SimPos(0, 0, 0), new SimPos(10, 0, 0));

        assertFalse(path.isEmpty());
        assertTrue(cells(path).stream().anyMatch(pos -> pos.z() == 1),
                "必须从 z=1 绕过去，实际路径 " + cells(path));
        assertFalse(cells(path).contains(new SimPos(5, 0, 0)), "缺口不能站人");
        // 抽稀之后不能退化成一条两点直线：那样就等于从缺口上方跨过去了。
        assertTrue(path.size() > 2, "绕行的路径不该被抽成一条直线，实际 " + cells(path));
        // 绕路必然比直路长。
        assertTrue(SimNavigation.length(path) > 10.0D,
                "绕行总长应当超过 10 格，实际 " + SimNavigation.length(path));
    }

    @Test
    void 直线上的每一段都必须是能站的格子() {
        FakeGrid grid = new FakeGrid();
        for (int x = 0; x <= 10; x++) {
            if (x != 5) {
                grid.stand(x, 0, 0);
            }
            grid.stand(x, 0, 1);
        }
        List<SimWaypoint> path = new GridPathfinder(grid).findPath(new SimPos(0, 0, 0), new SimPos(10, 0, 0));
        // 逐段按 0.2 格采样，每一个采样点都必须在能站的格子里 —— 这条就是「不会穿墙」的定义。
        for (int i = 1; i < path.size(); i++) {
            SimWaypoint from = path.get(i - 1);
            SimWaypoint to = path.get(i);
            double length = from.distanceTo(to);
            int steps = Math.max(1, (int) Math.ceil(length / 0.2D));
            for (int step = 0; step <= steps; step++) {
                double ratio = (double) step / steps;
                int x = (int) Math.floor(from.x() + (to.x() - from.x()) * ratio);
                int z = (int) Math.floor(from.z() + (to.z() - from.z()) * ratio);
                assertTrue(grid.cell(x, 0, z) != null,
                        "路径经过了一个站不住的格子 (" + x + ",0," + z + ")，完整路径 " + cells(path));
            }
        }
    }

    @Test
    void 走不过去时返回空表而不是硬穿() {
        FakeGrid grid = new FakeGrid();
        for (int x = 0; x <= 4; x++) {
            grid.stand(x, 0, 0);
        }
        for (int x = 8; x <= 12; x++) {
            grid.stand(x, 0, 0);
        }
        // 中间 5..7 完全没有落脚点。
        List<SimWaypoint> path = new GridPathfinder(grid).findPath(new SimPos(0, 0, 0), new SimPos(12, 0, 0));
        assertTrue(path.isEmpty(), "走不到就该返回空，交给上层标记受阻");
    }

    @Test
    void 走不到时要说清是哪一种走不到() {
        // 「目标点没有落脚点」「两头不连通」「搜索超上限」要改的东西完全不同，
        // 一句「寻路失败」等于没说 —— 这条守的就是那句诊断。
        FakeGrid disconnected = new FakeGrid();
        for (int x = 0; x <= 4; x++) {
            disconnected.stand(x, 0, 0);
        }
        for (int x = 8; x <= 12; x++) {
            disconnected.stand(x, 0, 0);
        }
        GridPathfinder pathfinder = new GridPathfinder(disconnected);
        assertTrue(pathfinder.findPath(new SimPos(0, 0, 0), new SimPos(12, 0, 0)).isEmpty());
        String why = pathfinder.explainPathFailure(new SimPos(0, 0, 0), new SimPos(12, 0, 0));
        assertTrue(why.contains("不连通") || why.contains("不在同一片"),
                "两段断开要说「不连通」，实际：" + why);

        // 目标点周围一片空白（容器埋在方块里）—— 这是另一种失败。
        FakeGrid isolated = new FakeGrid();
        for (int x = 0; x <= 4; x++) {
            isolated.stand(x, 0, 0);
        }
        GridPathfinder second = new GridPathfinder(isolated);
        assertTrue(second.findPath(new SimPos(0, 0, 0), new SimPos(40, 0, 40)).isEmpty());
        String missing = second.explainPathFailure(new SimPos(0, 0, 0), new SimPos(40, 0, 40));
        assertTrue(missing.contains("目标点"), "目标点附近没落脚点要说「目标点」，实际：" + missing);
    }

    @Test
    void 一格高的台阶可以直接走上去() {
        FakeGrid grid = new FakeGrid();
        for (int x = 0; x <= 3; x++) {
            grid.stand(x, 0, 0);
        }
        for (int x = 4; x <= 6; x++) {
            grid.stand(x, 1, 0);
        }
        List<SimWaypoint> path = new GridPathfinder(grid).findPath(new SimPos(0, 0, 0), new SimPos(6, 1, 0));

        assertFalse(path.isEmpty());
        assertEquals(1.0D, path.get(path.size() - 1).y(), 1.0E-6D, "终点应当站在上一层");
        // 上台阶那一步要标成 JUMP，客户端才知道补抛物线。
        assertTrue(path.stream().anyMatch(waypoint -> waypoint.motion().name().equals("JUMP")
                        || waypoint.motion().name().equals("CLIMB")),
                "上台阶要有竖直动作，实际 " + path.stream().map(SimWaypoint::motion).toList());
    }

    @Test
    void 地板门封住的边不算通路() {
        FakeGrid open = new FakeGrid();
        for (int x = 0; x <= 3; x++) {
            open.stand(x, 0, 0);
        }
        assertFalse(new GridPathfinder(open).findPath(new SimPos(0, 0, 0), new SimPos(3, 0, 0)).isEmpty());

        FakeGrid sealed = new FakeGrid();
        for (int x = 0; x <= 3; x++) {
            sealed.stand(x, 0, 0);
        }
        // 把 1↔2 之间的边界封死（模拟打开的地板门）。
        sealed.blockEdge(1, 0, 0, 2, 0);
        assertTrue(new GridPathfinder(sealed).findPath(new SimPos(0, 0, 0), new SimPos(3, 0, 0)).isEmpty(),
                "边界被封死时不能穿过去");
    }

    @Test
    void 斜角穿缝不被允许() {
        FakeGrid grid = new FakeGrid();
        // 只有 (0,0) 和 (1,1) 能站，(1,0) 与 (0,1) 都是空的 ⇒ 不能斜着挤过去。
        grid.stand(0, 0, 0);
        grid.stand(1, 0, 1);
        List<SimWaypoint> path = new GridPathfinder(grid).findPath(new SimPos(0, 0, 0), new SimPos(1, 0, 1));
        assertTrue(path.isEmpty(), "斜对角两边都没落脚点时不能斜穿，实际 " + cells(path));
    }

    @Test
    void 起点或终点附近没有可站格时返回空() {
        FakeGrid grid = new FakeGrid();
        grid.stand(0, 0, 0);
        assertTrue(new GridPathfinder(grid).findPath(new SimPos(0, 0, 0), new SimPos(999, 0, 999)).isEmpty());
        assertTrue(new GridPathfinder(grid).findPath(new SimPos(999, 0, 999), new SimPos(0, 0, 0)).isEmpty());
    }

    @Test
    void 起终点是同一格时返回单点路径() {
        FakeGrid grid = new FakeGrid();
        grid.stand(3, 2, 1);
        List<SimWaypoint> path = new GridPathfinder(grid).findPath(new SimPos(3, 2, 1), new SimPos(3, 2, 1));
        assertEquals(1, path.size());
        assertEquals(new SimPos(3, 2, 1), path.get(0).cell());
        assertEquals(3.5D, path.get(0).x(), 1.0E-6D);
        assertEquals(2.0D, path.get(0).y(), 1.0E-6D);
        assertEquals(1.5D, path.get(0).z(), 1.0E-6D);
    }

    @Test
    void 落点取的是格心与站立高度() {
        FakeGrid grid = new FakeGrid();
        for (int x = 0; x <= 2; x++) {
            grid.stand(x, 0, 0);
        }
        List<SimWaypoint> path = new GridPathfinder(grid).findPath(new SimPos(0, 0, 0), new SimPos(2, 0, 0));
        SimWaypoint last = path.get(path.size() - 1);
        assertEquals(2.5D, last.x(), 1.0E-6D);
        assertEquals(0.5D, last.z(), 1.0E-6D);
        assertEquals(0.0D, last.y(), 1.0E-6D);
    }

    @Test
    void 容器落脚点只在半径一到二的两圈里找() {
        FakeGrid grid = new FakeGrid();
        for (int x = -1; x <= 5; x++) {
            for (int z = -1; z <= 5; z++) {
                grid.stand(x, 0, z);
            }
        }
        SimPos container = new SimPos(2, 0, 2);
        List<SimPos> stands = new GridPathfinder(grid).standTargets(container);

        assertFalse(stands.isEmpty(), "周围一圈都是地板，必须能找到落脚点");
        // NSUK 的规则：半径 1 有就不去半径 2；且容器自己那一格不算。
        assertEquals(8, stands.size(), "半径 1 的八格，实际 " + stands);
        assertFalse(stands.contains(container), "不能把容器本身当成落脚点");
        for (SimPos stand : stands) {
            assertEquals(1, Math.max(Math.abs(stand.x() - container.x()), Math.abs(stand.z() - container.z())));
        }
    }

    @Test
    void 容器被围住时会向外找到半径二() {
        FakeGrid grid = new FakeGrid();
        // 半径 1 的八格全是空的，只有半径 2 的一圈有地板。
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                if (Math.max(Math.abs(x - 2), Math.abs(z - 2)) == 2) {
                    grid.stand(x, 0, z);
                }
            }
        }
        List<SimPos> stands = new GridPathfinder(grid).standTargets(new SimPos(2, 0, 2));
        assertFalse(stands.isEmpty());
        for (SimPos stand : stands) {
            assertEquals(2, Math.max(Math.abs(stand.x() - 2), Math.abs(stand.z() - 2)),
                    "半径 1 没地方站，应当退到半径 2");
        }
    }

    @Test
    void 容器往下三格内也能站() {
        FakeGrid grid = new FakeGrid();
        // 容器放在 (2,1,2)，它下面一层的地板在 y=0 的半径 1 处。
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                if (x == 2 && z == 2) {
                    continue;
                }
                grid.stand(x, 0, z);
            }
        }
        List<SimPos> stands = new GridPathfinder(grid).standTargets(new SimPos(2, 1, 2));
        assertFalse(stands.isEmpty(), "y 偏移 -1 应当能找到地板");
        for (SimPos stand : stands) {
            assertEquals(0, stand.y());
        }
    }

    @Test
    void 两圈内都没有落脚点时返回空表() {
        FakeGrid grid = new FakeGrid();
        grid.stand(20, 0, 20);
        assertTrue(new GridPathfinder(grid).standTargets(new SimPos(2, 0, 2)).isEmpty());
    }

    @Test
    void 空网格不会崩() {
        assertTrue(new GridPathfinder(null).findPath(new SimPos(0, 0, 0), new SimPos(1, 0, 0)).isEmpty());
        assertTrue(new GridPathfinder(new FakeGrid()).findPath(null, new SimPos(1, 0, 0)).isEmpty());
    }
}
