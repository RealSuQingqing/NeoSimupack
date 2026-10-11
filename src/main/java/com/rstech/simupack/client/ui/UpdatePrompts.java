package com.rstech.simupack.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.rstech.simupack.attention.AttentionCenter;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.attention.PromptPlanner;
import com.rstech.simupack.client.SimuPackClientSettings;
import com.rstech.simupack.client.screen.PackManagerScreen;
import com.rstech.simupack.attention.NsukOfficialPackVersionSource;
import com.rstech.simupack.pack.BuildingPackManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/**
 * 两个更新弹窗 + 排队 / 去重 / 记录。
 *
 * <p>刻意独立于 {@code PackManagerScreen}：那个类已经 947 行，再塞进去只会更难读。
 *
 * <p><b>弹窗 ≠ 诊断面板</b>：这里只负责"把该知道的事告知一次"，
 * 没有关注项列表。红点的解释与操作入口由这两个弹窗 + 两个常驻按钮承担。
 *
 * <p>三条规则（都由 {@link PromptPlanner} 这个纯函数钉住）：
 * <ol>
 *   <li><b>只弹一次</b>：同一 key 提示过就不再弹；key 含版本对，所以版本再变会重新弹；</li>
 *   <li><b>顺序固定</b>：官方包更新先于模组更新，每次打开界面顺序一致；</li>
 *   <li><b>一个关掉后弹下一个</b>，不是叠在一起。</li>
 * </ol>
 *
 * <p><b>记录写在关闭时</b>（确定/取消任一），不是弹出时：这样用户没做任何决定就退游戏/崩溃，
 * 下次仍会提示。写入失败（config 保存是软失败）只记一条 warn、不阻断弹窗——
 * 最坏情况是下次再弹一次，无害。
 */
@OnlyIn(Dist.CLIENT)
public final class UpdatePrompts {
    /** Release builds keep the old debug update prompts disabled. */
    private static final boolean ENABLE_UPDATE_PROMPTS = false;

    public static boolean enabled() {
        return ENABLE_UPDATE_PROMPTS;
    }

    private UpdatePrompts() {
    }

    /**
     * 打开管理器时调用：把该弹的更新项依次弹出来。
     *
     * <p>必须在界面构建完成后调用，因为 UI 要在 {@code super(...)} 之前构建完，
     * 那时界面还没挂上，所以推到下一帧再弹。
     *
     * <p>推帧用 {@code tell}：{@code Minecraft.execute} 在渲染线程上（构建/事件回调里）
     * 是当场同步执行的，只有从别的线程回来才真的入队 —— 用它会变成"构建过程中就弹窗"。
     *
     * @param host 弹窗宿主（内容区最外层）
     */
    public static void scheduleOnOpen(UIElement host) {
        if (!ENABLE_UPDATE_PROMPTS || host == null) {
            return;
        }
        List<AttentionItem> pending = pendingPrompts();
        if (pending.isEmpty()) {
            return;
        }
        // 与 askConvert 同一套做法：UI 必须在 super(...) 之前构建完，
        // 那时界面还没挂上，所以推到下一帧再弹。
        Minecraft.getInstance().tell(() -> showNext(host, pending, 0));
    }

    /** 当前该弹的项（已按 {@link PromptPlanner} 规则过滤与排序）。 */
    public static List<AttentionItem> pendingPrompts() {
        return PromptPlanner.pending(AttentionCenter.snapshot(),
                SimuPackClientSettings.attentionPromptedKeys()).stream()
                // 被显式关掉的类别连弹窗一起消失（整合包作者的逃生口）。
                .filter(item -> !SimuPackClientSettings.isAttentionKindDisabled(item.kind()))
                .toList();
    }

    /**
     * 弹队列里的第 {@code index} 个；关掉后自动弹下一个。
     *
     * <p>递归深度等于待弹项数量，而待弹项最多两类（官方包 + 模组），所以不必担心栈。
     */
    private static void showNext(UIElement host, List<AttentionItem> pending, int index) {
        if (index >= pending.size()) {
            return;
        }
        if (hasOpenDialog(host)) {
            // 已经有一个弹窗开着（例如旧包转换框）：不叠弹窗，直接放弃这一轮。
            // 用户下次打开管理器时会重新排到队首。
            return;
        }
        AttentionItem item = pending.get(index);
        Consumer<UIElement> advance = overlay -> {
            host.removeChild(overlay);
            markPrompted(item);
            showNext(host, pending, index + 1);
        };

        switch (item.kind()) {
            case PACK_UPDATE -> showPackUpdate(host, item, advance);
            case MOD_UPDATE -> showModUpdate(host, item, advance);
            default -> {
                // 故障包不弹窗；真到这里说明上游算错了，直接跳过而不是弹一个空框。
                markPrompted(item);
                showNext(host, pending, index + 1);
            }
        }
    }

    /** 弹窗一：官方建筑包有更新。 */
    private static void showPackUpdate(UIElement host, AttentionItem item,
                                       Consumer<UIElement> onDismiss) {
        String localVersion = arg(item, 0);
        String builtinVersion = arg(item, 1);

        UIElement body = body();
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.attention.pack_update.versions",
                        localVersion, builtinVersion),
                SimuPackTheme.TEXT_PRIMARY_COLOR));

        SimuPackDialog.open(host,
                Component.translatable("gui.simupack.attention.pack_update.title"),
                body,
                overlay -> {
                    // 「立即更新」：先把记录写掉再执行，避免更新成功后重建界面又弹一次。
                    host.removeChild(overlay);
                    markPrompted(item);
                    runOfficialPackUpdate(parentScreen());
                },
                () -> onDismiss.accept(overlayOf(host)),
                null, null, null,
                "gui.simupack.attention.pack_update.confirm",
                "gui.simupack.attention.pack_update.later");
    }

    /** 弹窗二：筑艺坊有新版本（唯一不能"点一下就解决"的类型）。 */
    private static void showModUpdate(UIElement host, AttentionItem item,
                                      Consumer<UIElement> onDismiss) {
        String currentVersion = arg(item, 0);
        String expectedVersion = arg(item, 1);

        UIElement body = body();
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.attention.mod_update.versions",
                        currentVersion, expectedVersion),
                SimuPackTheme.TEXT_PRIMARY_COLOR));
        // 只说清"要换 jar 并重启"这一步就够了；
        // 红点什么时候灭属于实现细节，不该出现在给用户看的文案里。
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.attention.mod_update.explain"),
                SimuPackTheme.TEXT_SECONDARY_COLOR));

        SimuPackDialog.open(host,
                Component.translatable("gui.simupack.attention.mod_update.title"),
                body,
                overlay -> {
                    host.removeChild(overlay);
                    markPrompted(item);
                    openModsFolder();
                },
                () -> onDismiss.accept(overlayOf(host)),
                null, null, null,
                "gui.simupack.attention.mod_update.confirm",
                "gui.simupack.attention.mod_update.later");
    }

    // ── 两个动作 ───────────────────────────────────────────────────────

    /**
     * 执行官方包更新并重建界面。
     *
     * <p>成功与失败都重建界面：成功时红点应当立刻熄灭（刷新在重建路径上触发），
     * 失败时至少让用户看到原因。
     *
     * @param parent 重建界面时要回去的父界面
     */
    public static void runOfficialPackUpdate(Screen parent) {
        com.rstech.simupack.pack.OfficialPackUpdateService.Outcome outcome =
                com.rstech.simupack.pack.OfficialPackUpdateService
                        .update(new NsukOfficialPackVersionSource());

        // 更新序列走完 -> 立刻重算红点（问题解决的瞬间红点就该灭）。
        AttentionCenter.refreshAsync(Util.backgroundExecutor());

        String notice = Component.translatable(outcome.messageKey()).getString();
        if (outcome.ok()) {
            notice = notice + "\n" + Component.translatable(
                    "gui.simupack.attention.pack_update.done_detail",
                    outcome.fromVersion().orElse("?"),
                    outcome.toVersion().orElse("?")).getString();
        } else if (outcome.rolledBack()) {
            notice = notice + "\n" + Component.translatable(
                    "gui.simupack.attention.pack_update.rolled_back").getString();
        }
        PackManagerScreen.rebuild(parent, notice, !outcome.ok());
    }

    /** 打开 mods 文件夹：模组更新的"解决动作"就是让用户去换 jar。 */
    public static void openModsFolder() {
        try {
            // 开发环境是 run/mods，正式环境是游戏目录下的 mods；
            // FMLPaths.MODSDIR 两种情形都指向对的地方。
            Util.getPlatform().openPath(
                    net.neoforged.fml.loading.FMLPaths.MODSDIR.get().toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            // 打开失败不该崩界面：用户看得到弹窗文案就已经知道该做什么了。
            AttentionCenter.debugSnapshot(AttentionCenter.snapshot());
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────────

    /** 把一项标成"已提示"。 */
    private static void markPrompted(AttentionItem item) {
        SimuPackClientSettings.markAttentionPrompted(item.key());
    }

    /** 取参数；越界时返回占位符而不是抛异常（弹窗崩了比显示"?"糟得多）。 */
    private static String arg(AttentionItem item, int index) {
        List<Object> args = item.args();
        if (index < 0 || index >= args.size() || args.get(index) == null) {
            return "?";
        }
        return String.valueOf(args.get(index));
    }

    private static UIElement body() {
        UIElement body = SimuPackLayout.column(3);
        return body;
    }

    /** 走"取消/关闭"路径时，遮罩还挂在宿主上，取最后一个 overlay。 */
    private static UIElement overlayOf(UIElement host) {
        UIElement found = null;
        for (UIElement child : host.getChildren()) {
            if (child.hasClass("simupack_overlay")) {
                found = child;
            }
        }
        if (found != null) {
            host.removeChild(found);
        }
        return found == null ? new UIElement() : found;
    }

    /** 弹窗宿主下是不是已经有一个弹窗开着。 */
    private static boolean hasOpenDialog(UIElement host) {
        return host.getChildren().stream()
                .anyMatch(child -> child.hasClass("simupack_overlay"));
    }

    /**
     * 当前界面的父界面。
     *
     * <p>与 {@code PackManagerScreen.currentParent()} 同一套逻辑：重建界面时要回到
     * 管理器的父界面（标题界面），而不是回到管理器自己。
     */
    private static Screen parentScreen() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof PackManagerScreen manager) {
            return manager.parentScreen();
        }
        return screen;
    }

    /** 供外部（工具栏/详情页）复用的刷新入口。 */
    public static void refreshAfterChange() {
        AttentionCenter.refreshAsync(Util.backgroundExecutor());
    }

    /**
     * 工具栏常驻入口：手动把模组更新弹窗调出来。
     *
     * <p>弹窗只弹一次，用户选了「稍后」之后必须有地方回来点——这就是那个地方。
     * 与自动弹窗共用同一套文案与动作，只是绕过了 {@code PromptPlanner} 的去重。
     */
    public static void showModUpdateDialog() {
        if (!ENABLE_UPDATE_PROMPTS) return;
        UIElement host = PackManagerScreen.dialogHostForUpdate();
        if (host == null || hasOpenDialog(host)) {
            return;
        }
        AttentionItem item = findItem(AttentionKind.MOD_UPDATE);
        if (item == null) {
            // 快照里已经没有这一项了（例如用户刚换完 jar 还没刷新）：不弹一个空框。
            checkForUpdatesNow();
            return;
        }
        showModUpdate(host, item, overlay -> host.removeChild(overlay));
    }

    /** 从快照里找第一项指定类别。 */
    private static AttentionItem findItem(AttentionKind kind) {
        for (AttentionItem item : AttentionCenter.snapshot().items()) {
            if (item.kind() == kind) {
                return item;
            }
        }
        return null;
    }

    /**
     * 工具栏「检查更新」与包详情页共用的刷新逻辑。
     *
     * <p>把这个原本的空实现接上：重扫包 + 后台重算红点。真正的"告知"仍由
     * {@link #scheduleOnOpen} 在下次打开管理器时完成（这样不会在一个按钮点击里
     * 突然弹出模态框）。
     */
    public static void checkForUpdatesNow() {
        BuildingPackManager.reload();
        AttentionCenter.refreshAsync(Util.backgroundExecutor());
    }

    /** 未使用的分类常量在这里显式引用一次（{@link AttentionKind} 用于 switch 覆盖检查）。 */
    static AttentionKind[] kinds() {
        return AttentionKind.values();
    }
}
