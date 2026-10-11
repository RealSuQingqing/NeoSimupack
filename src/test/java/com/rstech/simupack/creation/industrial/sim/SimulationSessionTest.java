package com.rstech.simupack.creation.industrial.sim;

import com.rstech.simupack.creation.json.BuildingJsonDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放状态机与走位插值的测试。
 *
 * <p>旧版把「第几步 / 第几 tick / 在不在播」和界面刷新、路径重算混在一个 tick 方法里，
 * 而且走位是「一 tick 一格」。这一组断言把规则钉死：
 * 游标只前进、步进长度由步骤耗时决定、<b>坐标沿折线按速度连续插值</b>、
 * 摆腿相位跟着里程走、首尾都精确落在途径点上、播到底就停住。
 */
class SimulationSessionTest {

    /**
     * 第 0 步：从 (0,0,0) 走到 (1,0,0)，1 格、11 tick。
     *
     * <p>之所以给 11 tick 而不是按 0.2 格/tick 算出来的 5：步内进度是
     * {@code tickInStep / (ticks - 1)}，取 11 之后每 tick 前进 0.1 格，断言里是整数好读。
     */
    private static final int WALK_TICKS = 11;

    private static List<SimWaypoint> straightRoute() {
        return List.of(
                SimWaypoint.of(new SimPos(0, 0, 0), 0.0D, SimMotion.WALK),
                SimWaypoint.of(new SimPos(1, 0, 0), 0.0D, SimMotion.WALK));
    }

    private static SimulationRunner.StepOutcome outcome(int index, String type, SimGesture gesture,
                                                        int startTick, int ticks, List<SimWaypoint> route,
                                                        String heldItem) {
        return new SimulationRunner.StepOutcome(index, BuildingJsonDocument.StepPath.root(index), type, "",
                startTick, ticks, SimulationRunner.Status.DONE, "", route, Set.of(), heldItem, gesture);
    }

    private static SimulationRunner.Result result() {
        List<SimulationRunner.StepOutcome> outcomes = new ArrayList<>();
        outcomes.add(outcome(0, "move_to", SimGesture.WALK, 0, WALK_TICKS, straightRoute(), ""));
        outcomes.add(outcome(1, "set_held_item", SimGesture.IDLE, WALK_TICKS, 2, List.of(),
                "minecraft:iron_pickaxe"));
        outcomes.add(outcome(2, "use_item", SimGesture.SWING, WALK_TICKS + 2, 5, List.of(),
                "minecraft:iron_pickaxe"));
        return new SimulationRunner.Result(List.copyOf(outcomes), new SimInventory(), "minecraft:iron_pickaxe",
                WALK_TICKS + 7, 3, -1, Set.of(), List.of(), Map.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    private static SimulationSession loaded() {
        SimulationSession session = new SimulationSession();
        session.load(result());
        return session;
    }

    @Test
    void 初始停在第0步且未播放() {
        SimulationSession session = loaded();
        assertEquals(0, session.index());
        assertFalse(session.playing());
        assertTrue(session.hasResult());
        assertEquals(3, session.stepCount());
    }

    @Test
    void 未播放时推进没有任何变化() {
        SimulationSession session = loaded();
        assertFalse(session.advance(10));
        assertEquals(0, session.index());
        assertEquals(0, session.tickInStep());
    }

    @Test
    void 起步精确站在途径点上不凭空前跳() {
        SimulationSession session = loaded();
        SimulationSession.Pose start = session.pose();
        assertEquals(0.5D, start.x(), 1.0E-6D);
        assertEquals(0.0D, start.y(), 1.0E-6D);
        assertEquals(0.5D, start.z(), 1.0E-6D);
        assertEquals(0.0D, start.walkedDistance(), 1.0E-6D);
    }

    @Test
    void 坐标沿折线按速度连续插值而不是一格一格跳() {
        SimulationSession session = loaded();
        session.play();

        // 1 格 / 10 个间隔 ⇒ 每 tick 0.1 格。
        session.advance(1);
        assertEquals(0.6D, session.pose().x(), 1.0E-6D);

        session.advance(4);
        assertEquals(1.0D, session.pose().x(), 1.0E-6D);

        // 第 10 个 tick（tickInStep = ticks-1 = 10）恰好站在终点，此时还没切步骤。
        session.advance(5);
        assertEquals(0, session.index());
        assertEquals(1.5D, session.pose().x(), 1.0E-6D);
        assertEquals(1.0D, session.pose().walkedDistance(), 1.0E-6D);
    }

    @Test
    void 朝向按当前这一段的方向给() {
        SimulationSession session = loaded();
        session.play();
        // 朝 +X 走 ⇒ Minecraft 的 yaw = -90（0 = +Z，90 = -X）。
        assertEquals(-90.0F, session.pose().yaw(), 1.0E-3F);
    }

    @Test
    void 走进下一步后仍停在上一段的终点() {
        SimulationSession session = loaded();
        session.play();
        session.advance(WALK_TICKS);
        assertEquals(1, session.index());
        assertEquals(0, session.tickInStep());
        SimulationSession.Pose anchor = session.pose();
        assertEquals(1.5D, anchor.x(), 1.0E-6D);
        assertEquals(1.0D, anchor.walkedDistance(), 1.0E-6D);
        assertEquals(SimGesture.IDLE, anchor.gesture(), "第 1 步是设手持物");
    }

    @Test
    void 站着做事时里程不再增长() {
        SimulationSession session = loaded();
        session.play();
        session.advance(WALK_TICKS);
        double afterWalk = session.pose().walkedDistance();
        session.advance(1);
        assertEquals(1, session.index());
        assertEquals(afterWalk, session.pose().walkedDistance(), 1.0E-6D);
        assertEquals(1.5D, session.pose().x(), 1.0E-6D);
    }

    @Test
    void 动作步骤也带上该播的动画与手持物() {
        SimulationSession session = loaded();
        session.play();
        session.advance(WALK_TICKS + 2);
        assertEquals(2, session.index());
        assertEquals(SimGesture.SWING, session.pose().gesture());
        assertEquals("minecraft:iron_pickaxe", session.pose().heldItem());
    }

    @Test
    void 起跳段补抛物线且落点不变() {
        List<SimWaypoint> route = List.of(
                SimWaypoint.of(new SimPos(0, 0, 0), 0.0D, SimMotion.WALK),
                SimWaypoint.of(new SimPos(1, 1, 0), 1.0D, SimMotion.JUMP));
        SimulationRunner.StepOutcome jump = outcome(0, "move_to", SimGesture.WALK, 0, 10, route, "");
        SimulationSession session = new SimulationSession();
        session.load(new SimulationRunner.Result(List.of(jump), new SimInventory(), "", 10, 1, -1, Set.of(),
                List.of(), Map.of(), List.of(), List.of(), Map.of(), Map.of()));
        session.play();

        double peak = 0.0D;
        for (int i = 0; i < 10; i++) {
            session.advance(1);
            peak = Math.max(peak, session.pose().y());
        }
        // 线性插值到 y=1 时中间只会落在 0~1；抛物线要把它抬到 1 以上。
        assertTrue(peak > 1.0D, "起跳应当是一段抛物线，实际峰值 " + peak);
        assertEquals(1.0D, session.pose().y(), 1.0E-6D, "落点必须仍然精确等于途径点");
    }

    @Test
    void 播到最后一步就停住() {
        SimulationSession session = loaded();
        session.play();
        session.advance(200);
        assertEquals(2, session.index());
        assertTrue(session.finished());
        assertFalse(session.playing());
        session.advance(10);
        assertEquals(2, session.index());
    }

    @Test
    void 重播回到第一步并立即播放() {
        SimulationSession session = loaded();
        session.play();
        session.advance(200);
        session.replay();
        assertEquals(0, session.index());
        assertEquals(0, session.tickInStep());
        assertTrue(session.playing());
        assertFalse(session.finished());
        assertEquals(0, session.elapsedTicks());
        assertEquals(0.5D, session.pose().x(), 1.0E-6D);
    }

    @Test
    void 暂停后推进无效恢复播放后继续() {
        SimulationSession session = loaded();
        session.play();
        session.advance(1);
        session.pause();
        assertFalse(session.advance(5));
        assertEquals(1, session.tickInStep());
        session.play();
        session.advance(1);
        assertEquals(2, session.tickInStep());
    }

    @Test
    void 跳到任意步骤并清零步内进度() {
        SimulationSession session = loaded();
        session.seek(2);
        assertEquals(2, session.index());
        assertEquals(0, session.tickInStep());
        // 第 2 步没有路线，位置沿用上一条路线的终点。
        assertEquals(1.5D, session.pose().x(), 1.0E-6D);
    }

    @Test
    void 累计tick按步骤耗时求和() {
        SimulationSession session = loaded();
        session.play();
        session.advance(WALK_TICKS);
        assertEquals(1, session.index());
        assertEquals(WALK_TICKS, session.elapsedTicks());
        assertEquals(WALK_TICKS + 7, session.totalTicks());
    }

    @Test
    void 单步前进把当前步骤直接走完() {
        SimulationSession session = loaded();
        session.skipCurrentStep();
        assertEquals(1, session.index());
        assertEquals(0, session.tickInStep());
        assertEquals(1.5D, session.pose().x(), 1.0E-6D);
    }

    @Test
    void 空结果不会崩也不会播() {
        SimulationSession session = new SimulationSession();
        assertFalse(session.hasResult());
        session.play();
        assertFalse(session.playing());
        assertFalse(session.advance(1));
        assertNull(session.current());
        assertNull(session.pose());
        assertEquals(0, session.stepCount());
        assertEquals(0, session.elapsedTicks());
        assertTrue(session.focus().isEmpty());
        assertEquals("", session.heldItem());
    }
}
