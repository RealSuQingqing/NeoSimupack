package com.rstech.simupack.creation;

import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.pack.BuildingPack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 已安装拓展包的只读读取器。
 *
 * <p>已安装包在 {@code simukraftbuilding/*.zip} 里，属于游戏资源而非工程，
 * 因此<b>只读</b>：这里把包内的 {@code buildings/<分类>/<id>.sk} 解析成临时
 * {@link CreationBuilding}（不落盘），供界面复用同一套「基本信息」表单展示。
 *
 * <p>临时对象绝不能被 {@code CreationWorkspace.save} 写回——调用方必须配合只读标志
 * 关闭全部写操作。
 */
public final class InstalledPackReader {
    private static final String BUILDINGS_DIR = "buildings";

    /**
     * {@code .sk} 里指向功能定义 JSON 的指针键。
     *
     * <p>取值来自 NSUK 的三个 {@code *DefinitionSourceResolver}：工业还额外认
     * {@code drilling:}（声明了它就是钻井平台，用另一份定义）。这些键<b>不在</b>
     * {@link CreationBuilding#SK_FIELDS} 里，所以会落到 {@code extraSkLines}。
     */
    private static final Set<String> DEFINITION_POINTER_KEYS =
            Set.of("industrial", "commercial", "medical", "drilling");

    private InstalledPackReader() {
    }

    /** 包内的一个建筑。 */
    public record Entry(String category, String id, CreationBuilding building, boolean hasStructure) {
        public String key() {
            return category + "/" + id;
        }
    }

    /** 错误信息（读不了时返回空表并在日志里留痕，绝不让界面崩）。 */
    public record Result(List<Entry> entries, String error) {
        public boolean ok() {
            return error == null || error.isBlank();
        }
    }

    /** 列出某个已安装包里的全部建筑。 */
    public static Result listBuildings(BuildingPack pack) {
        if (pack == null || pack.path() == null) {
            return new Result(List.of(), "拓展包路径无效");
        }
        Map<String, CreationBuilding> parsed = new LinkedHashMap<>();
        Map<String, String> categories = new LinkedHashMap<>();
        Set<String> withStructure = new HashSet<>();
        /** 包内全部 {@code .json}：{@code buildings/<分类>/<文件名主干>} → 条目名。 */
        Map<String, String> jsonByStem = new LinkedHashMap<>();

        try (ZipFile zip = new ZipFile(pack.path().toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith(BUILDINGS_DIR + "/")) {
                    continue;
                }
                String relative = name.substring(BUILDINGS_DIR.length() + 1);
                int slash = relative.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                String category = relative.substring(0, slash);
                String fileName = relative.substring(slash + 1);
                int dot = fileName.lastIndexOf('.');
                if (dot <= 0) {
                    continue;
                }
                String id = fileName.substring(0, dot);
                String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
                String key = category + "/" + id;

                if (".nbt".equals(extension)) {
                    withStructure.add(key);
                } else if (".json".equals(extension)) {
                    jsonByStem.put(key, name);
                } else if (".sk".equals(extension)) {
                    CreationBuilding building = new CreationBuilding(id, category);
                    building.applySkText(readText(zip, entry));
                    parsed.put(key, building);
                    categories.put(key, category);
                }
            }

            // 功能定义（工业配方 / 商业报价 / 医疗类型）在 .sk 之外的单文件里，
            // 不读它就等于让「分类设定」「动作组」「商业交易」三个页签全空 ——
            // 而这三个页签正是作者来学官方设计的入口。
            for (Map.Entry<String, CreationBuilding> item : parsed.entrySet()) {
                String jsonEntry = findDefinitionEntry(item.getKey(), item.getValue(), jsonByStem);
                if (jsonEntry != null) {
                    item.getValue().setJsonConfig(readText(zip, zip.getEntry(jsonEntry)));
                }
            }
        } catch (Exception exception) {
            SimuPackMod.LOGGER.error("读取已安装包 {} 失败", pack.id(), exception);
            return new Result(List.of(), "读取失败：" + exception.getMessage());
        }

        List<Entry> result = new ArrayList<>();
        for (Map.Entry<String, CreationBuilding> item : parsed.entrySet()) {
            boolean structure = withStructure.contains(item.getKey());
            item.getValue().setHasStructure(structure);
            result.add(new Entry(categories.get(item.getKey()),
                    item.getValue().id(), item.getValue(), structure));
        }
        return new Result(result, "");
    }

    /**
     * 找出这个建筑的功能定义 JSON 条目名；没有则返回 {@code null}。
     *
     * <p>顺序照抄 NSUK 的两个解析级别：
     * <ol>
     *   <li><b>显式指针</b>：{@code .sk} 里 {@code industrial:} / {@code commercial:} /
     *       {@code medical:} / {@code drilling:} 声明的包内文件名（必须真存在，
     *       NSUK 找不到就整栋丢弃，所以这里也只认存在的）；</li>
     *   <li><b>同名回落</b>：{@code <建筑id>.json}（NSUK 在没声明指针时的默认约定，
     *       也是本模组导出时写的形式）。</li>
     * </ol>
     *
     * <p>指针的取值只看 {@code .sk} 原文：这些键不在
     * {@link CreationBuilding#SK_FIELDS} 里，会被原样收进 {@code extraSkLines}。
     */
    private static String findDefinitionEntry(String key, CreationBuilding building,
                                              Map<String, String> jsonByStem) {
        String category = key.substring(0, key.indexOf('/'));
        for (String line : building.extraSkLines()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String pointerKey = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!DEFINITION_POINTER_KEYS.contains(pointerKey)) {
                continue;
            }
            String declared = line.substring(colon + 1).trim();
            if (declared.isEmpty()) {
                continue;
            }
            String candidate = BUILDINGS_DIR + "/" + category + "/" + declared;
            if (jsonByStem.containsValue(candidate)) {
                return candidate;
            }
        }
        return jsonByStem.get(key);
    }

    private static String readText(ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
