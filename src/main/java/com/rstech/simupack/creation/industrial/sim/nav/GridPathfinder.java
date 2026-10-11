package com.rstech.simupack.creation.industrial.sim.nav;

import com.rstech.simupack.creation.industrial.sim.SimMotion;
import com.rstech.simupack.creation.industrial.sim.SimNavigation;
import com.rstech.simupack.creation.industrial.sim.SimPos;
import com.rstech.simupack.creation.industrial.sim.SimWaypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 在 {@link NavGrid} 上跑 A* 找路。
 *
 * <p><b>这是 NSUK {@code HybridPathfinder} 的移植</b>，不是另写一套：
 * 邻居生成（八向平地走 / 上跳一格 / 下落最多三格 / 爬梯 / 入水）、每一步的代价、
 * 对角线要查「身体宽度 0.36 的走廊」、最后用弦长抽稀（string pulling）把拐点压平 ——
 * 常量与分支顺序都照抄，理由和上一轮一样：仿真要复现的是游戏里的实际走法，
 * 不是一个「看起来也能走」的走法。
 *
 * <p>与原版的差别只有两处，都是为了让它在编辑器里可用：
 * <ul>
 *   <li>几何来自 {@link NavGrid} 抽象，而不是包私有的 {@code PathSnapshot}；
 *       因此这套规则第一次变得<b>可以单元测试</b>；</li>
 *   <li>没有异步分片（原版为了不卡服务端 tick 把搜索拆成 500 步一批），
 *       预览里一次性算完，用 {@link #MAX_ITERATIONS} 封顶。</li>
 * </ul>
 */
public final class GridPathfinder implements SimNavigation {

    private static final int MAX_ITERATIONS = 20000;
    private static final int NEAREST_RANGE = 5;
    /** 连通性泛洪最多看这么多格（诊断用，别为了报错把界面卡住）。 */
    private static final int FLOOD_LIMIT = 20000;
    private static final int SMOOTH_LOOKAHEAD = 24;
    /** 身体半宽：对角线走廊要按 0.36 的四角采样。 */
    private static final double WALK_CORRIDOR_HALF_WIDTH = 0.36D;
    private static final double WALK_CORRIDOR_SAMPLE_STEP = 0.2D;
    private static final double STEP_CLEARANCE = 0.55D;

    private final NavGrid grid;
    /** 上一次 {@link #findPath} 是不是撞到了 {@link #MAX_ITERATIONS} 上限。 */
    private boolean lastSearchCapped;

    public GridPathfinder(NavGrid grid) {
        this.grid = grid;
    }

    /**
     * 容器周围的落脚点：半径 1，再半径 2；每圈 y 偏移从 0 找到 -3。
     *
     * <p>整段照抄 NSUK 的 {@code IndustrialWorkService.containerStandTarget}，
     * 只把「返回离 origin 最近的那一个」改成「返回这一圈的全部候选」——
     * 谁离市民最近由调用方用同一套距离规则挑，结果等价，但路径规划能看到全部选项。
     */
    @Override
    public List<SimPos> standTargets(SimPos container) {
        if (grid == null || container == null) {
            return List.of();
        }
        for (int radius = 1; radius <= STAND_RADIUS; radius++) {
            List<SimPos> ring = new ArrayList<>();
            for (int yOffset = 0; yOffset >= -STAND_MAX_DROP; yOffset--) {
                for (int xOffset = -radius; xOffset <= radius; xOffset++) {
                    for (int zOffset = -radius; zOffset <= radius; zOffset++) {
                        if (Math.max(Math.abs(xOffset), Math.abs(zOffset)) != radius) {
                            continue;
                        }
                        int x = container.x() + xOffset;
                        int y = container.y() + yOffset;
                        int z = container.z() + zOffset;
                        if (grid.cell(x, y, z) != null) {
                            ring.add(new SimPos(x, y, z));
                        }
                    }
                }
            }
            if (!ring.isEmpty()) {
                // 顺序照 NSUK：就按枚举顺序返回整圈，**不做任何偏好筛选**。
                // （曾经在这里「优先挑不带水的格子」，那是我为了绕一个解析 bug 加的，
                //   结果是与 NSUK 不一致 —— 已撤掉。对齐优先。）
                return List.copyOf(ring);
            }
        }
        return List.of();
    }

    /** 容器最多向外找两圈。 */
    private static final int STAND_RADIUS = 2;
    /** 容器最多向下找三格（箱子可能放在半砖或台阶上）。 */
    private static final int STAND_MAX_DROP = 3;

    @Override
    public List<SimWaypoint> findPath(SimPos from, SimPos to) {
        this.lastSearchCapped = false;
        if (grid == null || from == null || to == null) {
            return List.of();
        }
        NavCell start = nearestCell(from);
        NavCell target = nearestCell(to);
        if (start == null || target == null) {
            return List.of();
        }
        if (start.key() == target.key()) {
            return List.of(SimWaypoint.of(target.pos(), target.standY(), walkMode(target)));
        }

        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.fCost));
        Map<Long, SearchNode> best = new HashMap<>();
        SearchNode startNode = new SearchNode(start, null, walkMode(start), 0.0D, heuristic(start, target));
        open.add(startNode);
        best.put(start.key(), startNode);

        int iterations = 0;
        while (!open.isEmpty() && iterations++ < MAX_ITERATIONS) {
            SearchNode current = open.poll();
            if (best.get(current.cell.key()) != current) {
                continue;
            }
            if (current.cell.key() == target.key()) {
                return smooth(reconstruct(current));
            }
            for (Neighbor neighbor : neighbors(current.cell)) {
                double nextCost = current.gCost + neighbor.cost();
                SearchNode existing = best.get(neighbor.cell().key());
                if (existing != null && existing.gCost <= nextCost) {
                    continue;
                }
                SearchNode next = new SearchNode(neighbor.cell(), current, neighbor.mode(), nextCost,
                        nextCost + heuristic(neighbor.cell(), target));
                best.put(neighbor.cell().key(), next);
                open.add(next);
            }
        }
        // 循环退出时还有待扩展的节点 = 撞到了迭代上限，而不是「真的走不到」。
        this.lastSearchCapped = !open.isEmpty();
        return List.of();
    }

    /**
     * 寻路失败时给一句**具体**诊断（见 {@link SimNavigation#explainPathFailure}）。
     *
     * <p>顺序就是排查顺序：先看两头有没有落脚点，再看是不是被上限截断，
     * 最后才做一次连通性泛洪 —— 前三种都便宜，泛洪只在真的「走不到」时跑一次。
     */
    @Override
    public String explainPathFailure(SimPos from, SimPos to) {
        if (grid == null) {
            return "没有可用的寻路网格";
        }
        if (from == null || to == null) {
            return "";
        }
        NavCell start = nearestCell(from);
        if (start == null) {
            return "当前位置 " + describe(from) + " 附近 " + NEAREST_RANGE + " 格内没有能站的格子";
        }
        NavCell target = nearestCell(to);
        if (target == null) {
            return "目标点 " + describe(to) + " 附近 " + NEAREST_RANGE
                    + " 格内没有能站的格子（容器是不是埋在方块里、或者周围全是水/墙）";
        }
        if (this.lastSearchCapped) {
            return "搜索超过 " + MAX_ITERATIONS + " 步上限（建筑太大），不代表真的走不到";
        }
        // 两边各自泛洪一次：**两个区域各有多大、起点那片在哪**，才说得清是「市民被困在一小块
        // 平台上」还是「建筑被墙/水劈成两半」—— 这两种要改的东西完全不同。
        Region startRegion = floodRegion(start);
        if (startRegion.keys.contains(target.key())) {
            // 泛洪说连通、A* 却说找不到 —— 那不是「被挡住」，是**搜索本身**没找到。
            // 这种情况绝不能退回那句笼统的兜底话：它是仿真自己的问题。
            return "起点和终点之间其实是连通的（" + startRegion.size() + " 格），"
                    + "但搜索没找到路 —— 这是仿真自身的问题";
        }
        Region targetRegion = floodRegion(target);
        return "起点和终点不在同一片连通的落脚区域："
                + "起点这一片 " + startRegion.size() + " 格（" + startRegion.bounds() + "），"
                + "终点那一片 " + targetRegion.size() + " 格（" + targetRegion.bounds() + "）";
    }

    /**
     * 从某一格泛洪出整片连通区域。
     *
     * <p>只用于失败时的诊断：正常寻路不会走这里，所以它可以比寻路本身慢一点、简单一点 ——
     * 但要有上限，别为了报个错把界面卡住。
     */
    private Region floodRegion(NavCell start) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<NavCell> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.key());
        int minX = start.x();
        int minY = start.y();
        int minZ = start.z();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        while (!queue.isEmpty() && seen.size() <= FLOOD_LIMIT) {
            NavCell current = queue.poll();
            minX = Math.min(minX, current.x());
            minY = Math.min(minY, current.y());
            minZ = Math.min(minZ, current.z());
            maxX = Math.max(maxX, current.x());
            maxY = Math.max(maxY, current.y());
            maxZ = Math.max(maxZ, current.z());
            for (Neighbor neighbor : neighbors(current)) {
                if (seen.add(neighbor.cell().key())) {
                    queue.add(neighbor.cell());
                }
            }
        }
        return new Region(seen, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 一片连通区域：格集合 + 包围盒。 */
    private record Region(Set<Long> keys, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        int size() {
            return this.keys.size();
        }

        /** 说成「x 1..4, y 0..0, z 2..9」，一眼能看出这片在哪、有多大。 */
        String bounds() {
            return "x " + this.minX + ".." + this.maxX
                    + ", y " + this.minY + ".." + this.maxY
                    + ", z " + this.minZ + ".." + this.maxZ;
        }
    }

    private static String describe(SimPos pos) {
        return pos.x() + "," + pos.y() + "," + pos.z();
    }

    // ── 邻居 ───────────────────────────────────────────────────────────

    private List<Neighbor> neighbors(NavCell current) {
        List<Neighbor> out = new ArrayList<>(16);
        if (current.water()) {
            addWaterNeighbors(current, out);
            return out;
        }
        if (current.climbable()) {
            addClimbNeighbors(current, out);
            addClimbTopExitNeighbors(current, out);
            addClimbDropOffNeighbors(current, out);
        }
        addWalkNeighbors(current, out);
        addSpecialEntryNeighbors(current, out);
        addVerticalTransitions(current, out);
        return out;
    }

    private void addWalkNeighbors(NavCell current, List<Neighbor> out) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                NavCell next = grid.cell(current.x() + dx, current.y(), current.z() + dz);
                if (next == null || !canWalkOnSameLayer(current, next)) {
                    continue;
                }
                boolean diagonal = dx != 0 && dz != 0;
                if (diagonal && !hasClearWalkLine(current.pos(), next.pos())) {
                    continue;
                }
                if (!diagonal && !canCrossHorizontalBoundary(current, next)) {
                    continue;
                }
                out.add(new Neighbor(next, walkMode(next), distance(current, next) * next.cost()));
            }
        }
    }

    private void addSpecialEntryNeighbors(NavCell current, List<Neighbor> out) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                boolean diagonal = dx != 0 && dz != 0;
                if (diagonal && !diagonalClear(current, dx, dz)) {
                    continue;
                }
                NavCell next = grid.cell(current.x() + dx, current.y(), current.z() + dz);
                if (next == null) {
                    continue;
                }
                if (!diagonal && !canCrossHorizontalBoundary(current, next)) {
                    continue;
                }
                if (next.water()) {
                    out.add(new Neighbor(next, SimMotion.SWIM, 1.0D + distance(current, next) * next.cost()));
                } else if (next.climbable()) {
                    out.add(new Neighbor(next, SimMotion.CLIMB, 1.0D + distance(current, next) * next.cost()));
                }
            }
        }
    }

    private void addVerticalTransitions(NavCell current, List<Neighbor> out) {
        if (current.climbable()) {
            return;
        }
        addDirectDropTransition(current, out);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || dx != 0 && dz != 0) {
                    continue;
                }
                NavCell up = grid.cell(current.x() + dx, current.y() + 1, current.z() + dz);
                if (up != null && !up.water() && !up.climbable()
                        && up.standY() - current.standY() <= 1.25D
                        && canCrossHorizontalBoundary(current, up) && hasVerticalPassage(current, up)) {
                    out.add(new Neighbor(up, SimMotion.JUMP, 2.5D + distance(current, up)));
                }
                if (up != null && up.climbable()
                        && up.standY() - current.standY() <= 1.25D
                        && canCrossHorizontalBoundary(current, up) && hasVerticalPassage(current, up)) {
                    SimMotion mode = up.floorSupported() ? SimMotion.JUMP : SimMotion.CLIMB;
                    double entryCost = mode == SimMotion.JUMP ? 2.5D : 8.0D;
                    out.add(new Neighbor(up, mode, entryCost + distance(current, up)));
                }
                NavCell ladderBelow = grid.cell(current.x() + dx, current.y() - 1, current.z() + dz);
                if (ladderBelow != null && ladderBelow.climbable()
                        && current.standY() - ladderBelow.standY() <= 1.25D
                        && canCrossHorizontalBoundary(current, ladderBelow)
                        && hasVerticalPassage(current, ladderBelow)) {
                    out.add(new Neighbor(ladderBelow, SimMotion.CLIMB, 8.0D + distance(current, ladderBelow)));
                }
                for (int fall = 1; fall <= 3; fall++) {
                    NavCell down = grid.cell(current.x() + dx, current.y() - fall, current.z() + dz);
                    if (down == null || down.climbable()
                            || current.standY() - down.standY() > 3.5D
                            || !canCrossHorizontalBoundary(current, down)
                            || !hasVerticalPassage(current, down)) {
                        continue;
                    }
                    out.add(new Neighbor(down, down.water() ? SimMotion.SWIM : SimMotion.FALL,
                            down.water() ? 1.8D : fallCost(current.standY() - down.standY())));
                    break;
                }
            }
        }
        NavCell waterBelow = grid.cell(current.x(), current.y() - 1, current.z());
        if (waterBelow != null && waterBelow.water() && hasVerticalPassage(current, waterBelow)) {
            out.add(new Neighbor(waterBelow, SimMotion.SWIM, 1.8D));
        }
    }

    private void addDirectDropTransition(NavCell current, List<Neighbor> out) {
        for (int fall = 1; fall <= 3; fall++) {
            NavCell down = grid.cell(current.x(), current.y() - fall, current.z());
            if (down == null || down.climbable()) {
                continue;
            }
            double heightDrop = current.standY() - down.standY();
            if (heightDrop < 0.25D || heightDrop > 3.5D || !hasVerticalPassage(current, down)) {
                break;
            }
            out.add(new Neighbor(down, down.water() ? SimMotion.SWIM : SimMotion.FALL,
                    down.water() ? 1.8D : fallCost(heightDrop)));
            break;
        }
    }

    private void addWaterNeighbors(NavCell current, List<Neighbor> out) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    NavCell next = grid.cell(current.x() + dx, current.y() + dy, current.z() + dz);
                    if (next == null) {
                        continue;
                    }
                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal && !diagonalClear(current, dx, dz)) {
                        continue;
                    }
                    if (!diagonal && (dx != 0 || dz != 0) && !canCrossHorizontalBoundary(current, next)) {
                        continue;
                    }
                    if (next.water()) {
                        out.add(new Neighbor(next, SimMotion.SWIM, distance(current, next) * next.cost()));
                        continue;
                    }
                    if (next.climbable()) {
                        if (dy != 0 && diagonal) {
                            continue;
                        }
                        out.add(new Neighbor(next, SimMotion.CLIMB, 1.0D + distance(current, next) * next.cost()));
                        continue;
                    }
                    if (dy == 0) {
                        if (!canWalkOnSameLayer(current, next)
                                || diagonal && !hasClearWalkLine(current.pos(), next.pos())) {
                            continue;
                        }
                        out.add(new Neighbor(next, walkMode(next), distance(current, next) * next.cost()));
                        continue;
                    }
                    if (dy == 1) {
                        if (diagonal || next.standY() - current.standY() > 1.25D
                                || !canCrossHorizontalBoundary(current, next)
                                || !hasVerticalPassage(current, next)) {
                            continue;
                        }
                        out.add(new Neighbor(next, SimMotion.SWIM_EXIT, 2.5D + distance(current, next)));
                        continue;
                    }
                    if (diagonal || current.standY() - next.standY() > 3.5D
                            || !canCrossHorizontalBoundary(current, next)
                            || !hasVerticalPassage(current, next)) {
                        continue;
                    }
                    out.add(new Neighbor(next, SimMotion.FALL, fallCost(current.standY() - next.standY())));
                }
            }
        }
    }

    private void addClimbNeighbors(NavCell current, List<Neighbor> out) {
        NavCell up = grid.cell(current.x(), current.y() + 1, current.z());
        if (up != null && (up.climbable() || up.water())) {
            out.add(new Neighbor(up, up.water() ? SimMotion.SWIM : SimMotion.CLIMB, up.water() ? 2.0D : 6.0D));
        }
        NavCell down = grid.cell(current.x(), current.y() - 1, current.z());
        if (down != null) {
            SimMotion mode = down.water() ? SimMotion.SWIM : (down.climbable() ? SimMotion.CLIMB : walkMode(down));
            out.add(new Neighbor(down, mode, down.climbable() ? 6.0D : 2.0D));
        }
    }

    private void addClimbTopExitNeighbors(NavCell current, List<Neighbor> out) {
        NavCell sampledCurrent = grid.cell(current.x(), current.y(), current.z());
        if (sampledCurrent == null || !sampledCurrent.climbable()) {
            return;
        }
        int exitY = current.y() + 1;
        NavCell sampledTop = grid.cell(current.x(), exitY, current.z());
        if (sampledTop != null && (sampledTop.climbable() || sampledTop.water())) {
            return;
        }
        if (sampledTop == null && !grid.bodyPassage(current.x(), exitY, current.z())) {
            return;
        }
        NavCell topExit = sampledTop != null
                ? sampledTop
                : new NavCell(current.x(), exitY, current.z(), exitY, false, true, false, false, 2.0D);
        if (hasUpperFloorExit(topExit)) {
            out.add(new Neighbor(topExit, SimMotion.CLIMB, 2.0D));
        }
    }

    private boolean hasUpperFloorExit(NavCell virtualTop) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || dx != 0 && dz != 0) {
                    continue;
                }
                if (canWalkOnSameLayer(virtualTop, grid.cell(virtualTop.x() + dx, virtualTop.y(), virtualTop.z() + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addClimbDropOffNeighbors(NavCell current, List<Neighbor> out) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || dx != 0 && dz != 0) {
                    continue;
                }
                for (int fall = 1; fall <= 3; fall++) {
                    NavCell down = grid.cell(current.x() + dx, current.y() - fall, current.z() + dz);
                    if (down == null || down.climbable()
                            || current.standY() - down.standY() > 3.5D
                            || !canCrossHorizontalBoundary(current, down)
                            || !hasVerticalPassage(current, down)) {
                        continue;
                    }
                    out.add(new Neighbor(down, down.water() ? SimMotion.SWIM : SimMotion.FALL,
                            down.water() ? 1.8D : fallCost(current.standY() - down.standY())));
                    break;
                }
            }
        }
    }

    // ── 通行判定 ───────────────────────────────────────────────────────

    private boolean diagonalClear(NavCell current, int dx, int dz) {
        NavCell acrossX = grid.cell(current.x() + dx, current.y(), current.z());
        NavCell acrossZ = grid.cell(current.x(), current.y(), current.z() + dz);
        NavCell target = grid.cell(current.x() + dx, current.y(), current.z() + dz);
        return target != null && acrossX != null && acrossZ != null
                && canCrossHorizontalBoundary(current, acrossX)
                && canCrossHorizontalBoundary(acrossX, target)
                && canCrossHorizontalBoundary(current, acrossZ)
                && canCrossHorizontalBoundary(acrossZ, target);
    }

    private boolean canCrossHorizontalBoundary(NavCell from, NavCell to) {
        int horizontalDistance = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
        if (horizontalDistance != 1) {
            return horizontalDistance == 0;
        }
        if (grid.blocksHorizontalBoundary(from.x(), from.y(), from.z(), to.x(), to.z())) {
            return false;
        }
        return from.y() == to.y()
                || !grid.blocksHorizontalBoundary(from.x(), to.y(), from.z(), to.x(), to.z());
    }

    private boolean hasVerticalPassage(NavCell from, NavCell to) {
        int minY = Math.min(from.y(), to.y()) + 1;
        int maxY = Math.max(from.y(), to.y());
        for (int y = minY; y <= maxY; y++) {
            if (!grid.bodyPassage(to.x(), y, to.z())) {
                return false;
            }
        }
        return true;
    }

    private static boolean canWalkOnSameLayer(NavCell from, NavCell to) {
        return to != null && !to.water() && !to.climbable()
                && Math.abs(from.standY() - to.standY()) <= 0.75D;
    }

    private static SimMotion walkMode(NavCell cell) {
        return cell.water() ? SimMotion.SWIM : (cell.climbable() ? SimMotion.CLIMB : SimMotion.WALK);
    }

    private static double fallCost(double heightDiff) {
        if (heightDiff < 1.0D) {
            return 1.5D;
        }
        if (heightDiff <= 1.5D) {
            return 4.0D;
        }
        return 8.0D + heightDiff * 2.0D;
    }

    private static double heuristic(NavCell from, NavCell to) {
        return distance(from, to);
    }

    private static double distance(NavCell from, NavCell to) {
        double dx = from.x() - to.x();
        double dy = from.standY() - to.standY();
        double dz = from.z() - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ── 抽稀（string pulling） ──────────────────────────────────────────

    private List<SimWaypoint> reconstruct(SearchNode end) {
        List<SearchNode> nodes = new ArrayList<>();
        for (SearchNode node = end; node != null; node = node.parent) {
            nodes.add(node);
        }
        List<SimWaypoint> waypoints = new ArrayList<>(nodes.size());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            SearchNode node = nodes.get(i);
            waypoints.add(SimWaypoint.of(node.cell.pos(), node.cell.standY(), node.mode));
        }
        return waypoints;
    }

    private List<SimWaypoint> smooth(List<SimWaypoint> raw) {
        if (raw.size() <= 2) {
            return raw;
        }
        boolean[] woodenDoor = new boolean[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            woodenDoor[i] = isWoodenDoor(raw.get(i));
        }
        List<SimWaypoint> smoothed = new ArrayList<>(raw.size());
        int anchor = 0;
        smoothed.add(raw.get(anchor));
        while (anchor < raw.size() - 1) {
            int runCap = contiguousRunCap(raw, woodenDoor, anchor);
            int maxCandidate = Math.min(runCap, anchor + SMOOTH_LOOKAHEAD);
            int best = anchor + 1;
            for (int candidate = maxCandidate; candidate > anchor + 1; candidate--) {
                if (canSmoothSegment(raw, anchor, candidate, woodenDoor)) {
                    best = candidate;
                    break;
                }
            }
            smoothed.add(raw.get(best));
            anchor = best;
        }
        return smoothed;
    }

    private int contiguousRunCap(List<SimWaypoint> waypoints, boolean[] woodenDoor, int anchor) {
        SimWaypoint from = waypoints.get(anchor);
        if (isActionMode(from.motion()) || woodenDoor[anchor]) {
            return anchor + 1;
        }
        int feetY = from.cell().y();
        int cap = anchor;
        while (cap + 1 <= waypoints.size() - 1) {
            int candidate = cap + 1;
            SimWaypoint waypoint = waypoints.get(candidate);
            if (waypoint.motion() != from.motion() || isActionMode(waypoint.motion())
                    || waypoint.cell().y() != feetY || woodenDoor[candidate]) {
                break;
            }
            cap = candidate;
        }
        return Math.max(anchor + 1, cap);
    }

    private boolean canSmoothSegment(List<SimWaypoint> waypoints, int fromIndex, int toIndex, boolean[] woodenDoor) {
        SimWaypoint from = waypoints.get(fromIndex);
        SimWaypoint to = waypoints.get(toIndex);
        SimMotion mode = from.motion();
        if (mode != to.motion() || isActionMode(mode)
                || from.cell().y() != to.cell().y()
                || woodenDoor[fromIndex] || woodenDoor[toIndex]) {
            return false;
        }
        double segmentX = to.x() - from.x();
        double segmentZ = to.z() - from.z();
        for (int index = fromIndex + 1; index < toIndex; index++) {
            SimWaypoint waypoint = waypoints.get(index);
            if (waypoint.motion() != mode || isActionMode(waypoint.motion())
                    || waypoint.cell().y() != from.cell().y() || woodenDoor[index]) {
                return false;
            }
            if (exceedsChordStep(from, to, waypoint, segmentX, segmentZ)) {
                return false;
            }
        }
        return hasClearWalkLine(from.cell(), to.cell());
    }

    private static boolean exceedsChordStep(SimWaypoint from, SimWaypoint to, SimWaypoint mid,
                                            double segmentX, double segmentZ) {
        double progress = Math.abs(segmentX) >= Math.abs(segmentZ)
                ? (segmentX != 0.0D ? (mid.x() - from.x()) / segmentX : 0.0D)
                : (segmentZ != 0.0D ? (mid.z() - from.z()) / segmentZ : 0.0D);
        progress = Math.max(0.0D, Math.min(1.0D, progress));
        double chordY = from.y() + (to.y() - from.y()) * progress;
        return mid.y() - chordY > STEP_CLEARANCE;
    }

    private boolean isWoodenDoor(SimWaypoint waypoint) {
        NavCell cell = grid.cell(waypoint.cell().x(), waypoint.cell().y(), waypoint.cell().z());
        return cell != null && cell.woodenDoor();
    }

    /**
     * 从 {@code from} 到 {@code to} 之间，身体宽度 0.36 的方形footprint 是否一路畅通。
     *
     * <p>这条是「不穿墙角」的关键：只检查格子中心的话，市民会从两块斜对角摆放的方块
     * 之间「挤」过去。
     */
    private boolean hasClearWalkLine(SimPos from, SimPos to) {
        NavCell previous = grid.cell(from.x(), from.y(), from.z());
        NavCell target = grid.cell(to.x(), to.y(), to.z());
        if (previous == null || target == null || !canWalkOnSameLayer(previous, target)) {
            return false;
        }
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        double horizontalDistance = Math.sqrt((double) dx * dx + (double) dz * dz);
        int steps = Math.max(1, (int) Math.ceil(horizontalDistance / WALK_CORRIDOR_SAMPLE_STEP));
        int lastX = from.x();
        int lastZ = from.z();
        for (int step = 1; step <= steps; step++) {
            double ratio = (double) step / (double) steps;
            double sampleX = from.x() + 0.5D + dx * ratio;
            double sampleZ = from.z() + 0.5D + dz * ratio;
            if (!hasFootprintClearance(previous, sampleX, sampleZ)) {
                return false;
            }
            int x = (int) Math.floor(sampleX);
            int z = (int) Math.floor(sampleZ);
            if (x == lastX && z == lastZ) {
                continue;
            }
            int stepX = Integer.compare(x - lastX, 0);
            int stepZ = Integer.compare(z - lastZ, 0);
            if (Math.abs(x - lastX) > 1 || Math.abs(z - lastZ) > 1) {
                return false;
            }
            NavCell current = grid.cell(x, from.y(), z);
            if (current == null || !canWalkOnSameLayer(previous, current)) {
                return false;
            }
            if (!(stepX != 0 && stepZ != 0) && !canCrossHorizontalBoundary(previous, current)) {
                return false;
            }
            if (stepX != 0 && stepZ != 0 && !diagonalClear(previous, stepX, stepZ)) {
                return false;
            }
            previous = current;
            lastX = x;
            lastZ = z;
        }
        return true;
    }

    private boolean hasFootprintClearance(NavCell reference, double centerX, double centerZ) {
        return hasPointClearance(reference, centerX - WALK_CORRIDOR_HALF_WIDTH, centerZ - WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(reference, centerX - WALK_CORRIDOR_HALF_WIDTH, centerZ + WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(reference, centerX + WALK_CORRIDOR_HALF_WIDTH, centerZ - WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(reference, centerX + WALK_CORRIDOR_HALF_WIDTH, centerZ + WALK_CORRIDOR_HALF_WIDTH);
    }

    private boolean hasPointClearance(NavCell reference, double x, double z) {
        NavCell cell = grid.cell((int) Math.floor(x), reference.y(), (int) Math.floor(z));
        return cell != null && canWalkOnSameLayer(reference, cell);
    }

    private NavCell nearestCell(SimPos target) {
        NavCell direct = grid.cell(target.x(), target.y(), target.z());
        if (direct != null) {
            return direct;
        }
        NavCell best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -NEAREST_RANGE; dx <= NEAREST_RANGE; dx++) {
            for (int dy = -NEAREST_RANGE; dy <= NEAREST_RANGE; dy++) {
                for (int dz = -NEAREST_RANGE; dz <= NEAREST_RANGE; dz++) {
                    NavCell cell = grid.cell(target.x() + dx, target.y() + dy, target.z() + dz);
                    if (cell == null) {
                        continue;
                    }
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance < bestDistance) {
                        best = cell;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isActionMode(SimMotion mode) {
        return mode.vertical();
    }

    private record Neighbor(NavCell cell, SimMotion mode, double cost) {
    }

    private static final class SearchNode {
        private final NavCell cell;
        private final SearchNode parent;
        private final SimMotion mode;
        private final double gCost;
        private final double fCost;

        private SearchNode(NavCell cell, SearchNode parent, SimMotion mode, double gCost, double fCost) {
            this.cell = cell;
            this.parent = parent;
            this.mode = mode;
            this.gCost = gCost;
            this.fCost = fCost;
        }
    }
}
