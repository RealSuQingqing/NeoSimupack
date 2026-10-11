package com.rstech.simupack.attention;

/**
 * 关注项的分类。三类问题<b>取或</b>决定主菜单 P 按钮上那颗红点亮不亮。
 *
 * <p>这个枚举刻意留在纯逻辑层（不依赖任何客户端类）：分类决定的不只是显示，
 * 还决定"要不要主动弹窗"，而弹窗的排队与去重规则是要能单测的。
 */
public enum AttentionKind {
    /**
     * 故障拓展包：读不出来的、格式有错的、一栋建筑都没有的，以及待转换的旧包。
     *
     * <p>管理器列表里本来就有红灯（状态指示灯）+ 详情页的错误列表，点 P 就能看到，
     * 因此这一类<b>不弹窗</b>——故障包的问题点只有在列表里对着看才说得清。
     */
    FAULTY_PACK,

    /**
     * 官方建筑包有更新：本地 {@code official_building.zip} 的版本低于 NSUK JAR 内置的那一份。
     *
     * <p>判据与解决动作都由 NSUK 自己提供（{@code BuildingPackVersionChecker} /
     * {@code BuildingBuiltinResourceService.forceOverwrite}），我们只做聚合与呈现。
     */
    PACK_UPDATE,

    /**
     * 筑艺坊自身有新版本。
     *
     * <p>零网络时没有权威来源，只能靠一份本地期望版本清单（{@code latest.json}）判定；
     * 它是唯一<b>不能"点一下就解决"</b>的类型——用户必须替换 jar 并重启游戏。
     */
    MOD_UPDATE;

    /**
     * 是否需要主动弹窗告知。
     *
     * <p>故障包靠管理器列表的红灯表达，不弹窗：那类问题写不进一个弹窗，也没法在弹窗里操作。
     */
    public boolean promptsUser() {
        return this != FAULTY_PACK;
    }
}
