package com.rstech.simupack.client.ui.editor;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.client.ui.preview.StructurePreviewScene;
import com.rstech.simupack.client.ui.preview.UnitBoxVisual;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 选点会话：结构预览画布 + 点选累积 + 撤销 + 高亮 + 落盘回调。
 *
 * <h2>为什么它要独立出来</h2>
 *
 * <p>选点**不是工业专属能力**。它现在的使用者是工业点位与商业容器，
 * 将来住宅（家具/床位/停车位之类）也会用。原先这套逻辑直接写在
 * {@code SingleplayerEditorScreen} 里，并且深度绑死工业：
 * {@code pickScope} 里塞的是 {@code industrialPanel.recipeId()}，
 * 收尾时直接调 {@code industrialPanel.definePoint(...)}。
 * 结果是「谁想选点，就得先变成一个工业面板」——这是错的抽象。
 *
 * <p>抽取之后，领域相关的东西只剩下 {@link Target} 一个接口：<b>把选好的坐标交给谁</b>。
 * 工业实现它去弹命名框并写配方；商业实现它去写容器坐标；将来的住宅写自己的东西。
 * 选点本身——画布、点选、撤回、状态、作用域失效保护——完全领域无关。
 *
 * <h2>与屏幕的分工</h2>
 *
 * <p>本类<b>不碰页签系统</b>（那是屏幕的 TabView）。页签的挂载/关闭/是否处于活动态
 * 由 {@link Host} 代劳，所以这个类不依赖 {@code SingleplayerEditorScreen}，
 * 也就不会变成第二个上帝类。
 */
@OnlyIn(Dist.CLIENT)
public final class PointPicker {

    /** 落盘目标：抽取之后，唯一需要按领域实现的部分。 */
    public interface Target {
        /** 选点页签标题的翻译键。 */
        String titleKey();

        /** 重选时预填的坐标（新建时返回空表）。 */
        default List<BlockPos> initialPositions() {
            return List.of();
        }

        /**
         * 作用域是否仍然有效。
         *
         * <p>用户在选点过程中可能切换建筑、换配方、改配置 —— 那时选出来的坐标
         * 已经不属于原来的对象了。返回 false 会让会话<b>安全退出</b>，而不是把坐标
         * 写到一个已经不相干的条目上。
         */
        boolean stillValid();

        /** 作用域失效时的提示键。 */
        String scopeChangedKey();

        /**
         * 这个方块能不能被选中。
         *
         * <p>默认都行；商业容器用它把选择限制在 {@code c:chests} 标签内 ——
         * 「能开箱子的方块」和「能当物流端口的方块」不是一回事，前者宽得多。
         */
        default boolean accepts(net.minecraft.core.BlockPos pos) {
            return true;
        }

        /** 被 {@link #accepts} 拒绝时的提示键；返回 null 用通用文案。 */
        default String rejectKey() {
            return null;
        }

        /**
         * 选点期间一起画出来的<b>参考盒子</b>（例如该建筑里已经定义好的其它户型）。
         *
         * <p>默认不画。加在 {@code Target} 上而不是让 {@link PointPicker} 去问建筑，
         * 是为了不让这个通用选点器认识「户型」这个概念 —— 它只收到一串盒子。
         */
        default List<UnitBoxVisual> contextBoxes() {
            return List.of();
        }

        /**
         * 收下选好的坐标。
         *
         * @return {@code true} 表示收工（会话会关掉页签）；
         *     {@code false} 表示还没完 —— 例如工业那边弹了命名框，
         *     等用户确认后再自己调 {@link PointPicker#close()}。
         */
        boolean commit(List<BlockPos> positions);

    }

    /** 宿主：提供屏幕侧的能力，避免本类依赖具体屏幕。 */
    public interface Host {
        /** 当前预览用的结构 NBT。 */
        CompoundTag structureTag();

        /** 顶栏状态文案。 */
        void notify(String message);

        /** 刷新顶栏。 */
        void refreshStatus();

        /** 是否已有别的东西（对话框）盖在上面 —— 那时不该响应回车。 */
        boolean dialogOpen();

        /** 打开一个对话框。 */
        void openDialog(Component title, UIElement body, Consumer<UIElement> onConfirm);

        /** 把选点页签挂上并选中。 */
        void showPickTab(String titleKey, UIElement content, Runnable onClose);

        /** 选点页签已存在时，只把它切到前面（不重建画布）。 */
        void selectPickTab();

        /** 关掉选点页签。 */
        void hidePickTab();

        /** 选点页签当前是否为选中页签（决定按钮/回车是否生效）。 */
        boolean pickTabActive();
    }

    private final Host host;
    private final List<BlockPos> picked = new ArrayList<>();

    private Target target;
    private StructurePreviewScene scene;
    private UIElement statusHost;

    public PointPicker(Host host) {
        this.host = host;
    }

    /** 是否有正在进行的选点会话。 */
    public boolean active() {
        return target != null;
    }

    /** 已选坐标（只读快照）。 */
    public List<BlockPos> picked() {
        return List.copyOf(picked);
    }

    /**
     * 开始一次选点。
     *
     * <p>若已经有一个会话在跑，直接切回它的页签（屏幕侧负责选中）而不重开，
     * 这样反复点「选取点位」不会叠出第二个画布。
     */
    public void open(Target target) {
        if (target == null) {
            return;
        }
        if (active()) {
            host.selectPickTab();
            return;
        }
        this.target = target;
        picked.clear();
        picked.addAll(target.initialPositions());

        UIElement content = SimuPackLayout.column(2);
        SimuPackLayout.fill(content);

        // 选点使用透视相机，允许旋转后看进建筑内部；点选仍读取真实渲染矩阵。
        // 黄色悬浮与青色已选边框由场景在独立的一遍里画。
        scene = new StructurePreviewScene(false, false);
        scene.addClass("simupack_preview_canvas");
        SimuPackLayout.fill(scene);
        // 选中反馈归场景所有，不走原生 raycast。
        scene.setRenderSelect(false);
        scene.showStructure(host.structureTag());
        // 点选由场景自己按「相机有没有转过」判定（见 StructurePreviewScene.onPick），
        // 所以**不需要**关掉拖拽 —— 左键点选与左键拖拽旋转共存，没有模式开关。
        scene.onPick((pos, face) -> add(pos));
        // 点空处明确说一声：建筑常常只是包围盒里的一小部分（5×3×5 里三根细柱子），
        // 画布上大片是空的，没有这句反馈就分不清「没点中方块」和「功能坏了」。
        scene.onPickMiss(() -> host.notify(
                Component.translatable("gui.simupack.editor.pick_miss").getString()));
        // 右键 = 撤回上一个。场景里右键没有用途（平移是**中键**），所以不会打架。
        scene.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button == 1) {
                undo();
            }
        });

        statusHost = SimuPackLayout.column(2);
        UIElement buttons = SimuPackLayout.buttonRow(4);
        buttons.addChild(actionButton(
                Component.translatable("gui.simupack.editor.pick_done").getString(), this::finish));
        buttons.addChild(actionButton(
                Component.translatable("gui.simupack.editor.pick_cancel").getString(), this::close));

        content.addChild(scene);
        content.addChild(statusHost);
        content.addChild(buttons);
        host.showPickTab(target.titleKey(), content, this::close);
        refreshStatus();
    }

    /** 关闭选点会话并清干净状态。 */
    public void close() {
        target = null;
        scene = null;
        statusHost = null;
        picked.clear();
        host.hidePickTab();
    }

    /** 回车或「完成选取」。 */
    public void finish() {
        if (!active() || !host.pickTabActive() || host.dialogOpen()) {
            return;
        }
        if (!checkScope()) {
            return;
        }
        if (picked.isEmpty()) {
            host.notify(Component.translatable("gui.simupack.editor.pick_none").getString());
            close();
            return;
        }
        // commit 返回 false 时由目标自己收尾（例如它弹了命名框），这里不动页签。
        if (target.commit(List.copyOf(picked))) {
            close();
        }
    }

    /** 顶栏或底部状态里的坐标文本，例如 {@code (1,2,3)   (4,5,6)}。 */
    public String pickedText() {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < picked.size(); index++) {
            BlockPos pos = picked.get(index);
            if (index > 0) {
                builder.append("   ");
            }
            builder.append('(').append(pos.getX()).append(',').append(pos.getY())
                    .append(',').append(pos.getZ()).append(')');
        }
        return builder.toString();
    }

    private void add(BlockPos pos) {
        // 目标可以拒收（例如商业容器只认 c:chests 标签里的方块）。
        // 静默忽略是不行的：用户会觉得「点了没反应」，分不清是坏了还是不允许。
        if (target != null && !target.accepts(pos)) {
            String key = target.rejectKey();
            host.notify(Component.translatable(
                    key == null ? "gui.simupack.editor.pick_rejected" : key).getString());
            return;
        }
        // 同一个坐标重复点没有意义（positions 是集合语义）
        if (picked.contains(pos)) {
            return;
        }
        picked.add(pos);
        refreshStatus();
    }

    /** 撤回上一个（右键）。 */
    public void undo() {
        if (picked.isEmpty()) {
            return;
        }
        picked.remove(picked.size() - 1);
        refreshStatus();
    }

    /**
     * 作用域校验；失效时安全退出并提示。
     *
     * <p><b>公开</b>是给目标用的：目标的 {@code commit} 可能异步收尾（工业那边会弹命名框），
     * 等用户确认时早已过了 {@code finish()} 那一次校验，必须自己再查一遍 ——
     * 否则会把 A 建筑选的点写进 B 建筑的条目里。
     *
     * @return true 表示作用域仍然有效
     */
    public boolean checkScope() {
        if (target != null && target.stillValid()) {
            return true;
        }
        String message = target == null ? ""
                : Component.translatable(target.scopeChangedKey()).getString();
        close();
        if (!message.isEmpty()) {
            host.notify(message);
        }
        return false;
    }

    /**
     * 画布上要画的盒子：目标的参考盒子 + 当前两点合并出来的那一户。
     *
     * <p><b>「两个点自动合并成一个立方体」就在这里落地</b>：点数不足 2 时只画高亮点，
     * 凑满 2 个立刻画出归一化后的立方体 —— 用户在按「完成选取」之前就能看见这一户的实际范围，
     * 不必先提交再看结果。归一化交给 {@link UnitBoxVisual#boxed}，
     * 所以先点右上再点左下也得到同一个盒子。
     */
    private List<UnitBoxVisual> overlayBoxes() {
        List<UnitBoxVisual> boxes = new ArrayList<>(
                target == null ? List.of() : target.contextBoxes());
        if (picked.size() == 2) {
            BlockPos first = picked.get(0);
            BlockPos second = picked.get(1);
            boxes.add(UnitBoxVisual.boxed(
                    first.getX(), first.getY(), first.getZ(),
                    second.getX(), second.getY(), second.getZ(),
                    PENDING_BOX_RED, PENDING_BOX_GREEN, PENDING_BOX_BLUE, PENDING_BOX_ALPHA, true));
        }
        return boxes;
    }

    /** 待确认的合并盒：橙色半透明，与青色已选点、黄色悬浮方块区分开。 */
    private static final float PENDING_BOX_RED = 1.0F;
    private static final float PENDING_BOX_GREEN = 0.55F;
    private static final float PENDING_BOX_BLUE = 0.15F;
    private static final float PENDING_BOX_ALPHA = 0.16F;

    private void refreshStatus() {
        if (scene != null) {
            // 同步高亮：把已选点染成青色，「选了哪些」在画布上直接看得见。
            scene.setHighlighted(picked);
            scene.setUnitBoxes(overlayBoxes());
        }
        // 顶栏也报一次数：画布下面那一行有可能被挤到看不见
        //（高度是这套布局最容易出问题的地方），顶栏是常驻的，给它第二条反馈通道。
        host.notify(Component.translatable("gui.simupack.editor.pick_count", picked.size())
                .getString());
        if (statusHost == null) {
            return;
        }
        statusHost.clearAllChildren();
        statusHost.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.editor.pick_hint", picked.size()),
                SimuPackTheme.TEXT_MUTED_COLOR));
        String text = pickedText();
        if (!text.isEmpty()) {
            statusHost.addChild(SimuPackLayout.wrappedLabel(Component.literal(text),
                    SimuPackTheme.TEXT_SECONDARY_COLOR));
        }
    }

    private static com.lowdragmc.lowdraglib2.gui.ui.elements.Button actionButton(
            String text, Runnable action) {
        var button = new com.lowdragmc.lowdraglib2.gui.ui.elements.Button();
        button.setText(Component.literal(text));
        button.layout(layout -> {
            layout.height(EditorWidgets.ROW_HEIGHT + 2F);
            layout.flexShrink(0);
        });
        button.setOnClick(event -> action.run());
        return button;
    }
}