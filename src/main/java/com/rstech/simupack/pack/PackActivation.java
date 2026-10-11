package com.rstech.simupack.pack;

import com.rstech.simupack.SimuPackMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 拓展包的「启用 / 禁用」开关。
 *
 * <p>NSUK 只扫 {@code simukraftbuilding/} 里以 {@code .zip} 结尾的常规文件，
 * 所以禁用一个包最省事、也最可靠的做法就是把它改名成 {@code <原名>.zip.disabled}：
 * 文件还在原地（用户的文件不会"消失"，也没有第二个目录要同步），但 NSUK 不再认它。
 * 启用就是改回 {@code .zip}。
 *
 * <p>改名之后<b>必须</b>让 NSUK 重新扫描一次（见 {@link NsukCatalogBridge#refresh()}），
 * 否则它缓存里的旧清单还会继续生效，直到下次重启才更新。
 *
 * <p>本类只做文件系统这一层的搬迁与状态判断，不碰界面、不碰 NSUK 内部结构；
 * 唯一的外部副作用是刷新后 {@link BuildingPackManager#reload() 重新扫描}自己的列表。
 */
public final class PackActivation {

    /** 禁用后缀：{@code example.zip} → {@code example.zip.disabled}。 */
    public static final String DISABLED_SUFFIX = ".disabled";

    /** NSUK 自带、缺失时会被它自己补回来的包文件名。 */
    public static final String OFFICIAL_FILE_NAME = "official_building.zip";

    /**
     * 是否抑制日志。
     *
     * <p>默认 true，由游戏侧启动时（{@code SimuPackMod}）关掉。原因很实际：
     * {@link SimuPackMod#LOGGER} 是静态字段，在单元测试环境里 slf4j 不在测试运行时的
     * classpath 上，第一次碰它就会 {@code NoClassDefFoundError} 并且把整个类初始化搞坏。
     * 本类的大部分逻辑（文件名换算、状态判定）是刻意能脱离游戏跑的纯逻辑，
     * 不该为了几条日志把测试环境弄崩。
     */
    private static volatile boolean quiet = true;

    /** 由游戏侧调用一次：之后启用日志。 */
    public static void enableLogging() {
        quiet = false;
    }

    private static void log(String message, Object... arguments) {
        if (quiet) {
            return;
        }
        switch (arguments.length) {
            case 0 -> SimuPackMod.LOGGER.info(message);
            case 1 -> SimuPackMod.LOGGER.info(message, arguments[0]);
            default -> SimuPackMod.LOGGER.info(message, arguments[0], arguments[1]);
        }
    }

    private static void logWarn(String message, Object... arguments) {
        if (quiet) {
            return;
        }
        switch (arguments.length) {
            case 1 -> SimuPackMod.LOGGER.warn(message, arguments[0]);
            default -> SimuPackMod.LOGGER.warn(message, arguments);
        }
    }

    private static void logError(String message, Path path, Exception exception) {
        if (quiet) {
            return;
        }
        SimuPackMod.LOGGER.error(message, path, exception);
    }

    private PackActivation() {
    }

    // ── 文件名换算（纯函数，便于单测） ─────────────────────────────────

    /** 去掉末尾的禁用后缀；对已启用的名字原样返回。 */
    public static String enabledFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        return isDisabledFileName(fileName)
                ? fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length())
                : fileName;
    }

    /** 加上禁用后缀；已经是禁用名的不重复叠加。 */
    public static String disabledFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        return isDisabledFileName(fileName) ? fileName : fileName + DISABLED_SUFFIX;
    }

    /** 这个文件名是不是被禁用状态（大小写不敏感）。 */
    public static boolean isDisabledFileName(String fileName) {
        return fileName != null
                && fileName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX);
    }

    /** 路径版本的 {@link #isDisabledFileName}。 */
    public static boolean isDisabled(Path path) {
        return path != null && path.getFileName() != null
                && isDisabledFileName(path.getFileName().toString());
    }

    /** 去掉扩展名（可叠加：{@code a.zip.disabled} → {@code a}），用于包 id 回退值。 */
    public static String stripExtensions(String fileName) {
        String name = enabledFileName(fileName);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 是不是官方内置包（忽略启用/禁用后缀与大小写）。 */
    public static boolean isOfficialFileName(String fileName) {
        return OFFICIAL_FILE_NAME.equalsIgnoreCase(enabledFileName(fileName));
    }

    /**
     * 这个文件名是不是"包文件"：{@code .zip}（已启用）或 {@code .zip.disabled}（已禁用）。
     *
     * <p>刻意不接受 {@code .simupack}：那是旧版包的源文件后缀，属于待转换输入，
     * 不是本管理器认得的新版包。
     */
    public static boolean isPackFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".zip" + DISABLED_SUFFIX);
    }

    // ── 状态与搬迁 ─────────────────────────────────────────────────────

    /** 单个包的启用/禁用结果。 */
    public record Outcome(Path path, boolean enabled, boolean changed,
                          /** NSUK 是否真的重新扫过目录；false 表示没装 NSUK 或调用失败。 */
                          boolean nsukReloaded) {
    }

    /**
     * 禁用受保护包或操作损坏包时抛的异常。
     *
     * <p>单独一个类型而不是通用 {@link IOException}：界面要能区分"改名失败"
     * （应当报错）和"这个包不让这么做"（应当只是说明原因）。
     */
    public static class ProtectedPackException extends IOException {
        public ProtectedPackException(String message) {
            super(message);
        }
    }

    /**
     * 切换一个包的启用状态，并让 NSUK 立刻认到。
     *
     * @param pack 要切换的包（启用中的和已禁用的都行）
     * @return 切换后的状态
     * @throws ProtectedPackException 禁用官方内置包，或操作一个读不出来的坏包
     * @throws IOException 改名失败（文件被占用、权限不足等）
     */
    public static Outcome toggle(BuildingPack pack) throws IOException {
        return setEnabled(pack, !pack.enabled());
    }

    /**
     * 把包设成指定状态；已经是目标状态时只做一次刷新，不动文件。
     *
     * @throws ProtectedPackException 禁用官方内置包，或操作一个读不出来的坏包
     */
    public static Outcome setEnabled(BuildingPack pack, boolean enabled) throws IOException {
        if (pack.unusable()) {
            throw new ProtectedPackException("拓展包无法读取，不能启用或禁用：" + pack.path().getFileName());
        }
        if (!enabled && !canDisable(pack)) {
            throw new ProtectedPackException("官方内置包不能禁用：" + pack.path().getFileName());
        }
        if (pack.enabled() == enabled) {
            return new Outcome(pack.path(), enabled, false, refresh());
        }
        Path target = rename(pack.path(), enabled);
        return new Outcome(target, enabled, true, refresh());
    }

    /**
     * 这个包能不能被禁用。
     *
     * <p>官方内置包不能：它是 NSUK 的最小可用建筑集，而且 NSUK 只在"目录里一个可用包都没有"
     * 时才补它。禁用它会让建筑列表整个变空，用户没有任何游戏内手段恢复。
     *
     * <p>只限制"禁用"这一个方向：{@link #setEnabled(BuildingPack, boolean)} 重新启用官方包
     * 必须照常可用，否则自动恢复就少了兜底手段。
     */
    static boolean canDisable(BuildingPack pack) {
        return pack != null && !pack.official();
    }

    /**
     * 把一组包改成启用状态（<b>只改文件</b>，不刷新 NSUK 也不动界面的列表）。
     *
     * <p>{@link BuildingPack#unusable()} 的坏包会被跳过：启用它不会让游戏里多出任何建筑，
     * 只会把"装了一大堆却没一个生效"的困惑留给用户。旧包源文件也不在这个方法的职责内。
     *
     * <p>刻意不在这里刷新：单包操作走 {@link #setEnabled} 就自带刷新，而批量启用需要
     * "全部改完只刷新一次"（逐个刷新会让 NSUK 每改一个包就重扫整个目录，包多时界面明显卡住）。
     * 调用方改完后自己调一次 {@link #refresh()}。
     *
     * @param packs 候选包；调用方传完整列表即可，过滤在这里做
     * @return 实际改名的数量
     */
    public static int enablePacks(List<BuildingPack> packs) {
        return enablePacks(packs, BuildingPackManager.rootDirectory());
    }

    /** 同上，显式指定建筑目录（便于测试，不依赖 FML 的游戏目录）。 */
    static int enablePacks(List<BuildingPack> packs, Path buildingDirectory) {
        int enabled = 0;
        for (BuildingPack pack : packs) {
            if (pack.enabled() || pack.unusable()) {
                continue;
            }
            // 只处理真正躺在建筑目录里的包文件：包列表可能包含其它来源的对象，
            // 而这里要保证"改名的对象就在我们要刷新的那个目录里"。
            Path path = pack.path().toAbsolutePath().normalize();
            if (!path.getParent().equals(buildingDirectory.toAbsolutePath().normalize())) {
                continue;
            }
            if (!isPackFileName(path.getFileName().toString())) {
                continue;
            }
            try {
                rename(path, true);
            } catch (IOException exception) {
                // 单个包改名失败（被占用等）不该让整批停下：能启用的先启用，
                // 失败的记一条日志，界面刷新后它仍然是"未启用"。
                logError("启用拓展包失败 {}", path, exception);
                continue;
            }
            enabled++;
        }
        return enabled;
    }

    /**
     * 刷新 NSUK 并重新扫描自己的包列表。
     *
     * <p>文件改动之后必须调用一次，否则 NSUK 手里还是旧快照，用户得重启游戏才看得到变化。
     *
     * @return NSUK 是否真的重新扫过目录（没装 NSUK 或调用失败时为 false）
     */
    public static boolean refresh() {
        boolean reloaded = NsukCatalogBridge.refresh();
        BuildingPackManager.reload();
        return reloaded;
    }

    /**
     * 永久删除一个包文件（不进回收站）。
     *
     * <p>界面必须在调用前确认一次：这里没有任何恢复途径。
     */
    public static Outcome delete(BuildingPack pack) throws IOException {
        Path path = pack.path();
        boolean removed = Files.deleteIfExists(path);
        if (!removed) {
            return new Outcome(path, false, false, false);
        }
        log("已删除建筑拓展包", path);
        return new Outcome(path, false, true, refresh());
    }

    /**
     * 官方内置包若处于禁用状态，就把它改回启用。
     *
     * <p>双保险的第二道：界面已经不给官方包「禁用」按钮了，但用户可以在资源管理器里
     * 手工改名。官方包一旦被禁用，NSUK 的建筑目录就可能是空的（它不会自己补回来——
     * 补包只发生在目录里完全没有可用包的时候），建筑列表会整个消失，用户只能靠重启恢复。
     *
     * <p>每次扫描前调用（见 {@link BuildingPackManager#reload()}），所以手工改坏的名字
     * 在下次打开管理器时就会被修回来。
     *
     * @return true 表示这次确实修回来了
     */
    public static boolean restoreOfficialIfDisabled() {
        return restoreOfficialIfDisabled(BuildingPackManager.rootDirectory());
    }

    /** 同上，显式指定建筑目录（便于测试，不依赖 FML 的游戏目录）。 */
    static boolean restoreOfficialIfDisabled(Path buildingDirectory) {
        Path enabled = buildingDirectory.resolve(OFFICIAL_FILE_NAME);
        Path disabled = buildingDirectory.resolve(OFFICIAL_FILE_NAME + DISABLED_SUFFIX);
        if (!Files.isRegularFile(disabled)) {
            return false;
        }
        if (Files.isRegularFile(enabled)) {
            // 两种状态各留了一份。启用中的那份才是 NSUK 正在读的，必须保住它；
            // 剩下这个 .disabled 副本只会让人困惑（列表里出现两个同名官方包），直接清掉。
            try {
                Files.delete(disabled);
                logWarn("官方内置包同时存在启用与禁用两份文件，已清理多余副本：{}", disabled.getFileName());
            } catch (IOException exception) {
                logError("无法清理多余的官方内置包副本 {}", disabled, exception);
            }
            return false;
        }
        try {
            Path restored = rename(disabled, true);
            logWarn("官方内置包处于禁用状态，已自动恢复为启用：{}", restored.getFileName());
            return true;
        } catch (IOException exception) {
            // 只记日志：改名失败（文件被占用）不该阻断整个扫描，包列表照常显示。
            logError("无法自动恢复官方内置包 {}", disabled, exception);
            return false;
        }
    }

    /**
     * 找一个包的"另一个状态"文件：{@code a.zip} ↔ {@code a.zip.disabled}。
     *
     * <p>给展示层用：禁用包时能报出启用后它会叫什么，反之亦然。
     */
    public static Optional<Path> counterpart(BuildingPack pack) {
        Path path = pack.path();
        String other = pack.enabled()
                ? disabledFileName(path.getFileName().toString())
                : enabledFileName(path.getFileName().toString());
        if (other.isBlank() || other.equals(path.getFileName().toString())) {
            return Optional.empty();
        }
        return Optional.of(path.resolveSibling(other));
    }

    /** 改名：{@code enabled=true} → 去掉 {@code .disabled}，反之加上。 */
    private static Path rename(Path source, boolean enabled) throws IOException {
        String name = source.getFileName().toString();
        String targetName = enabled ? enabledFileName(name) : disabledFileName(name);
        if (targetName.equals(name)) {
            return source;
        }
        Path target = source.resolveSibling(targetName);
        if (Files.exists(target)) {
            // 同名目标已存在：说明两个状态各留了一份，属于用户手工操作留下的重复。
            // 以"当前这个文件"为准覆盖，避免改名直接抛 FileAlreadyExistsException。
            logWarn("{} 已存在，覆盖为 {}", target, source);
            Files.delete(target);
        }
        Files.move(source, target);
        log("建筑拓展包 {} → {}", enabled ? "已启用" : "已禁用", target.getFileName());
        return target;
    }

    /** 建筑目录里所有已禁用的包文件，按文件名排序（供"全部启用"用）。 */
    public static List<Path> disabledFiles() {
        Path root = BuildingPackManager.rootDirectory();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(PackActivation::isDisabled)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException exception) {
            SimuPackMod.LOGGER.warn("无法列出 {} 下已禁用的拓展包", root, exception);
            return List.of();
        }
    }
}
