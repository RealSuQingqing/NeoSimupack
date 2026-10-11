package com.rstech.simupack.attention.provider;

import com.rstech.simupack.attention.AttentionContext;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.attention.AttentionTestSupport;
import com.rstech.simupack.pack.BuildingPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.rstech.simupack.attention.AttentionTestSupport.collect;
import static com.rstech.simupack.attention.AttentionTestSupport.context;
import static com.rstech.simupack.attention.AttentionTestSupport.empty;
import static com.rstech.simupack.attention.AttentionTestSupport.healthy;
import static com.rstech.simupack.attention.AttentionTestSupport.unreadable;
import static com.rstech.simupack.attention.AttentionTestSupport.withErrors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 类：故障拓展包。
 *
 * <p>这里最重要的一条是"与列表红灯同源"：provider 用 {@code faulty()} 而不是
 * {@code unusable()}。用错的话会出现"列表里红着、主菜单却不亮"的不一致，
 * 而那种不一致用户不会报告成 bug，只会觉得红点不准。
 */
class FaultyPackAttentionProviderTest {

    private final FaultyPackAttentionProvider provider = new FaultyPackAttentionProvider();

    @Test
    void healthyPackIsNotReported(@TempDir Path dir) {
        AttentionContext context = context(List.of(healthy(dir.resolve("city.zip"))),
                List.of(), new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));

        assertTrue(collect(provider, context).isEmpty());
    }

    @Test
    void unreadablePackIsReported(@TempDir Path dir) {
        AttentionContext context = context(List.of(unreadable(dir.resolve("broken.zip"))),
                List.of(), new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));

        List<AttentionItem> items = collect(provider, context);
        assertEquals(1, items.size());
        assertEquals(AttentionKind.FAULTY_PACK, items.get(0).kind());
    }

    @Test
    void packWithFormatErrorsIsReported(@TempDir Path dir) {
        AttentionContext context = context(List.of(withErrors(dir.resolve("partial.zip"))),
                List.of(), new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));

        assertEquals(1, collect(provider, context).size());
    }

    @Test
    void packWithNoBuildingsIsReported(@TempDir Path dir) {
        AttentionContext context = context(List.of(AttentionTestSupport.empty(dir.resolve("zero.zip"))),
                List.of(), new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));

        assertEquals(1, collect(provider, context).size());
    }

    @Test
    void pendingLegacyPackIsReported(@TempDir Path dir) {
        Path legacy = dir.resolve("old_pack.simupack");
        AttentionContext context = context(List.of(), List.of(legacy),
                new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));

        List<AttentionItem> items = collect(provider, context);
        assertEquals(1, items.size());
        assertEquals(AttentionKind.FAULTY_PACK, items.get(0).kind());
        assertEquals(FaultyPackAttentionProvider.LEGACY_BODY_KEY, items.get(0).bodyKey());
    }

    /**
     * 同一条问题在启用↔禁用切换后 key 必须稳定。
     *
     * <p>这正是<b>不能</b>用 {@code BuildingPack.packId()} 的原因：它带
     * {@code #disabled} 后缀，切一次就换一个 key。红点本身不吃 key（它只看非空），
     * 但 {@code PromptPlanner} 的去重与将来可能的排障都靠它，
     * 而且"同一条问题翻倍"会让 <code>count</code> 之类的统计说谎。
     */
    @Test
    void keyIsStableAcrossActivationToggling(@TempDir Path dir) {
        Path file = dir.resolve("city.zip");
        BuildingPack enabled = new BuildingPack("city", "City", "1.0", "", "", file,
                0, List.of(), false, true, true);
        BuildingPack disabled = new BuildingPack("city", "City", "1.0", "", "", file,
                0, List.of(), false, false, true);

        // 前提：packId 确实会变（说明这条测试防的是什么）。
        assertNotEquals(enabled.packId(), disabled.packId());

        AttentionContext contextEnabled = context(List.of(enabled), List.of(),
                new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));
        AttentionContext contextDisabled = context(List.of(disabled), List.of(),
                new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));

        assertEquals(collect(provider, contextEnabled).get(0).key(),
                collect(provider, contextDisabled).get(0).key());
    }

    /** 路径大小写不同不该算两条：Windows 上它们指向同一个文件。 */
    @Test
    void keyIgnoresPathCasing(@TempDir Path dir) {
        AttentionContext upper = context(List.of(unreadable(dir.resolve("City.ZIP"))), List.of(),
                new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));
        AttentionContext lower = context(List.of(unreadable(dir.resolve("city.zip"))), List.of(),
                new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));

        assertEquals(collect(provider, upper).get(0).key(),
                collect(provider, lower).get(0).key());
    }

    /** 旧包与转换产物同名时不能互相顶掉（转换结果是固定同名的 .zip）。 */
    @Test
    void legacyAndConvertedPackDoNotShareAKey(@TempDir Path dir) {
        Path legacy = dir.resolve("old_pack.zip");
        AttentionContext context = context(List.of(unreadable(dir.resolve("old_pack.zip"))),
                List.of(legacy),
                new AttentionTestSupport.FakeOfficialVersions(null, null),
                new AttentionTestSupport.FakeSelfUpdate(null, null));

        List<AttentionItem> items = collect(provider, context);
        assertEquals(2, items.size());
        assertNotEquals(items.get(0).key(), items.get(1).key());
    }

    /** 故障包<b>不弹窗</b>：它的解释与操作都在管理器列表里。 */
    @Test
    void faultyPacksNeverPrompt() {
        assertFalse(AttentionKind.FAULTY_PACK.promptsUser());
        assertFalse(AttentionItem.of(AttentionKind.FAULTY_PACK, "k", "t", "b").promptsUser());
    }

    @Test
    void emptyContextProducesNothing() {
        assertTrue(collect(provider, empty()).isEmpty());
    }
}
