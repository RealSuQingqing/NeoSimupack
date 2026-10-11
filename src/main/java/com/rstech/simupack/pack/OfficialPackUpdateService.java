package com.rstech.simupack.pack;

import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.attention.NsukOfficialPackVersionSource;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 官方建筑包更新：备份 → 调 NSUK 的 {@code forceOverwrite} → 重扫 → <b>事后重读验证</b>。
 *
 * <p>判据与动作都来自 NSUK 自己（{@code BuildingPackVersionChecker} /
 * {@code BuildingBuiltinResourceService}），我们只负责编排与<b>兜底</b>——
 * <b>不自己解包 JAR 再原子替换</b>：那会造成两个真相源，而且 NSUK 内部的
 * {@code COPIED_ROOTS} 缓存也不会对齐。
 *
 * <p><b>为什么必须事后重读验证</b>：{@code forceOverwrite} 返回 {@code void}
 * 且<b>吞掉所有异常</b>（内部只 {@code LOGGER.error}），调用方无法从返回值判断成败。
 * 唯一可靠的判据是"事后重新读一遍版本"。不等则判失败并回滚备份。
 *
 * <p>更新序列（对齐 NSUK 的 {@code /simukraft reload buildings}，但去掉它面向玩家的部分）：
 * <pre>
 * ① 备份现有 official_building.zip → config/simupack/backup/official_building-&lt;旧版本&gt;-&lt;时间戳&gt;.zip（保留 3 份）
 * ② BuildingBuiltinResourceService.forceOverwrite(BuildingPackageCatalog.rootDirectory())
 * ③ NsukCatalogBridge.refresh()          ← 含 §5.4 的四个 DefinitionLoader 清缓存修复
 * ④ BuildingPackManager.reload()
 * ⑤ 重新读 localVersion() 验证 == builtinVersion()；不等则判失败并回滚备份
 * ⑥ AttentionCenter.refreshAsync()       ← 由调用方触发（红点应当立刻熄灭）
 * </pre>
 */
public final class OfficialPackUpdateService {

    /** 备份目录：{@code config/simupack/backup/}。 */
    private static final String BACKUP_DIRECTORY = "backup";

    /** 保留的备份份数；超出即删最旧。 */
    private static final int KEEP_BACKUPS = 3;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    private static final String SERVICE_CLASS =
            "common.cn.kafei.simukraft.building.BuildingBuiltinResourceService";
    private static final String CATALOG_CLASS =
            "common.cn.kafei.simukraft.building.BuildingPackageCatalog";
    private static final String PACKAGE_FILE_NAME = "official_building.zip";

    /** 结果；{@code ok()} 为 false 时 messageKey 说明失败原因，且已尝试回滚。 */
    public record Outcome(boolean ok, boolean rolledBack, String messageKey,
                          Optional<String> fromVersion, Optional<String> toVersion,
                          Optional<Path> backup) {

        static Outcome failure(String messageKey, boolean rolledBack,
                               Optional<String> from, Optional<String> to) {
            return new Outcome(false, rolledBack, messageKey, from, to, Optional.empty());
        }
    }

    private static volatile boolean quiet = true;

    public static void enableLogging() {
        quiet = false;
    }

    private OfficialPackUpdateService() {
    }

    // ── 路径 ───────────────────────────────────────────────────────────

    /** 备份目录。 */
    public static Path backupDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve("simupack").resolve(BACKUP_DIRECTORY)
                .toAbsolutePath().normalize();
    }

    // ── 主流程 ─────────────────────────────────────────────────────────

    /**
     * 执行一次官方包更新。
     *
     * <p>永不抛异常：所有失败都收敛成 {@link Outcome#ok()} 为 false 并带原因翻译键，
     * 因为这条路径是从弹窗按钮/详情页按钮直接触发的，抛出去只会变成一次界面崩溃。
     *
     * @param localVersionSource  用来读更新前后的版本（生产环境传 NSUK 反射桥）
     * @return 更新结果
     */
    public static Outcome update(NsukOfficialPackVersionSource localVersionSource) {
        NsukOfficialPackVersionSource source = localVersionSource == null
                ? new NsukOfficialPackVersionSource() : localVersionSource;

        Optional<String> before = source.localVersion();
        Optional<String> target = source.builtinVersion();
        if (target.isEmpty()) {
            // 连内置版本都读不出来（NSUK 缺失或 API 变更）：不做任何事。
            return Outcome.failure("gui.simupack.attention.pack_update.failed_unknown", false,
                    before, target);
        }

        // ① 备份（失败不阻断：备份是"尽量保住旧文件"，不是更新的前置条件）。
        Optional<Path> backup = backupExistingPack(before);

        // ② 调 NSUK 自己的 forceOverwrite。
        if (!forceOverwrite()) {
            return Outcome.failure("gui.simupack.attention.pack_update.failed_nsuk", false,
                    before, target);
        }

        // ③④ 刷新 NSUK（含定义层清缓存）+ 重扫自己的列表。
        NsukCatalogBridge.refresh();
        BuildingPackManager.reload();

        // ⑤ 事后重读验证——这是唯一可靠的判据，因为 forceOverwrite 吞异常。
        Optional<String> after = source.localVersion();
        if (after.isPresent() && !com.rstech.simupack.attention.VersionCompare
                .isOlderThan(after.get(), target.get())) {
            log("官方建筑包已更新：{} → {}", before.orElse("?"), after.get());
            return new Outcome(true, false, "gui.simupack.attention.pack_update.done",
                    before, after, backup);
        }

        // 验证不过：回滚备份，并把红点留亮着。
        boolean rolledBack = restoreFromBackup(backup);
        warn("官方建筑包更新后版本验证失败（期望 {}，实际 {}），回滚：{}",
                target.get(), after.orElse("?"), rolledBack);
        return Outcome.failure("gui.simupack.attention.pack_update.failed_verify", rolledBack,
                before, after);
    }

    /**
     * 反射调 {@code BuildingBuiltinResourceService.forceOverwrite(Path)}。
     *
     * <p>路径安全由 NSUK 自己保证（目标必须在 rootDirectory 之内，否则拒绝），
     * 所以这里直接把 {@code BuildingPackageCatalog.rootDirectory()} 递进去。
     */
    private static boolean forceOverwrite() {
        ClassLoader loader = NsukCatalogBridge.nsukClassLoader();
        if (loader == null) {
            warn("未找到 NSUK，无法更新官方建筑包");
            return false;
        }
        try {
            Class<?> catalog = Class.forName(CATALOG_CLASS, true, loader);
            Object root = catalog.getMethod("rootDirectory").invoke(null);
            if (!(root instanceof Path rootPath)) {
                warn("以意外类型调用 {}.rootDirectory()：{}", CATALOG_CLASS, root);
                return false;
            }
            Class<?> service = Class.forName(SERVICE_CLASS, true, loader);
            Method method = service.getMethod("forceOverwrite", Path.class);
            method.invoke(null, rootPath);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            // forceOverwrite 自己吞异常，所以走到这里说明是"调用不进去"（API 改名等）。
            warn("调用 NSUK forceOverwrite 失败", exception);
            return false;
        }
    }

    // ── 备份与回滚 ─────────────────────────────────────────────────────

    /**
     * 把现有官方包复制进备份目录，并按时间戳清理旧备份。
     *
     * @return 备份文件路径；没有可备份的文件时返回空
     */
    static Optional<Path> backupExistingPack(Optional<String> version) {
        Path local = BuildingPackManager.rootDirectory().resolve(PACKAGE_FILE_NAME);
        if (!Files.isRegularFile(local)) {
            return Optional.empty();
        }
        try {
            Path directory = backupDirectory();
            Files.createDirectories(directory);
            String tag = version.filter(value -> !value.isBlank()).orElse("unknown");
            Path target = directory.resolve("official_building-" + sanitize(tag) + "-"
                    + LocalDateTime.now().format(TIMESTAMP) + ".zip");
            Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);
            pruneBackups(directory);
            log("已备份官方建筑包到 {}", target.getFileName());
            return Optional.of(target);
        } catch (IOException | RuntimeException exception) {
            // 备份失败不阻断更新：它只是"多一层保险"。
            warn("备份官方建筑包失败（继续更新）", exception);
            return Optional.empty();
        }
    }

    /** 失败回滚：把备份复制回去，再刷新一次让 NSUK 认到。 */
    static boolean restoreFromBackup(Optional<Path> backup) {
        if (backup.isEmpty() || !Files.isRegularFile(backup.get())) {
            return false;
        }
        try {
            Path local = BuildingPackManager.rootDirectory().resolve(PACKAGE_FILE_NAME);
            Files.copy(backup.get(), local, StandardCopyOption.REPLACE_EXISTING);
            NsukCatalogBridge.refresh();
            BuildingPackManager.reload();
            return true;
        } catch (IOException | RuntimeException exception) {
            warn("回滚官方建筑包备份失败", exception);
            return false;
        }
    }

    /** 只保留最近 {@link #KEEP_BACKUPS} 份，按修改时间删最旧。 */
    private static void pruneBackups(Path directory) {
        try (var stream = Files.list(directory)) {
            List<Path> backups = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".zip"))
                    .toList());
            if (backups.size() <= KEEP_BACKUPS) {
                return;
            }
            backups.sort(Comparator.comparingLong(OfficialPackUpdateService::lastModified));
            // 超出部分全删（从最旧的开始）。
            for (int index = 0; index < backups.size() - KEEP_BACKUPS; index++) {
                Files.deleteIfExists(backups.get(index));
            }
        } catch (IOException | RuntimeException exception) {
            warn("清理旧备份失败", exception);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            // 读不到就当作"最旧"，优先删掉它。
            return Long.MIN_VALUE;
        }
    }

    /** 版本串里的非法文件名字符换成下划线。 */
    static String sanitize(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        return version.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void log(String message, Object... arguments) {
        if (quiet) {
            return;
        }
        SimuPackMod.LOGGER.info(message, arguments);
    }

    private static void warn(String message, Object... arguments) {
        if (quiet) {
            return;
        }
        SimuPackMod.LOGGER.warn(message, arguments);
    }
}
