package com.rstech.simupack.creation.industrial.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 仿真内核读取 JSON 的唯一入口。
 *
 * <p>这些取值规则是照着 NSUK 2.2.0 的 {@code IndustrialDefinitionLoader} /
 * {@code IndustrialItemSpecJsonParser} 抄下来的（默认值、下限、别名顺序都必须一致），
 * 否则仿真结果会和游戏里跑出来的不是同一件事。改动这里之前请先核对反编译参考。
 *
 * <p>刻意不依赖 Minecraft：整个 sim 包要能脱离客户端跑单元测试。
 */
final class SimJson {
    private SimJson() {
    }

    static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static String stringAny(JsonObject object, String fallback, String... keys) {
        for (String key : keys) {
            String value = string(object, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    static int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static int integerAny(JsonObject object, int fallback, String... keys) {
        for (String key : keys) {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return integer(object, key, fallback);
            }
        }
        return fallback;
    }

    static double decimal(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** 布尔值允许写成 {@code true} 或 {@code "true"}。 */
    static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static boolean hasAny(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return true;
            }
        }
        return false;
    }

    static JsonArray arrayAny(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object != null && object.has(key) && object.get(key).isJsonArray()) {
                return object.getAsJsonArray(key);
            }
        }
        return null;
    }

    static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static List<JsonObject> objects(JsonArray array) {
        List<JsonObject> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            JsonObject object = asObject(element);
            if (object != null) {
                result.add(object);
            }
        }
        return result;
    }

    static JsonArray positionArray(SimPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.x());
        array.add(pos.y());
        array.add(pos.z());
        return array;
    }

    /** NSUK 的 {@code parseOptionalPositions}：先取 {@code pos}，再取 {@code positions}，各自最多 64 个。 */
    static List<SimPos> positions(JsonObject object) {
        List<SimPos> result = new ArrayList<>();
        if (object == null) {
            return result;
        }
        if (object.has("pos") && object.get("pos").isJsonArray()) {
            SimPos single = position(object.getAsJsonArray("pos"));
            if (single != null) {
                result.add(single);
            }
        }
        if (object.has("positions") && object.get("positions").isJsonArray()) {
            JsonArray array = object.getAsJsonArray("positions");
            int limit = Math.min(array.size(), 64);
            for (int i = 0; i < limit; i++) {
                JsonElement element = array.get(i);
                if (element == null || !element.isJsonArray()) {
                    continue;
                }
                SimPos pos = position(element.getAsJsonArray());
                if (pos != null) {
                    result.add(pos);
                }
            }
        }
        return List.copyOf(result);
    }

    private static SimPos position(JsonArray array) {
        if (array.size() != 3) {
            return null;
        }
        try {
            return new SimPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
