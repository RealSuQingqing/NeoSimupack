package com.rstech.simupack.creation;

/**
 * 拓展包格式号与 Minecraft 版本的对应关系 —— <b>全工程唯一的事实来源</b>。
 *
 * <p>规则（由维护者规定）：
 * <table border="1">
 *   <caption>包格式号</caption>
 *   <tr><th>Minecraft</th><th>{@code pack.json} 的 {@code format}</th></tr>
 *   <tr><td>1.20.1</td><td>1</td></tr>
 *   <tr><td>1.21.1</td><td>2</td></tr>
 *   <tr><td>26.3</td><td>3</td></tr>
 * </table>
 *
 * <p><b>为什么必须集中在一处</b>：这个数字会出现在三个地方 —— 导出时写进
 * {@code pack.json}、读取已安装包时解析、导入/转换时判兼容。任何两处各写一份，
 * 迟早会出现"导出写 2、校验按 1 判、于是自己刚导出的包被自己判成旧格式"这种自相矛盾。
 * 值与顺序也刻意只在这里出现一次，升级版本线时改这一个地方。
 *
 * <p>{@link #expected()} 指的是<b>本模组所在的那条版本线</b>（1.21.1 → 2），
 * 不是"最新格式"：26.3 的包格式号更大，但把 1.21.1 的编辑器导出的包写成 3
 * 只会让 1.21.1 的 NSUK 与其它工具认不出来。要支持 26.3 得另开一条版本线，
 * 而不是把这里的常量往上调。
 *
 * <p>刻意不依赖任何 Minecraft / NeoForge 类：这样导入/导出的格式判定能脱机跑测试。
 */
public final class PackFormat {

    /** 1.20.1 线的包格式号。 */
    public static final int FORMAT_1_20_1 = 1;
    /** 1.21.1 线的包格式号（本模组当前所在版本线）。 */
    public static final int FORMAT_1_21_1 = 2;
    /** 26.3 线的包格式号。 */
    public static final int FORMAT_26_3 = 3;

    /**
     * 本模组这条版本线期望的包格式号。
     *
     * <p>硬编码为 1.21.1 的 2：模组的 {@code minecraft_version} 就是 1.21.1，
     * 且这个类不能依赖 Minecraft 类（见类注释）。换版本线时连同
     * {@code gradle.properties} 一起改这里。
     */
    private static final int EXPECTED = FORMAT_1_21_1;

    private PackFormat() {
    }

    /** 当前版本线期望的格式号。 */
    public static int expected() {
        return EXPECTED;
    }

    /**
     * 这个格式号对应的 Minecraft 版本名；未知返回 {@code null}。
     */
    public static String minecraftVersion(int format) {
        return switch (format) {
            case FORMAT_1_20_1 -> "1.20.1";
            case FORMAT_1_21_1 -> "1.21.1";
            case FORMAT_26_3 -> "26.3";
            default -> null;
        };
    }

    /**
     * 包格式与本版本线的兼容判定。
     *
     * @param format 包声明的格式号；{@link #UNKNOWN} 表示 {@code pack.json} 里没写
     */
    public static Compatibility check(int format) {
        if (format == UNKNOWN) {
            return Compatibility.UNDECLARED;
        }
        if (format == EXPECTED) {
            return Compatibility.MATCH;
        }
        return format < EXPECTED ? Compatibility.OLDER : Compatibility.NEWER;
    }

    /**
     * 包没声明 {@code format} 时的取值。
     *
     * <p>0 而不是 -1：{@code pack.json} 里的数字字段读不到就是 0，用它当哨兵值
     * 可以让 {@code format == 0} 与"字段缺失"天然等价，不必再多一个布尔位。
     */
    public static final int UNKNOWN = 0;

    /** 包格式相对本版本线的兼容性。 */
    public enum Compatibility {
        /** 没写 {@code format}（老包，或手写的包）。 */
        UNDECLARED,
        /** 与本版本线一致。 */
        MATCH,
        /** 更早的版本线（例如 1.20.1 的 format=1 装到 1.21.1 上）。 */
        OLDER,
        /** 更新的版本线（例如 26.3 的 format=3 装到 1.21.1 上）。 */
        NEWER;

        /** 能不能直接用。只有 {@link #MATCH} 与 {@link #UNDECLARED} 放行。 */
        public boolean usable() {
            return this == MATCH || this == UNDECLARED;
        }
    }
}
