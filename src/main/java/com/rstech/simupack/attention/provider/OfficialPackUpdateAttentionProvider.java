package com.rstech.simupack.attention.provider;

import com.rstech.simupack.attention.AttentionContext;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.attention.AttentionProvider;
import com.rstech.simupack.attention.OfficialPackVersionSource;
import com.rstech.simupack.attention.VersionCompare;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * B 类：官方建筑包更新。
 *
 * <p>判据与解决动作都由 NSUK 自己提供（{@code BuildingPackVersionChecker} /
 * {@code BuildingBuiltinResourceService.forceOverwrite}），我们只做聚合与呈现——
 * 自己再写一套解包/校验/替换只会有两个真相源。
 *
 * <p><b>用 {@code localVersion()} + {@code builtinVersion()} 而不是 {@code isLocalOutdated()}</b>：
 * 弹窗要显示"本地 2.1.0 → 内置 2.2.0"，两个版本串本来就得拿到；
 * {@code isLocalOutdated()} 内部也只是把这两个值比一下。
 *
 * <p><b>两侧都非空才比较</b>：任一侧读不出来就判 UNKNOWN → 不亮（fail-closed）。
 */
public final class OfficialPackUpdateAttentionProvider implements AttentionProvider {

    static final String TITLE_KEY = "gui.simupack.attention.pack_update.title";
    static final String BODY_KEY = "gui.simupack.attention.pack_update.body";

    @Override
    public void collect(AttentionContext context, Consumer<AttentionItem> sink) {
        OfficialPackVersionSource source = context.officialPackVersions();
        Optional<String> local = source.localVersion();
        Optional<String> builtin = source.builtinVersion();
        if (local.isEmpty() || builtin.isEmpty()) {
            return;
        }

        String localVersion = local.get();
        String builtinVersion = builtin.get();
        if (!VersionCompare.isOlderThan(localVersion, builtinVersion)) {
            return;
        }

        sink.accept(new AttentionItem(AttentionKind.PACK_UPDATE,
                // key 带版本对：版本再变（例如又发布了 2.3.0）时会重新提示。
                key(localVersion, builtinVersion),
                TITLE_KEY, BODY_KEY,
                List.of(localVersion, builtinVersion)));
    }

    /**
     * 弹窗去重键：{@code PACK_UPDATE:official:<本地>-><内置>}。
     *
     * <p>带上版本对是刻意的：用户点过「稍后」之后不再重复打扰，但一旦版本又变了，
     * 这是一件新的事，应该重新告知。
     */
    static String key(String localVersion, String builtinVersion) {
        return "PACK_UPDATE:official:" + localVersion + "->" + builtinVersion;
    }
}
