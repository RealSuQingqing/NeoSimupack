package com.rstech.simupack.attention;

import java.util.List;
import java.util.Objects;

/**
 * 一个关注项。
 *
 * <p>这是一个<b>纯数据</b>对象：只有翻译键与参数，没有任何 {@code Component}。
 * 理由与 {@code BuildingJsonDocument} 一致——聚合与去重逻辑要能在没有 Minecraft 客户端的
 * JUnit 里跑，而 {@code Component} 一碰就会把客户端类拉进测试运行时。
 * 翻译键到实际文案的转换发生在展示层（{@code UpdatePrompts}）。
 *
 * @param kind     分类；决定要不要弹窗（见 {@link AttentionKind#promptsUser()}）
 * @param key      稳定身份，同时用作弹窗去重键。
 *                 <p>更新类的 key 必须带上版本对（如 {@code PACK_UPDATE:official:2.1.0->2.2.0}），
 *                 这样版本再变时会重新提示；
 *                 <p>故障包的 key 是磁盘文件的规范化绝对路径——<b>不能用
 *                 {@link com.rstech.simupack.pack.BuildingPack#packId()}</b>，
 *                 它在启用/禁用切换后会变（{@code id} ↔ {@code id#disabled}），
 *                 会让同一条问题在红点里翻倍。
 * @param titleKey 弹窗标题的翻译键
 * @param bodyKey  弹窗正文的翻译键
 * @param args     正文的翻译参数（版本号、包名……）
 */
public record AttentionItem(AttentionKind kind, String key,
                            String titleKey, String bodyKey, List<Object> args) {

    public AttentionItem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(titleKey, "titleKey");
        Objects.requireNonNull(bodyKey, "bodyKey");
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** 无参数的便捷构造。 */
    public static AttentionItem of(AttentionKind kind, String key,
                                   String titleKey, String bodyKey) {
        return new AttentionItem(kind, key, titleKey, bodyKey, List.of());
    }

    /** 是否需要为这一项主动弹窗。 */
    public boolean promptsUser() {
        return kind.promptsUser();
    }
}
