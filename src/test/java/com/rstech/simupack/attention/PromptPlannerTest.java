package com.rstech.simupack.attention;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹窗排队规则。
 *
 * <p>这个类是纯函数，所以"只弹一次""顺序稳定""版本变了重新弹"三条规则
 * 全都能在这里钉住，不需要起客户端。
 *
 * <p>要记住的一条边界：<b>弹窗去重 ≠ 红点去重</b>。这里只决定"要不要再告知一次"，
 * 红点亮不亮完全由快照决定——用户点「稍后」后弹窗不再弹，但红点继续亮到问题真的解决。
 */
class PromptPlannerTest {

    private static AttentionItem packUpdate(String local, String builtin) {
        return new AttentionItem(AttentionKind.PACK_UPDATE,
                "PACK_UPDATE:official:" + local + "->" + builtin,
                "title", "body", List.of(local, builtin));
    }

    private static AttentionItem modUpdate(String current, String expected) {
        return new AttentionItem(AttentionKind.MOD_UPDATE,
                "MOD_UPDATE:simupack:" + current + "->" + expected,
                "title", "body", List.of(current, expected));
    }

    private static AttentionItem faulty(String path) {
        return new AttentionItem(AttentionKind.FAULTY_PACK, path, "title", "body",
                List.of("broken.zip"));
    }

    private static AttentionSnapshot snapshot(AttentionItem... items) {
        return new AttentionSnapshot(List.of(items), System.currentTimeMillis());
    }

    @Test
    void faultyPacksNeverEnterThePromptQueue() {
        // 故障包靠管理器列表的红灯表达，不弹窗。
        List<AttentionItem> pending = PromptPlanner.pending(
                snapshot(faulty("/a/broken.zip"), packUpdate("2.1.0", "2.2.0")), Set.of());

        assertEquals(1, pending.size());
        assertEquals(AttentionKind.PACK_UPDATE, pending.get(0).kind());
    }

    @Test
    void alreadyPromptedKeysAreExcluded() {
        AttentionItem item = packUpdate("2.1.0", "2.2.0");
        assertTrue(PromptPlanner.pending(snapshot(item), Set.of(item.key())).isEmpty());
    }

    @Test
    void aNewVersionPairEntersTheQueueAgain() {
        // 用户点过「稍后」，所以 2.1.0->2.2.0 不再弹；
        // 但一旦内置版本涨到 2.3.0，那是一件新的事，应该重新告知。
        Set<String> alreadyPrompted = Set.of(packUpdate("2.1.0", "2.2.0").key());

        List<AttentionItem> pending = PromptPlanner.pending(
                snapshot(packUpdate("2.1.0", "2.3.0")), alreadyPrompted);

        assertEquals(1, pending.size());
        assertEquals("PACK_UPDATE:official:2.1.0->2.3.0", pending.get(0).key());
    }

    @Test
    void packUpdateSortsBeforeModUpdateRegardlessOfInputOrder() {
        // 固定顺序：能当场解决的（官方包）先弹，必须重启的（模组）后弹。
        List<AttentionItem> pending = PromptPlanner.pending(
                snapshot(modUpdate("0.1.0", "0.2.0"), packUpdate("2.1.0", "2.2.0")), Set.of());

        assertEquals(2, pending.size());
        assertEquals(AttentionKind.PACK_UPDATE, pending.get(0).kind());
        assertEquals(AttentionKind.MOD_UPDATE, pending.get(1).kind());
    }

    @Test
    void orderIsStableAcrossRepeatedCalls() {
        AttentionSnapshot snapshot = snapshot(modUpdate("0.1.0", "0.2.0"),
                packUpdate("2.1.0", "2.2.0"), faulty("/a/broken.zip"));

        List<String> first = PromptPlanner.pending(snapshot, Set.of()).stream()
                .map(AttentionItem::key).toList();
        List<String> second = PromptPlanner.pending(snapshot, Set.of()).stream()
                .map(AttentionItem::key).toList();

        assertEquals(first, second);
    }

    @Test
    void duplicateKeysCollapseToOneEntry() {
        // key 是稳定身份；上游算重了也不该让同一个问题弹两次。
        AttentionItem item = packUpdate("2.1.0", "2.2.0");
        List<AttentionItem> pending = PromptPlanner.pending(snapshot(item, item), Set.of());

        assertEquals(1, pending.size());
    }

    @Test
    void emptyOrNullInputsProduceNothing() {
        assertTrue(PromptPlanner.pending(null, Set.of()).isEmpty());
        assertTrue(PromptPlanner.pending(AttentionSnapshot.EMPTY, Set.of()).isEmpty());
        assertTrue(PromptPlanner.pending(AttentionSnapshot.EMPTY, null).isEmpty());
        // alreadyPrompted 为 null 等价于"什么都没提示过"。
        assertEquals(1, PromptPlanner.pending(snapshot(packUpdate("2.1.0", "2.2.0")), null).size());
    }

    /** 弹窗去重不改变快照本身——红点仍然是亮的。 */
    @Test
    void suppressingThePromptDoesNotClearTheAttention() {
        AttentionItem item = packUpdate("2.1.0", "2.2.0");
        AttentionSnapshot snapshot = snapshot(item);

        assertTrue(PromptPlanner.pending(snapshot, Set.of(item.key())).isEmpty());
        assertEquals(false, snapshot.isEmpty(), "点过「稍后」不等于问题解决了");
    }
}
