package com.rstech.simupack.pack.legacy;

import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.client.SimuPackClientSettings;
import com.rstech.simupack.pack.BuildingPackManager;
import com.rstech.simupack.pack.NsukCatalogBridge;
import com.rstech.simupack.pack.PackActivation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 旧包转换的落地入口：识别 → 转换 → 写进建筑目录（未启用）→ 刷新列表。
 *
 * <p>命名策略是<b>固定同名</b>：{@code <源文件名>.zip.disabled}。重复拖同一个旧包会覆盖上一次的
 * 转换结果，不会在建筑目录里堆出一串 {@code _2/_3}。用户在转换前就能从结果里看到覆盖了哪一个。
 *
 * <p>产物带 {@code .disabled} 后缀，是有意的：NSUK 只扫 {@code .zip}，所以转换不会立刻改变
 * 游戏里的建筑清单，用户可以先检查再点「启用」。
 *
 * <p>源文件一律保持原样：转换只在建筑目录里产出新 ZIP，不动用户的旧包。
 * 旧包本来的 {@code .simupack} 后缀不会被 NSUK 扫描（它只认 {@code .zip}），
 * 所以放在同一目录也不会被误加载。
 */
public final class LegacyPackInstaller {

    /** 转换结果。 */
    public record Outcome(
            LegacyPackDetector.LegacyPackInfo source,
            Path output,
            boolean overwritten,
            LegacyPackConverter.Result conversion
    ) {
        /** 给界面用的一句话摘要。 */
        public String summary() {
            return conversion.skCount() + " 栋建筑（结构 " + conversion.structureCount()
                    + "，转换 " + conversion.convertedStructureCount()
                    + "，功能定义 " + conversion.functionJsonCount()
                    + "，商店 " + conversion.upgradedShopCount() + "）";
        }

        /** 摘要 + 需要用户知道的问题（最多 4 条，避免提示条被刷屏）。 */
        public String summaryWithNotes() {
            if (!conversion.hasNotes()) {
                return summary();
            }
            return summary() + "\n" + conversion.noticeSummary(4);
        }
    }

    private static volatile long legacyDirectoryFingerprint = Long.MIN_VALUE;
    private static volatile List<LegacyPackDetector.LegacyPackInfo> legacyScanCache = List.of();

    private LegacyPackInstaller() {
    }

    /**
     * 把一个旧包转换成新版包，并以<b>未启用</b>状态装进建筑目录。
     *
     * <p>无论用户是拖进来还是选中列表里的旧包，转换结果一律先落成
     * {@code <包名>.zip.disabled}：NSUK 不认这个后缀，所以转换本身不会改变
     * 游戏里现有的建筑清单，用户确认没问题后再自己点「启用」。
     *
     * @return 转换结果（{@link Outcome#output()} 指向的就是那个 .disabled 文件）
     * @throws IOException 读取源包、转换结构或写目标文件失败
     */
    public static Outcome install(Path source) throws IOException {
        LegacyPackDetector.LegacyPackInfo info = LegacyPackDetector.detect(source);
        if (info.error() != null) {
            throw new IOException(info.error());
        }
        if (!info.legacy()) {
            throw new IOException("这不是旧版建筑拓展包（已含 pack.json 或缺少建筑文件）");
        }
        if (!info.convertible()) {
            throw new IOException("旧包里没有 .sk 建筑元数据，无法转换");
        }

        Path output = outputPath(source);
        // 覆盖提示用：目标已存在说明是重复转换同一个旧包（固定同名，不堆 _2/_3）。
        boolean overwritten = Files.isRegularFile(output);
        LegacyPackConverter.Result conversion = LegacyPackConverter.convert(source, output,
                NbtStructureConverter.create());
        NsukCatalogBridge.refresh();
        BuildingPackManager.reload();
        SimuPackMod.LOGGER.info("已转换旧版建筑拓展包 {} → {}（{}），当前为未启用状态",
                source, output, conversion.noticeSummary(20));
        return new Outcome(info, output, overwritten, conversion);
    }

    /** 转换结果的目标路径：建筑目录下、固定同名、且带禁用后缀（NSUK 只认 .zip，所以不会加载它）。 */
    private static Path outputPath(Path source) throws IOException {
        Path directory = BuildingPackManager.rootDirectory();
        Files.createDirectories(directory);
        return directory.resolve(outputFileName(source) + PackActivation.DISABLED_SUFFIX);
    }

    /**
     * 建筑目录下扫描到的、还没转换的旧包。
     *
     * <p>只报告、不自动转换：转换会产生新文件，必须由用户点确认。
     *
     * <p>被用户按过「不再提示」的旧包会被排除——源文件留在原地不动，但提示不该每次都弹。
     * 判断依据是"路径 + 文件指纹"：文件内容一改就重新提示。
     */
    public static List<LegacyPackDetector.LegacyPackInfo> pendingInBuildingDirectory() {
        Path root = BuildingPackManager.rootDirectory();
        long fingerprint = directoryFingerprint(root);
        if (fingerprint == legacyDirectoryFingerprint) {
            return legacyScanCache;
        }
        List<LegacyPackDetector.LegacyPackInfo> result = new ArrayList<>();
        for (LegacyPackDetector.LegacyPackInfo info : LegacyPackDetector.scanDirectory(
                root, LegacyPackInstaller::shouldPrompt)) {
            if (info.convertible()) {
                result.add(info);
            }
        }
        legacyDirectoryFingerprint = fingerprint;
        legacyScanCache = List.copyOf(result);
        return legacyScanCache;
    }

    /** 用户改变“不再提示”设置后，目录指纹不变但筛选结果已变化。 */
    public static void invalidatePendingCache() {
        legacyDirectoryFingerprint = Long.MIN_VALUE;
        legacyScanCache = List.of();
    }

    private static long directoryFingerprint(Path root) {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        try (var stream = Files.list(root)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(LegacyPackDetector::looksLikePackFile)
                    .sorted((left, right) -> left.getFileName().toString()
                            .compareToIgnoreCase(right.getFileName().toString())).toList()) {
                hash ^= path.getFileName().toString().hashCode();
                hash *= 0x100000001b3L;
                hash ^= Files.size(path);
                hash *= 0x100000001b3L;
                hash ^= Files.getLastModifiedTime(path).toMillis();
                hash *= 0x100000001b3L;
            }
            return hash;
        } catch (IOException exception) {
            return System.nanoTime();
        }
    }

    /** 这个路径下的旧包还要不要提示用户转换。 */
    private static boolean shouldPrompt(Path source) {
        return !SimuPackClientSettings.isLegacyPromptDismissed(source,
                SimuPackClientSettings.fingerprint(source));
    }

    /**
     * 输出文件名：与源文件同名，后缀换成 {@code .zip}。
     *
     * <p>纯中文包名清洗成 ASCII 后会变成空串，因此再拼一段确定性的短哈希
     * （与旧版转换脚本同样的做法）：既保住了"同名可覆盖"的可预期性，
     * 又不会让两个不同的旧包都落到 {@code converted_pack.zip} 上互相覆盖。
     */
    static String outputFileName(Path source) {
        String file = source.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        String safe = LegacyPackConverter.sanitizeId(stem);
        if (!safe.isEmpty()) {
            return safe + ".zip";
        }
        String digest = Integer.toHexString(stem.hashCode());
        return "converted_pack_" + digest.replace("-", "n") + ".zip";
    }
}
