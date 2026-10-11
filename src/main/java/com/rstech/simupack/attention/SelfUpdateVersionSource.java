package com.rstech.simupack.attention;

import java.util.Optional;

/**
 * 筑艺坊<b>自身</b>的期望版本来源。
 *
 * <p>零网络时它只能读一份本地清单（{@code config/simupack/latest.json}）。
 * 抽成接口是为了两件事：
 * <ul>
 *   <li>单测注入替身，不必造文件；</li>
 *   <li>将来接入官网 / Modrinth 时，<b>联网层只负责刷新那个文件或换掉这个实现</b>，
 *       红点层一行都不用改。</li>
 * </ul>
 *
 * <p>返回空 = UNKNOWN = <b>不亮</b>（fail-closed）。
 */
public interface SelfUpdateVersionSource {

    /** 期望的最新版本号；没有权威来源时返回空。 */
    Optional<String> expectedVersion();

    /** 当前实际加载的版本号；读不到返回空。 */
    Optional<String> currentVersion();
}
