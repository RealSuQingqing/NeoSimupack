package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.creation.industrial.IndustrialStepType;
import com.rstech.simupack.creation.json.BuildingJsonDocument;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成一份「能测到所有仿真能力」的工业测试建筑（结构 + 配置），并**自检它自洽**。
 *
 * <p>为什么要用代码生成结构：它是二进制 NBT，手写不现实。这里**手写 NBT 二进制**而不是用
 * {@code net.minecraft.nbt} —— 因为测试类路径上没有 Minecraft（这个项目刻意让内核能脱离 MC 跑），
 * 而且手写之后可以做一个**严格的往返校验**（写出去再读回来，和布局逐块比对）。
 *
 * <p>跑一次就得到文件：
 * <pre>
 * run/config/simupack/workspace/my_pack_2/buildings/industry/sim_test.nbt
 * run/config/simupack/workspace/my_pack_2/buildings/industry/sim_test.json
 * </pre>
 */
class SimulationTestBuildingTest {

    private static final Path PROJECT = Path.of("run", "config", "simupack", "workspace", "my_pack_2");
    private static final String BUILDING_ID = "sim_test";
    private static final String CATEGORY = "industry";

    /**
     * 控制箱位置 —— 决定**市民起点**（起点 = 控制箱上方 = {@code (7,2,3)}）。
     *
     * <p>放在空地上只是为了市民一开局就在开阔处走着好看，和刷怪无关：
     * 刷怪的规则照 NSUK，是「建筑外框内随机撒点」，不看控制箱。
     */
    private static final int CONTROL_BOX_X = 7;
    private static final int CONTROL_BOX_Y = 1;
    private static final int CONTROL_BOX_Z = 3;

    @Test
    void 生成测试建筑并自检自洽() throws IOException {
        Path directory = PROJECT.resolve("buildings").resolve(CATEGORY);
        Files.createDirectories(directory);
        Path nbtPath = directory.resolve(BUILDING_ID + ".nbt");
        Path jsonPath = directory.resolve(BUILDING_ID + ".json");

        Map<String, String> layout = layout();
        String configText = configJson();
        writeStructure(nbtPath, layout);
        Files.writeString(jsonPath, configText, StandardCharsets.UTF_8);
        registerInProject(configText, layout);

        // ── 自检 0：建筑必须**注册进工程**，否则界面上根本看不到它 ──────
        //
        // 这是踩过的坑：建筑列表不是扫目录扫出来的，而是来自 project.json 的 buildings 数组；
        // 光把 .nbt/.json 写进文件夹，界面上一条都不会多出来。
        JsonObject project = JsonParser.parseString(
                Files.readString(PROJECT.resolve("project.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject registered = null;
        for (JsonElement element : project.getAsJsonArray("buildings")) {
            JsonObject candidate = element.getAsJsonObject();
            if (BUILDING_ID.equals(candidate.get("id").getAsString())) {
                registered = candidate;
            }
        }
        assertNotNull(registered, "sim_test 必须出现在 project.json 的 buildings 里");
        assertEquals(CATEGORY, registered.get("category").getAsString());
        assertTrue(registered.get("hasStructure").getAsBoolean(), "要标成「有结构」，否则界面显示无结构");
        assertEquals(Set.of("id", "category", "hasStructure", "materials", "sk", "jsonConfig", "pos1", "pos2"),
                registered.keySet(), "注册条目的字段要和工程模型对得上");
        // 注册的 jsonConfig 就是那份配置（界面读它，磁盘上的 .json 是产物）。
        assertEquals(configText, registered.get("jsonConfig").getAsString(),
                "工程里存的配置要和生成的文件一致");
        assertTrue(project.getAsJsonArray("buildings").size() >= 5,
                "不能把原有建筑挤掉（原有 4 个 + sim_test）");

        // ── 自检 1：结构往返一致（写出去再读回来，逐块比对）──────────────
        Map<String, String> readBack = readStructure(nbtPath);
        assertEquals(layout.size(), readBack.size(), "读回来的方块数量应当一致");
        assertEquals(layout, readBack, "读回来的每一块都要和布局一致");
        Set<String> ids = new LinkedHashSet<>(layout.values());
        for (String required : List.of("simukraft:industrial_control_box", "minecraft:chest",
                "minecraft:furnace", "minecraft:oak_planks", "minecraft:dirt",
                "minecraft:stone_bricks")) {
            assertTrue(ids.contains(required), "结构里应当有 " + required + "（缺了对应的仿真就测不到）");
        }
        // 结构里**不该有原木/树叶**：那两样由仿真在建筑外框之外自动生成
        // （见 IndustrialSimulationPanel.ensureStandInEnvironment）。
        // 这条断言同时守着那个开关 —— 结构里一旦混进树，「建筑内整簇否决」就会把它否掉。
        assertFalse(ids.stream().anyMatch(id -> id.endsWith("_log") || id.endsWith("_leaves")),
                "结构里不该有原木/树叶，它们由示意环境在建筑外生成");
        long controlBoxes = ids.stream().filter(id -> id.contains("control_box")).count();
        assertEquals(1, controlBoxes, "控制箱只能有一个（市民起点取它的上方）");

        // ── 自检 1b：刷怪那一层要有足够多能站的格子 ─────────────────────
        //
        // 刷怪的规则照 NSUK：在**建筑外框内均匀随机**取 (x, z)、y = 建筑最低角 + 1，
        // 每只最多试 24 次，挑到「这格空 + 上面也空 + 下面能站」才生成。
        // 所以那一层必须真的有足够的空位 —— 否则羊会凑不够数（NSUK 就是挑不到就不生成）。
        //
        // 注意它**不看控制箱**：控制箱只决定市民起点。
        int baseY = 1;
        int standable = 0;
        for (int x = 0; x <= 11; x++) {
            for (int z = 0; z <= 11; z++) {
                if (!layout.containsKey(key(x, baseY, z)) && !layout.containsKey(key(x, baseY + 1, z))
                        && layout.containsKey(key(x, baseY - 1, z))) {
                    standable++;
                }
            }
        }
        assertTrue(standable >= 6, "刷怪那一层至少要有 count 个能站的格子（配置里是 6 只），实际 " + standable);

        // ── 自检 2：建筑外框外要有地方放示意环境 ────────────────────────
        //
        // 收获的树是仿真**在建筑外框之外**生成的，所以外框外必须空旷：
        // 生成的地面铺在 y = 建筑最低角，四角还要能立起一棵树（树干 4 格 + 树冠）。
        int groundY = 0;
        for (int[] corner : new int[][] {{-4, -4}, {15, 15}, {-4, 15}, {15, -4}}) {
            for (int y = groundY + 1; y <= groundY + 5; y++) {
                assertFalse(layout.containsKey(key(corner[0], y, corner[1])),
                        "建筑外框外的四角必须空着（示意环境要在这儿立树）：" + corner[0] + "," + corner[1]);
            }
        }

        // ── 自检 3：配置自洽 ────────────────────────────────────────────
        JsonObject config = BuildingJsonDocument.parse(configText).root();
        Set<String> points = config.getAsJsonObject("points").keySet();
        Set<String> containers = config.getAsJsonObject("containers").keySet();
        assertEquals(Set.of("input", "output"), containers, "容器应当是 input / output 两个");
        assertTrue(config.getAsJsonObject("workArea").get("radius").getAsInt() > 0,
                "workArea.radius 必须大于 0（等于 0 时成簇收获永远扫不到东西）");
        assertTrue(config.getAsJsonObject("spawnEntity").get("enabled").getAsBoolean(),
                "要测实体动作就得开刷怪");

        int steps = 0;
        Set<String> usedTypes = new LinkedHashSet<>();
        for (JsonElement element : config.getAsJsonArray("recipes").get(0).getAsJsonObject()
                .getAsJsonArray("steps")) {
            JsonObject step = element.getAsJsonObject();
            String type = step.get("type").getAsString();
            steps++;
            usedTypes.add(type);
            assertNotNull(IndustrialStepType.byRawType(type), "步骤类型 " + type + " 必须是已知类型");
            // 引用了不存在的点位/容器 = 游戏里莫名卡住，这里直接拦下。
            if (step.has("point")) {
                assertTrue(points.contains(step.get("point").getAsString()),
                        type + " 引用了不存在的点位 " + step.get("point").getAsString());
            }
            for (String key : List.of("container", "input", "output")) {
                if (step.has(key)) {
                    assertTrue(containers.contains(step.get(key).getAsString()),
                            type + " 引用了不存在的容器 " + step.get(key).getAsString());
                }
            }
        }
        assertTrue(steps >= 20, "这套配方应当覆盖 20 步以上，实际 " + steps);
        for (String required : List.of("place_block", "place_fluid", "require_block",
                "destroy_block", "harvest_block_clusters",
                "shear_entities", "slaughter_entities", "breed_entities",
                "collect_drops", "require_drops", "deposit_carried_items",
                "craft_recipe", "craft_available_recipe", "real_machine_recipe",
                "insert_item", "inspect_container", "require_inputs", "require_output_space",
                "move_to", "move_to_container", "move_to_entity", "look_at", "look_at_container",
                "set_held_item", "use_item", "set_status")) {
            assertTrue(usedTypes.contains(required), "配方里应当覆盖 " + required);
        }
    }

    /**
     * 建筑布局（结构原点 0,0,0）。
     *
     * <pre>
     *  y=0        泥土底板 12×12（树根、建筑、空地的支撑）
     *  y=1        车间地面 5×5 石砖；控制箱（在空地上，见下）
     *  y=2..3     车间后墙两面（x=0 / z=0）
     *  y=2        输入箱(1,2,1) 输出箱(3,2,1) 熔炉(2,2,3) 待破坏的木板(4,2,4)
     *  空地       控制箱 (7,1,3) —— 四周一圈都是空的泥地，刷怪正好铺在这里
     *  树 A       原木 (8,1..4,8)，树冠 y=4..5
     *  树 B       原木 (10,1..2,10)，树冠 y=3
     * </pre>
     *
     * <p><b>控制箱为什么不能放墙角</b>：刷怪是围着「控制箱上方」铺一圈（半径 1 起，每圈 6 只），
     * 放在车间角落的话那一圈会落进墙里 / 半空 —— 羊就刷在墙角了。所以它放在车间与树林之间的空地上，
     * 下面那条断言会一直守着这件事。
     */
    private static Map<String, String> layout() {
        Map<String, String> blocks = new LinkedHashMap<>();
        for (int x = 0; x <= 11; x++) {
            for (int z = 0; z <= 11; z++) {
                blocks.put(key(x, 0, z), "minecraft:dirt");
            }
        }
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                blocks.put(key(x, 1, z), "minecraft:stone_bricks");
            }
        }
        for (int y = 2; y <= 3; y++) {
            for (int z = 1; z <= 4; z++) {
                blocks.put(key(0, y, z), "minecraft:oak_planks");
            }
            for (int x = 1; x <= 4; x++) {
                blocks.put(key(x, y, 0), "minecraft:oak_planks");
            }
        }
        // 控制箱位置只影响市民起点，和刷怪无关（刷怪是外框内随机撒点）。
        blocks.put(key(CONTROL_BOX_X, CONTROL_BOX_Y, CONTROL_BOX_Z),
                "simukraft:industrial_control_box");
        blocks.put(key(1, 2, 1), "minecraft:chest");
        blocks.put(key(3, 2, 1), "minecraft:chest");
        blocks.put(key(2, 2, 3), "minecraft:furnace");
        blocks.put(key(4, 2, 4), "minecraft:oak_planks");
        // 刻意**不放树**：成簇收获在预览里走**虚拟收获**（工作区里没有可收的方块时按标签估算），
        // 结构里不放树才能测到那条路径，建筑文件也干净（尺寸/材料只统计建筑）。
        // 注意「建筑以内无条件不收」是正确行为（不砍建筑本身）—— 结构里放木头反而会被整簇否决。
        return blocks;
    }

    private static String configJson() {
        return """
                {
                  "points": {
                    "p":       { "type": "structure_pos", "positions": [[2, 2, 1]] },
                    "w":       { "type": "structure_pos", "positions": [[2, 2, 2]] },
                    "r":       { "type": "structure_pos", "positions": [[4, 2, 2]] },
                    "q":       { "type": "structure_pos", "positions": [[4, 2, 4]] },
                    "machine": { "type": "structure_pos", "positions": [[2, 2, 3]] }
                  },
                  "containers": {
                    "input":  { "type": "structure_pos", "positions": [[1, 2, 1]] },
                    "output": { "type": "structure_pos", "positions": [[3, 2, 1]] }
                  },
                  "spawnEntity": {
                    "enabled": true,
                    "type": "minecraft:sheep",
                    "count": 6
                  },
                  "workArea": {
                    "radius": 12,
                    "startOffset": 1,
                    "minYOffset": -2,
                    "maxYOffset": 6,
                    "scanColumnsPerTick": 64
                  },
                  "recipes": [
                    {
                      "id": "all_in_one",
                      "name": "全流程",
                      "inputs": [],
                      "outputs": [],
                      "steps": [
                        { "type": "move_to", "point": "p" },
                        { "type": "look_at", "point": "p" },
                        { "type": "set_held_item", "item": "minecraft:diamond_pickaxe" },
                        { "type": "set_status", "statusKey": "simupack.test.start", "statusText": "开始全流程测试" },

                        { "type": "move_to_container", "container": "input" },
                        { "type": "inspect_container", "container": "input", "ticks": 20 },
                        { "type": "look_at_container", "container": "input" },
                        { "type": "use_item", "ticks": 20, "swing": true },

                        { "type": "insert_item", "container": "input", "item": "minecraft:oak_sapling", "count": 4 },
                        { "type": "insert_item", "container": "input", "item": "minecraft:wheat", "count": 6 },
                        { "type": "insert_item", "container": "input", "item": "minecraft:iron_ingot", "count": 6 },
                        { "type": "require_inputs", "container": "input" },
                        { "type": "require_output_space", "container": "output" },

                        { "type": "place_block", "point": "p", "block": "minecraft:oak_planks" },
                        { "type": "place_fluid", "point": "w", "fluid": "minecraft:water" },
                        { "type": "require_block", "point": "r", "block": "minecraft:crafting_table" },
                        { "type": "destroy_block", "point": "q", "dropItems": true,
                          "container": "output", "item": "minecraft:diamond_pickaxe", "swing": true },

                        { "type": "move_to_entity", "entityType": "minecraft:sheep", "radius": 16.0 },
                        { "type": "shear_entities", "entityType": "minecraft:sheep", "count": 2, "container": "output" },
                        { "type": "slaughter_entities", "entityType": "minecraft:sheep", "count": 1, "container": "output" },
                        { "type": "breed_entities", "entityType": "minecraft:sheep", "count": 1, "container": "input" },
                        { "type": "collect_drops", "item": "minecraft:feather", "count": 3, "container": "output" },
                        { "type": "require_drops", "item": "minecraft:feather" },

                        { "type": "harvest_block_clusters", "targetBlockTag": "#minecraft:logs",
                          "attachedBlockTag": "#minecraft:leaves", "minAttachedBlocks": 0,
                          "maxClusterBlocks": 64, "maxCarryStacks": 14,
                          "plantItemTag": "#minecraft:saplings", "container": "input",
                          "item": "minecraft:diamond_pickaxe", "ticks": 20 },
                        { "type": "deposit_carried_items", "container": "output" },

                        { "type": "craft_recipe", "input": "input", "output": "output",
                          "inputs": [{ "item": "minecraft:oak_log", "count": 2 }],
                          "outputs": [{ "item": "minecraft:oak_planks", "count": 4 }] },
                        { "type": "craft_available_recipe", "input": "input", "output": "output",
                          "inputs": [{ "item": "minecraft:oak_log", "count": 1 }],
                          "outputs": [{ "item": "minecraft:stick", "count": 2 }] },

                        { "type": "real_machine_recipe", "point": "machine", "input": "input", "output": "output",
                          "pollTicks": 20, "timeoutTicks": 2400,
                          "inputs": [{ "item": "minecraft:iron_ingot", "count": 2 }],
                          "outputs": [{ "item": "minecraft:iron_block", "count": 1 }] },

                        { "type": "set_status", "statusKey": "simupack.test.done", "statusText": "全流程完成" }
                      ]
                    }
                  ],
                  "_simupackEditor": { "order": ["all_in_one"], "positions": {} }
                }
                """;
    }

    // ── 手写 NBT（只用到 Compound / List / String / Int 四种标签）──────────────
    //
    // 格式：gzip 压缩的二进制 NBT，数字一律**大端**。
    //   Compound: 后面跟一串「标签」直到 TAG_End(0)
    //   每个标签: 类型(1) + 名字长度(2) + 名字 + 内容
    //   List:     元素类型(1) + 长度(4) + 各元素内容（元素不带类型和名字）
    //   String:   长度(2) + UTF-8
    //   Int:      4 字节

    private static final byte TAG_END = 0;
    private static final byte TAG_INT = 3;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;

    /** 写一个和原版 {@code StructureTemplate} 同形的结构文件（palette + blocks + size）。 */
    private static void writeStructure(Path path, Map<String, String> blocks) throws IOException {
        List<String> ids = new ArrayList<>();
        for (String id : blocks.values()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buffer))) {
            out.writeByte(TAG_COMPOUND);
            writeName(out, "");
            // palette：下标就是 blocks 里的 state
            writeListHeader(out, "palette", TAG_COMPOUND, ids.size());
            for (String id : ids) {
                writeString(out, "Name", id);
                out.writeByte(TAG_END);
            }
            // blocks：pos + state
            writeListHeader(out, "blocks", TAG_COMPOUND, blocks.size());
            for (Map.Entry<String, String> entry : blocks.entrySet()) {
                String[] parts = entry.getKey().split(",");
                writeListHeader(out, "pos", TAG_INT, 3);
                for (String part : parts) {
                    out.writeInt(Integer.parseInt(part));
                }
                writeInt(out, "state", ids.indexOf(entry.getValue()));
                out.writeByte(TAG_END);
            }
            writeListHeader(out, "size", TAG_INT, 3);
            out.writeInt(12);
            out.writeInt(8);
            out.writeInt(12);
            writeListHeader(out, "entities", TAG_END, 0);
            writeInt(out, "DataVersion", 3955);
            out.writeByte(TAG_END);
        }
        Files.write(path, buffer.toByteArray());
    }

    /** 读回来（校验用）：坐标 → 方块 id。 */
    private static Map<String, String> readStructure(Path path) throws IOException {
        Map<String, String> blocks = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
            assertEquals(TAG_COMPOUND, in.readByte(), "根标签应当是 Compound");
            readName(in);
            List<String> palette = new ArrayList<>();
            Map<String, String> entries = new LinkedHashMap<>();
            List<String> pendingPositions = new ArrayList<>();
            List<Integer> pendingStates = new ArrayList<>();
            while (true) {
                byte type = in.readByte();
                if (type == TAG_END) {
                    break;
                }
                String name = readName(in);
                if ("palette".equals(name)) {
                    int size = readListHeader(in);
                    for (int index = 0; index < size; index++) {
                        palette.add(readStringField(in, "Name"));
                        assertEquals(TAG_END, in.readByte(), "调色板条目应当以 TAG_End 收尾");
                    }
                } else if ("blocks".equals(name)) {
                    int size = readListHeader(in);
                    for (int index = 0; index < size; index++) {
                        int[] pos = new int[3];
                        String position = null;
                        int state = -1;
                        while (true) {
                            byte fieldType = in.readByte();
                            if (fieldType == TAG_END) {
                                break;
                            }
                            String field = readName(in);
                            if ("pos".equals(field)) {
                                int count = readListHeader(in);
                                for (int axis = 0; axis < Math.min(3, count); axis++) {
                                    pos[axis] = in.readInt();
                                }
                                position = key(pos[0], pos[1], pos[2]);
                            } else if ("state".equals(field)) {
                                state = in.readInt();
                            } else {
                                skip(fieldType, in);
                            }
                        }
                        assertNotNull(position, "每个方块条目都要有 pos");
                        // 先记下来，**等调色板读完再解析**：NBT 里键的顺序不保证
                        //（官方包就是 blocks 在 palette 前面），边读边查会索引到空的调色板。
                        pendingPositions.add(position);
                        pendingStates.add(state);
                    }
                } else {
                    skip(type, in);
                }
            }
            // 调色板和方块表都读完了，这时候再解析（键的顺序不保证，见上面的注释）。
            for (int index = 0; index < pendingPositions.size(); index++) {
                int state = pendingStates.get(index);
                assertTrue(state >= 0 && state < palette.size(),
                        "方块状态索引越界：" + state + "（调色板 " + palette.size() + " 项）");
                entries.put(pendingPositions.get(index), palette.get(state));
            }
            blocks.putAll(entries);
        }
        return blocks;
    }

    private static void writeListHeader(DataOutputStream out, String name, byte elementType, int size)
            throws IOException {
        out.writeByte(TAG_LIST);
        writeName(out, name);
        out.writeByte(elementType);
        out.writeInt(size);
    }

    private static int readListHeader(DataInputStream in) throws IOException {
        in.readByte(); // 元素类型（这里用不上：调用方知道自己写的是什么）
        return in.readInt();
    }

    private static void writeName(DataOutputStream out, String name) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readName(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(TAG_STRING);
        writeName(out, name);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readStringField(DataInputStream in, String expectedName) throws IOException {
        assertEquals(TAG_STRING, in.readByte(), "字段 " + expectedName + " 应当是字符串");
        assertEquals(expectedName, readName(in));
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeInt(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(TAG_INT);
        writeName(out, name);
        out.writeInt(value);
    }

    /** 跳过不关心的标签（这里只会遇到 List / Int，别的不需要）。 */
    private static void skip(byte type, DataInputStream in) throws IOException {
        if (type == TAG_INT) {
            in.readInt();
            return;
        }
        if (type == TAG_STRING) {
            int length = in.readUnsignedShort();
            in.skipBytes(length);
            return;
        }
        if (type == TAG_LIST) {
            byte element = in.readByte();
            int size = in.readInt();
            for (int index = 0; index < size; index++) {
                skip(element, in);
            }
            return;
        }
        throw new IOException("测试用跳读不支持标签类型 " + type);
    }

    /**
     * 把这座建筑注册进工程的 {@code project.json}。
     *
     * <p><b>这一步不能省</b>：建筑列表不是扫目录扫出来的，而是工程模型的一部分
     * （{@code CreationProject.buildings()}），界面读的是 {@code project.json} 里的
     * {@code buildings} 数组；磁盘上的 {@code .nbt} / {@code .json} 是它存盘时的**产物**。
     * 只写文件不注册，界面上一条都不会多出来 —— 踩过。
     *
     * <p>幂等：先删掉同 id 的旧条目再追加，所以反复跑不会攒出一堆重名建筑；
     * 也**不会动**工程里原有的建筑。
     */
    private static void registerInProject(String config, Map<String, String> layout) throws IOException {
        Path projectFile = PROJECT.resolve("project.json");
        JsonObject project;
        JsonArray buildings;
        if (Files.isRegularFile(projectFile)) {
            project = JsonParser.parseString(Files.readString(projectFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            buildings = project.has("buildings") ? project.getAsJsonArray("buildings") : new JsonArray();
        } else {
            project = new JsonObject();
            project.addProperty("formatVersion", 1);
            project.addProperty("id", PROJECT.getFileName().toString());
            project.addProperty("name", PROJECT.getFileName().toString());
            buildings = new JsonArray();
        }
        if (!project.has("buildings")) {
            project.add("buildings", buildings);
        }
        for (int index = buildings.size() - 1; index >= 0; index--) {
            JsonElement element = buildings.get(index);
            if (element.isJsonObject() && BUILDING_ID.equals(element.getAsJsonObject().get("id").getAsString())) {
                buildings.remove(index);
            }
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("id", BUILDING_ID);
        entry.addProperty("category", CATEGORY);
        entry.addProperty("hasStructure", true);
        JsonObject sk = new JsonObject();
        sk.addProperty("name", "仿真全流程测试建筑");
        sk.addProperty("author", "SimuPack");
        sk.addProperty("size", "12x8x12");
        sk.addProperty("amount", "");
        sk.addProperty("description", "覆盖工业仿真全部步骤的测试建筑，由单测生成");
        sk.addProperty("unlockLevel", "");
        entry.add("sk", sk);
        // 材料清单（材料页签用）：方块 id → 数量
        Map<String, Integer> counts = new java.util.TreeMap<>();
        layout.values().forEach(id -> counts.merge(id, 1, Integer::sum));
        JsonObject materials = new JsonObject();
        counts.forEach(materials::addProperty);
        entry.add("materials", materials);
        JsonObject pos1 = new JsonObject();
        pos1.addProperty("x", 0);
        pos1.addProperty("y", 0);
        pos1.addProperty("z", 0);
        JsonObject pos2 = new JsonObject();
        pos2.addProperty("x", 11);
        pos2.addProperty("y", 5);
        pos2.addProperty("z", 11);
        entry.add("pos1", pos1);
        entry.add("pos2", pos2);
        entry.addProperty("jsonConfig", config);
        buildings.add(entry);

        Files.writeString(projectFile, new GsonBuilder().setPrettyPrinting().create().toJson(project),
                StandardCharsets.UTF_8);
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
