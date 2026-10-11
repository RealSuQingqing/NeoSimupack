package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rstech.simupack.creation.json.BuildingJsonDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一份配方展开成<b>扁平</b>的动作列表。
 *
 * <p>这是整个仿真最要紧的一步，也是旧版错得最离谱的一步。
 * NSUK 在 {@code IndustrialDefinitionLoader.parseSteps} 里就把 {@code repeat} 展开了：
 * 运行时只有一张扁平的 {@code steps} 表，{@code currentStep} 是它的线性下标，
 * 展开上限 256 条。旧版仿真器却在<b>运行时</b>递归展开，把展开结果塞进
 * {@code List<StepResult>}，同时把 {@code repeat} 节点本身漏掉 ——
 * 于是这个列表的下标<b>再也回不到原始 JSON 的下标</b>，
 * 而面板的时间轴又是拿 {@code frame} 去索引原始 {@code steps[]} 的。
 * 两边从一开始就不是同一张表：时间轴第 N 行和场景里高亮的方块可以毫无关系。
 *
 * <p>这里严格照抄 NSUK 的展开规则：
 * <ul>
 *   <li>{@code repeat}/{@code loop} 的重复次数 = 有 {@code positions}（含 {@code pos}）时取点位个数，
 *       否则取 {@code count}；两者都拿不到就整段丢弃；</li>
 *   <li>每次迭代把第 i 个点位以 {@code pos} 的形式补进子步骤 —— <b>只补没有点位字段的子步骤</b>；</li>
 *   <li>总数上限 {@value #MAX_STEPS}，超出的部分直接截断（旧版没有上限，
 *       {@code count: 2000000000} 能当场把客户端卡死）。</li>
 * </ul>
 */
public final class SimulationPlan {

    /** 与 NSUK 的 256 条展开上限一致。 */
    public static final int MAX_STEPS = 256;

    /** JSON 嵌套保护：NSUK 靠逐层 parse 调用间接限制，这里显式挡一道。 */
    private static final int MAX_DEPTH = 32;

    /**
     * 展开后的一条步骤。
     *
     * @param step       归一化后的步骤
     * @param sourcePath 在<b>原始</b> JSON 里的位置（用于回链到动作组编辑器、定位高亮）
     * @param iteration  repeat 的第几次迭代；非 repeat 为 -1
     */
    public record Entry(SimStepSpec step, BuildingJsonDocument.StepPath sourcePath, int iteration) {

        public SimStepSpec spec() {
            return step;
        }

        public boolean fromRepeat() {
            return iteration >= 0;
        }
    }

    private SimulationPlan() {
    }

    /** 展开一份配方。传入的必须是配方对象（含 {@code steps} 数组），不是整份建筑 JSON。 */
    public static List<Entry> of(JsonObject recipe) {
        List<Entry> entries = new ArrayList<>();
        if (recipe == null || !recipe.has("steps") || !recipe.get("steps").isJsonArray()) {
            return List.copyOf(entries);
        }
        appendArray(entries, recipe.getAsJsonArray("steps"), null, -1, 0);
        return List.copyOf(entries);
    }

    /** 展开结果里是否有被截断的步骤（界面上要提示，不能假装配方就这么长）。 */
    public static boolean truncated(List<Entry> entries) {
        return entries.size() >= MAX_STEPS;
    }

    private static void appendArray(List<Entry> out, JsonArray array, BuildingJsonDocument.StepPath parent,
                                    int iteration, int depth) {
        if (array == null || depth > MAX_DEPTH) {
            return;
        }
        for (int index = 0; index < array.size() && out.size() < MAX_STEPS; index++) {
            JsonObject object = SimJson.asObject(array.get(index));
            BuildingJsonDocument.StepPath path = parent == null
                    ? BuildingJsonDocument.StepPath.root(index)
                    : parent.down(index);
            if (object == null) {
                // 保留占位：时间轴必须能指着一条「这里是坏的」的记录，
                // 而不是静默丢掉后让后面的行整体错位。
                out.add(new Entry(new SimStepSpec(new JsonObject()), path, iteration));
                continue;
            }
            appendStep(out, object, path, iteration, depth);
        }
    }

    private static void appendStep(List<Entry> out, JsonObject object, BuildingJsonDocument.StepPath path,
                                   int iteration, int depth) {
        if (out.size() >= MAX_STEPS) {
            return;
        }
        String rawType = SimJson.string(object, "type", "");
        if ("repeat".equalsIgnoreCase(rawType) || "loop".equalsIgnoreCase(rawType)) {
            appendRepeat(out, object, path, depth);
            return;
        }
        out.add(new Entry(new SimStepSpec(object.deepCopy()), path, iteration));
    }

    private static void appendRepeat(List<Entry> out, JsonObject repeat, BuildingJsonDocument.StepPath path, int depth) {
        JsonArray nested = SimJson.arrayAny(repeat, "steps");
        if (nested == null || nested.isEmpty()) {
            // NSUK 记 missing_repeat_steps 后**整段丢弃、不产生任何步骤**
            // （IndustrialDefinitionLoader:327-336）。这里照做：插占位步骤会让时间轴比 NSUK 多一行、
            // 后面所有下标整体错位。
            return;
        }
        List<SimPos> positions = SimJson.positions(repeat);
        int repetitions = !positions.isEmpty() ? positions.size() : Math.max(0, SimJson.integer(repeat, "count", 0));
        if (repetitions <= 0) {
            // 同上：NSUK 记 missing_repeat_count 后整段丢弃。
            return;
        }
        for (int repeatIndex = 0; repeatIndex < repetitions && out.size() < MAX_STEPS; repeatIndex++) {
            SimPos position = positions.isEmpty() ? null : positions.get(repeatIndex);
            for (int nestedIndex = 0; nestedIndex < nested.size() && out.size() < MAX_STEPS; nestedIndex++) {
                JsonObject child = SimJson.asObject(nested.get(nestedIndex));
                BuildingJsonDocument.StepPath childPath = path.down(nestedIndex);
                if (child == null) {
                    out.add(new Entry(new SimStepSpec(new JsonObject()), childPath, repeatIndex));
                    continue;
                }
                JsonObject expanded = child.deepCopy();
                // 循环位置的注入条件**照 NSUK 原样**：只看子步骤有没有写 pos/positions，
                // point / container **不参与判断**（IndustrialDefinitionLoader:346）。
                //
                // 那「move_to_container 被奶池坐标带偏」怎么解释？不是注入错了，是**目标解析**错了：
                // NSUK 里方块类步骤 positions 优先、point 兜底；move_to/look_at 只认 point；
                // 容器类只用容器坐标。我们原先在客户端把三类坐标并集后交给内核取最近点，
                // 才让注入的循环坐标污染了容器步骤（见 SIMUPACK_SIMULATION_REVIEW.md 23.5）。
                if (position != null && !expanded.has("pos") && !expanded.has("positions")) {
                    expanded.add("pos", (JsonElement) SimJson.positionArray(position));
                }
                appendStep(out, expanded, childPath, repeatIndex, depth + 1);
            }
        }
    }
}
