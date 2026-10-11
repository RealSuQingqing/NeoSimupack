package com.rstech.simupack.attention.provider;

import com.rstech.simupack.attention.AttentionContext;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.attention.AttentionTestSupport;
import com.rstech.simupack.attention.AttentionTestSupport.FakeOfficialVersions;
import com.rstech.simupack.attention.AttentionTestSupport.FakeSelfUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.rstech.simupack.attention.AttentionTestSupport.collect;
import static com.rstech.simupack.attention.AttentionTestSupport.context;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B 类：官方建筑包更新。
 *
 * <p>五种情形里<b>只有"本地旧"应该产出</b>。其余四种（相同、本地更新、两侧都缺、只缺一侧）
 * 都必须保持沉默——红点在没问题时亮起来，比漏报更糟：用户会去点一个点了没反应的更新按钮。
 */
class OfficialPackUpdateAttentionProviderTest {

    private final OfficialPackUpdateAttentionProvider provider =
            new OfficialPackUpdateAttentionProvider();

    private static AttentionContext withVersions(String local, String builtin) {
        return context(List.of(), List.of(), new FakeOfficialVersions(local, builtin),
                new FakeSelfUpdate(null, null));
    }

    @Test
    void localOlderThanBuiltinIsReported() {
        List<AttentionItem> items = collect(provider, withVersions("2.1.0", "2.2.0"));

        assertEquals(1, items.size());
        AttentionItem item = items.get(0);
        assertEquals(AttentionKind.PACK_UPDATE, item.kind());
        // 弹窗要显示 "本地 2.1.0 → 内置 2.2.0"，两个版本串都得在参数里。
        assertEquals(List.of("2.1.0", "2.2.0"), item.args());
    }

    @Test
    void equalVersionsAreNotReported() {
        // 当前开发环境的真实状态：两侧都是 2.2.0 -> 不亮。
        assertTrue(collect(provider, withVersions("2.2.0", "2.2.0")).isEmpty());
    }

    @Test
    void localNewerThanBuiltinIsNotReported() {
        // 用户自己装了更新的官方包：不该劝他"更新"回旧的内置版本。
        assertTrue(collect(provider, withVersions("2.3.0", "2.2.0")).isEmpty());
    }

    @Test
    void bothSidesMissingIsNotReported() {
        assertTrue(collect(provider, withVersions(null, null)).isEmpty());
    }

    @Test
    void missingEitherSideIsNotReported() {
        // fail-closed：任一侧读不出来就判 UNKNOWN，不亮。
        assertTrue(collect(provider, withVersions(null, "2.2.0")).isEmpty());
        assertTrue(collect(provider, withVersions("2.1.0", null)).isEmpty());
    }

    /**
     * key 必须带版本对：这样"版本又变了"会重新弹窗，
     * 而"同一个版本对"只弹一次。
     */
    @Test
    void keyEncodesBothVersionsSoANewerReleasePromptsAgain() {
        String first = collect(provider, withVersions("2.1.0", "2.2.0")).get(0).key();
        String second = collect(provider, withVersions("2.1.0", "2.3.0")).get(0).key();

        assertNotEquals(first, second, "版本对变了必须换一个新 key，否则更新永远不会再提示");
    }

    @Test
    void sameVersionPairProducesTheSameKey() {
        assertEquals(
                collect(provider, withVersions("2.1.0", "2.2.0")).get(0).key(),
                collect(provider, withVersions("2.1.0", "2.2.0")).get(0).key());
    }

    /** B 类是要弹窗的。 */
    @Test
    void packUpdatePromptsTheUser() {
        assertTrue(AttentionKind.PACK_UPDATE.promptsUser());
    }

    /** 版本比较必须与 NSUK 一致，否则会出现"红点亮着、点更新却说已是最新"。 */
    @Test
    void numericComparisonIsNotLexicographic() {
        // 字符串比较会认为 2.10.0 < 2.9.0，那样就会漏报。
        assertEquals(1, collect(provider, withVersions("2.9.0", "2.10.0")).size());
    }
}
