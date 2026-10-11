package com.rstech.simupack.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.SimuPackMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 筑艺坊的客户端设置（跨启动持久化）。
 *
 * <p>与工作区一样落在 {@code config/simupack/} 下，但放在独立的 {@code client.json}：
 * 这是纯客户端显示设置，不该和工程数据混在一起，也方便出问题时单独删掉重置。
 *
 * <p>读取一律失败软处理：设置文件损坏时退回默认值并继续，绝不因为一个显示选项让游戏起不来。
 */
public final class SimuPackClientSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "client.json";
    private static final String KEY_UI_SCALE = "editorUiScale";
    private static final String KEY_LEGACY_DISMISSED = "legacyConversionDismissed";
    private static final String KEY_ATTENTION_DOT = "attentionDot";
    private static final String KEY_ATTENTION_ANIMATE = "attentionDotAnimate";
    private static final String KEY_ATTENTION_DISABLED_KINDS = "attentionDisabledKinds";
    private static final String KEY_ATTENTION_PROMPTED = "attentionPrompted";

    /** 0 表示自动（跟随 Minecraft 的 GUI 缩放）。 */
    private static float editorUiScale = 0F;

    /** 红点总开关。 */
    private static boolean attentionDot = true;

    /** {@code false} = 静态点（不读时钟）：无障碍 / 低端机 / 整合包作者偏好。 */
    private static boolean attentionDotAnimate = true;

    /**
     * 被显式关掉的关注项类别名（例如 {@code ["MOD_UPDATE"]}）。
     *
     * <p>整合包作者的逃生口：某一类长期常亮又解决不了时，可以<b>同时</b>关掉它的红点与弹窗。
     */
    private static final java.util.Set<String> attentionDisabledKinds = new java.util.LinkedHashSet<>();

    /**
     * 已经弹过窗的 {@link com.rstech.simupack.attention.AttentionItem#key()} → 提示时间戳。
     *
     * <p>判断只用到"key 是否存在"；时间戳只为排障与将来做"过期重提"。
     *
     * <p><b>这里记的是"提示过"，不是"读过了"</b>：用户点「稍后」后弹窗不再弹，
     * 但红点继续亮到问题真的解决。
     */
    private static final Map<String, Long> attentionPrompted = new LinkedHashMap<>();

    /**
     * 用户点过「不再提示」的旧包：文件路径 → 当时的指纹（见 {@link #fingerprint}）。
     *
     * <p>记指纹而不是只记路径：文件换了内容（用户又放了个新版本进来）就应该重新问一次，
     * 否则"不再提示"会退化成"永远不转这个包"。
     */
    private static final Map<String, String> dismissedLegacyPacks = new LinkedHashMap<>();

    private static boolean loaded;

    private SimuPackClientSettings() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("simupack").resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    /** 编辑器界面缩放：0 = 自动。 */
    public static synchronized float editorUiScale() {
        ensureLoaded();
        return editorUiScale;
    }

    public static synchronized void setEditorUiScale(float scale) {
        ensureLoaded();
        if (Math.abs(editorUiScale - scale) < 0.001F) {
            return;
        }
        editorUiScale = scale;
        save();
    }

    // ── 旧包转换提示的"不再提示"记录 ─────────────────────────────────

    /** 这个旧包是不是已经被用户按「不再提示」忽略过（且内容没变）。 */
    public static synchronized boolean isLegacyPromptDismissed(Path packPath, String fingerprint) {
        ensureLoaded();
        if (packPath == null) {
            return false;
        }
        String recorded = dismissedLegacyPacks.get(key(packPath));
        return recorded != null && recorded.equals(fingerprint);
    }

    /** 记住「这个旧包别再提示了」。 */
    public static synchronized void dismissLegacyPrompt(Path packPath, String fingerprint) {
        ensureLoaded();
        if (packPath == null) {
            return;
        }
        dismissedLegacyPacks.put(key(packPath), fingerprint);
        save();
    }

    /** 清掉某个旧包的"已忽略"记录（它被转掉或用户要求重新提示时）。 */
    public static synchronized void forgetLegacyPrompt(Path packPath) {
        ensureLoaded();
        if (packPath == null) {
            return;
        }
        if (dismissedLegacyPacks.remove(key(packPath)) != null) {
            save();
        }
    }

    /** 文件指纹：大小 + 修改时间；读不到返回空串（等价于"没忽略过"）。 */
    public static String fingerprint(Path packPath) {
        if (packPath == null) {
            return "";
        }
        try {
            return Files.size(packPath) + "@" + Files.getLastModifiedTime(packPath).toMillis();
        } catch (IOException exception) {
            return "";
        }
    }

    private static String key(Path packPath) {
        return packPath.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    /** 已忽略的旧包数量（界面显示用）。 */
    public static synchronized int dismissedLegacyPackCount() {
        ensureLoaded();
        return dismissedLegacyPacks.size();
    }

    /** 已忽略的旧包路径快照（只读，避免外部改到内部结构）。 */
    public static synchronized List<String> dismissedLegacyPackPaths() {
        ensureLoaded();
        return new ArrayList<>(dismissedLegacyPacks.keySet());
    }

    // ── 红点（关注项）设置 ─────────────────────────────────────────────

    /** 红点总开关。 */
    public static synchronized boolean attentionDot() {
        ensureLoaded();
        return attentionDot;
    }

    public static synchronized void setAttentionDot(boolean enabled) {
        ensureLoaded();
        if (attentionDot == enabled) {
            return;
        }
        attentionDot = enabled;
        save();
    }

    /** 是否播放呼吸/涟漪动画；{@code false} 时为静态点。 */
    public static synchronized boolean attentionDotAnimate() {
        ensureLoaded();
        return attentionDotAnimate;
    }

    public static synchronized void setAttentionDotAnimate(boolean animate) {
        ensureLoaded();
        if (attentionDotAnimate == animate) {
            return;
        }
        attentionDotAnimate = animate;
        save();
    }

    /**
     * 某一类关注项是否被用户/整合包作者关掉。
     *
     * <p>关掉之后<b>红点与弹窗一起消失</b>——这是刻意的：留一半（比如只关弹窗）会让
     * 用户以为红点坏了，而留红点又正是他想摆脱的东西。
     */
    public static synchronized boolean isAttentionKindDisabled(
            com.rstech.simupack.attention.AttentionKind kind) {
        ensureLoaded();
        return kind != null && attentionDisabledKinds.contains(kind.name());
    }

    /** 关掉/打开某一类关注项。 */
    public static synchronized void setAttentionKindDisabled(
            com.rstech.simupack.attention.AttentionKind kind, boolean disabled) {
        ensureLoaded();
        if (kind == null) {
            return;
        }
        boolean changed = disabled
                ? attentionDisabledKinds.add(kind.name())
                : attentionDisabledKinds.remove(kind.name());
        if (changed) {
            save();
        }
    }

    /** 已关掉的类别名快照（只读）。 */
    public static synchronized List<String> attentionDisabledKinds() {
        ensureLoaded();
        return new ArrayList<>(attentionDisabledKinds);
    }

    /** 这个 key 是不是已经提示过（用于弹窗去重）。 */
    public static synchronized boolean isAttentionPrompted(String key) {
        ensureLoaded();
        return key != null && attentionPrompted.containsKey(key);
    }

    /** 记录"这个 key 提示过了"。写入失败是软失败，只记 warn，不阻断弹窗。 */
    public static synchronized void markAttentionPrompted(String key) {
        ensureLoaded();
        if (key == null || key.isBlank()) {
            return;
        }
        attentionPrompted.put(key, System.currentTimeMillis());
        save();
    }

    /** 已提示过的 key 集合快照；喂给 {@code PromptPlanner}。 */
    public static synchronized java.util.Set<String> attentionPromptedKeys() {
        ensureLoaded();
        return new java.util.LinkedHashSet<>(attentionPrompted.keySet());
    }

    /** 清掉全部"已提示"记录（排障与"重新提示一次"用）。 */
    public static synchronized void clearAttentionPrompted() {
        ensureLoaded();
        if (attentionPrompted.isEmpty()) {
            return;
        }
        attentionPrompted.clear();
        save();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            if (json.has(KEY_UI_SCALE)) {
                editorUiScale = json.get(KEY_UI_SCALE).getAsFloat();
            }
            JsonElement dismissed = json.get(KEY_LEGACY_DISMISSED);
            if (dismissed != null && dismissed.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : dismissed.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        dismissedLegacyPacks.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            if (json.has(KEY_ATTENTION_DOT)) {
                attentionDot = json.get(KEY_ATTENTION_DOT).getAsBoolean();
            }
            if (json.has(KEY_ATTENTION_ANIMATE)) {
                attentionDotAnimate = json.get(KEY_ATTENTION_ANIMATE).getAsBoolean();
            }
            JsonElement kinds = json.get(KEY_ATTENTION_DISABLED_KINDS);
            if (kinds != null && kinds.isJsonArray()) {
                for (JsonElement element : kinds.getAsJsonArray()) {
                    // 只收下枚举里真的存在的类别名：配置里写错了不该把红点弄成永远不亮。
                    if (element.isJsonPrimitive() && knownKind(element.getAsString()) != null) {
                        attentionDisabledKinds.add(element.getAsString());
                    }
                }
            }
            JsonElement prompted = json.get(KEY_ATTENTION_PROMPTED);
            if (prompted != null && prompted.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : prompted.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        attentionPrompted.put(entry.getKey(), entry.getValue().getAsLong());
                    }
                }
            }
        } catch (Exception exception) {
            // 损坏就退回默认，不影响启动。
            SimuPackMod.LOGGER.warn("无法读取客户端设置 {}，使用默认值", path, exception);
        }
    }

    /** 配置里的类别名 → 枚举；不认识的名字返回 null（静默忽略）。 */
    private static com.rstech.simupack.attention.AttentionKind knownKind(String name) {
        if (name == null) {
            return null;
        }
        for (com.rstech.simupack.attention.AttentionKind kind
                : com.rstech.simupack.attention.AttentionKind.values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return null;
    }

    private static void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonObject json = new JsonObject();
            json.addProperty(KEY_UI_SCALE, editorUiScale);
            JsonObject dismissed = new JsonObject();
            dismissedLegacyPacks.forEach(dismissed::addProperty);
            json.add(KEY_LEGACY_DISMISSED, dismissed);
            json.addProperty(KEY_ATTENTION_DOT, attentionDot);
            json.addProperty(KEY_ATTENTION_ANIMATE, attentionDotAnimate);
            JsonArray kinds = new JsonArray();
            attentionDisabledKinds.forEach(kinds::add);
            json.add(KEY_ATTENTION_DISABLED_KINDS, kinds);
            JsonObject prompted = new JsonObject();
            attentionPrompted.forEach(prompted::addProperty);
            json.add(KEY_ATTENTION_PROMPTED, prompted);
            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            SimuPackMod.LOGGER.warn("无法保存客户端设置 {}", path, exception);
        }
    }
}
