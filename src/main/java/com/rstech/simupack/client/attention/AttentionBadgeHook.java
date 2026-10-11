package com.rstech.simupack.client.attention;

import com.rstech.simupack.attention.AttentionCenter;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.attention.AttentionSnapshot;
import com.rstech.simupack.client.SimuPackClientSettings;
import com.rstech.simupack.client.ui.UpdatePrompts;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 把红点挂到主菜单的 P 按钮上。
 *
 * <p><b>挂载方式：{@code ScreenEvent.Render.Post}</b>（M0 已在 NeoForge sources 里核对到确切触发位置）：
 * <pre>
 * // ClientHooks.drawScreenInternal(Screen, GuiGraphics, int, int, float)
 * if (!post(new ScreenEvent.Render.Pre(...)).isCanceled())
 *     screen.renderWithTooltip(guiGraphics, mouseX, mouseY, partialTick);  // 所有控件 + tooltip
 * post(new ScreenEvent.Render.Post(screen, guiGraphics, mouseX, mouseY, partialTick));  // ← 挂这里
 * </pre>
 * 而且 {@code drawScreen()} 先画 GUI 层（{@code guiLayers}），最后才画 screen，
 * 所以 {@code Render.Post} 是<b>整屏最后一个绘制机会</b>（连 tooltip 都在它之前）。两个好处：
 * <ol>
 *   <li><b>绝对最顶层</b>，不会被任何后添加的控件或 tooltip 盖住；</li>
 *   <li><b>不会把渲染状态泄漏给同屏其它控件</b>（SDF 纹理会改混合模式并关深度测试）。</li>
 * </ol>
 *
 * <p>刻意<b>不做 {@code Button} 子类</b>（虽然 M0 核实过那条路也可行）：
 * 它把点画在控件自己的 render 阶段，理论上仍可能被同帧后画的元素覆盖，
 * 且会把 SDF 改过的渲染状态留给后续控件。
 *
 * <p><b>渲染路径红线</b>（踩坑清单）：
 * <ul>
 *   <li>❌ 渲染里调 {@code AttentionCenter.refresh()} —— 读 ZIP 会直接卡帧；</li>
 *   <li>❌ {@code Init.Post} 里做 IO —— 窗口每次缩放都会重建界面，会变成"缩放就卡一下"；</li>
 *   <li>❌ 红点状态存在按钮实例里 —— 界面重建会丢。</li>
 * </ul>
 * 所以按钮引用与所属 Screen 存在这里的静态字段，而"亮不亮"每帧从 {@code AttentionCenter} 现读。
 */
@OnlyIn(Dist.CLIENT)
public final class AttentionBadgeHook {

    /** 按钮在屏幕右上角的固定位置（与 {@code SimuPackClient.onScreenInit} 的 bounds 一致）。 */
    private static final int BUTTON_LEFT_INSET = 28;
    private static final int BUTTON_TOP = 8;
    private static final int BUTTON_SIZE = 20;

    /** 快照为空且距上次刷新超过这个间隔时，在标题界面补一次后台刷新。 */
    private static final long STALE_REFRESH_MILLIS = 30_000L;

    /** 当前挂着 P 按钮的界面；界面重建时会被新实例覆盖。 */
    private static Screen badgeScreen;
    /** 当前 P 按钮；用 {@link AbstractWidget} 而不是具体类型，只是要它的 visible/bounds。 */
    private static AbstractWidget badgeButton;

    /** 上一次真正画点时的快照内容哈希：只在内容变化时记日志，避免每帧刷屏。 */
    private static int lastLoggedHash = Integer.MIN_VALUE;

    private AttentionBadgeHook() {
    }

    // ── 挂载（Init.Post） ──────────────────────────────────────────────

    /**
     * 记下"这个界面上有这个按钮"，供 {@code Render.Post} 判断要不要画点。
     *
     * <p>只存引用，<b>不做任何 IO、不触发刷新</b>。
     */
    public static void attach(Screen screen, AbstractWidget button) {
        badgeScreen = screen;
        badgeButton = button;
    }

    /** 界面换了或按钮没了：清掉记录，避免把点画到别的界面上。 */
    public static void clear() {
        badgeScreen = null;
        badgeButton = null;
    }

    // ── 渲染（Render.Post） ────────────────────────────────────────────

    /**
     * {@code Render.Post} 的处理：判断该不该画，然后画。
     *
     * <p>每帧都调，所以这里的一切都必须廉价：只读 {@link AttentionCenter#snapshot()}
     * （O(1) 的 {@code AtomicReference} 读）与几个布尔开关。
     */
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        // 只画在"记录了按钮的那个界面"上：界面重建后新实例会重新 attach，
        // 期间旧实例的事件不该画点。
        if (screen == null || screen != badgeScreen) {
            return;
        }
        AbstractWidget button = badgeButton;
        if (button == null || !button.visible) {
            return;
        }
        // 只画在标题界面：P 按钮本来就是标题界面的入口。
        if (!(screen instanceof TitleScreen)) {
            return;
        }

        if (!SimuPackClientSettings.attentionDot() || !updatePromptsEnabled()) {
            return;
        }
        AttentionSnapshot snapshot = AttentionCenter.snapshot();
        if (!lightsUp(snapshot)) {
            return;
        }

        logOnContentChange(snapshot);

        AttentionDotRenderer.render(event.getGuiGraphics(),
                button.getX(), button.getY(), button.getWidth(), button.getHeight(),
                SimuPackClientSettings.attentionDotAnimate());
    }

    private static boolean updatePromptsEnabled() {
        return UpdatePrompts.enabled();
    }

    /**
     * 快照是否该点亮红点。
     *
     * <p>要考虑 {@code attentionDisabledKinds}：被关掉的类别<b>既不亮红点也不弹窗</b>
     * （整合包作者面对"某类问题长期解决不了"时的逃生口）。
     */
    static boolean lightsUp(AttentionSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        for (AttentionItem item : snapshot.items()) {
            if (!SimuPackClientSettings.isAttentionKindDisabled(item.kind())) {
                return true;
            }
        }
        return false;
    }

    // ── 时机：标题界面上只读缓存，必要时补一次后台刷新 ────────────────

    /**
     * 标题界面打开时的刷新判断。
     *
     * <p><b>只读缓存</b>；只有"快照还没算过、或者已经放了很久"才补一次后台刷新——
     * 避免每次进出标题界面都触发一遍磁盘扫描。
     *
     * <p>用 {@code Util.backgroundExecutor()} 而不是当前线程：这里仍然在界面初始化路径上。
     */
    public static void maybeRefreshOnTitleScreen() {
        AttentionSnapshot snapshot = AttentionCenter.snapshot();
        boolean stale = snapshot.isEmpty()
                && AttentionCenter.millisSinceLastRefresh() > STALE_REFRESH_MILLIS;
        if (!stale) {
            return;
        }
        AttentionCenter.refreshAsync(Util.backgroundExecutor());
    }

    /** 只在实际画点、且快照<b>内容</b>变了时记一条 debug 日志。 */
    private static void logOnContentChange(AttentionSnapshot snapshot) {
        int hash = snapshot.hashCode();
        if (hash == lastLoggedHash) {
            return;
        }
        lastLoggedHash = hash;
        AttentionCenter.debugSnapshot(snapshot);
    }

    /** 供调试：把按钮矩形暴露出来，方便与 M0 结论核对。 */
    public static int buttonLeft(int screenWidth) {
        return screenWidth - BUTTON_LEFT_INSET;
    }

    public static int buttonTop() {
        return BUTTON_TOP;
    }

    public static int buttonSize() {
        return BUTTON_SIZE;
    }

    /** 屏幕尺寸变了之后按钮会重建，此时旧的矩形记录必须失效。 */
    public static void onScreenResize() {
        Minecraft minecraft = Minecraft.getInstance();
        if (badgeScreen != null && badgeScreen != minecraft.screen) {
            clear();
        }
    }

    /** 未使用的分类常量在这里显式引用一次，保证枚举被初始化（避免 IDE 误删导入）。 */
    static AttentionKind[] kinds() {
        return AttentionKind.values();
    }
}
