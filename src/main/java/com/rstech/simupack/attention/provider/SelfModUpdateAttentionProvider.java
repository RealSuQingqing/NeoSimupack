package com.rstech.simupack.attention.provider;

import com.rstech.simupack.attention.AttentionContext;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.attention.AttentionProvider;
import com.rstech.simupack.attention.SelfUpdateVersionSource;
import com.rstech.simupack.attention.VersionCompare;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * C 类：筑艺坊自身有新版本。
 *
 * <p>零网络时它<b>没有权威来源</b>，只能靠一份本地期望版本清单
 * （{@code config/simupack/latest.json} 的 {@code simupack} 字段）。
 * 文件不存在 / 字段缺失 / 版本串解析失败 → UNKNOWN → <b>不亮</b>
 * （fail-closed：宁可漏报，不可误报）。
 *
 * <p>不采用"读整合包 {@code manifest.json} / {@code modrinth.index.json}"的替代方案：
 * 那里面是<b>整合包</b>版本号，不是 simupack 的模组版本号，语义对不上，
 * 会把整合包更新误报成模组更新。
 *
 * <p>这一类还是唯一<b>不能"点一下就解决"</b>的类型：用户必须替换 jar 并重启游戏。
 * 弹窗文案必须明说，否则用户会以为红点坏了。
 */
public final class SelfModUpdateAttentionProvider implements AttentionProvider {

    static final String TITLE_KEY = "gui.simupack.attention.mod_update.title";
    static final String BODY_KEY = "gui.simupack.attention.mod_update.body";

    @Override
    public void collect(AttentionContext context, Consumer<AttentionItem> sink) {
        SelfUpdateVersionSource source = context.selfUpdate();
        Optional<String> current = source.currentVersion();
        Optional<String> expected = source.expectedVersion();
        if (current.isEmpty() || expected.isEmpty()) {
            return;
        }

        String currentVersion = current.get();
        String expectedVersion = expected.get();
        if (!VersionCompare.isOlderThan(currentVersion, expectedVersion)) {
            return;
        }

        sink.accept(new AttentionItem(AttentionKind.MOD_UPDATE,
                key(currentVersion, expectedVersion),
                TITLE_KEY, BODY_KEY,
                List.of(currentVersion, expectedVersion)));
    }

    /** 弹窗去重键：{@code MOD_UPDATE:simupack:<当前>-><期望>}。 */
    static String key(String currentVersion, String expectedVersion) {
        return "MOD_UPDATE:simupack:" + currentVersion + "->" + expectedVersion;
    }
}
