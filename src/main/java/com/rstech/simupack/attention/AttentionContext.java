package com.rstech.simupack.attention;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * provider 的输入：把所有外部依赖（目录、版本来源、包列表）都从这里注入。
 *
 * <p>这是<b>测试替身的入口</b>。照抄 {@code PackActivation} 的做法——不依赖
 * {@code FMLPaths} / {@code ModList} / 反射，单测直接喂构造数据。
 *
 * <p>刻意做成 {@code Supplier} 而不是直接持有值：provider 在后台线程上跑，
 * 而"当前包列表"可能在刷新开始后又被别的操作改了。用 supplier 每次取最新值，
 * 避免 provider 拿着过期快照算出一个马上就该变的结果。
 */
public final class AttentionContext {

    private final Supplier<java.util.List<com.rstech.simupack.pack.BuildingPack>> packs;
    private final Supplier<java.util.List<java.nio.file.Path>> pendingLegacyPacks;
    private final OfficialPackVersionSource officialPackVersions;
    private final SelfUpdateVersionSource selfUpdate;

    public AttentionContext(Supplier<java.util.List<com.rstech.simupack.pack.BuildingPack>> packs,
                            Supplier<java.util.List<java.nio.file.Path>> pendingLegacyPacks,
                            OfficialPackVersionSource officialPackVersions,
                            SelfUpdateVersionSource selfUpdate) {
        this.packs = Objects.requireNonNull(packs, "packs");
        this.pendingLegacyPacks = Objects.requireNonNull(pendingLegacyPacks, "pendingLegacyPacks");
        this.officialPackVersions = Objects.requireNonNull(officialPackVersions, "officialPackVersions");
        this.selfUpdate = Objects.requireNonNull(selfUpdate, "selfUpdate");
    }

    /** 当前扫到的拓展包列表。 */
    public java.util.List<com.rstech.simupack.pack.BuildingPack> packs() {
        return packs.get();
    }

    /** 建筑目录里待转换的旧包文件路径。 */
    public java.util.List<java.nio.file.Path> pendingLegacyPacks() {
        return pendingLegacyPacks.get();
    }

    /** 官方包版本来源（生产实现是 NSUK 反射桥）。 */
    public OfficialPackVersionSource officialPackVersions() {
        return officialPackVersions;
    }

    /** 筑艺坊自身的期望/当前版本来源。 */
    public SelfUpdateVersionSource selfUpdate() {
        return selfUpdate;
    }

    /** 用固定值构造一个上下文；单测与"用假快照先做渲染"时最顺手。 */
    public static AttentionContext of(java.util.List<com.rstech.simupack.pack.BuildingPack> packs,
                                      java.util.List<java.nio.file.Path> pendingLegacyPacks,
                                      OfficialPackVersionSource officialPackVersions,
                                      SelfUpdateVersionSource selfUpdate) {
        return new AttentionContext(() -> packs, () -> pendingLegacyPacks,
                officialPackVersions, selfUpdate);
    }
}
