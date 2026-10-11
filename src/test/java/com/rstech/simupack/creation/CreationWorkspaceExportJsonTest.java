package com.rstech.simupack.creation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 导出时必须剥掉编辑器私有元数据，发布包保持干净。 */
class CreationWorkspaceExportJsonTest {

    @Test
    void stripsEditorLayoutButKeepsEverythingElse() {
        String config = """
                {"id":"b","recipes":[],"_simupackEditor":{"order":["r"],"positions":{}},
                 "points":{"p":{"positions":[[1,2,3]]}},"futureModKey":{"a":1}}
                """;
        String exported = CreationWorkspace.exportJson(config);
        JsonObject object = JsonParser.parseString(exported).getAsJsonObject();
        assertFalse(object.has(BuildingJsonDocument.EDITOR_LAYOUT_KEY));
        assertTrue(object.has("points"));
        assertTrue(object.has("futureModKey"));
        assertEquals(1, object.getAsJsonObject("futureModKey").get("a").getAsInt());
    }

    @Test
    void blankOrBrokenInputIsReturnedUnchanged() {
        assertEquals("", CreationWorkspace.exportJson(""));
        assertEquals("   ", CreationWorkspace.exportJson("   "));
        // 坏 JSON 原样返回：宁可多带一个键，也不能把配置换成空内容。
        assertEquals("{ 坏掉", CreationWorkspace.exportJson("{ 坏掉"));
    }
}
