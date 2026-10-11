package com.rstech.simupack.creation;

import java.nio.file.Path;
import com.rstech.simupack.creation.residential.CreationUnit;
import com.rstech.simupack.creation.residential.ResidentialUnitCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 创作中的单个建筑。
 *
 * <p>建筑允许「只有属性、暂缺结构文件」：{@code .sk} 字段可以随时编辑，
 * {@code .nbt} 结构与功能 {@code .json} 后续再补。
 */
public final class CreationBuilding {
    /** 允许的建筑分类，与 Sim-U-Kraft 官方的 ZIP 目录一致。 */
    public static final String[] CATEGORIES = {"residential", "commercial", "industry", "public", "other"};

    /** .sk 元数据字段顺序，保持与官方文件可读性一致。 */
    public static final String[] SK_FIELDS = {"name", "author", "size", "amount", "description", "unlockLevel"};

    private String id = "";
    private String category = CATEGORIES[0];
    private final Map<String, String> skFields = new LinkedHashMap<>();
    private String jsonConfig = "";
    /** 语言代码 → 字段名 → 译文。字段名通常是 name / description。 */
    private boolean hasStructure;
    /** 世界选区（闭区间）；为 null 表示尚未选点。 */
    private net.minecraft.core.BlockPos regionPos1;
    private net.minecraft.core.BlockPos regionPos2;
    /** 材料统计：方块 id → 数量；由结构捕获时统计。 */
    private final Map<String, Integer> materials = new LinkedHashMap<>();
    /** Ordered residential definitions; not serialized through jsonConfig. */
    private final List<CreationUnit> units = new ArrayList<>();
    /** Unknown .sk lines retained so import/export does not erase NSUK metadata. */
    private final List<String> extraSkLines = new ArrayList<>();

    public CreationBuilding() {
    }

    public CreationBuilding(String id, String category) {
        this.id = id;
        this.category = category;
        for (String field : SK_FIELDS) {
            skFields.put(field, "");
        }
    }

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String category() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category == null || category.isBlank() ? CATEGORIES[0] : category;
    }

    public Map<String, String> skFields() {
        return skFields;
    }

    public String skField(String field) {
        return skFields.getOrDefault(field, "");
    }

    public void setSkField(String field, String value) {
        skFields.put(field, value == null ? "" : value);
    }

    public String jsonConfig() {
        return jsonConfig;
    }

    public void setJsonConfig(String jsonConfig) {
        this.jsonConfig = jsonConfig == null ? "" : jsonConfig;
    }

    public boolean hasStructure() {
        return hasStructure;
    }

    public void setHasStructure(boolean hasStructure) {
        this.hasStructure = hasStructure;
    }

    // ── 选区与材料 ─────────────────────────────────────────────────────

    public boolean hasRegion() {
        return regionPos1 != null && regionPos2 != null;
    }

    public void setRegion(net.minecraft.core.BlockPos from, net.minecraft.core.BlockPos to) {
        this.regionPos1 = from;
        this.regionPos2 = to;
        if (from != null && to != null) {
            var size = regionSize();
            setSkField("size", size.getX() + "x" + size.getY() + "x" + size.getZ());
        }
    }

    public net.minecraft.core.BlockPos regionPos1() {
        return regionPos1;
    }

    public net.minecraft.core.BlockPos regionPos2() {
        return regionPos2;
    }

    /** 选区最小角，即 .nbt 结构原点。 */
    public net.minecraft.core.BlockPos regionMin() {
        if (!hasRegion()) {
            return net.minecraft.core.BlockPos.ZERO;
        }
        return new net.minecraft.core.BlockPos(
                Math.min(regionPos1.getX(), regionPos2.getX()),
                Math.min(regionPos1.getY(), regionPos2.getY()),
                Math.min(regionPos1.getZ(), regionPos2.getZ()));
    }

    /** 选区尺寸；闭区间所以每轴 +1（与旧版筑艺坊一致）。 */
    public net.minecraft.core.Vec3i regionSize() {
        if (!hasRegion()) {
            return net.minecraft.core.Vec3i.ZERO;
        }
        return new net.minecraft.core.Vec3i(
                Math.abs(regionPos2.getX() - regionPos1.getX()) + 1,
                Math.abs(regionPos2.getY() - regionPos1.getY()) + 1,
                Math.abs(regionPos2.getZ() - regionPos1.getZ()) + 1);
    }

    public List<CreationUnit> units() {
        return units;
    }

    public void setUnits(List<CreationUnit> values) {
        units.clear();
        if (values != null) {
            units.addAll(values);
        }
    }

    public List<String> extraSkLines() {
        return extraSkLines;
    }

    public Map<String, Integer> materials() {
        return materials;
    }

    public void setMaterials(Map<String, Integer> values) {
        materials.clear();
        if (values != null) {
            materials.putAll(values);
        }
    }

    /** 展示用名称：优先 .sk 的 name，其次 id。 */
    public String displayName() {
        String name = skField("name");
        return name.isBlank() ? (id.isBlank() ? "(未命名)" : id) : name;
    }

    /** .sk 文本内容：UTF-8 的 `key: value` 行。 */
    public String toSkText() {
        StringBuilder builder = new StringBuilder();
        for (String field : SK_FIELDS) {
            builder.append(field).append(": ").append(skFieldValue(field)).append('\n');
        }
        for (String line : extraSkLines) {
            builder.append(line).append('\n');
        }
        for (CreationUnit unit : units) {
            String line = ResidentialUnitCodec.toSkLine(unit);
            if (line != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * 写文件时对某些字段做规范化；读取时仍按用户输入原样存着。
     *
     * <p>目前只有 {@code size}：官方包一律写 {@code "24 x 55 x 24"}，
     * 而编辑器把手填或结构导入得到的 {@code "24x55x24"} 直接写出去。
     * NSUK 把它当纯字符串（只有游戏内展示用），所以两种都能加载，
     * 但发布出去的包该跟官方同构 —— 别的工具按官方格式解析才不会认不出来。
     */
    private String skFieldValue(String field) {
        String value = skField(field);
        return "size".equals(field) ? normalizeSize(value) : value;
    }

    /**
     * 把尺寸规范成 {@code "X x Y x Z"}。
     *
     * <p>认不出三段数字时原样返回：{@code size} 只是个展示字符串，
     * 官方包里甚至有写成 {@code "见预览"} 的，没必要为了格式去拦用户。
     */
    public static String normalizeSize(String value) {
        if (value == null) {
            return "";
        }
        String[] parts = value.trim().split("\\s*[xX*×]\\s*");
        if (parts.length != 3) {
            return value;
        }
        for (String part : parts) {
            if (!part.matches("[0-9]+")) {
                return value;
            }
        }
        return parts[0] + " x " + parts[1] + " x " + parts[2];
    }

    /** Parse known fields, repeated unit definitions, and retain unknown source lines. */
    public void applySkText(String text) {
        units.clear();
        extraSkLines.clear();
        if (text == null) {
            return;
        }
        for (String line : stripBom(text).split("\\R")) {
            CreationUnit unit = ResidentialUnitCodec.parseLine(line);
            if (unit != null) {
                units.add(unit);
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                if (!line.isBlank()) {
                    extraSkLines.add(line);
                }
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (isKnownSkField(key)) {
                skFields.put(key, value);
            } else if (!key.equalsIgnoreCase("unit")) {
                extraSkLines.add(line);
            }
        }
    }

    private static boolean isKnownSkField(String key) {
        for (String field : SK_FIELDS) {
            if (field.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉 UTF-8 BOM（{@code U+FEFF}）。
     *
     * <p><b>为什么非去掉不可</b>：NSUK 的 {@code BuildingPackageCatalog} 读 {@code .sk}
     * 时<b>不剥 BOM</b>（它只在 {@code _files.txt} 上剥），而行键是「行首 {@code key:}」匹配的。
     * 带 BOM 的文件首行会变成 {@code \uFEFFname: 某某}，{@code name} 这个键就<b>整个认不出来</b>，
     * 建筑显示名静默退化成文件名——建筑还在，但玩家看到的是 {@code modern_apartment_small_1}
     * 而不是它真正的名字，且没有任何报错。
     *
     * <p>本模组自己写 {@code .sk} 时用 {@code Files.writeString(..., UTF_8)}，不会带 BOM；
     * 但这条路径是<b>导入</b>别人做的包（Windows 记事本另存为 UTF-8 就会加 BOM）时唯一的入口，
     * 所以剥在这里，而不是指望每个读文件的地方都记得剥。
     */
    private static String stripBom(String text) {
        return text.isEmpty() || text.charAt(0) != '\uFEFF' ? text : text.substring(1);
    }

    // ── 字段取值范围（界面与校验共用同一条规则） ─────────────────────────

    /** {@code amount} 允许带的货币单位字符（前后都认）。 */
    public static final String CURRENCY_SUFFIX_CHARS = "元￥$¥";

    /** 造价的数字语法：{@code 865} / {@code 865.00} / {@code .5} / {@code 865.}。 */
    private static final String AMOUNT_NUMBER = "[0-9]+(\\.[0-9]*)?|\\.[0-9]+";

    /**
     * 「造价」这个<b>键的值</b>合法吗的语法：数字，可带小数，前后可带<b>一个</b>货币单位。
     *
     * <p><b>为什么不是"必须是整数"</b>：NSUK 的 {@code amount} 是<b>纯字符串</b>——
     * {@code BuildingCatalog$BuildingDefinition.amount()} 返回 {@code String}，整个 jar 在
     * {@code .sk} 这一侧唯一的 {@code Integer.parseInt} 是给 {@code unlockLevel} 用的。
     * 官方包绝大多数造价写着 {@code 180.00元}、{@code 1543.20元}，而且 NSUK 会<b>原样保留</b>
     * 货币单位（{@code amount} 缺省时才回退成 {@code price}，再回退成 {@code -}）。
     *
     * <p>前缀也收（{@code $12.5}）：那是从游戏交易界面直接抄下来的写法，而"抄一个价格"
     * 正是这个字段最常见的用法。
     *
     * <p>空值合法：等同于不写这个键。
     */
    public static final String AMOUNT_KEY_REGEX =
            "[" + CURRENCY_SUFFIX_CHARS + "]?(" + AMOUNT_NUMBER + ")[" + CURRENCY_SUFFIX_CHARS + "]?";

    /**
     * 「造价」<b>输入框</b>用的正则：与 {@link #AMOUNT_KEY_REGEX} 同一套数字语法。
     *
     * <p>两者<b>刻意完全一致</b>：{@code "865."} 这种中间态在两边都放行 ——
     * 不放行的话用户敲到小数点那一下就被拒，小数部分永远写不进去；而它本身也不是坏值，
     * NSUK 把 {@code amount} 当字符串存，{@code "865."} 只会在界面上原样显示。
     * 单独一个 {@code "."} 两边都不合法，所以框里不会留下读不懂的值。
     */
    public static final String AMOUNT_INPUT_REGEX = AMOUNT_KEY_REGEX;

    /** 这个字符串能不能当 {@code amount}（造价）的值用。 */
    public static boolean isValidAmount(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return value.trim().matches(AMOUNT_KEY_REGEX);
    }

    /**
     * 「解锁等级」<b>这个键的值</b>合法吗：留空，或正整数。
     *
     * <p><b>为什么这里必须严格</b>：NSUK 的 {@code readUnlockLevel} 用
     * {@code Integer.parseInt}，值 ≤ 0 或不是数字都会抛异常，而它被 {@code scanCategory}
     * 捕获后只写一行 warn，结果是<b>整栋建筑不进目录</b>——不是"等级没生效"，是建筑直接消失。
     *
     * <p><b>与输入框正则的差别只有一处</b>：空值在这里合法（键不写就是"没有等级要求"），
     * 而输入框正则不接受空串。这一处在 {@link #UNLOCK_LEVEL_INPUT_REGEX} 上有说明。
     */
    public static boolean isValidUnlockLevel(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String text = value.trim();
        if (!text.matches(UNLOCK_LEVEL_KEY_REGEX)) {
            return false;
        }
        try {
            return Integer.parseInt(text) >= 1;
        } catch (NumberFormatException exception) {
            // 位数太多、溢出成"不是合法 int"：NSUK 那边同样是丢弃。
            return false;
        }
    }

    /** 合法解锁等级的语法：正整数。位数上限交给 {@code Integer.parseInt} 判溢出。 */
    public static final String UNLOCK_LEVEL_KEY_REGEX = "[0-9]+";

    /**
     * 「解锁等级」<b>输入框</b>用的正则：留空，或正整数。
     *
     * <p>刻意不接受 {@code 0} 与纯零（{@code 0} / {@code 00}）：这两个值会让 NSUK 丢掉整栋建筑
     * （见 {@link #isValidUnlockLevel}）。但接受前导零（{@code 007} = 7，
     * NSUK 的 {@code Integer.parseInt} 也这么读），因为那只是个显示习惯，不是错误。
     *
     * <p>与 {@link #UNLOCK_LEVEL_KEY_REGEX} 的差别就是"空串"：那个正则要照顾
     * "键整个不写"的情况，所以第一位允许没有；输入框没有"空值"这个概念。
     */
    public static final String UNLOCK_LEVEL_INPUT_REGEX = "0*[1-9][0-9]*";

    /** 该建筑在工程目录下的目录（尚未使用，保留给结构文件组织）。 */
    public Path folder(Path projectRoot) {
        return projectRoot.resolve("buildings").resolve(category);
    }

    public Path skPath(Path projectRoot) {
        return folder(projectRoot).resolve(id + ".sk");
    }

    public Path nbtPath(Path projectRoot) {
        return folder(projectRoot).resolve(id + ".nbt");
    }

    public Path jsonPath(Path projectRoot) {
        return folder(projectRoot).resolve(id + ".json");
    }

    /** 导出到 ZIP 时的稳定标识：分类 + 文件名。 */
    public String key() {
        return category + "/" + id;
    }
}
