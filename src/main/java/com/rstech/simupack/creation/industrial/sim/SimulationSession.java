package com.rstech.simupack.creation.industrial.sim;

import java.util.List;

/**
 * 播放状态机：负责「第几步、这一步走到第几 tick、在不在播」，并把这两件事换算成
 * <b>市民此刻站在世界的哪个连续坐标上</b>。
 *
 * <p><b>为什么走位要放在这里而不是渲染层：</b>旧版是「一个 tick 跳一格」，
 * 市民看起来像瞬移；而「沿折线按速度插值」是纯粹的数学，放在内核里可以脱机测试。
 * 渲染层拿到的已经是一个带朝向、带行走里程、带当前动画意图的
 * {@link Pose}，只负责画。
 *
 * <p>规则：
 * <ul>
 *   <li>游标只往前走，不存在「重算路线把游标清零」这回事；</li>
 *   <li>步内进度 {@code (tickInStep + 1) / ticks}，因此最后 1 tick 正好落在途径点上；</li>
 *   <li>起跳段（{@link SimMotion#JUMP}）自动补一段抛物线，不然会变成爬坡；</li>
 *   <li>{@code walkedDistance} 是全流程累计里程，走路的摆腿相位由它驱动 ——
 *       相位跟着<t>距离</t>而不是 tick 走，停下时腿才会停。</li>
 * </ul>
 */
public final class SimulationSession {

    /**
     * 市民此刻的状态快照。
     *
     * @param yaw            朝向（Minecraft 约定：0 = +Z，90 = -X）
     * @param walkedDistance 从流程开始累计走过的格数
     */
    public record Pose(double x, double y, double z, float yaw, double walkedDistance,
                       SimMotion motion, SimGesture gesture, String heldItem) {
    }

    private SimulationRunner.Result result;
    private int index;
    private int tickInStep;
    private boolean playing;
    private boolean finished;

    public void load(SimulationRunner.Result loaded) {
        this.result = loaded;
        this.index = 0;
        this.tickInStep = 0;
        this.playing = false;
        this.finished = false;
    }

    public SimulationRunner.Result result() {
        return result;
    }

    public boolean hasResult() {
        return result != null && !result.outcomes().isEmpty();
    }

    public boolean playing() {
        return playing;
    }

    public boolean finished() {
        return finished;
    }

    public int index() {
        return index;
    }

    public int stepCount() {
        return result == null ? 0 : result.outcomes().size();
    }

    public int tickInStep() {
        return tickInStep;
    }

    public SimulationRunner.StepOutcome current() {
        return result == null ? null : result.outcome(index);
    }

    /** 从开始到当前这一刻累计的 tick 数。 */
    public int elapsedTicks() {
        if (result == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < index && i < result.outcomes().size(); i++) {
            total += Math.max(0, result.outcomes().get(i).ticks());
        }
        return total + tickInStep;
    }

    public int totalTicks() {
        return result == null ? 0 : result.totalTicks();
    }

    public void play() {
        if (hasResult() && !finished) {
            playing = true;
        }
    }

    public void pause() {
        playing = false;
    }

    public void toggle() {
        if (playing) {
            pause();
        } else {
            play();
        }
    }

    public void replay() {
        if (result == null) {
            return;
        }
        index = 0;
        tickInStep = 0;
        finished = false;
        playing = true;
    }

    /** 跳到某一步的开头。 */
    public void seek(int step) {
        if (result == null) {
            return;
        }
        index = Math.max(0, Math.min(step, Math.max(0, result.outcomes().size() - 1)));
        tickInStep = 0;
        finished = false;
    }

    /** 单步前进：把当前这一步直接走完。 */
    public void skipCurrentStep() {
        if (!hasResult()) {
            return;
        }
        SimulationRunner.StepOutcome current = current();
        tickInStep = current == null ? 0 : Math.max(1, current.ticks());
        step();
    }

    /**
     * 推进若干 tick。
     *
     * @return 状态是否发生了变化（界面据此决定要不要重画）
     */
    public boolean advance(int ticks) {
        if (!playing || !hasResult()) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < Math.max(1, ticks); i++) {
            if (finished) {
                break;
            }
            SimulationRunner.StepOutcome current = current();
            if (current == null) {
                playing = false;
                break;
            }
            tickInStep++;
            changed = true;
            if (tickInStep >= Math.max(1, current.ticks())) {
                step();
            }
        }
        return changed;
    }

    private void step() {
        tickInStep = 0;
        if (result == null) {
            return;
        }
        if (index + 1 >= result.outcomes().size()) {
            SimulationRunner.StepOutcome last = current();
            tickInStep = last == null ? 0 : Math.max(1, last.ticks());
            finished = true;
            playing = false;
            return;
        }
        index++;
    }

    /** 当前步骤要强调的方块。 */
    public java.util.Set<SimPos> focus() {
        SimulationRunner.StepOutcome current = current();
        return current == null ? java.util.Set.of() : current.focus();
    }

    /** 到当前这一步为止手上的物品（最后一条 set_held_item 的结果）。 */
    public String heldItem() {
        if (result == null) {
            return "";
        }
        for (int i = Math.min(index, result.outcomes().size() - 1); i >= 0; i--) {
            String held = result.outcomes().get(i).heldItem();
            if (held != null && !held.isBlank()) {
                return held;
            }
        }
        return "";
    }

    /**
     * 市民此刻的连续坐标与动画状态。
     *
     * @return 还没有任何路线信息时返回 {@code null}，客户端应自行回退到控制盒位置
     */
    public Pose pose() {
        SimulationRunner.StepOutcome current = current();
        if (result == null || current == null) {
            return null;
        }
        double distanceBefore = 0;
        SimWaypoint anchor = null;
        List<SimulationRunner.StepOutcome> outcomes = result.outcomes();
        for (int i = 0; i < index && i < outcomes.size(); i++) {
            List<SimWaypoint> route = outcomes.get(i).route();
            if (!route.isEmpty()) {
                distanceBefore += SimNavigation.length(route);
                anchor = route.get(route.size() - 1);
            }
        }

        List<SimWaypoint> route = current.route();
        if (route.isEmpty()) {
            // 站着做事：沿用上一条路线的终点，朝向也沿用，不然会每帧归零、看起来在原地打转。
            if (anchor == null) {
                return null;
            }
            return new Pose(anchor.x(), anchor.y(), anchor.z(), yawBefore(index),
                    distanceBefore, SimMotion.WALK, current.gesture(), heldItem());
        }

        double progress = progress(current);
        Sample sample = sample(route, progress);
        if (sample == null) {
            return null;
        }
        return new Pose(sample.x, sample.y, sample.z, sample.yaw,
                distanceBefore + sample.distance, sample.motion, current.gesture(), heldItem());
    }

    /**
     * 步内进度。
     *
     * <p>{@code tickInStep} 在这一步里只会取到 {@code 0 .. ticks-1}（到 {@code ticks} 就切下一步了），
     * 所以除以 {@code ticks - 1} 才能让<b>首尾都落在途径点上</b>：
     * 起始 tick 恰好站在起点、最后一个 tick 恰好站在终点。用 {@code /ticks} 的话
     * 每进入一个移动步骤都会先凭空往前蹦 1/ticks 段 —— 看着就是「起步一顿」。
     */
    private double progress(SimulationRunner.StepOutcome outcome) {
        int ticks = Math.max(1, outcome.ticks());
        return ticks <= 1 ? 1.0D : Math.min(1.0D, (double) tickInStep / (double) (ticks - 1));
    }

    private record Sample(double x, double y, double z, float yaw, double distance, SimMotion motion) {
    }

    /** 沿折线按里程取点；起跳段补一段抛物线。 */
    private static Sample sample(List<SimWaypoint> route, double progress) {
        if (route.isEmpty()) {
            return null;
        }
        if (route.size() == 1) {
            SimWaypoint only = route.get(0);
            return new Sample(only.x(), only.y(), only.z(), 0.0F, 0.0D, only.motion());
        }
        double total = SimNavigation.length(route);
        double target = total * progress;
        double walked = 0;
        for (int i = 1; i < route.size(); i++) {
            SimWaypoint from = route.get(i - 1);
            SimWaypoint to = route.get(i);
            double segment = from.distanceTo(to);
            boolean last = i == route.size() - 1;
            if (walked + segment >= target || last) {
                double t = segment <= 1.0E-9D ? 1.0D : Math.max(0.0D, Math.min(1.0D, (target - walked) / segment));
                double x = from.x() + (to.x() - from.x()) * t;
                double y = from.y() + (to.y() - from.y()) * t;
                double z = from.z() + (to.z() - from.z()) * t;
                if (to.motion() == SimMotion.JUMP) {
                    // 起跳不是爬坡：补一段抛物线，落点仍然精确等于途径点。
                    y += Math.sin(Math.PI * t) * 0.4D;
                }
                return new Sample(x, y, z, yawOf(from, to), walked + segment * t, to.motion());
            }
            walked += segment;
        }
        return null;
    }

    /** 走到第 {@code index} 步之前，最后一次移动的方向；没有就返回 0（朝 +Z）。 */
    private float yawBefore(int index) {
        if (result == null) {
            return 0.0F;
        }
        List<SimulationRunner.StepOutcome> outcomes = result.outcomes();
        for (int i = Math.min(index, outcomes.size()) - 1; i >= 0; i--) {
            List<SimWaypoint> route = outcomes.get(i).route();
            if (route.size() >= 2) {
                return yawOf(route.get(route.size() - 2), route.get(route.size() - 1));
            }
        }
        return 0.0F;
    }

    /** Minecraft 的 yaw 约定：0 = 朝 +Z，90 = 朝 -X。 */
    private static float yawOf(SimWaypoint from, SimWaypoint to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        if (dx == 0.0D && dz == 0.0D) {
            return 0.0F;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
