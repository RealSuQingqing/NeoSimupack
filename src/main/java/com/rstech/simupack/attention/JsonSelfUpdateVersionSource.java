package com.rstech.simupack.attention;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rstech.simupack.SimuPackMod;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 筑艺坊自身版本的生产实现。
 *
 * <p>两层来源，都是纯本地：
 * <ul>
 *   <li><b>期望版本</b>：{@code config/simupack/latest.json} 的 {@code simupack} 字段。
 *       将来接入官网 / Modrinth 时，<b>联网层只负责刷新这个文件</b>，红点层一行都不用改；</li>
 *   <li><b>当前版本</b>：运行时读 {@code ModList}，而不是读编译期常量
 *       （{@code gradle.properties} 里的 {@code mod_version}）。
 *       只有读运行时才知道用户<b>实际加载的那个 jar</b> 是哪个版本——
 *       用户换完 jar 重启后，红点必须马上灭，读常量做不到这一点。</li>
 * </ul>
 *
 * <p>任何一步失败都返回 {@code Optional.empty()} → UNKNOWN → 不亮。
 *
 * <p>{@code latest.json} 的格式：
 * <pre>
 * {
 *   "simupack": "0.2.0",
 *   "source": "manual",
 *   "checkedAt": 0
 * }
 * </pre>
 * {@code source} 与 {@code checkedAt} 目前只作记录，判定只用到 {@code simupack}。
 */
public final class JsonSelfUpdateVersionSource implements SelfUpdateVersionSource {

    private static final String FILE_NAME = "latest.json";
    private static final String KEY_VERSION = "simupack";

    /**
     * 抑制日志；默认 true，由游戏侧启动时关掉（同 {@code PackActivation} 的理由：
     * 单测运行时 slf4j 不在 classpath 上）。
     */
    private static volatile boolean quiet = true;

    public static void enableLogging() {
        quiet = false;
    }

    @Override
    public Optional<String> expectedVersion() {
        return readExpected(file());
    }

    @Override
    public Optional<String> currentVersion() {
        return readCurrent();
    }

    /** 期望版本清单的路径；{@code public} 是为了让"打开 mods 文件夹"这类入口能定位到同目录。 */
    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("simupack").resolve(FILE_NAME)
                .toAbsolutePath().normalize();
    }

    /**
     * 从清单文件里读期望版本。
     *
     * <p>独立成包私有静态方法，便于单测直接喂路径（不碰 {@code FMLPaths}）——
     * 照抄 {@code PackActivation.restoreOfficialIfDisabled(Path)} 的做法。
     */
    public static Optional<String> readExpected(Path manifest) {
        if (manifest == null || !Files.isRegularFile(manifest)) {
            // 文件不存在是<b>完全正常</b>的状态（零网络时的默认情形），不是错误。
            return Optional.empty();
        }
        try {
            String text = Files.readString(manifest, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject json = root.getAsJsonObject();
            if (!json.has(KEY_VERSION)) {
                return Optional.empty();
            }
            JsonElement version = json.get(KEY_VERSION);
            if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isString()) {
                return Optional.empty();
            }
            String value = version.getAsString();
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
        } catch (Exception exception) {
            // 文件损坏 -> UNKNOWN -> 不亮。绝不因为一份清单写坏了就常亮红点。
            warn("无法读取更新清单 " + manifest + "，期望版本视为未知", exception);
            return Optional.empty();
        }
    }

    /** 运行时当前版本。 */
    static Optional<String> readCurrent() {
        try {
            return ModList.get().getModContainerById(SimuPackMod.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .filter(value -> !value.isBlank());
        } catch (RuntimeException | LinkageError exception) {
            // 启动早期或测试环境里 ModList 不可用：UNKNOWN。
            return Optional.empty();
        }
    }

    private static void warn(String message, Throwable cause) {
        if (quiet) {
            return;
        }
        SimuPackMod.LOGGER.warn(message, cause);
    }
}
