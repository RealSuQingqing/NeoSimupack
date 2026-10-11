package com.rstech.simupack.attention;

import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.pack.NsukCatalogBridge;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 官方包版本来源的生产实现：<b>全程反射</b>调 NSUK。
 *
 * <p>与 {@link NsukCatalogBridge} 同一套范式，理由也一样：NSUK 在 dev 环境是
 * {@code localRuntime}，缺了它这个模组仍要能编译、能加载、界面要能用，
 * 只是 B 类红点降级成"不亮"。直接 {@code import} 会让这条路径变成硬依赖，
 * 也会在类校验阶段把缺失依赖炸到调用方。
 *
 * <p>用到的 API（M0 已用 {@code javap -public} 逐个核实签名）：
 * <pre>
 * common.cn.kafei.simukraft.building.BuildingPackVersionChecker
 *   public static Optional&lt;String&gt; builtinVersion()   // 读 JAR 内 official_building.zip
 *   public static Optional&lt;String&gt; localVersion()     // 读建筑目录里的同名文件
 * </pre>
 *
 * <p><b>失败一律降级成 {@code Optional.empty()}</b>：不亮、不误报。
 * 红点宁可漏报也不能因为 NSUK 改名就持续常亮。
 */
public final class NsukOfficialPackVersionSource implements OfficialPackVersionSource {

    private static final String CHECKER_CLASS =
            "common.cn.kafei.simukraft.building.BuildingPackVersionChecker";
    private static final String METHOD_BUILTIN = "builtinVersion";
    private static final String METHOD_LOCAL = "localVersion";
    private static final String NSUK_MOD_ID = "simukraft";

    /**
     * 抑制日志；默认 true，由游戏侧启动时关掉。
     *
     * <p>同 {@code NsukCatalogBridge}：{@code SimuPackMod.LOGGER} 是静态字段，
     * 单测运行时 slf4j 不在 classpath 上。
     */
    private static volatile boolean quiet = true;

    public static void enableLogging() {
        quiet = false;
    }

    @Override
    public Optional<String> builtinVersion() {
        return invokeString(METHOD_BUILTIN);
    }

    @Override
    public Optional<String> localVersion() {
        return invokeString(METHOD_LOCAL);
    }

    /**
     * 反射调一个返回 {@code Optional<String>} 的 public static 方法。
     *
     * <p>返回值处理得比"直接强转"啰嗦一点是有意的：
     * NSUK 改签名（比如改成返回 {@code String}）时，我们应当降级成 UNKNOWN，
     * 而不是在用户面前抛 {@code ClassCastException}。
     */
    private Optional<String> invokeString(String methodName) {
        ClassLoader loader = loaderOf(NSUK_MOD_ID);
        if (loader == null) {
            log("未找到 NSUK，官方包版本视为未知");
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(CHECKER_CLASS, true, loader);
            Method method = type.getMethod(methodName);
            Object result = method.invoke(null);
            if (result instanceof Optional<?> optional) {
                return optional.filter(String.class::isInstance).map(String.class::cast)
                        .filter(value -> !value.isBlank());
            }
            if (result instanceof String text && !text.isBlank()) {
                // NSUK 若把返回类型改成裸 String 也能用。
                return Optional.of(text);
            }
            return Optional.empty();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            warn("调用 " + CHECKER_CLASS + "." + methodName + "() 失败，官方包版本视为未知", exception);
            return Optional.empty();
        }
    }

    /**
     * 取能看见 NSUK 类的加载器。
     *
     * <p>顺序与 {@link NsukCatalogBridge} 保持一致：mod 容器 → 线程上下文 → 本模组自己的。
     * 多试几个只是为了不对某一种装载方式做硬假设。
     */
    private static ClassLoader loaderOf(String modId) {
        ClassLoader fromContainer = containerLoader(modId);
        if (fromContainer != null) {
            return fromContainer;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (hasClass(context)) {
            return context;
        }
        ClassLoader own = NsukOfficialPackVersionSource.class.getClassLoader();
        return hasClass(own) ? own : null;
    }

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
        try {
            ModFileInfo info = LoadingModList.get().getModFileById(modId);
            return info == null ? null : loaderFromObject(info.getFile());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static ClassLoader loaderFromObject(Object owner) {
        if (owner == null) {
            return null;
        }
        try {
            Object loader = owner.getClass().getMethod("getClassLoader").invoke(owner);
            if (loader instanceof ClassLoader classLoader && hasClass(classLoader)) {
                return classLoader;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 没有这个方法或方法挂了：继续回退。
        }
        return null;
    }

    private static boolean hasClass(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName(CHECKER_CLASS, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError | RuntimeException exception) {
            return false;
        }
    }

    private static void log(String message) {
        if (quiet) {
            return;
        }
        SimuPackMod.LOGGER.debug(message);
    }

    private static void warn(String message, Throwable cause) {
        if (quiet) {
            return;
        }
        SimuPackMod.LOGGER.warn(message, cause);
    }
}
