package com.rstech.simupack.attention;

import java.util.Optional;

/**
 * 官方建筑包的版本来源。
 *
 * <p>生产实现（{@link NsukOfficialPackVersionSource}）反射调 NSUK 的
 * {@code BuildingPackVersionChecker}；单测注入假实现，<b>不碰 ModList、不碰反射</b>。
 *
 * <p>把这一层抽出来还有一个现实好处：NSUK 改 API 名或签名时，只有这一个实现类要改，
 * provider 与红点层完全不受影响。
 */
public interface OfficialPackVersionSource {

    /** NSUK JAR 内置 {@code official_building.zip} 的版本；读不到返回空。 */
    Optional<String> builtinVersion();

    /** 建筑目录下本地 {@code official_building.zip} 的版本；读不到返回空。 */
    Optional<String> localVersion();
}
