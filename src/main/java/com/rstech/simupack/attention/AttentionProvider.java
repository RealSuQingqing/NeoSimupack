package com.rstech.simupack.attention;

import java.util.function.Consumer;

/**
 * 一类关注项的采集器。
 *
 * <p>每个 provider 只负责<b>一类</b>问题，且必须能在没有 Minecraft 客户端的环境里跑完
 * （见 {@link AttentionCenter} 的异常隔离规则：一个坏掉只丢它那一类，另外两类红点照常）。
 *
 * <p>实现里<b>不要自己 try/catch 收场</b>成"静默返回空"——那是 {@code AttentionCenter} 的职责。
 * provider 抛出异常是允许的，它会被单独隔离并记一条 debug 日志，这样排查时能看到是哪一个坏了。
 */
@FunctionalInterface
public interface AttentionProvider {

    /**
     * 采集关注项，逐个交给 {@code sink}。
     *
     * @param context 注入的依赖（包列表、版本来源……），provider 不直接碰 FMLPaths / ModList
     * @param sink    每产出一项调用一次
     */
    void collect(AttentionContext context, Consumer<AttentionItem> sink);
}
