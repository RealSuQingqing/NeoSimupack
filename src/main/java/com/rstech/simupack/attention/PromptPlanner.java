package com.rstech.simupack.attention;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 弹窗排队规则：<b>纯函数</b>，便于单测。
 *
 * <p>三条规则都靠这一个函数钉住，不需要起客户端：
 * <ol>
 *   <li>"同一 key 只弹一次"——已经提示过的 key 被排除；</li>
 *   <li>"顺序稳定"——固定按 kind 排序（PACK_UPDATE 先于 MOD_UPDATE）；</li>
 *   <li>"版本变了重新弹"——key 里带版本对，版本一变就是新 key，
 *       自然重新进入待弹列表。</li>
 * </ol>
 *
 * <p><b>弹窗去重 ≠ 红点去重</b>：这里只决定"要不要再告知一次"，
 * 红点亮不亮完全由 {@link AttentionSnapshot} 决定，两者互不影响。
 * 用户点「稍后」之后，弹窗不再弹，但红点继续亮到问题真的被解决。
 */
public final class PromptPlanner {

    private PromptPlanner() {
    }

    /**
     * {@link AttentionKind} 的弹窗顺序权重。
     *
     * <p>官方包更新排在模组更新之前：前者能在管理器里当场解决（有「立即更新」按钮），
     * 后者必须退出去换 jar 重启。先处理能立刻闭环的那一个，用户更容易形成"弹窗=有事要做"的预期。
     */
    private static final Map<AttentionKind, Integer> ORDER = Map.of(
            AttentionKind.PACK_UPDATE, 0,
            AttentionKind.MOD_UPDATE, 1,
            AttentionKind.FAULTY_PACK, 2);

    /**
     * 挑出这次该弹的项。
     *
     * @param snapshot        当前快照
     * @param alreadyPrompted 已提示过的 {@link AttentionItem#key()} 集合（来自配置）
     * @return 有序的待弹列表；调用方负责"一个关掉后弹下一个"
     */
    public static List<AttentionItem> pending(AttentionSnapshot snapshot,
                                              Set<String> alreadyPrompted) {
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        Set<String> prompted = alreadyPrompted == null ? Set.of() : alreadyPrompted;

        // 同一个 key 重复出现只留一条：key 是稳定身份，重复说明上游算重了，
        // 这里兜住，免得同一个问题弹两次。
        Map<String, AttentionItem> unique = new LinkedHashMap<>();
        for (AttentionItem item : snapshot.items()) {
            if (!item.promptsUser()) {
                continue;
            }
            if (prompted.contains(item.key())) {
                continue;
            }
            unique.putIfAbsent(item.key(), item);
        }
        if (unique.isEmpty()) {
            return List.of();
        }

        List<AttentionItem> ordered = new ArrayList<>(unique.values());
        // 排序要稳定：同一 kind 内保持上游产出顺序（LinkedHashMap 保序），
        // 这样每次打开管理器看到的弹窗顺序完全一致。
        ordered.sort(Comparator.comparingInt(item -> ORDER.getOrDefault(item.kind(), 99)));
        return List.copyOf(ordered);
    }
}
