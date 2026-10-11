package com.rstech.simupack.attention.provider;

import com.rstech.simupack.attention.AttentionContext;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.attention.AttentionTestSupport.FakeOfficialVersions;
import com.rstech.simupack.attention.AttentionTestSupport.FakeSelfUpdate;
import com.rstech.simupack.attention.JsonSelfUpdateVersionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.rstech.simupack.attention.AttentionTestSupport.collect;
import static com.rstech.simupack.attention.AttentionTestSupport.context;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C 类：筑艺坊自身版本更新。
 *
 * <p>判据完全来自本地期望版本清单（{@code latest.json}），所以这里同时测两件事：
 * provider 的比较逻辑，以及清单读取的失败降级（文件不存在 / 损坏 / 字段缺失 → UNKNOWN → 不亮）。
 */
class SelfModUpdateAttentionProviderTest {

    private final SelfModUpdateAttentionProvider provider = new SelfModUpdateAttentionProvider();

    private static AttentionContext withVersions(String current, String expected) {
        return context(List.of(), List.of(), new FakeOfficialVersions(null, null),
                new FakeSelfUpdate(current, expected));
    }

    @Test
    void expectedNewerThanCurrentIsReported() {
        List<AttentionItem> items = collect(provider, withVersions("0.1.0", "0.2.0"));

        assertEquals(1, items.size());
        AttentionItem item = items.get(0);
        assertEquals(AttentionKind.MOD_UPDATE, item.kind());
        assertEquals(List.of("0.1.0", "0.2.0"), item.args());
    }

    @Test
    void equalVersionsAreNotReported() {
        assertTrue(collect(provider, withVersions("0.2.0", "0.2.0")).isEmpty());
    }

    @Test
    void lowerExpectedVersionIsNotReported() {
        // 清单里写了个更低的版本（用户手动写的、或清单过期）时不能误报。
        assertTrue(collect(provider, withVersions("0.2.0", "0.0.1")).isEmpty());
    }

    @Test
    void unknownOnEitherSideIsNotReported() {
        assertTrue(collect(provider, withVersions(null, "9.9.9")).isEmpty());
        assertTrue(collect(provider, withVersions("0.1.0", null)).isEmpty());
        assertTrue(collect(provider, withVersions(null, null)).isEmpty());
    }

    @Test
    void keyChangesWhenTheExpectedVersionChanges() {
        assertNotEquals(
                collect(provider, withVersions("0.1.0", "0.2.0")).get(0).key(),
                collect(provider, withVersions("0.1.0", "0.3.0")).get(0).key());
    }

    @Test
    void modUpdatePromptsTheUser() {
        assertTrue(AttentionKind.MOD_UPDATE.promptsUser());
    }

    // ── 清单文件读取（不碰 FMLPaths，直接喂路径） ─────────────────────

    @Test
    void readsVersionFromManifest(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(dir.resolve("latest.json"),
                "{\"simupack\": \"0.2.0\", \"source\": \"manual\", \"checkedAt\": 0}",
                StandardCharsets.UTF_8);

        assertEquals(java.util.Optional.of("0.2.0"),
                JsonSelfUpdateVersionSource.readExpected(manifest));
    }

    @Test
    void missingManifestIsUnknownNotAnError(@TempDir Path dir) {
        // 零网络时的默认状态：没有清单文件。这完全正常，不该亮红点。
        assertEquals(java.util.Optional.empty(),
                JsonSelfUpdateVersionSource.readExpected(dir.resolve("latest.json")));
        assertEquals(java.util.Optional.empty(),
                JsonSelfUpdateVersionSource.readExpected(null));
    }

    @Test
    void brokenManifestIsUnknown(@TempDir Path dir) throws Exception {
        // 损坏的清单 -> UNKNOWN -> 不亮。绝不因为一份清单写坏了就常亮红点。
        Path manifest = Files.writeString(dir.resolve("latest.json"), "{not json",
                StandardCharsets.UTF_8);

        assertEquals(java.util.Optional.empty(),
                JsonSelfUpdateVersionSource.readExpected(manifest));
    }

    @Test
    void manifestWithoutTheFieldOrWithWrongTypeIsUnknown(@TempDir Path dir) throws Exception {
        Path missingField = Files.writeString(dir.resolve("a.json"), "{\"source\": \"manual\"}",
                StandardCharsets.UTF_8);
        Path wrongType = Files.writeString(dir.resolve("b.json"), "{\"simupack\": 2}",
                StandardCharsets.UTF_8);
        Path blank = Files.writeString(dir.resolve("c.json"), "{\"simupack\": \"   \"}",
                StandardCharsets.UTF_8);
        Path arrayRoot = Files.writeString(dir.resolve("d.json"), "[]",
                StandardCharsets.UTF_8);

        assertEquals(java.util.Optional.empty(), JsonSelfUpdateVersionSource.readExpected(missingField));
        assertEquals(java.util.Optional.empty(), JsonSelfUpdateVersionSource.readExpected(wrongType));
        assertEquals(java.util.Optional.empty(), JsonSelfUpdateVersionSource.readExpected(blank));
        assertEquals(java.util.Optional.empty(), JsonSelfUpdateVersionSource.readExpected(arrayRoot));
    }

    @Test
    void manifestVersionIsTrimmed(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(dir.resolve("latest.json"), "{\"simupack\": \" 0.2.0 \"}",
                StandardCharsets.UTF_8);

        assertEquals(java.util.Optional.of("0.2.0"),
                JsonSelfUpdateVersionSource.readExpected(manifest));
    }

    /** 从清单读到版本后，provider 端到端地能判出更新。 */
    @Test
    void manifestDrivesTheProviderEndToEnd(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(dir.resolve("latest.json"), "{\"simupack\": \"9.9.9\"}",
                StandardCharsets.UTF_8);
        java.util.Optional<String> expected = JsonSelfUpdateVersionSource.readExpected(manifest);

        AttentionContext context = context(List.of(), List.of(), new FakeOfficialVersions(null, null),
                new FakeSelfUpdate("0.1.0", expected.orElse(null)));

        assertEquals(1, collect(provider, context).size());
    }
}
