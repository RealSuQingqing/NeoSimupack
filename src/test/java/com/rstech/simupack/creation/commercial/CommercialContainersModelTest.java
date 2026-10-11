package com.rstech.simupack.creation.commercial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.json.DefinitionIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商业 {@code containers} 段的读写与校验。
 *
 * <p>格式依据 NSUK {@code CommercialDefinitionLoader.parseContainers/parsePositions}：
 * 单个 {@code pos} 与数组 {@code positions} 都接受，{@code type} 缺省
 * {@code structure_pos}，单个容器上限 {@code MAX_POSITIONS}。
 */
class CommercialContainersModelTest {

    private static CommercialContainersModel wrap(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return CommercialContainersModel.wrap(root);
    }

    private static JsonObject root(CommercialContainersModel model) {
        return model.containers(false);
    }

    private static Set<String> codes(List<DefinitionIssue> issues) {
        return issues.stream().map(DefinitionIssue::code).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("读取：pos 单个与 positions 数组都要收；type 缺省是 structure_pos")
    void readsBothPosAndPositions() {
        CommercialContainersModel model = wrap("""
                { "containers": {
                    "input":  { "type": "structure_pos", "positions": [[2,1,4],[3,1,4]] },
                    "output": { "pos": [6,1,4] },
                    "plain":  { "positions": [[1,0,1]] }
                } }
                """);
        assertEquals(List.of("input", "output", "plain"), model.ids());
        assertEquals(2, model.positions("input").size());
        assertArrayEquals(new int[]{2, 1, 4}, model.positions("input").get(0));
        assertArrayEquals(new int[]{6, 1, 4}, model.positions("output").get(0));
        // 缺 type 时按 NSUK 的缺省值处理
        assertEquals(CommercialContainersModel.DEFAULT_TYPE,
                CommercialContainersModel.typeOf(model.container("plain")));
    }

    @Test
    @DisplayName("读取：不是长度≥3 的数字数组一律丢弃（与 NSUK parsePositionArray 一致）")
    void rejectsMalformedPositions() {
        CommercialContainersModel model = wrap("""
                { "containers": { "c": { "positions": [
                    [1,2,3], [1,2], ["a",2,3], [1,2,3,4], null, [7,8,9] ] } } }
                """);
        List<int[]> positions = model.positions("c");
        // [1,2] 与 ["a",2,3] 与 null 被丢；[1,2,3,4] 收前三个分量
        assertEquals(3, positions.size());
        assertArrayEquals(new int[]{1, 2, 3}, positions.get(0));
        assertArrayEquals(new int[]{1, 2, 3}, positions.get(1));
        assertArrayEquals(new int[]{7, 8, 9}, positions.get(2));
    }

    @Test
    @DisplayName("写入：新建绝不覆盖已有 id；改名冲突时拒绝；坐标去重且统一成 positions 一种写法")
    void writesAreNonDestructiveAndNormaliseShape() {
        CommercialContainersModel model = wrap("""
                { "containers": { "input": { "pos": [1,1,1] } } }
                """);
        // 覆盖已有 id 必须被拒绝
        assertNull(model.addContainer("input", List.of()));
        // 新建成功，id 会被 trim
        assertNotNull(model.addContainer("  output  ", List.of(new int[]{4, 4, 4})));
        assertEquals(List.of("input", "output"), model.ids());
        // 改名到已存在的 id 必须被拒绝，原条目不动
        assertFalse(model.renameContainer("output", "input"));
        assertEquals(List.of("input", "output"), model.ids());
        // 改名成功后顺序不变（不是挪到末尾）
        assertTrue(model.renameContainer("output", "out2"));
        assertEquals(List.of("input", "out2"), model.ids());

        // 往用 pos 写的容器里加坐标：统一成 positions 数组，pos 被移除
        assertTrue(model.addPosition("input", new int[]{2, 1, 1}));
        assertFalse(root(model).getAsJsonObject("input").has("pos"));
        assertEquals(2, model.positions("input").size());
        // 重复坐标拒绝
        assertFalse(model.addPosition("input", new int[]{1, 1, 1}));

        // 改分量 / 删除
        assertTrue(model.setPositionAxis("input", 1, 1, 9));
        assertArrayEquals(new int[]{2, 9, 1}, model.positions("input").get(1));
        assertTrue(model.removePosition("input", 0));
        assertEquals(1, model.positions("input").size());
        assertFalse(model.removePosition("input", 5));
    }

    @Test
    @DisplayName("上限：单个容器最多 64 个坐标，超出部分会被 NSUK 静默截断")
    void enforcesPositionLimit() {
        CommercialContainersModel model = wrap("{ \"containers\": {} }");
        model.addContainer("c", List.of());
        for (int index = 0; index < CommercialContainersModel.MAX_POSITIONS + 5; index++) {
            model.addPosition("c", new int[]{index, 0, 0});
        }
        assertEquals(CommercialContainersModel.MAX_POSITIONS, model.positions("c").size());

        // 文件里本来就超上限时，校验要报出来（那是会被静默截断的数据）
        StringBuilder many = new StringBuilder();
        for (int index = 0; index < CommercialContainersModel.MAX_POSITIONS + 1; index++) {
            if (index > 0) {
                many.append(',');
            }
            many.append('[').append(index).append(",0,0]");
        }
        CommercialContainersModel oversized = wrap(
                "{ \"containers\": { \"c\": { \"positions\": [" + many + "] } } }");
        assertTrue(codes(oversized.validate()).contains("container_too_many_positions"));
    }

    @Test
    @DisplayName("校验：没有坐标的容器等于没写（NSUK 会直接跳过），必须报错误级")
    void emptyContainerIsAnError() {
        CommercialContainersModel model = wrap("""
                { "containers": {
                    "blank":  { "positions": [] },
                    "empty":  { },
                    "broken": { "positions": [[1,2]] },
                    "good":   { "positions": [[1,2,3]] }
                } }
                """);
        List<DefinitionIssue> issues = model.validate();
        assertTrue(codes(issues).contains("container_no_positions"));
        // 只报那三个坏的
        long bad = issues.stream().filter(i -> i.code().equals("container_no_positions")).count();
        assertEquals(3, bad);
        assertTrue(issues.stream().allMatch(i ->
                i.severity() == DefinitionIssue.Severity.ERROR || i.code().startsWith("container_")));
    }

    @Test
    @DisplayName("校验：未知 type 是警告（NSUK 只认 structure_pos），不是错误")
    void unknownTypeIsOnlyAWarning() {
        CommercialContainersModel model = wrap("""
                { "containers": { "c": { "type": "entity_pos", "positions": [[1,2,3]] } } }
                """);
        List<DefinitionIssue> issues = model.validate();
        assertTrue(codes(issues).contains("container_unknown_type"));
        assertTrue(issues.stream()
                .filter(i -> i.code().equals("container_unknown_type"))
                .allMatch(i -> i.severity() == DefinitionIssue.Severity.WARNING));
    }

    @Test
    @DisplayName("没有 containers 段时什么都不报；按需创建不留下空对象")
    void absentSectionIsValid() {
        CommercialContainersModel model = wrap("{ \"offers\": [] }");
        assertTrue(model.isEmpty());
        assertEquals(List.of(), model.validate());
        assertNull(root(model));
        // create=false 不写键
        assertNull(model.containers(false));
        assertFalse(root(model) != null);
        // create=true 才写
        assertNotNull(model.containers(true));
        assertTrue(root(model).isEmpty());
    }

    @Test
    @DisplayName("只读路径不会凭空造出 containers 键：渲染与校验都改不了 JSON")
    void readPathsNeverCreateContainers() {
        // 写盘时是把当前 JSON 整份写出去（EditorPanel.persist），所以渲染/校验这些只读路径
        // 一旦顺手建了键，用户的作品里就会多出一个空容器（然后是两条错误级校验）。
        JsonObject root = JsonParser.parseString("{ \"offers\": [] }").getAsJsonObject();
        CommercialContainersModel model = CommercialContainersModel.wrap(root);
        assertTrue(model.isEmpty());
        assertEquals(List.of(), model.ids());
        assertEquals(List.of(), model.positions("container"));
        assertNull(model.container("container"));
        assertEquals(List.of(), model.validate());
        assertFalse(root.has("containers"), "只读 API 不得在 JSON 里留下 containers");
    }
}
