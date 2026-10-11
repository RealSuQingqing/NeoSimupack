package com.rstech.simupack.pack;

import com.rstech.simupack.SimuPackMod;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import java.lang.reflect.Method;

/**
 * 让 NSUK（New Sim-U-Kraft）立刻重新扫描建筑目录。
 *
 * <p>为什么非做不可：NSUK 把 {@code simukraftbuilding/} 的扫描结果缓存在
 * {@code BuildingPackageCatalog.SNAPSHOTS} 里（key 是建筑目录路径），
 * {@code ensurePrepared()} 走的是 {@code computeIfAbsent}。
 * 也就是说筑艺坊往目录里放包、或者改名禁用包之后，NSUK 手里还是旧快照，
 * 必须有人显式调一次 {@code reload} 才会重新读盘——这正是"导入后要重启/刷新才生效"的根因。
 *
 * <p>用的是公开 API（javap 确认签名）：
 * <pre>
 * common.cn.kafei.simukraft.building.BuildingCatalog.reload()            // public static void
 * common.cn.kafei.simukraft.building.BuildingPackageCatalog.clearCache() // public static void
 * </pre>
 * 两个类都是 public，不涉及访问转换。
 *
 * <p>刻意<b>全程反射</b>而不是直接 {@code import}：NSUK 在 dev 环境是 {@code localRuntime}，
 * 缺了它这个模组仍然要能编译、能加载、界面要能用，只是"自动刷新"降级成"不刷新"。
 * 直接引用会让这两条路径变成硬依赖，也会在类校验阶段把缺失依赖炸到调用方。
 *
 * <p>顺序是先 {@code clearCache()}（连定义层的商业/工业/医疗缓存一起清）再 {@code reload()}：
 * 只 reload 不清缓存的话，建筑清单是新的，但按包内路径缓存的功能定义可能还是旧的。
 */
public final class NsukCatalogBridge {

    private static final String CATALOG_CLASS = "common.cn.kafei.simukraft.building.BuildingCatalog";
    private static final String PACKAGE_CATALOG_CLASS =
            "common.cn.kafei.simukraft.building.BuildingPackageCatalog";
    /** NSUK 在 1.21.1 上的 mod id；用来先判断它到底在不在。 */
    private static final String NSUK_MOD_ID = "simukraft";

    /**
     * 包内功能定义的四组缓存，<b>必须与建筑清单一起清</b>。
     *
     * <p>这是修的一个既有缺陷：{@code BuildingCatalog.reload()} / {@code clearCache()}
     * 都只转发给 {@code BuildingPackageCatalog}，<b>一个都不碰</b>这四个 loader。
     * 后果是官方包（或任何包）里商业报价 / 工业配方 / 医疗 / 钻井的功能 JSON 定义，
     * 在包被替换或重新启用之后 NSUK 手里仍是旧的缓存 —— 建筑清单更新了，行为没更新。
     *
     * <p>NSUK 自己的 {@code /simukraft reload buildings} 命令显式清了这四个，
     * 所以这里照抄它的行为。
     *
     * <p><b>包名是逐个用 {@code javap -public} 核对过的，不要按直觉"归拢"到
     * {@code building} 包下</b>：这四个类各自住在自己的功能包
     * （{@code commercial} / {@code industrial} / {@code mineraldrilling} / {@code medical}），
     * 而不是 {@code building}。写成 {@code building.*} 时 {@code Class.forName} 会静默失败
     * （被 {@link #invokeQuietly} 吞成一条日志），表现为"清缓存看起来跑了、其实一个都没清"。
     *
     * <p>另外两个 {@code *DefinitionLoader}（{@code city.CityLevelDefinitionLoader}、
     * {@code virtualvein.VirtualVeinDefinitionLoader}）<b>没有 {@code clearCache()}</b>，
     * 所以刻意不在这个列表里。
     */
    private static final String[] DEFINITION_LOADERS = {
            "common.cn.kafei.simukraft.commercial.CommercialDefinitionLoader",
            "common.cn.kafei.simukraft.industrial.IndustrialDefinitionLoader",
            "common.cn.kafei.simukraft.mineraldrilling.MineralDrillingDefinitionLoader",
            "common.cn.kafei.simukraft.medical.MedicalDefinitionLoader",
    };

    /**
     * 内置资源服务的缓存：{@code BuildingBuiltinResourceService.clearCache()}。
     *
     * <p>javap 确认它存在（{@code public static void clearCache()}），
     * 而它管的是"哪个 root 目录已经复制过内置包"这类记忆。
     * 官方包刚被 {@code forceOverwrite} 覆盖之后，这份记忆必须一起失效，
     * 否则 NSUK 可能仍按旧状态判断"这个 root 已经处理过了"。
     */
    private static final String BUILTIN_SERVICE_CLASS =
            "common.cn.kafei.simukraft.building.BuildingBuiltinResourceService";

    /**
     * 是否抑制日志；默认 true，由游戏侧启动时关掉。
     *
     * <p>同 {@link PackActivation} 的理由：{@link SimuPackMod#LOGGER} 是静态字段，
     * 单元测试运行时 slf4j 不在 classpath 上，第一次碰它就会 {@code NoClassDefFoundError}
     * 并把类初始化搞坏。而"刷新失败"这种日志在没装 NSUK 时本来也无意义。
     */
    private static volatile boolean quiet = true;

    /** 由游戏侧调用一次：之后启用日志。 */
    public static void enableLogging() {
        quiet = false;
    }

    private NsukCatalogBridge() {
    }

    /**
     * 触发一次 NSUK 建筑目录重载。
     *
     * <p>永不抛异常：拿不到 NSUK、API 改名、反射被拦，都只是返回 false 并记一条日志。
     * 刷新失败不该让用户的"启用/禁用"操作看起来也失败了——文件操作本身是成功的。
     *
     * @return true 表示 NSUK 确实重新扫过目录
     */
    public static boolean refresh() {
        ClassLoader loader = loaderOf(NSUK_MOD_ID);
        if (loader == null) {
            log("未找到 NSUK，跳过建筑目录刷新");
            return false;
        }
        boolean cleared = invokeQuietly(loader, PACKAGE_CATALOG_CLASS, "clearCache");
        int clearedDefinitions = clearDefinitionCaches(loader);
        // 内置资源服务也要一起失效：它记着"哪个 root 已经复制过内置包"，
        // 官方包刚被覆盖时这份记忆必须跟着作废。
        boolean clearedBuiltin = invokeQuietly(loader, BUILTIN_SERVICE_CLASS, "clearCache");
        boolean reloaded = invokeQuietly(loader, CATALOG_CLASS, "reload");
        if (!reloaded) {
            warn("NSUK 建筑目录刷新失败（API 可能已变更）；本次改动会在下次进入世界或重启后生效");
        } else {
            log("已请求 NSUK 重新扫描建筑目录（包缓存：{}，定义缓存：{}/{}，内置服务缓存：{}）",
                    cleared, clearedDefinitions, DEFINITION_LOADERS.length, clearedBuiltin);
        }
        return reloaded;
    }

    /**
     * 清掉四个功能定义 loader 的缓存。
     *
     * <p>只清能清到的，<b>不因为某一个缺失就放弃其余</b>：NSUK 改版本时可能少一两个类，
     * 那种情况下清掉能清的那些仍然比什么都不做强。
     *
     * @return 成功清掉的个数
     */
    private static int clearDefinitionCaches(ClassLoader loader) {
        int cleared = 0;
        for (String className : DEFINITION_LOADERS) {
            if (invokeQuietly(loader, className, "clearCache")) {
                cleared++;
            }
        }
        return cleared;
    }

    private static void log(String message, Object... arguments) {
        if (quiet) {
            return;
        }
        if (arguments.length == 0) {
            SimuPackMod.LOGGER.debug(message);
        } else {
            SimuPackMod.LOGGER.info(message, arguments);
        }
    }

    private static void warn(String message) {
        if (!quiet) {
            SimuPackMod.LOGGER.warn(message);
        }
    }

    /** NSUK 是否加载中；界面用它决定要不要显示"已请求 NSUK 刷新"这类文案。 */
    public static boolean available() {
        return loaderOf(NSUK_MOD_ID) != null;
    }

    /**
     * 能看见 NSUK 类的加载器；没装 NSUK 时返回 {@code null}。
     *
     * <p>暴露出来是给同包的 {@link OfficialPackUpdateService} 用的：
     * 它要反射调 NSUK 的 {@code forceOverwrite}，而"怎么找到 NSUK 的类"这套逻辑
     * 只该有一份，否则两处会漂移。
     */
    static ClassLoader nsukClassLoader() {
        return loaderOf(NSUK_MOD_ID);
    }

    /**
     * 取能看见 NSUK 类的加载器。
     *
     * <p>顺序：NSUK 自己 mod 容器的加载器（最准） → 线程上下文加载器 → 本模组自己的加载器。
     * 实测 dev 环境（NeoForge runClient）下三者都能看见 NSUK 的类，正式环境同理；
     * 多试几个只是为了不对某一种装载方式做硬假设。
     */
    private static ClassLoader loaderOf(String modId) {
        ClassLoader fromContainer = containerLoader(modId);
        if (fromContainer != null) {
            return fromContainer;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (hasClass(context, CATALOG_CLASS)) {
            return context;
        }
        ClassLoader own = NsukCatalogBridge.class.getClassLoader();
        return hasClass(own, CATALOG_CLASS) ? own : null;
    }

    /**
     * 从 mod 容器上问加载器。
     *
     * <p>用反射取 {@code getClassLoader()}：NeoForge 各版本给的是容器实现类
     * （{@code ModContainerImpl} / {@code ModFileInfo} 等），编译期直接依赖这些内部类型
     * 会让本模组在版本差异下编不过。拿不到就返回 null，交给上层回退。
     */
    private static ClassLoader containerLoader(String modId) {
        try {
            var container = ModList.get().getModContainerById(modId);
            if (container.isPresent()) {
                ClassLoader loader = loaderFromObject(container.get());
                if (loader != null) {
                    return loader;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 启动早期访问 ModList 会炸：静默回退。
        }
        // dev 运行时 ModList 可能还没装好，问扫描结果。
        try {
            return scanLoader(modId);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** {@code LoadingModList} 里的 ModFileInfo；拿它的 {@code getFile()} 再问加载器。 */
    private static ClassLoader scanLoader(String modId) {
        ModFileInfo info = LoadingModList.get().getModFileById(modId);
        if (info == null) {
            return null;
        }
        return loaderFromObject(info.getFile());
    }

    /** 对任意对象反射调 {@code getClassLoader()}，并要求它真的看得见 NSUK。 */
    private static ClassLoader loaderFromObject(Object owner) {
        if (owner == null) {
            return null;
        }
        try {
            Object loader = owner.getClass().getMethod("getClassLoader").invoke(owner);
            if (loader instanceof ClassLoader classLoader && hasClass(classLoader, CATALOG_CLASS)) {
                return classLoader;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 没有这个方法或方法挂了：继续回退。
        }
        return null;
    }

    private static boolean hasClass(ClassLoader loader, String className) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError | RuntimeException exception) {
            return false;
        }
    }

    /** 调一个无参静态方法；任何失败都只记日志并返回 false。 */
    private static boolean invokeQuietly(ClassLoader loader, String className, String methodName) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            Method method = type.getMethod(methodName);
            method.invoke(null);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (!quiet) {
                SimuPackMod.LOGGER.warn("调用 {}.{}() 失败", className, methodName, exception);
            }
            return false;
        }
    }
}
