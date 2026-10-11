package com.rstech.simupack.creation.industrial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.json.BuildingJsonDocument;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑器布局元数据（动作链顺序与摆放）的回归测试。
 *
 * <p>它放在建筑 JSON 的私有键里，用来支持「撤销拖动」和「重开建筑记住位置与空链」。
 * 关键约束有两条：<b>不能影响校验、导出时必须剥掉</b>。
 */
class EditorLayoutJsonTest {

    private static final String CONFIG = """
            {
              "id": "b",
              "points": { "p": { "positions": [[1,2,3]] } },
              "containers": { "c": { "positions": [[4,5,6]] } },
              "recipes": [ { "id": "r1", "steps": [ { "type": "set_status", "statusText": "x" } ] } ],
              "_simupackEditor": {
                "order": ["r1"],
                "positions": { "r1": [12.5, 40.0] },
                "futureExtra": { "keep": true }
              },
              "someModKey": 7
            }
            """;

    @Test
    void layoutKeySurvivesRoundTripWithoutTouchingOtherUnknownKeys() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(CONFIG);
        JsonObject layout = model.editorLayout(false);
        assertNotNull(layout);
        assertEquals(1, layout.getAsJsonArray("order").size());
        assertEquals(12.5F, layout.getAsJsonObject("positions")
                .getAsJsonArray("r1").get(0).getAsFloat(), 1.0E-6F);
        // 私有键内部将来加东西也要原样保留（不能因为不认识就丢掉）。
        assertTrue(layout.getAsJsonObject("futureExtra").get("keep").getAsBoolean());

        BuildingJsonDocument again = BuildingJsonDocument.parse(model.toJsonString());
        assertEquals(model.toJsonString(), again.toJsonString());
        assertEquals(7, again.root().get("someModKey").getAsInt());
    }

    @Test
    void layoutKeyNeverProducesValidationIssuesAndIsNotRewritten() {
        BuildingJsonDocument model = BuildingJsonDocument.parse(CONFIG);
        String before = model.toJsonString();
        var issues = model.validate(BuildingJsonDocument.StructureBounds.UNKNOWN);
        assertFalse(issues.stream().anyMatch(i -> i.location().contains("_simupackEditor")),
                issues.toString());
        // 只读校验不得顺手改配置。
        assertEquals(before, model.toJsonString());
    }

    @Test
    void editorLayoutIsCreatedOnDemandAndKeptInOrder() {
        BuildingJsonDocument model = BuildingJsonDocument.parse("{\"recipes\":[]}");
        assertFalse(model.root().has(BuildingJsonDocument.EDITOR_LAYOUT_KEY));
        JsonObject created = model.editorLayout(true);
        created.add("order", new com.google.gson.JsonArray());
        assertTrue(model.root().has(BuildingJsonDocument.EDITOR_LAYOUT_KEY));
        assertEquals(0, model.editorLayout(false).getAsJsonArray("order").size());
    }
}
