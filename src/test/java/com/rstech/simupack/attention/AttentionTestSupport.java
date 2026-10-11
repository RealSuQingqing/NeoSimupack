package com.rstech.simupack.attention;

import com.rstech.simupack.pack.BuildingPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 测试替身与公共工具。
 *
 * <p>刻意<b>不碰 {@code FMLPaths} / {@code ModList} / 反射</b>：全部靠参数注入，
 * 照抄 {@code PackActivation} 的 {@code static} 包私有重载范式。
 */
public final class AttentionTestSupport {

    private AttentionTestSupport() {
    }

    /** 固定的官方包版本来源；{@code null} 表示"读不出来"。 */
    public record FakeOfficialVersions(String local, String builtin)
            implements OfficialPackVersionSource {
        @Override
        public Optional<String> localVersion() {
            return Optional.ofNullable(local);
        }

        @Override
        public Optional<String> builtinVersion() {
            return Optional.ofNullable(builtin);
        }
    }

    /** 固定的自身版本来源；{@code null} 表示"读不出来"。 */
    public record FakeSelfUpdate(String current, String expected)
            implements SelfUpdateVersionSource {
        @Override
        public Optional<String> currentVersion() {
            return Optional.ofNullable(current);
        }

        @Override
        public Optional<String> expectedVersion() {
            return Optional.ofNullable(expected);
        }
    }

    /** 造一个健康的包。 */
    public static BuildingPack healthy(Path path) {
        return new BuildingPack("healthy", "Healthy", "1.0", "author", "", path,
                3, List.of(), false, true, true);
    }

    /** 造一个格式有错的包（errors 非空）。 */
    public static BuildingPack withErrors(Path path) {
        return new BuildingPack("broken", "Broken", "1.0", "", "", path,
                2, List.of("Missing structure for buildings/x/y.sk"), false, true, true);
    }

    /** 造一个读不出来的包。 */
    public static BuildingPack unreadable(Path path) {
        return new BuildingPack("unreadable", "Unreadable", "", "", "", path,
                0, List.of("Cannot read ZIP"), false, true, false);
    }

    /** 造一个读得出来但一栋建筑都没有的包。 */
    public static BuildingPack empty(Path path) {
        return new BuildingPack("empty", "Empty", "1.0", "", "", path,
                0, List.of(), false, true, true);
    }

    /** 跑一个 provider，收集结果。 */
    public static List<AttentionItem> collect(AttentionProvider provider, AttentionContext context) {
        List<AttentionItem> items = new ArrayList<>();
        provider.collect(context, items::add);
        return items;
    }

    /** 只带一个 provider 的上下文。 */
    public static AttentionContext context(List<BuildingPack> packs, List<Path> legacy,
                                           OfficialPackVersionSource official,
                                           SelfUpdateVersionSource selfUpdate) {
        return AttentionContext.of(packs, legacy, official, selfUpdate);
    }

    /** 空上下文：两侧版本都读不出来、没有包。 */
    public static AttentionContext empty() {
        return context(List.of(), List.of(), new FakeOfficialVersions(null, null),
                new FakeSelfUpdate(null, null));
    }
}
