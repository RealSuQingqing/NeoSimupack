package com.rstech.simupack.attention;

import com.rstech.simupack.attention.provider.FaultyPackAttentionProvider;
import com.rstech.simupack.attention.provider.OfficialPackUpdateAttentionProvider;
import com.rstech.simupack.attention.provider.SelfModUpdateAttentionProvider;
import com.rstech.simupack.pack.BuildingPackManager;
import com.rstech.simupack.pack.legacy.LegacyPackInstaller;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 红点的聚合中心：把三个 provider 的结果合成一份不可变快照，并负责后台刷新。
 *
 * <p><b>四条硬规则</b>（踩过坑的地方）：
 * <ol>
 *   <li>{@link #snapshot()} 是 O(1) 只读，返回 {@link AtomicReference} 里的不可变对象
 *       （与 {@code BuildingPackManager.PACKS} 同一套写法）——<b>渲染路径上零 IO</b>。
 *       红点每帧都要判断亮不亮，而算一次快照要读盘、读 ZIP、反射问 NSUK，两者必须彻底分开；</li>
 *   <li>{@link #refreshAsync()} 走注入的 executor（游戏侧是 {@code Util.backgroundExecutor()}），
 *       并且带"已在跑"标志，避免窗口缩放等高频触发把任务堆积成队列；</li>
 *   <li><b>每个 provider 单独 try/catch</b>：一个坏掉只丢它那一类，另外两类红点照常；</li>
 *   <li><b>初始快照为空</b>（不亮）。没算出结论前绝不点灯——先亮一下再灭看起来像"点了个假的"。</li>
 * </ol>
 *
 * <p>本类刻意<b>不引用任何 Minecraft 类</b>：executor 由调用方注入。
 * 这样"异常隔离""重入不堆任务""初始为空"这三条都能在 JUnit 里钉住。
 */
public final class AttentionCenter {

    /**
     * 抑制日志；默认 true，由游戏侧启动时关掉。
     *
     * <p>同 {@code PackActivation} / {@code NsukCatalogBridge} 的理由：
     * {@code SimuPackMod.LOGGER} 是静态字段，单元测试运行时 slf4j 不在 classpath 上，
     * 第一次碰它就会 {@code NoClassDefFoundError} 并把类初始化搞坏。
     */
    private static volatile boolean quiet = true;

    /** 由游戏侧调用一次：之后启用日志。 */
    public static void enableLogging() {
        quiet = false;
    }

    private static final AtomicReference<AttentionSnapshot> SNAPSHOT =
            new AtomicReference<>(AttentionSnapshot.EMPTY);

    /** "已在跑"标志：避免高频触发把后台任务堆成队列。 */
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);

    private static volatile long lastAttemptMillis;

    /** 生产用的 provider 列表；顺序无关紧要（快照的顺序由 provider 自己决定，弹窗顺序由 PromptPlanner 定）。 */
    private static volatile List<AttentionProvider> providers = defaultProviders();

    /** 生产用的上下文工厂；测试可替换。 */
    private static volatile java.util.function.Supplier<AttentionContext> contextFactory =
            AttentionCenter::productionContext;

    private static volatile Executor executor = Runnable::run;

    private AttentionCenter() {
    }

    // ── 只读路径（渲染线程） ───────────────────────────────────────────

    /** 当前快照；O(1)、无 IO、线程安全。渲染路径只能调它。 */
    public static AttentionSnapshot snapshot() {
        return SNAPSHOT.get();
    }

    /** 距上次<b>发起</b>刷新的毫秒数；界面用它决定要不要再触发一次。 */
    public static long millisSinceLastRefresh() {
        long last = lastAttemptMillis;
        return last <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    public static boolean isRefreshing() {
        return REFRESHING.get();
    }

    // ── 刷新 ───────────────────────────────────────────────────────────

    /**
     * 后台刷新一次快照。
     *
     * <p>已经有一次在跑时<b>直接返回</b>，不排队：窗口缩放、连续点 P 都会触发刷新，
     * 排队只会让最后算出的是同一份结果，白烧 CPU。
     *
     * @param executor 后台执行器；传 {@code null} 表示在当前线程同步跑（单测用）
     * @return true 表示这次真的发起了刷新；false 表示已有一个在跑
     */
    public static boolean refreshAsync(Executor executor) {
        if (!REFRESHING.compareAndSet(false, true)) {
            return false;
        }
        lastAttemptMillis = System.currentTimeMillis();
        Executor target = executor == null ? Runnable::run : executor;
        try {
            target.execute(AttentionCenter::refreshNow);
        } catch (RuntimeException exception) {
            // 执行器拒绝任务（关服、线程池已停）时必须把标志放回去，
            // 否则红点会永远停在"刷新中"、再也不刷新。
            REFRESHING.set(false);
            debug("提交红点刷新任务失败", exception);
            throw exception;
        }
        return true;
    }

    /**
     * 同步算一次并更新快照；异常已逐个隔离，本方法不会抛。
     *
     * <p>包 {@code public} 是为了让单测能同步驱动，不必等后台线程。
     */
    public static AttentionSnapshot refreshNow() {
        AttentionSnapshot computed = compute();
        SNAPSHOT.set(computed);
        REFRESHING.set(false);
        return computed;
    }

    /** 纯计算：跑一遍所有 provider，不碰静态快照。 */
    static AttentionSnapshot compute() {
        List<AttentionItem> items = new ArrayList<>();
        AttentionContext context;
        try {
            context = contextFactory.get();
        } catch (RuntimeException | LinkageError exception) {
            // 连上下文都建不出来（NSUK 缺失、FML 未就绪）：退化成空快照，不点灯。
            debug("构建红点上下文失败", exception);
            return new AttentionSnapshot(List.of(), System.currentTimeMillis());
        }

        for (AttentionProvider provider : providers) {
            try {
                provider.collect(context, items::add);
            } catch (RuntimeException | LinkageError exception) {
                // 一个 provider 坏掉只丢它那一类，另外两类照常。
                debug("红点 provider " + provider.getClass().getSimpleName() + " 失败", exception);
            }
        }
        return new AttentionSnapshot(items, System.currentTimeMillis());
    }

    /** 直接写一份快照；只给测试与"用假快照先做渲染"用。 */
    static void setSnapshotForTesting(AttentionSnapshot snapshot) {
        SNAPSHOT.set(snapshot == null ? AttentionSnapshot.EMPTY : snapshot);
    }

    /** 替换 provider 列表；只给测试用。 */
    static void setProvidersForTesting(List<AttentionProvider> replacement) {
        providers = replacement == null ? List.of() : List.copyOf(replacement);
    }

    /** 替换上下文工厂；只给测试用。 */
    static void setContextFactoryForTesting(java.util.function.Supplier<AttentionContext> factory) {
        contextFactory = factory == null ? AttentionCenter::productionContext : factory;
    }

    /** 替换执行器；只给测试用。 */
    static void setExecutorForTesting(Executor replacement) {
        executor = replacement == null ? Runnable::run : replacement;
    }

    /** 复位成初始状态（空快照 + 不忙）；只给测试用。 */
    static void resetForTesting() {
        SNAPSHOT.set(AttentionSnapshot.EMPTY);
        REFRESHING.set(false);
        lastAttemptMillis = 0L;
        providers = defaultProviders();
        contextFactory = AttentionCenter::productionContext;
        executor = Runnable::run;
    }

    /** 游戏侧提交后台刷新用的执行器。 */
    static Executor executor() {
        return executor;
    }

    private static List<AttentionProvider> defaultProviders() {
        return List.of(
                new FaultyPackAttentionProvider(),
                new OfficialPackUpdateAttentionProvider(),
                new SelfModUpdateAttentionProvider());
    }

    /**
     * 生产上下文。
     *
     * <p>两个 supplier 都用方法引用而不是取值：包列表可能在刷新途中被别的操作改掉，
     * 每次取最新值才不会拿着过期快照算出马上就该变的结果。
     *
     * <p>版本来源的构造放在这里而不是静态初始化：{@link NsukOfficialPackVersionSource}
     * 是反射桥，它的类加载不该在"没装 NSUK"的环境里出问题——
     * 放在方法体里，只在真正刷新时才碰它。
     */
    private static AttentionContext productionContext() {
        return new AttentionContext(
                BuildingPackManager::packs,
                AttentionCenter::pendingLegacyPacks,
                new NsukOfficialPackVersionSource(),
                new JsonSelfUpdateVersionSource());
    }

    /**
     * 建筑目录里待转换的旧包。
     *
     * <p>包一层 try/catch：旧包探测要读 ZIP，任何 IO 异常都不该让 A 类整类消失
     * （"有坏包"这类问题即使旧包扫描失败也应该照常亮）。
     */
    private static List<java.nio.file.Path> pendingLegacyPacks() {
        try {
            return LegacyPackInstaller.pendingInBuildingDirectory().stream()
                    .map(com.rstech.simupack.pack.legacy.LegacyPackDetector.LegacyPackInfo::path)
                    .toList();
        } catch (RuntimeException | LinkageError exception) {
            debug("扫描待转换旧包失败", exception);
            return List.of();
        }
    }

    private static void debug(String message, Throwable cause) {
        if (quiet) {
            return;
        }
        com.rstech.simupack.SimuPackMod.LOGGER.debug(message, cause);
    }

    /**
     * 快照内容变化时记一条 debug 日志。
     *
     * <p>由渲染侧在"内容真的变了"时调用（它知道上一次画的是什么），
     * 避免每帧刷屏——这类日志一旦刷屏，真正要看的那条就淹没了。
     */
    public static void debugSnapshot(AttentionSnapshot snapshot) {
        if (quiet || snapshot == null) {
            return;
        }
        com.rstech.simupack.SimuPackMod.LOGGER.debug(
                "红点快照更新：{} 项（故障 {} / 官方包更新 {} / 模组更新 {}）",
                snapshot.items().size(),
                snapshot.count(AttentionKind.FAULTY_PACK),
                snapshot.count(AttentionKind.PACK_UPDATE),
                snapshot.count(AttentionKind.MOD_UPDATE));
    }
}
