package com.rstech.simupack.attention;

import java.util.List;
import java.util.Objects;

/**
 * 聚合后的不可变快照。
 *
 * <p>渲染线程只读它，<b>永不触发 IO</b>：红点每帧都要判断"亮不亮"，而算一次快照要读盘、
 * 读 ZIP、反射问 NSUK。两者必须彻底分开（见 {@link AttentionCenter}）。
 *
 * @param items      关注项；不可变
 * @param computedAt 算出这份快照的时刻（{@code System.currentTimeMillis()}），供"距上次刷新多久"用
 */
public record AttentionSnapshot(List<AttentionItem> items, long computedAt) {

    /**
     * 空快照：<b>没算出结论前绝不点灯</b>。
     *
     * <p>它同时是 {@link AttentionCenter} 的初始值。启动早期 provider 还没跑完时，
     * 红点必须是灭的——宁可晚一会儿亮，也不能先亮一下再灭（那看起来像"点了个假的"）。
     */
    public static final AttentionSnapshot EMPTY = new AttentionSnapshot(List.of(), 0L);

    public AttentionSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 没有任何关注项——红点灭。 */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** 某一类的数量；红点本身不用，但排障与将来的展示会用到。 */
    public long count(AttentionKind kind) {
        return items.stream().filter(item -> item.kind() == kind).count();
    }

    /** 某一类是否存在。 */
    public boolean has(AttentionKind kind) {
        return items.stream().anyMatch(item -> item.kind() == kind);
    }

    /** 需要弹窗的项（已按 {@link PromptPlanner} 的规则排序前的原始顺序）。 */
    public List<AttentionItem> promptingItems() {
        return items.stream().filter(AttentionItem::promptsUser).toList();
    }

    /** 这份快照是不是"还没算过"——用于决定要不要触发一次后台刷新。 */
    public boolean neverComputed() {
        return computedAt <= 0L;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof AttentionSnapshot snapshot && Objects.equals(items, snapshot.items);
    }

    /**
     * 只看内容，不看时刻。
     *
     * <p>{@code computedAt} 每次都不同，所以红色渲染器判断"内容变没变"时不能直接用
     * {@code equals}（那会每帧都认为变了、每帧写一条日志）。用这个哈希比较内容。
     */
    @Override
    public int hashCode() {
        return items.hashCode();
    }
}
