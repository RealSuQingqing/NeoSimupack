package com.rstech.simupack.client.screen;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.SearchComponent;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TabView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TreeList;
import com.rstech.simupack.attention.AttentionCenter;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.client.SimuPackClientSettings;
import com.rstech.simupack.client.ui.SimuPackDialog;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.client.ui.SimuPackWindowFrame;
import com.rstech.simupack.client.ui.UpdatePrompts;
import com.rstech.simupack.attention.NsukOfficialPackVersionSource;
import com.rstech.simupack.attention.OfficialPackVersionSource;
import com.rstech.simupack.attention.VersionCompare;
import com.rstech.simupack.creation.PackFormat;
import com.rstech.simupack.pack.BuildingPack;
import com.rstech.simupack.pack.BuildingPackManager;
import com.rstech.simupack.pack.NsukCatalogBridge;
import com.rstech.simupack.pack.PackActivation;
import com.rstech.simupack.pack.legacy.LegacyPackDetector;
import com.rstech.simupack.pack.legacy.LegacyPackInstaller;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 筑艺坊建筑拓展包管理器（主菜单 P 按钮入口）。
 *
 * <p>职责划分：本界面是<b>包粒度</b>的本地管理层，只处理拓展包的安装状态与元数据。
 * 建筑清单与结构三维预览属于<b>创作粒度</b>，放在游戏内的拓展包创作空间
 * （{@link SingleplayerEditorScreen}），不在此界面出现。
 *
 * <p>布局：
 * <pre>
 * [工具栏] 统计 | 搜索 | 刷新 | 检查更新
 * [TabView] 已安装拓展包 | 拓展包创意工坊
 * [拓展包列表] [拓展包详情：作者/版本/建筑数量/来源/状态/错误/更新日志]
 * </pre>
 *
 * <p>本界面只处理客户端本地内容，不读取、不显示任何服务器拓展包信息。
 *
 * <p><b>旧版拓展包</b>（1.20.1 时代、没有 {@code pack.json}）在列表里以「（旧版）」标记出现，
 * 来源有两处：已经躺在建筑目录里的，以及被直接拖进本界面的。选中其一就会弹出转换确认，
 * 确认后由 {@link LegacyPackInstaller} 转成新版格式（{@code .litematic}/{@code .schem}
 * 结构转 {@code .nbt}、{@code .meta.json} 改名 {@code .json}、旧商店字段升级成 {@code offers}）。
 */
public final class PackManagerScreen extends ModularUIScreen {
    /**
     * 固定尺寸按 Minecraft 自动 GUI 缩放的常见下限取值（实测可达 427x240），
     * 避免固定像素值占满整个宽度。
     */
    private static final float PACK_LIST_PERCENT = 34F;
    private static final int TOOLBAR_HEIGHT = 20;
    private static final float SEARCH_WIDTH = 90F;
    private static final int BUTTON_HEIGHT = 16;
    private static final int GAP = 4;
    private static final int COLUMN_GAP = 3;

    /** 旧版包节点 key 的前缀：和 {@code BuildingPack.id} 区分开，选中时据此分流。 */
    private static final String LEGACY_KEY_PREFIX = "legacy:";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * 弹窗宿主：内容区最外层，需要盖住整个窗口内容。
     *
     * <p>做成静态字段是有意的：{@code ModularUIScreen} 要求 UI 在 {@code super(...)}
     * 之前构建好，那时实例字段还不存在；而重建界面时一定会有新实例接管它。
     */
    private static UIElement dialogHost;

    /**
     * 左栏当前的搜索词。
     *
     * <p>必须是 static：本界面的「启用/禁用/删除/刷新」都是"重建整个界面"（见 {@link #refreshScreen}），
     * 只存在实例里的过滤词会在每次操作后归零，用户刚筛出来的那一小撮包又变回全量列表。
     * {@code dialogHost} 出于同样的理由也是 static。
     */
    private static String searchQuery = "";

    private final Screen parent;

    public PackManagerScreen(Screen parent) {
        this(parent, null, false);
    }

    /** 转换/安装完成后带着提示重建界面用。 */
    private PackManagerScreen(Screen parent, String notice, boolean error) {
        super(buildUi(new PendingNotice(notice, error)), Component.translatable("gui.simupack.title"));
        this.parent = parent;
    }

    /**
     * 带着一次性提示重建界面。
     *
     * <p>给 {@code UpdatePrompts} 在官方包更新完成后用：更新结果必须让用户看到，
     * 而弹窗随界面重建一起消失，所以结果只能通过这条"重建 + 提示"的路径带出来。
     *
     * <p>{@code parent} 显式传入而不是用 {@code currentParent()}：调用方来自弹窗回调，
     * 那时 {@code Minecraft.screen} 仍是本界面，{@code currentParent()} 也能算对，
     * 但显式传参更清楚，也不依赖"当前屏幕还没换"这个隐含前提。
     */
    public static void rebuild(Screen parent, String notice, boolean error) {
        Minecraft.getInstance().setScreen(new PackManagerScreen(parent, notice, error));
    }

    /** 本管理器的父界面（通常是标题界面）；供弹窗回调重建时使用。 */
    public Screen parentScreen() {
        return parent;
    }

    /**
     * 弹窗宿主；供 {@code UpdatePrompts} 的常驻入口（工具栏按钮）复用。
     *
     * <p>返回 {@code null} 表示界面还没建好或已经拆掉——调用方必须判空，
     * 不能假设它一直存在。
     */
    public static UIElement dialogHostForUpdate() {
        return dialogHost;
    }

    /** 构建界面时要顺带显示的一次性提示（安装/转换结果）。 */
    private record PendingNotice(String text, boolean error) {
        private boolean visible() {
            return text != null && !text.isBlank();
        }
    }

    private static ModularUI buildUi(PendingNotice notice) {
        SimuPackLayout.ScreenSize size = SimuPackLayout.screenSize();

        // 旧包只扫一次，工具栏徽标与列表共用同一份结果（避免同一帧里反复读盘）。
        List<LegacyPackDetector.LegacyPackInfo> legacy = LegacyPackInstaller.pendingInBuildingDirectory();

        UIElement content = SimuPackLayout.pageColumn(GAP);
        dialogHost = content;

        SearchBar searchBar = searchBar(searchQuery);
        content.addChild(toolbar(searchBar));
        content.addChild(mainTabs(notice, legacy, searchBar));

        UIElement root = SimuPackWindowFrame.create(
                size,
                Component.translatable("gui.simupack.title"),
                content,
                () -> Minecraft.getInstance().setScreen(null));

        // 打开管理器时把该告知的更新项弹出来（见 UpdatePrompts：同一版本只弹一次、
        // 两个弹窗排队而不叠加、记录写在关闭时）。界面已经建好了，所以这里可以安排弹窗。
        UpdatePrompts.scheduleOnOpen(content);

        return new ModularUI(SimuPackTheme.createUi(root)).shouldCloseOnEsc(true);
    }

    // ── 拖放：旧包转换 + 新包安装 ──────────────────────────────────────

    /**
     * 把一个包文件拖进本界面。
     *
     * <p>旧版包（没有 {@code pack.json} 但有建筑文件）先问一句再转换；
     * 已是新版的 {@code .zip} 直接复制进建筑目录。
     *
     * <p>两条路径的产物都是<b>未启用</b>状态（{@code .zip.disabled}）：导入不该悄悄改变
     * 游戏里现有的建筑清单，启用与否交给用户在详情里决定。
     */
    @Override
    public void onFilesDrop(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        for (Path path : paths) {
            if (path == null || !Files.isRegularFile(path)
                    || !LegacyPackDetector.looksLikePackFile(path)) {
                continue;
            }
            LegacyPackDetector.LegacyPackInfo info = LegacyPackDetector.detect(path);
            if (info.error() != null) {
                rebuildWithNotice(Component.translatable("gui.simupack.legacy.not_a_pack",
                        path.getFileName().toString(), info.error()).getString(), true);
                return;
            }
            if (info.legacy()) {
                // 旧包：只弹确认，不直接写任何文件。
                askConvert(info);
                return;
            }
            installCurrentFormat(path);
            return;
        }
    }

    /**
     * 新版包复制进建筑目录，落成未启用状态（同名覆盖，与旧包转换保持同一套命名规则）。
     *
     * <p>覆盖判定会同时看 {@code x.zip} 和 {@code x.zip.disabled}：用户可能已经装过
     * 同一个包、只是当前没启用，这种情况也是"覆盖"而不是"全新安装"。
     */
    private void installCurrentFormat(Path source) {
        try {
            Path directory = BuildingPackManager.rootDirectory();
            Files.createDirectories(directory);
            Path output = directory.resolve(PackActivation.disabledFileName(source.getFileName().toString()));
            boolean overwritten = Files.isRegularFile(output)
                    || Files.isRegularFile(directory.resolve(PackActivation.enabledFileName(
                            source.getFileName().toString())));
            Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING);
            // 文件是新的，但 NSUK 手里的快照还是旧的；这里刷新一次保证"禁用列表/启用列表"
            // 和实际目录一致（导入的是禁用包，所以 NSUK 的清单通常不会变）。
            NsukCatalogBridge.refresh();
            BuildingPackManager.reload();
            UpdatePrompts.refreshAfterChange();
            rebuildWithNotice(Component.translatable(
                    overwritten ? "gui.simupack.drop.replaced" : "gui.simupack.drop.installed",
                    output.getFileName().toString()).getString(), false);
        } catch (IOException exception) {
            rebuildWithNotice(Component.translatable("gui.simupack.drop.failed",
                    message(exception)).getString(), true);
        }
    }

    /**
     * 旧包转换确认框。
     *
     * <p>文案刻意只说"可能来自旧版筑艺坊"：包内容无法可靠区分 1.20.1 与更早的版本，
     * 断言具体版本号会在别的旧包上说谎。
     */
    private static void askConvert(LegacyPackDetector.LegacyPackInfo info) {
        UIElement host = dialogHost;
        if (host == null || hasOpenDialog()) {
            return;
        }
        UIElement body = SimuPackLayout.column(COLUMN_GAP);
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.legacy.prompt"), SimuPackTheme.TEXT_SECONDARY_COLOR));
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.literal(legacySummary(info)), SimuPackTheme.TEXT_MUTED_COLOR));
        if (info.structuresToConvert() > 0) {
            body.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.legacy.will_convert",
                            String.valueOf(info.structuresToConvert())), SimuPackTheme.TEXT_INFO_COLOR));
        }

        SimuPackDialog.open(host,
                Component.translatable("gui.simupack.legacy.dialog_title"),
                body,
                overlay -> {
                    host.removeChild(overlay);
                    // 转掉以后就不必再记着"别提示"了。
                    SimuPackClientSettings.forgetLegacyPrompt(info.path());
                    runConversion(info);
                },
                () -> rebuildWithNotice(
                        Component.translatable("gui.simupack.legacy.cancelled").getString(), false),
                overlay -> {
                    // 「不再提示」：源文件留在原地不动，只是下次不再自动弹这个框。
                    LegacyPackInstaller.invalidatePendingCache();
                     SimuPackClientSettings.dismissLegacyPrompt(info.path(),
                            SimuPackClientSettings.fingerprint(info.path()));
                    host.removeChild(overlay);
                    rebuildWithNotice(Component.translatable("gui.simupack.legacy.dismissed",
                            info.path().getFileName().toString()).getString(), false);
                },
                "gui.simupack.legacy.dismiss_action", "gui.simupack.legacy.dismiss_action_confirm");
    }

    /** 执行转换并刷新界面。 */
    private static void runConversion(LegacyPackDetector.LegacyPackInfo info) {
        try {
            LegacyPackInstaller.Outcome outcome = LegacyPackInstaller.install(info.path());
            // 旧包被转掉之后 A 类红点就该跟着灭：重算一次快照。
            UpdatePrompts.refreshAfterChange();
            // 转换结果固定是未启用状态，文案要把这一点说清楚，否则用户会以为没装上。
            String key = outcome.overwritten()
                    ? "gui.simupack.legacy.converted_replaced"
                    : "gui.simupack.legacy.converted";
            rebuildWithNotice(Component.translatable(key,
                    outcome.output().getFileName().toString(),
                    outcome.summaryWithNotes()).getString()
                    + "\n" + Component.translatable("gui.simupack.activation.converted_hint").getString(),
                    false);
        } catch (IOException | RuntimeException exception) {
            rebuildWithNotice(Component.translatable("gui.simupack.legacy.convert_failed",
                    message(exception)).getString(), true);
        }
    }

    // ── 启用 / 禁用 / 删除 ─────────────────────────────────────────────

    /**
     * 切换一个包的启用状态。
     *
     * <p>成功时不弹提示：状态已经由列表里的指示灯和右侧详情表达，再弹一条
     * 「已禁用/已启用：……zip.disabled」只是把文件名重复一遍（那也正是用户不想看的东西）。
     * 只有失败才需要提示——那时用户必须知道为什么没生效。
     *
     * <p>禁用官方内置包会被服务层拒绝（{@link PackActivation.ProtectedPackException}）：
     * 它是 NSUK 的最小可用建筑集，禁用它会让游戏里一个可建建筑都不剩。
     */
    private static void toggleActivation(BuildingPack pack) {
        try {
            PackActivation.toggle(pack);
            // 问题解决的瞬间红点就该灭：包状态变了就重算一次快照。
            UpdatePrompts.refreshAfterChange();
            refreshScreen();
        } catch (PackActivation.ProtectedPackException exception) {
            // 服务层与界面用的是同一套规则，正常点不到这里；真到了说明状态在两次重建之间
            // 变了（例如文件被外部改坏），按包的具体情况给出原因。
            rebuildWithNotice(Component.translatable(pack.unusable()
                    ? "gui.simupack.activation.unusable_locked"
                    : "gui.simupack.activation.official_locked").getString(), true);
        } catch (IOException | RuntimeException exception) {
            rebuildWithNotice(Component.translatable("gui.simupack.activation.failed",
                    message(exception)).getString(), true);
        }
    }

    /** 一次性启用所有可启用的包：先批量改名，再统一刷新一次（逐个刷新会明显卡顿）。 */
    private static void enableAll() {
        List<BuildingPack> disabled = BuildingPackManager.packs().stream()
                .filter(pack -> !pack.enabled())
                .toList();
        // 坏包不能启用（启用也不会多出任何建筑），所以"有没有可启用的包"要把它们排除掉。
        boolean anyUsable = disabled.stream().anyMatch(pack -> !pack.unusable());
        if (!anyUsable) {
            rebuildWithNotice(Component.translatable("gui.simupack.activation.none_disabled").getString(), false);
            return;
        }
        try {
            int enabled = PackActivation.enablePacks(disabled);
            PackActivation.refresh();
            if (enabled == 0) {
                // 全都没改成功（例如文件被占用），必须让用户知道，否则界面看起来"点了没反应"。
                rebuildWithNotice(Component.translatable("gui.simupack.activation.none_enabled").getString(), true);
                return;
            }
            UpdatePrompts.refreshAfterChange();
            refreshScreen();
        } catch (RuntimeException exception) {
            rebuildWithNotice(Component.translatable("gui.simupack.activation.failed",
                    message(exception)).getString(), true);
        }
    }

    /**
     * 永久删除一个包：先弹确认框。
     *
     * <p>不可恢复，所以不做"点了就删"。二次确认里的文字会写明删的是哪个文件。
     */
    private static void askDelete(BuildingPack pack) {
        UIElement host = dialogHost;
        if (host == null || hasOpenDialog()) {
            return;
        }
        UIElement body = SimuPackLayout.column(COLUMN_GAP);
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.delete.prompt"), SimuPackTheme.TEXT_SECONDARY_COLOR));
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.literal(pack.path().getFileName().toString()), SimuPackTheme.TEXT_MUTED_COLOR));
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.delete.hint"), SimuPackTheme.TEXT_ERROR_COLOR));

        SimuPackDialog.open(host,
                Component.translatable("gui.simupack.delete.dialog_title"),
                body,
                overlay -> {
                    host.removeChild(overlay);
                    deletePack(pack);
                },
                () -> rebuildWithNotice(
                        Component.translatable("gui.simupack.delete.cancelled").getString(), false));
    }

    private static void deletePack(BuildingPack pack) {
        try {
            PackActivation.Outcome outcome = PackActivation.delete(pack);
            if (!outcome.changed()) {
                // 真正需要提示的情况：用户以为删了，其实文件早就没了。
                rebuildWithNotice(Component.translatable("gui.simupack.delete.missing",
                        pack.path().getFileName().toString()).getString(), true);
                return;
            }
            UpdatePrompts.refreshAfterChange();
            refreshScreen();
        } catch (IOException | RuntimeException exception) {
            rebuildWithNotice(Component.translatable("gui.simupack.delete.failed",
                    message(exception)).getString(), true);
        }
    }

    /** 无提示地重建界面（用于"状态已经自己说明清楚"的操作，例如启用/禁用/删除成功）。 */
    private static void refreshScreen() {
        Minecraft.getInstance().setScreen(new PackManagerScreen(currentParent()));
    }

    private static boolean hasOpenDialog() {
        if (dialogHost == null) {
            return false;
        }
        return dialogHost.getChildren().stream()
                .anyMatch(child -> child.hasClass("simupack_overlay"));
    }

    private static void rebuildWithNotice(String text, boolean error) {
        Minecraft.getInstance().setScreen(new PackManagerScreen(currentParent(), text, error));
    }

    private static String message(Exception exception) {
        String text = exception.getMessage();
        return text == null || text.isBlank() ? exception.getClass().getSimpleName() : text;
    }

    // ── 左栏过滤 ───────────────────────────────────────────────────────

    /**
     * 按搜索词筛出要显示的包。
     *
     * <p>空词返回原列表（保持 {@link BuildingPackManager} 的顺序）；匹配范围是
     * <b>包 id / 名称 / 作者 / 版本</b>——用户找一个包时脑子里想的通常是这四样之一，
     * 而列表上显示的也正是这几样，看得见就该搜得到。
     *
     * <p><b>旧包不参与过滤</b>：它们还没进包列表，本来就是"待处理"的少数几项，
     * 被搜索藏起来只会让人以为转换入口不见了。
     */
    private static List<BuildingPack> matchingPacks(List<BuildingPack> packs, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return packs;
        }
        List<BuildingPack> result = new ArrayList<>();
        for (BuildingPack pack : packs) {
            if (packMatches(pack, needle)) {
                result.add(pack);
            }
        }
        return result;
    }

    private static boolean packMatches(BuildingPack pack, String needle) {
        return contains(pack.id(), needle)
                || contains(pack.name(), needle)
                || contains(pack.author(), needle)
                || contains(pack.version(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * 左栏那棵树的"重建一次"所需的全部东西。
     *
     * <p>过滤会频繁触发（每敲一个字符一次），所以这里刻意把状态收在一个对象里，
     * 让 {@link #show} 与选中回调都只吃一个参数 —— 否则每次都要传七个。
     */
    private static final class NodeRefresh {
        private final TreeList<PackTreeNode> tree;
        private final String[] selectedKey;
        private final boolean[] restoring;
        private final boolean[] reshowDetail;
        private final UIElement detailBody;
        private final UIElement detailActions;
        private final List<LegacyPackDetector.LegacyPackInfo> legacy;

        private NodeRefresh(TreeList<PackTreeNode> tree, String[] selectedKey, boolean[] restoring,
                            boolean[] reshowDetail, UIElement detailBody, UIElement detailActions,
                            List<LegacyPackDetector.LegacyPackInfo> legacy) {
            this.tree = tree;
            this.selectedKey = selectedKey;
            this.restoring = restoring;
            this.reshowDetail = reshowDetail;
            this.detailBody = detailBody;
            this.detailActions = detailActions;
            this.legacy = legacy;
        }

        /**
         * 用这一批包重建树。
         *
         * <p>顺序有讲究：{@code setRoot} 内部会先广播一次"空选择"（{@link TreeList#reloadList}
         * 把选中集合清空后通知），所以先立 {@code restoring} 旗标挡住那次通知，
         * 等新节点就位后再把原来选中的那一项选回来。
         */
        private void show(List<BuildingPack> visible) {
            String keep = selectedKey[0];
            rootReplace(visible);
            if (keep == null) {
                return;
            }
            for (PackTreeNode node : tree.getRoot() == null ? List.<PackTreeNode>of() : tree.getRoot().getChildren()) {
                if (keep.equals(node.getKey())) {
                    restoring[0] = true;
                    tree.setSelected(List.of(node), true);
                    restoring[0] = false;
                    return;
                }
            }
            // 走到这里说明过滤把原来选中的那个包藏起来了。右栏必须跟着空掉：
            // 否则它会继续描述一个列表里已经看不见的包，而「启用/停用/删除」按钮
            // 仍然指向它 —— 用户以为自己在动搜索结果，实际动的是别的东西。
            clearDetail();
        }

        /** 右栏回到「什么都没选中」：清空详情，并撤掉所有包级操作按钮。 */
        private void clearDetail() {
            selectedKey[0] = null;
            reshowDetail[0] = true;
            detailBody.clearAllChildren();
            updateDetailActions(detailActions, null);
        }

        private void rootReplace(List<BuildingPack> visible) {
            List<PackTreeNode> nodes = new ArrayList<>();
            PackTreeNode root = PackTreeNode.root(nodes);
            for (BuildingPack pack : visible) {
                nodes.add(PackTreeNode.pack(pack, packLabel(pack), root));
            }
            for (LegacyPackDetector.LegacyPackInfo info : legacy) {
                nodes.add(PackTreeNode.legacy(info.path().toAbsolutePath().toString(),
                        info.displayName() + Component.translatable("gui.simupack.legacy.suffix").getString(),
                        root));
            }
            restoring[0] = true;
            tree.setRoot(root);
            restoring[0] = false;
        }
    }

    /** 选中项变化：画右侧详情。被过滤重建时由 {@link NodeRefresh#show} 的旗标挡掉空选择。 */
    private static void onPackSelected(Set<PackTreeNode> selected, NodeRefresh refresh) {
        if (selected == null || selected.isEmpty()) {
            if (refresh.restoring[0]) {
                return;
            }
            // 选中旧包/空选择时不显示任何包级操作，避免误操作到"上一个"包。
            refresh.clearDetail();
            return;
        }
        PackTreeNode node = selected.iterator().next();
        refresh.selectedKey[0] = node.getKey();
        refresh.reshowDetail[0] = false;
        refresh.detailBody.clearAllChildren();
        if (node.kind() == PackTreeNode.Kind.LEGACY) {
            LegacyPackDetector.LegacyPackInfo info = legacyByKey(refresh.legacy, node.getKey());
            if (info != null) {
                refresh.detailBody.addChild(legacyDetail(info));
                // 选中旧包即询问是否转换（右侧同时写清它为什么需要转换）。
                askConvert(info);
            }
            return;
        }
        BuildingPack chosen = node.pack();
        if (chosen != null) {
            refresh.detailBody.addChild(packDetail(chosen));
            updateDetailActions(refresh.detailActions, chosen);
        }
    }

    /** 旧包摘要：建筑数、结构格式构成、缺什么。 */
    private static String legacySummary(LegacyPackDetector.LegacyPackInfo info) {
        StringBuilder text = new StringBuilder();
        text.append(info.path().getFileName()).append("  ·  ")
                .append(info.skCount()).append(" 栋建筑");
        if (info.nonNbtCount() > 0) {
            text.append("（其中 ").append(info.nonNbtCount()).append(" 栋结构需要转换）");
        }
        if (info.jsonCount() > 0) {
            text.append("  ·  功能定义 ").append(info.jsonCount());
        }
        if (!info.hasManifest()) {
            text.append("  ·  缺少 pack.json（旧格式）");
        }
        return text.toString();
    }

    // ── 工具栏 ─────────────────────────────────────────────────────────

    /**
     * 工具栏。
     *
     * <p>左侧只留拓展包数量：状态已经由列表指示灯表达，建筑总数、未启用数、旧版数、
     * 故障数再堆一行只会挤掉按钮、还要靠截断收场。任何一个包出问题，用户都能在列表里
     * 直接看到红灯，比在工具栏上读一个数字更快。
     *
     * <p>文字仍按可用宽度截断：窗口被 GUI 缩放压小时，宁可省略号也不能让文字压到按钮上。
     */
    private static UIElement toolbar(SearchBar searchBar) {
        UIElement bar = SimuPackLayout.toolbarRow(TOOLBAR_HEIGHT, GAP);
        bar.addClass("simupack_toolbar");

        List<BuildingPack> packs = BuildingPackManager.packs();
        // 只有"禁用的、且真能启用的"包才让「全部启用」出现：坏包点了也没用。
        long disabled = packs.stream().filter(pack -> !pack.enabled() && !pack.unusable()).count();

        // 模组更新的常驻入口：弹窗只弹一次，用户当时选「稍后」之后必须有地方回来点。
        ModUpdateBadge modUpdate = UpdatePrompts.enabled() ? modUpdateBadge() : ModUpdateBadge.hidden();
        float modUpdateWidth = modUpdate.visible() ? 96 + GAP : 0F;

        // 右侧控件占用的固定宽度：搜索框 + 可能出现的"全部启用" + 模组更新 + 刷新 + 检查更新。
        float controls = SEARCH_WIDTH + 44 + 56 + modUpdateWidth + (disabled > 0 ? 52 + GAP : 0);
        float summaryWidth = Math.max(60F, SimuPackLayout.screenSize().width()
                - controls - GAP * 6 - COLUMN_GAP * 2);

        String summary = packs.size() + " "
                + Component.translatable("gui.simupack.packs_unit").getString();
        bar.addChild(SimuPackLayout.pinWidth(SimuPackLayout.label(
                Component.literal(SimuPackLayout.fitText(summary, summaryWidth)),
                SimuPackTheme.TEXT_SECONDARY_COLOR), summaryWidth));

        bar.addChild(SimuPackLayout.spacer(1));

        SearchComponent<String> search = searchBar.component;
        SimuPackLayout.pinWidth(search, SEARCH_WIDTH);
        bar.addChild(search);
        if (disabled > 0) {
            bar.addChild(actionButton(Component.translatable("gui.simupack.activation.enable_all"), 52,
                    PackManagerScreen::enableAll));
        }
        if (modUpdate.visible()) {
            // 文案写明版本号，用户一眼知道"有新版可用"而不是"点了没反应"。
            bar.addChild(actionButton(Component.translatable("gui.simupack.attention.mod_update.toolbar",
                    modUpdate.expectedVersion()), 96, UpdatePrompts::showModUpdateDialog));
        }
        bar.addChild(actionButton(Component.translatable("gui.simupack.refresh"), 44, () -> {
            BuildingPackManager.reload();
            UpdatePrompts.refreshAfterChange();
            Minecraft.getInstance().setScreen(new PackManagerScreen(currentParent()));
        }));
        // 「检查更新」原本是空实现；现在接上：重扫包 + 后台重算红点。
        bar.addChild(actionButton(Component.translatable("gui.simupack.check_updates"), 56,
                UpdatePrompts::checkForUpdatesNow));
        return bar;
    }

    /** 工具栏常驻入口要显示的模组更新信息。 */
    private record ModUpdateBadge(boolean visible, String expectedVersion, String currentVersion) {
        static ModUpdateBadge hidden() {
            return new ModUpdateBadge(false, "", "");
        }
    }

    /**
     * 当前有没有"筑艺坊自身可更新"。
     *
     * <p>直接读快照（O(1) 零 IO）而不是再去问一次版本来源：快照本来就是
     * {@code AttentionCenter} 在后台算好的那一份，界面只是把它显示出来。
     */
    private static ModUpdateBadge modUpdateBadge() {
        if (SimuPackClientSettings.isAttentionKindDisabled(AttentionKind.MOD_UPDATE)) {
            // 被关掉的类别连常驻入口一起消失（与红点、弹窗保持一致）。
            return ModUpdateBadge.hidden();
        }
        for (AttentionItem item : AttentionCenter.snapshot().items()) {
            if (item.kind() != AttentionKind.MOD_UPDATE) {
                continue;
            }
            List<Object> args = item.args();
            String current = args.size() > 0 ? String.valueOf(args.get(0)) : "?";
            String expected = args.size() > 1 ? String.valueOf(args.get(1)) : "?";
            return new ModUpdateBadge(true, expected, current);
        }
        return ModUpdateBadge.hidden();
    }

    /**
     * 工具栏的搜索框，以及它的"过滤条件变了"回调。
     *
     * <p>做成一个小对象是因为过滤的目标（左栏那棵树）在 {@link #installedWorkspace} 里创建，
     * 而搜索框在 {@link #toolbar} 里创建 —— 两者是兄弟节点，只能靠这个对象把线牵过去。
     *
     * <p><b>事件语义</b>（查过 LDLib2 的 SearchComponent 字节码）：它的
     * {@code textField.setTextResponder} 挂在 {@code TextField} 的值监听上，
     * <b>每敲一个字符都会回调一次</b>，回车不触发任何东西。所以过滤器必须能承受高频调用，
     * 也因此不能在每个字符上重建整个界面（会连输入框一起重建，用户没法继续打字）。
     */
    private static final class SearchBar {
        private final SearchComponent<String> component;
        private Consumer<String> onQueryChanged = query -> { };

        private SearchBar(SearchComponent<String> component) {
            this.component = component;
        }

        void onQueryChanged(Consumer<String> listener) {
            this.onQueryChanged = listener;
            // 同一个 SearchComponent 上再挂一个监听是安全的：registerValueListener 是追加，
            // 不会替换掉 LDLib 内部那个 onSearchWordChanged。
            this.component.textField.setTextResponder(query -> {
                // 先落到 static 字段上：本界面随时可能因为"启用/删除/刷新"被整个重建，
                // 不记住的话用户刚筛出来的结果会在操作一次后突然变回全量列表。
                searchQuery = query == null ? "" : query;
                this.onQueryChanged.accept(searchQuery);
            });
        }

        /** 当前搜索词。 */
        String query() {
            return searchQuery;
        }
    }

    /** 工具栏的搜索框；没有任何候选项（不显示弹窗列表），只当普通输入框用。 */
    private static SearchBar searchBar(String initialQuery) {
        SearchComponent<String> search = new SearchComponent<>();
        search.layout(layout -> layout.height(BUTTON_HEIGHT));
        // 静默写入（notify=false）：构造期间不能触发过滤 —— 那时监听器还没挂，
        // 而 show()/hide() 会用它再回调一次，真正的应用由 onQueryChanged 里的首次调用完成。
        search.textField.setValue(initialQuery == null ? "" : initialQuery, false);
        return new SearchBar(search);
    }

    /** 工具栏刷新时重建界面用的父界面；没有记录时退回标题界面。 */
    private static Screen currentParent() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof PackManagerScreen manager) {
            return manager.parent;
        }
        return screen;
    }

    private static Button actionButton(Component text, float width, Runnable action) {
        Button button = new Button().setText(text);
        button.setOnClick(event -> action.run());
        SimuPackLayout.pinWidth(button, width);
        button.layout(layout -> layout.height(BUTTON_HEIGHT));
        return button;
    }

    /**
     * 置灰的按钮：看得见、点不动。
     *
     * <p>必须自己上色，不能只靠 {@code setActive(false)}：Button 的底色是
     * {@code ButtonStyle} 的贴图画的（LSS 的 {@code background} 会被它盖掉），
     * 不换贴图的话按钮看起来和可点的按钮一模一样，用户会一直点它却没反应。
     * 悬停/按下态也给成同一个灰，避免出现"看起来能点"的反馈。
     */
    private static Button lockedButton(Component text, float width) {
        Button button = actionButton(text, width, () -> {
        });
        button.setActive(false);
        button.buttonStyle(style -> style
                .baseTexture(disabledTexture())
                .hoverTexture(disabledTexture())
                .pressedTexture(disabledTexture()));
        button.textStyle(style -> style.textColor(SimuPackTheme.TEXT_MUTED_COLOR));
        return button;
    }

    private static com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture disabledTexture() {
        return com.lowdragmc.lowdraglib2.gui.texture.RectTexture.of(0xFF2A2A2A)
                .setStroke(1F)
                .setBorderColor(0xFF4A4A4A);
    }
    // ── 主 TabView ─────────────────────────────────────────────────────

    private static UIElement mainTabs(PendingNotice notice, List<LegacyPackDetector.LegacyPackInfo> legacy,
                                      SearchBar searchBar) {
        TabView tabs = SimuPackLayout.constrainedTabs();
        tabs.layout(layout -> {
            layout.flex(1);
            layout.flexShrink(1);
        });
        Tab installed = new Tab().setText(Component.translatable("gui.simupack.installed"));
        Tab workshop = new Tab().setText(Component.translatable("gui.simupack.workshop"));
        tabs.addTab(installed, installedWorkspace(notice, legacy, searchBar));
        tabs.addTab(workshop, workshopWorkspace());
        // 显式选中第一个页签：LDLib2 默认会选中最后添加的页签。
        tabs.selectTab(installed);
        return tabs;
    }

    // ── Tab A：已安装拓展包（拓展包列表 + 包详情） ─────────────────────

    private static UIElement installedWorkspace(PendingNotice notice,
                                                List<LegacyPackDetector.LegacyPackInfo> legacy,
                                                SearchBar searchBar) {
        UIElement row = SimuPackLayout.fillRow(GAP);
        List<BuildingPack> packs = BuildingPackManager.packs();

        // 右侧：包详情。信息区可纵向滚动，操作行固定在面板底部。
        UIElement detailBody = SimuPackLayout.column(COLUMN_GAP);
        var detailScroller = SimuPackLayout.scroller();
        detailScroller.addScrollViewChild(detailBody);

        UIElement detailPanel = SimuPackLayout.asPercentColumn(
                SimuPackTheme.panel(), 100F - PACK_LIST_PERCENT, COLUMN_GAP);
        detailPanel.addChild(sectionTitle(Component.translatable("gui.simupack.pack_detail")));
        detailPanel.addChild(detailScroller);

        UIElement detailActions = SimuPackLayout.shrinkable(SimuPackLayout.buttonRow(GAP));
        detailActions.layout(layout -> layout.flexShrink(0));
        detailPanel.addChild(detailActions);

        // 左侧：拓展包列表。旧版包以「（旧版）」形式混在同一棵树里。
        UIElement listPanel = SimuPackLayout.asPercentColumn(
                SimuPackTheme.panel(), PACK_LIST_PERCENT, COLUMN_GAP);
        listPanel.addChild(sectionTitle(Component.translatable("gui.simupack.pack_list")));

        // 安装/转换结果提示：只在本轮界面显示一次，重建后自然消失。
        if (notice.visible()) {
            listPanel.addChild(SimuPackLayout.wrappedLabel(
                    Component.literal(notice.text()),
                    notice.error() ? SimuPackTheme.TEXT_ERROR_COLOR : SimuPackTheme.TEXT_SUCCESS_COLOR));
        }

        // 树的回调要按「先读、后 setRoot」的顺序装好：setRoot 会立刻重建节点 UI。
        // 用一格数组装"选中了哪个包"与"正在恢复选中"，回调与过滤器共用同一份状态。
        String[] selectedKey = new String[1];
        boolean[] restoring = new boolean[1];
        boolean[] reshowDetail = new boolean[]{true};
        TreeList<PackTreeNode> tree = new TreeList<>(PackTreeNode.root(new ArrayList<>()), true);
        NodeRefresh refresh = new NodeRefresh(tree, selectedKey, restoring, reshowDetail,
                detailBody, detailActions, legacy);
        tree.setFlattenRoot(true);
        tree.setClickToExpand(true);
        // 每行左侧一个状态指示灯：绿实心=已启用、空心框=未启用、红实心=故障或旧版待转换。
        tree.setNodeUISupplier(node -> switch (node.kind()) {
            case PACK -> {
                BuildingPack pack = node.pack();
                boolean lit = pack.enabled() || pack.faulty();
                yield indicatorRow(packIndicatorColor(pack), packLabel(pack), lit);
            }
            case LEGACY -> indicatorRow(SimuPackTheme.TEXT_ERROR_COLOR, node.getContent(), true);
            case ROOT -> indicatorRow(SimuPackTheme.TEXT_PRIMARY_COLOR, node.getContent(), true);
        });
        tree.setOnSelectedChanged(selected -> onPackSelected(selected, refresh));

        // 搜索框接在树的根上：过滤只重建节点列表，不重建界面 ——
        // SearchComponent 每敲一个字符就回调一次，重建界面会让输入框失焦、没法继续打字。
        searchBar.onQueryChanged(query -> refresh.show(matchingPacks(packs, query)));
        // 首次应用：把初始查询（重建界面时带回来的那个）真正过滤一次。
        refresh.show(matchingPacks(packs, searchBar.query()));

        SimuPackLayout.fill(tree);
        listPanel.addChild(tree);

        row.addChild(listPanel);
        row.addChild(detailPanel);

        // 默认展示第一个包的详情，避免右侧一开始是空白。
        // 「过滤后自动恢复选中」那条路径已经把详情画好了（reshowDetail 被消费掉），不要覆盖它。
        if (reshowDetail[0]) {
            if (!packs.isEmpty()) {
                detailBody.addChild(packDetail(packs.get(0)));
                updateDetailActions(detailActions, packs.get(0));
            } else if (!legacy.isEmpty()) {
                detailBody.addChild(legacyDetail(legacy.get(0)));
                updateDetailActions(detailActions, null);
            } else {
                detailBody.addChild(SimuPackLayout.wrappedLabel(
                        Component.translatable("gui.simupack.empty"), SimuPackTheme.TEXT_MUTED_COLOR));
                updateDetailActions(detailActions, null);
            }
        }

        // 打开界面时目录里就有旧包：立刻提示一次（NSUK 不会读它们，但用户需要知道能转换）。
        if (!legacy.isEmpty()) {
            LegacyPackDetector.LegacyPackInfo first = legacy.get(0);
            // tell 而不是 execute：构建界面时仍在渲染线程上，execute 会当场执行，
            // 于是"等界面挂上再弹"变成"构建过程中就弹"；tell 排到下一帧开头。
            Minecraft.getInstance().tell(() -> askConvert(first));
        }
        return row;
    }

    /**
     * 重建详情面板底部的操作行。
     *
     * <p>「启用/禁用」和「删除」都是<b>包级</b>动作，必须跟着当前选中的包走：
     * 操作行如果固定不动，用户切了包再点按钮，删掉的会是上一个包。
     *
     * <p>三种包不给启用/禁用按钮：
     * <ul>
     *   <li><b>读不出来的坏包</b>：启用它不会让游戏多出任何建筑，禁用也没有意义，
     *       按钮置灰并写明原因；</li>
     *   <li><b>官方内置包</b>：不显示「禁用」（禁用它游戏里一个可建建筑都不剩，服务层也会拒绝），
     *       也不显示「删除」（NSUK 目录空了会把它补回来）；</li>
     *   <li>没有任何可用建筑的包：同上按坏包处理。</li>
     * </ul>
     */
    private static void updateDetailActions(UIElement actions, BuildingPack pack) {
        actions.clearAllChildren();
        actions.addChild(actionButton(Component.translatable("gui.simupack.open_folder"), 64, () ->
                Util.getPlatform().openPath(BuildingPackManager.rootDirectory())));
        if (pack == null) {
            return;
        }

        // 坏包：按钮置灰（点不动）。不额外写一行说明——右侧「状态: 存在格式错误」已经说清原因，
        // 再写一句只会挤在同一行里被裁掉（而且这句话对"读不出来"的包并不准确）。
        if (pack.unusable()) {
            actions.addChild(lockedButton(
                    Component.translatable(pack.enabled()
                            ? "gui.simupack.activation.disable" : "gui.simupack.activation.enable"),
                    52));
            if (!pack.official()) {
                actions.addChild(actionButton(Component.translatable("gui.simupack.delete.action"), 52,
                        () -> askDelete(pack)));
            }
            return;
        }

        if (pack.official() && pack.enabled()) {
            // 官方包已启用：唯一可做的操作是"更新到内置版本"，而且只在真的有更新时出现
            // ——没有更新时给一个点了没反应的按钮，比不给按钮更让人困惑。
            //
            // 注意这里**只加能力、不改锁定语义**：仍然没有「禁用」，也没有「删除」，
            // 因为官方内置包是 NSUK 的最小可用建筑集，"不能禁用或删除"的既有结论不变。
            OfficialPackUpdate update = UpdatePrompts.enabled() ? officialPackUpdate() : OfficialPackUpdate.none();
            if (update.available()) {
                actions.addChild(actionButton(
                        Component.translatable("gui.simupack.attention.pack_update.action"), 96,
                        () -> askOfficialPackUpdate(update)));
            } else {
                // 没有可做的操作。这里明确说一句，避免用户以为按钮丢了。
                actions.addChild(SimuPackLayout.shrinkable(SimuPackLayout.label(
                        Component.translatable("gui.simupack.activation.official_locked"),
                        SimuPackTheme.TEXT_MUTED_COLOR)));
            }
            return;
        }
        actions.addChild(actionButton(
                pack.enabled() ? Component.translatable("gui.simupack.activation.disable")
                        : Component.translatable("gui.simupack.activation.enable"),
                52, () -> toggleActivation(pack)));
        if (!pack.official()) {
            actions.addChild(actionButton(Component.translatable("gui.simupack.delete.action"), 52,
                    () -> askDelete(pack)));
        }
    }

    /**
     * 官方包更新的现状：有没有更新、两边的版本号。
     *
     * <p>读的是注入的版本来源（生产环境是 NSUK 反射桥）。<b>只在详情面板重建时调用</b>
     * （用户选中包才走这条路径），不在渲染路径上，所以这点反射开销可以接受。
     */
    private record OfficialPackUpdate(boolean available, String localVersion, String builtinVersion) {
        static OfficialPackUpdate none() {
            return new OfficialPackUpdate(false, "", "");
        }
    }

    /** 问一次官方包版本来源，判断有没有更新。 */
    private static OfficialPackUpdate officialPackUpdate() {
        try {
            OfficialPackVersionSource source = new NsukOfficialPackVersionSource();
            java.util.Optional<String> local = source.localVersion();
            java.util.Optional<String> builtin = source.builtinVersion();
            if (local.isEmpty() || builtin.isEmpty()) {
                // 任一侧读不出来 = UNKNOWN = 不显示按钮（fail-closed，与红点判据同源）。
                return OfficialPackUpdate.none();
            }
            if (!VersionCompare.isOlderThan(local.get(), builtin.get())) {
                return OfficialPackUpdate.none();
            }
            return new OfficialPackUpdate(true, local.get(), builtin.get());
        } catch (RuntimeException | LinkageError exception) {
            // NSUK 缺失或 API 变更：不显示按钮，不影响其余界面。
            return OfficialPackUpdate.none();
        }
    }

    /**
     * 官方包更新的二次确认。
     *
     * <p>更新会覆盖本地官方包（虽然先备份），所以不做"点了就更新"。
     * 这也是弹窗只弹一次之后用户回来操作的常驻入口。
     */
    private static void askOfficialPackUpdate(OfficialPackUpdate update) {
        UIElement host = dialogHost;
        if (host == null || hasOpenDialog()) {
            return;
        }
        UIElement body = SimuPackLayout.column(COLUMN_GAP);
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.attention.pack_update.versions",
                        update.localVersion(), update.builtinVersion()),
                SimuPackTheme.TEXT_PRIMARY_COLOR));

        Screen parent = currentParent();
        SimuPackDialog.open(host,
                Component.translatable("gui.simupack.attention.pack_update.title"),
                body,
                overlay -> {
                    host.removeChild(overlay);
                    UpdatePrompts.runOfficialPackUpdate(parent);
                },
                () -> rebuildWithNotice(
                        Component.translatable("gui.simupack.attention.pack_update.cancelled").getString(),
                        false),
                null, null, null,
                "gui.simupack.attention.pack_update.confirm",
                "gui.simupack.attention.pack_update.later");
    }

    /**
     * 列表里一行包的显示文字。
     *
     * <p>不再带「（已启用）/（未启用）」文字后缀：状态已经由左侧指示灯表达，
     * 文字再重复一遍只会把行撑长。官方包仍标出来——它没有开关，用户需要知道为什么。
     */
    private static String packLabel(BuildingPack pack) {
        StringBuilder text = new StringBuilder(pack.name());
        if (pack.faulty()) {
            text.append("  ⚠");
        }
        if (pack.official()) {
            text.append(Component.translatable("gui.simupack.activation.suffix_official").getString());
        }
        return text.toString();
    }

    /**
     * 状态指示灯的颜色。
     *
     * <p>绿 = 已启用（NSUK 会读到它）；灭 = 未启用（文件在，但游戏里没有它）；
     * 红 = 故障（格式错误或读不出任何建筑）与旧版待转换——这两种都需要用户处理。
     */
    private static int packIndicatorColor(BuildingPack pack) {
        if (pack.faulty()) {
            return SimuPackTheme.TEXT_ERROR_COLOR;
        }
        return pack.enabled() ? SimuPackTheme.TEXT_SUCCESS_COLOR : SimuPackTheme.INDICATOR_OFF_COLOR;
    }

    /**
     * 树节点的一行：左边指示灯，右边文字。
     *
     * <p>指示灯有两种画法，对应"亮"和"灭"两种读法：
     * <ul>
     *   <li>亮（已启用 / 故障）：实心色块，一眼就能看到；</li>
     *   <li>灭（未启用）：<b>空心方框</b>。实心暗块会和面板底色混在一起看不见，
     *       空心框才能在任意底色上读出"这里有个灯，但它是灭的"。</li>
     * </ul>
     *
     * <p>用色块而不是画一个圆点字符：颜色与描边完全可控，也不受字体 fallback 影响
     * （与创作空间左栏的节点配色保持同一套做法）。
     */
    private static UIElement indicatorRow(int indicatorColor, String text, boolean lit) {
        UIElement row = new UIElement();
        row.layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.minHeight(14F);
            layout.minWidth(0);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(dev.vfyjxf.taffy.style.AlignItems.CENTER);
            layout.gapAll(4);
        });

        com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture indicatorTexture = lit
                ? new com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture(indicatorColor)
                : new com.lowdragmc.lowdraglib2.gui.texture.ColorBorderTexture(0x00000000, indicatorColor)
                        .setBorder(1);

        UIElement indicator = new UIElement();
        indicator.layout(layout -> {
            layout.width(6);
            layout.height(6);
            layout.flexShrink(0);
        });
        indicator.style(style -> style.background(indicatorTexture));
        row.addChild(indicator);

        UIElement label = SimuPackLayout.label(Component.literal(text), SimuPackTheme.TEXT_SECONDARY_COLOR);
        label.layout(layout -> {
            layout.flex(1);
            layout.flexShrink(1);
            layout.minWidth(0);
        });
        row.addChild(label);
        return row;
    }

    private static LegacyPackDetector.LegacyPackInfo legacyByKey(
            List<LegacyPackDetector.LegacyPackInfo> legacy, String key) {
        String path = key.startsWith(LEGACY_KEY_PREFIX) ? key.substring(LEGACY_KEY_PREFIX.length()) : key;
        for (LegacyPackDetector.LegacyPackInfo info : legacy) {
            if (info.path().toAbsolutePath().toString().equals(path)) {
                return info;
            }
        }
        return null;
    }

    /** 旧包详情：为什么被判定为旧版、转换后会做什么。 */
    private static UIElement legacyDetail(LegacyPackDetector.LegacyPackInfo info) {
        UIElement body = SimuPackLayout.column(COLUMN_GAP);
        body.layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(dev.vfyjxf.taffy.style.AlignItems.STRETCH);
            layout.gapAll(COLUMN_GAP);
        });

        body.addChild(SimuPackLayout.shrinkable(SimuPackLayout.label(
                Component.literal(info.displayName()), SimuPackTheme.TEXT_PRIMARY_COLOR)));
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.legacy.detected"), SimuPackTheme.TEXT_INFO_COLOR));

        body.addChild(valueRow("gui.simupack.detail.file", info.path().getFileName().toString()));
        body.addChild(valueRow("gui.simupack.detail.buildings", String.valueOf(info.skCount())));
        body.addChild(valueRow("gui.simupack.legacy.detail_manifest",
                Component.translatable(info.hasManifest() ? "gui.simupack.detail.yes"
                        : "gui.simupack.detail.no").getString()));
        body.addChild(valueRow("gui.simupack.legacy.detail_nbt", String.valueOf(info.nbtCount())));
        body.addChild(valueRow("gui.simupack.legacy.detail_other_struct",
                String.valueOf(info.nonNbtCount())));
        if (!info.version().isBlank()) {
            body.addChild(valueRow("gui.simupack.detail.version", info.version()));
        }
        if (!info.author().isBlank()) {
            body.addChild(valueRow("gui.simupack.detail.author", info.author()));
        }
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.literal(Component.translatable("gui.simupack.detail.path").getString()
                        + ": " + abbreviate(info.path().toString(), 42)),
                SimuPackTheme.TEXT_MUTED_COLOR));

        body.addChild(SimuPackLayout.fixedSpacer(2));
        body.addChild(sectionTitle(Component.translatable("gui.simupack.legacy.what_next")));
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.legacy.prompt"), SimuPackTheme.TEXT_SECONDARY_COLOR));
        if (info.nonNbtCount() > 0) {
            body.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.legacy.will_convert",
                            String.valueOf(info.nonNbtCount())), SimuPackTheme.TEXT_INFO_COLOR));
        }
        return body;
    }

    /** 包详情：名称、来源、作者、版本、建筑数量、状态、格式错误与更新日志。 */
    private static UIElement packDetail(BuildingPack pack) {
        UIElement body = SimuPackLayout.column(COLUMN_GAP);
        body.layout(layout -> {
            layout.widthPercent(100);
            layout.minWidth(0);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(dev.vfyjxf.taffy.style.AlignItems.STRETCH);
            layout.gapAll(COLUMN_GAP);
        });

        body.addChild(SimuPackLayout.shrinkable(SimuPackLayout.label(
                Component.literal(pack.name()), SimuPackTheme.TEXT_PRIMARY_COLOR)));

        body.addChild(detailRow("gui.simupack.detail.status",
                pack.valid() ? "gui.simupack.detail.status_ok" : "gui.simupack.detail.status_error",
                pack.valid() ? SimuPackTheme.TEXT_SUCCESS_COLOR : SimuPackTheme.TEXT_ERROR_COLOR));
        // 启用状态单独一行、用颜色区分：这是用户唯一能看出"装了但没生效"的地方。
        body.addChild(detailRow("gui.simupack.detail.activated",
                pack.enabled() ? "gui.simupack.detail.activated_yes" : "gui.simupack.detail.activated_no",
                pack.enabled() ? SimuPackTheme.TEXT_SUCCESS_COLOR : SimuPackTheme.TEXT_INFO_COLOR));
        // 坏包不解释"未启用是什么意思"：它的问题不是没启用，而是读不出来，
        // 该说的是上面那行「状态: 存在格式错误」。
        // 只有"能用的包却没启用"需要额外解释一句 —— 那是用户唯一看不出来的状态
        // （装了但没生效）。官方包那条下面已经有「官方包: 是」与操作行里的说明，
        // 这里再插一段就是同一句话说三遍。
        if (!pack.enabled() && !pack.unusable()) {
            body.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.detail.disabled_hint"),
                    SimuPackTheme.TEXT_INFO_COLOR));
        }
        body.addChild(detailRow("gui.simupack.detail.official",
                pack.official() ? "gui.simupack.detail.yes" : "gui.simupack.detail.no",
                pack.official() ? SimuPackTheme.TEXT_INFO_COLOR : SimuPackTheme.TEXT_SECONDARY_COLOR));
        body.addChild(valueRow("gui.simupack.detail.author",
                pack.author().isBlank() ? "—" : pack.author()));
        body.addChild(valueRow("gui.simupack.detail.version",
                pack.hasVersion() ? pack.version() : "—"));
        body.addChild(valueRow("gui.simupack.detail.buildings",
                String.valueOf(pack.buildingCount())));
        body.addChild(valueRow("gui.simupack.detail.file",
                pack.path().getFileName().toString()));
        // 不显示"最后检查时间"与"完整路径"：
        //   · 检查时间每次刷新都是当下，不含任何用户能据以决策的信息；
        //   · 所有包都在同一个建筑目录里，路径对每个包都一样，"来源文件"那行
        //     （文件名）已经足够区分；完整路径又会把面板撑破、还得靠省略号收场。
        if (!pack.description().isBlank()) {
            body.addChild(SimuPackLayout.wrappedLabel(
                    Component.literal(pack.description()), SimuPackTheme.TEXT_SECONDARY_COLOR));
        }

        body.addChild(SimuPackLayout.fixedSpacer(2));
        // 构建版本（包格式号对应的 Minecraft 版本线，见 PackFormat）。
        // 版本线不符时把它说出来 —— 玩家遇到的"这个包怎么没效果"里，
        // 有一部分就是拿了别的版本线的包。
        body.addChild(formatRow(pack));
        body.addChild(sectionTitle(Component.translatable("gui.simupack.detail.errors")));
        if (pack.errors().isEmpty()) {
            body.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.errors.empty"), SimuPackTheme.TEXT_SUCCESS_COLOR));
        } else {
            for (String error : pack.errors()) {
                body.addChild(SimuPackLayout.wrappedLabel(
                        Component.literal("⚠ " + error), SimuPackTheme.TEXT_ERROR_COLOR));
            }
        }

        body.addChild(SimuPackLayout.fixedSpacer(2));
        body.addChild(sectionTitle(Component.translatable("gui.simupack.changelog")));
        body.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.changelog.empty"), SimuPackTheme.TEXT_MUTED_COLOR));
        return body;
    }

    /**
     * 「构建版本」这一行：这个包是给哪条 Minecraft 版本线做的。
     *
     * <p>不显示格式号本身（`2` 这种数字对用户没有意义），只说版本名 ——
     * 但判定仍按 {@code PackFormat} 的格式号来。四种情况分开处理，因为该做的事不一样：
     * <ul>
     *   <li><b>一致</b>：只报版本名，不占用注意力；</li>
     *   <li><b>更早</b>：装到本版本线上可能缺内容，建议找作者要新版；</li>
     *   <li><b>更新</b>：本版本线读不全，要换游戏版本；</li>
     *   <li><b>未声明</b>：老包或手写的包，如实说"未声明"。</li>
     * </ul>
     */
    private static UIElement formatRow(BuildingPack pack) {
        String labelKey = "gui.simupack.detail.build_version";
        if (pack.format() == PackFormat.UNKNOWN) {
            return valueRow(labelKey,
                    Component.translatable("gui.simupack.detail.format_undeclared").getString());
        }

        String minecraft = pack.formatMinecraftVersion();
        PackFormat.Compatibility compatibility = pack.formatCompatibility();
        int color = compatibility.usable()
                ? SimuPackTheme.TEXT_SECONDARY_COLOR : SimuPackTheme.TEXT_WARNING_COLOR;
        UIElement row = SimuPackLayout.shrinkable(SimuPackLayout.label(
                Component.literal(Component.translatable(labelKey).getString() + ": "
                        + (minecraft == null ? "?" : "Minecraft " + minecraft)), color));

        if (compatibility == PackFormat.Compatibility.OLDER) {
            UIElement column = SimuPackLayout.column(2);
            column.addChild(row);
            column.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.detail.format_older"),
                    SimuPackTheme.TEXT_WARNING_COLOR));
            return column;
        }
        if (compatibility == PackFormat.Compatibility.NEWER) {
            UIElement column = SimuPackLayout.column(2);
            column.addChild(row);
            column.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.detail.format_newer"),
                    SimuPackTheme.TEXT_WARNING_COLOR));
            return column;
        }
        return row;
    }

    /**
     * 从左侧缩写过长文本。
     *
     * <p>文件路径没有空格，无法自动换行，直接显示会把面板横向撑破，
     * 因此保留结尾（对路径而言结尾信息量更大）并加省略号。
     */
    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return "…" + value.substring(value.length() - maxLength + 1);
    }

    /** 标签 + 取值来自翻译键的一行。 */
    private static UIElement detailRow(String labelKey, String valueKey, int valueColor) {
        return SimuPackLayout.shrinkable(SimuPackLayout.label(Component.literal(
                        Component.translatable(labelKey).getString() + ": "
                                + Component.translatable(valueKey).getString()),
                valueColor));
    }

    /** 标签 + 字面量取值的一行。 */
    private static UIElement valueRow(String labelKey, String value) {
        return SimuPackLayout.shrinkable(SimuPackLayout.label(Component.literal(
                        Component.translatable(labelKey).getString() + ": " + value),
                SimuPackTheme.TEXT_SECONDARY_COLOR));
    }

    // ── Tab B：拓展包创意工坊 ──────────────────────────────────────────

    private static UIElement workshopWorkspace() {
        // 页签内容根节点用 flex(1) 撑满内容容器，避免用百分比高度造成高度链断裂。
        UIElement wrapper = SimuPackLayout.pageColumn(GAP);
        wrapper.layout(layout -> {
            layout.flex(1);
            layout.flexShrink(1);
            layout.minHeight(0);
        });

        UIElement panel = SimuPackLayout.asFlexColumn(SimuPackTheme.panel(), COLUMN_GAP);
        panel.addChild(sectionTitle(Component.translatable("gui.simupack.workshop.title")));
        panel.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.workshop.search_hint"), SimuPackTheme.TEXT_SECONDARY_COLOR));
        panel.addChild(SimuPackLayout.spacer(1));

        UIElement actions = SimuPackLayout.shrinkable(SimuPackLayout.buttonRow(GAP));
        actions.layout(layout -> layout.flexShrink(0));
        actions.addChild(actionButton(Component.translatable("gui.simupack.workshop.open_web"), 84, () -> {
            // 官网接口接入后打开 https://nsimu.cn/
        }));
        panel.addChild(actions);

        wrapper.addChild(panel);
        return wrapper;
    }

    // ── 通用小工具 ─────────────────────────────────────────────────────

    private static UIElement sectionTitle(Component text) {
        return SimuPackLayout.shrinkable(
                SimuPackLayout.label(text, SimuPackTheme.TEXT_PRIMARY_COLOR));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
