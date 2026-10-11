package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonObject;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作列表展开（{@code repeat} 扁平化）的测试。
 *
 * <p>这一组断言存在的理由：旧版仿真器把 repeat 在运行时展开、又漏掉 repeat 节点本身，
 * 导致「结果列表的下标」和「原始 JSON steps 的下标」错位，
 * 而界面时间轴正是拿前者去索引后者。这里把「展开后下标 == 原始下标」这件事钉死。
 */
class SimulationPlanTest {

    private static List<SimulationPlan.Entry> plan(String json) {
        BuildingJsonDocument document = BuildingJsonDocument.parse(json);
        assertFalse(document.recipes().isEmpty(), "测试 JSON 里必须有一个配方");
        return SimulationPlan.of(document.recipes().get(0));
    }

    @Test
    void 普通步骤按顺序展开且下标与原始数组一致() {
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"move_to","point":"p1"},
                  {"type":"use_item","ticks":20},
                  {"type":"set_status","statusKey":"k"}]}]}
                """);

        assertEquals(3, plan.size());
        assertEquals("move_to", plan.get(0).spec().typeId());
        assertEquals("use_item", plan.get(1).spec().typeId());
        assertEquals(BuildingJsonDocument.StepPath.of(2), plan.get(2).sourcePath());
        assertTrue(plan.stream().noneMatch(SimulationPlan.Entry::fromRepeat));
    }

    @Test
    void repeat按count展开且每个子步骤记录迭代序号() {
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"repeat","count":3,"steps":[
                    {"type":"use_item","ticks":5},
                    {"type":"set_status"}]}]}]}
                """);

        assertEquals(6, plan.size());
        assertEquals(0, plan.get(0).iteration());
        assertEquals(0, plan.get(1).iteration());
        assertEquals(1, plan.get(2).iteration());
        assertEquals(2, plan.get(5).iteration());
        // 子步骤回链到原始 JSON 的嵌套位置：父下标 0、子下标 1。
        assertEquals(BuildingJsonDocument.StepPath.of(0, 1), plan.get(1).sourcePath());
    }

    @Test
    void repeat按positions展开并把点位补进没有点位的子步骤() {
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"repeat","positions":[[1,2,3],[4,5,6]],"steps":[
                    {"type":"move_to"}]}]}]}
                """);

        assertEquals(2, plan.size());
        assertEquals(new SimPos(1, 2, 3), plan.get(0).spec().positions().get(0));
        assertEquals(new SimPos(4, 5, 6), plan.get(1).spec().positions().get(0));
        // 原始 JSON 不能被改动。
        assertEquals(0, plan.get(0).iteration());
        assertEquals(1, plan.get(1).iteration());
    }

    @Test
    void 子步骤自带点位时不被repeat补的点位覆盖() {
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"repeat","positions":[[1,2,3],[4,5,6]],"steps":[
                    {"type":"move_to","pos":[9,9,9]}]}]}]}
                """);

        assertEquals(2, plan.size());
        assertEquals(new SimPos(9, 9, 9), plan.get(0).spec().positions().get(0));
        assertEquals(new SimPos(9, 9, 9), plan.get(1).spec().positions().get(0));
    }

    @Test
    void 嵌套repeat递归展开() {
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"repeat","count":2,"steps":[
                    {"type":"repeat","count":3,"steps":[{"type":"use_item","ticks":1}]},
                    {"type":"set_status"}]}]}]}
                """);

        // 2 次外层 ×（3 个内层 + 1 个 set_status）= 8
        assertEquals(8, plan.size());
        // 内层 repeat 用 count 展开，所以 3 个 use_item 的迭代序号是 0/1/2。
        assertEquals(0, plan.get(0).iteration());
        assertEquals(1, plan.get(1).iteration());
        assertEquals(2, plan.get(2).iteration());
        assertEquals("set_status", plan.get(3).spec().typeId());
    }

    @Test
    void 展开数量被限制在256条以内() {
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"repeat","count":100000,"steps":[{"type":"use_item","ticks":1}]}]}]}
                """);

        assertEquals(SimulationPlan.MAX_STEPS, plan.size());
        assertTrue(SimulationPlan.truncated(plan));
    }

    @Test
    void 缺少count和positions的repeat整段丢弃() {
        // NSUK 记 missing_repeat_count 之后**不产生任何步骤**（IndustrialDefinitionLoader:327-336）——
        // 早先我们插一条「坏步骤」占位，那会让时间轴比 NSUK 多一行、后面所有下标整体错位。
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"repeat","steps":[{"type":"use_item"}]},
                  {"type":"set_status"}]}]}
                """);

        assertEquals(1, plan.size(), "缺失次数的 repeat 不该留下任何步骤");
        assertEquals("set_status", plan.get(0).spec().typeId());
    }

    @Test
    void 未知类型仍然占一行而不是被丢掉() {
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"没有这个类型"},
                  {"type":"set_status"}]}]}
                """);

        assertEquals(2, plan.size());
        assertFalse(plan.get(0).spec().known());
        assertEquals("没有这个类型", plan.get(0).spec().typeId());
        assertEquals("set_status", plan.get(1).spec().typeId());
    }

    @Test
    void 旧别名loop与旧容器别名都能识别() {
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"move_to_chest","container":"箱"},
                  {"type":"store_item","container":"箱","item":"minecraft:stone","count":2}]}]}
                """);

        assertEquals("move_to_container", plan.get(0).spec().typeId());
        assertEquals("insert_item", plan.get(1).spec().typeId());
        assertEquals("minecraft:stone", plan.get(1).spec().itemSpec().key());
        assertEquals(2, plan.get(1).spec().count());
    }

    @Test
    void 对齐真实NSUK展开规则的边界情况() {
        // positions 存在时忽略 count，这是 NSUK 的写法，抄错就会多跑或少跑几轮。
        List<SimulationPlan.Entry> plan = plan("""
                {"recipes":[{"id":"r","steps":[
                  {"type":"repeat","count":99,"positions":[[0,0,0]],"steps":[{"type":"use_item"}]}]}]}
                """);
        assertEquals(1, plan.size());
    }

    @Test
    void 没有steps数组的配方展开为空() {
        BuildingJsonDocument document = BuildingJsonDocument.parse("""
                {"recipes":[{"id":"r"}]}
                """);
        JsonObject recipe = document.recipes().get(0);
        assertTrue(SimulationPlan.of(recipe).isEmpty());
        assertTrue(SimulationPlan.of(null).isEmpty());
    }
}
