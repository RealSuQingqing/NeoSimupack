package com.rstech.simupack.attention;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合中心的四条硬规则。
 *
 * <p>这些规则平时看不出来，出问题时却都是用户直接碰到的：
 * 渲染路径做 IO 会卡帧、任务堆积会越玩越卡、一个 provider 坏掉会让三类红点全灭、
 * 初始快照非空会让红点先亮一下再灭（看起来像"点了个假的"）。
 */
class AttentionCenterTest {

    @AfterEach
    void tearDown() {
        AttentionCenter.resetForTesting();
    }

    /** 一个永远抛异常的 provider：模拟 NSUK 改名、读盘炸了之类。 */
    private static final AttentionProvider BROKEN = (context, sink) -> {
        throw new IllegalStateException("provider 坏了");
    };

    private static AttentionProvider producing(AttentionKind kind) {
        return (context, sink) -> sink.accept(
                AttentionItem.of(kind, "key:" + kind, "title", "body"));
    }

    // ── 规则 4：初始快照为空 ───────────────────────────────────────────

    @Test
    void initialSnapshotIsEmptySoTheDotStartsDark() {
        // resetForTesting 之后就是进程刚起来的状态：还没算出任何结论，绝不点灯。
        assertTrue(AttentionCenter.snapshot().isEmpty());
        assertTrue(AttentionCenter.snapshot().neverComputed());
    }

    // ── 规则 3：每个 provider 单独隔离 ─────────────────────────────────

    @Test
    void abrokenProviderDoesNotTakeDownTheOthers() {
        AttentionCenter.setProvidersForTesting(List.of(
                producing(AttentionKind.FAULTY_PACK), BROKEN));
        AttentionCenter.setContextFactoryForTesting(AttentionTestSupport::empty);

        AttentionSnapshot snapshot = AttentionCenter.refreshNow();

        assertEquals(1, snapshot.items().size(), "坏的只丢它那一类，好的照常产出");
        assertTrue(snapshot.has(AttentionKind.FAULTY_PACK));
    }

    @Test
    void allProvidersBrokenStillYieldsAnEmptySnapshotInsteadOfThrowing() {
        AttentionCenter.setProvidersForTesting(List.of(BROKEN, BROKEN));
        AttentionCenter.setContextFactoryForTesting(AttentionTestSupport::empty);

        // 不该抛：渲染线程与界面操作都不该因为红点算不出来而炸。
        assertTrue(AttentionCenter.refreshNow().isEmpty());
    }

    /** 上下文都建不出来（NSUK 缺失、FML 未就绪）时降级成空快照，而不是崩。 */
    @Test
    void contextFailureDegradesToAnEmptySnapshot() {
        AttentionCenter.setContextFactoryForTesting(() -> {
            throw new IllegalStateException("没有 NSUK");
        });

        assertTrue(AttentionCenter.refreshNow().isEmpty());
    }

    // ── 规则 2：重入不堆任务 ───────────────────────────────────────────

    @Test
    void aSecondRefreshWhileOneIsRunningIsDropped() {
        AtomicInteger submitted = new AtomicInteger();
        // 一个"只登记不执行"的执行器：任务留在队列里，刷新标志保持 true。
        Executor pending = command -> submitted.incrementAndGet();
        AttentionCenter.setProvidersForTesting(List.of(producing(AttentionKind.FAULTY_PACK)));
        AttentionCenter.setContextFactoryForTesting(AttentionTestSupport::empty);

        assertTrue(AttentionCenter.refreshAsync(pending), "第一次应当发起");
        assertTrue(AttentionCenter.isRefreshing());
        assertFalse(AttentionCenter.refreshAsync(pending), "已在跑时第二次应当被丢掉");
        assertFalse(AttentionCenter.refreshAsync(pending), "第三次同样");
        assertEquals(1, submitted.get(), "窗口缩放连点不该把任务堆成队列");
    }

    @Test
    void theBusyFlagIsClearedAfterARefreshSoTheNextOneRuns() {
        AttentionCenter.setProvidersForTesting(List.of(producing(AttentionKind.FAULTY_PACK)));
        AttentionCenter.setContextFactoryForTesting(AttentionTestSupport::empty);

        AttentionCenter.refreshAsync(null); // 同步跑（Runnable::run）
        assertFalse(AttentionCenter.isRefreshing());
        assertTrue(AttentionCenter.refreshAsync(null), "跑完之后应当能再次刷新");
    }

    /** 执行器拒绝任务（关服、线程池已停）时必须把标志放回去，否则红点永远不再刷新。 */
    @Test
    void rejectedSubmissionReleasesTheBusyFlag() {
        Executor rejecting = command -> {
            throw new java.util.concurrent.RejectedExecutionException("已停止");
        };
        AttentionCenter.setProvidersForTesting(List.of(producing(AttentionKind.FAULTY_PACK)));
        AttentionCenter.setContextFactoryForTesting(AttentionTestSupport::empty);

        assertThrows(RuntimeException.class, () -> AttentionCenter.refreshAsync(rejecting));
        assertFalse(AttentionCenter.isRefreshing(), "拒绝之后必须能重来");
    }

    // ── 规则 1：快照只读且原子替换 ─────────────────────────────────────

    @Test
    void snapshotIsReplacedWholesaleAndNeverMutated() {
        AttentionCenter.setProvidersForTesting(List.of(producing(AttentionKind.FAULTY_PACK)));
        AttentionCenter.setContextFactoryForTesting(AttentionTestSupport::empty);

        AttentionSnapshot first = AttentionCenter.refreshNow();
        assertEquals(1, first.items().size());

        AttentionCenter.setProvidersForTesting(List.of());
        AttentionSnapshot second = AttentionCenter.refreshNow();

        assertTrue(second.isEmpty());
        assertEquals(1, first.items().size(), "旧快照必须原封不动（它是不可变对象）");
        assertTrue(AttentionCenter.snapshot().isEmpty(), "读到的应当是新快照");
    }

    @Test
    void snapshotItemsAreImmutable() {
        AttentionCenter.setProvidersForTesting(List.of(producing(AttentionKind.PACK_UPDATE)));
        AttentionCenter.setContextFactoryForTesting(AttentionTestSupport::empty);

        AttentionSnapshot snapshot = AttentionCenter.refreshNow();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.items().add(AttentionItem.of(AttentionKind.MOD_UPDATE, "x", "t", "b")));
    }

    // ── 快照辅助语义 ───────────────────────────────────────────────────

    @Test
    void countAndHasFilterByKind() {
        AttentionSnapshot snapshot = new AttentionSnapshot(List.of(
                AttentionItem.of(AttentionKind.FAULTY_PACK, "a", "t", "b"),
                AttentionItem.of(AttentionKind.FAULTY_PACK, "b", "t", "b"),
                AttentionItem.of(AttentionKind.PACK_UPDATE, "c", "t", "b")), 1L);

        assertEquals(2, snapshot.count(AttentionKind.FAULTY_PACK));
        assertEquals(1, snapshot.count(AttentionKind.PACK_UPDATE));
        assertEquals(0, snapshot.count(AttentionKind.MOD_UPDATE));
        assertTrue(snapshot.has(AttentionKind.PACK_UPDATE));
        assertFalse(snapshot.has(AttentionKind.MOD_UPDATE));
        assertFalse(snapshot.isEmpty());
    }

    @Test
    void promptingItemsExcludeFaultyPacks() {
        AttentionSnapshot snapshot = new AttentionSnapshot(List.of(
                AttentionItem.of(AttentionKind.FAULTY_PACK, "a", "t", "b"),
                AttentionItem.of(AttentionKind.PACK_UPDATE, "c", "t", "b")), 1L);

        assertEquals(1, snapshot.promptingItems().size());
        assertEquals(AttentionKind.PACK_UPDATE, snapshot.promptingItems().get(0).kind());
    }

    /**
     * {@code computedAt} 每次都不同，所以"内容变没变"必须能单独判断
     * （否则渲染器会每帧认为变了、每帧写一条日志）。
     */
    @Test
    void equalityComparesContentNotTheTimestamp() {
        AttentionItem item = AttentionItem.of(AttentionKind.PACK_UPDATE, "c", "t", "b");
        AttentionSnapshot earlier = new AttentionSnapshot(List.of(item), 1L);
        AttentionSnapshot later = new AttentionSnapshot(List.of(item), 999L);

        assertEquals(earlier, later);
        assertEquals(earlier.hashCode(), later.hashCode());
    }

    @Test
    void nullItemsNormalizeToEmpty() {
        assertTrue(new AttentionSnapshot(null, 1L).isEmpty());
        assertTrue(AttentionItem.of(AttentionKind.FAULTY_PACK, "k", "t", "b").args().isEmpty());
    }

    @Test
    void millisSinceLastRefreshIsHugeBeforeAnyRefresh() {
        assertEquals(Long.MAX_VALUE, AttentionCenter.millisSinceLastRefresh());
    }

    @Test
    void millisSinceLastRefreshIsSmallRightAfterARefresh() {
        AttentionCenter.setProvidersForTesting(List.of());
        AttentionCenter.setContextFactoryForTesting(AttentionTestSupport::empty);

        AttentionCenter.refreshAsync(null);

        assertTrue(AttentionCenter.millisSinceLastRefresh() < 5_000L);
    }
}
