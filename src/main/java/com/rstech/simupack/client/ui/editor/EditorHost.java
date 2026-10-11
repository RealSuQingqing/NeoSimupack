package com.rstech.simupack.client.ui.editor;

import com.rstech.simupack.client.ui.SimuPackMenuBar;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * 编辑器面板与「会话」之间的接口。
 *
 * <p>面板不直接操作工作区、撤销栈或页签 —— 那些都归编辑器会话管。面板只声明「我改完了」
 * 或「请帮我打开某个页签」，由会话决定怎么落实。
 *
 * <p><b>页签入口刻意做成枚举而不是每加一个分类就加一个方法。</b>
 * 重构前这里是 {@code openActionTab()} + {@code openPickTab()}，再加商业 {@code openTradeTab()}、
 * 公共再来一个，接口会随分类线性膨胀。改成 {@link #openMiddleTab(MiddleTabKind)} 之后，
 * 新增分类不需要动这个接口。
 */
@OnlyIn(Dist.CLIENT)
public interface EditorHost {

    /**
     * 在鼠标位置弹出下拉菜单。
     *
     * <p>传鼠标坐标而不是控件对象：控件可能位于带缩放／平移变换的画布里，
     * 用控件自身的布局坐标算出来的位置是错的。鼠标坐标本身就在 GUI 空间，
     * 宿主再换算到菜单宿主的局部坐标即可（{@code worldToLocal}）。
     */
    void openMenu(float mouseX, float mouseY, List<SimuPackMenuBar.Entry> entries);

    /** 结构性改动前先拍快照，保证撤销能回退。 */
    void pushHistory();

    /** 连续输入合并入栈。 */
    void recordEdit(String tag);

    /** 模型已写回 {@code building.jsonConfig}：刷新状态栏（校验计数、提示）。 */
    void commit();

    /** 重建各个视图（选择变化、结构变化后调用）。 */
    void refresh();

    /** 给用户一句反馈（拒绝了一次非法操作、或者做成了什么）。 */
    void notify(String message);

    /** 打开（必要时新建）中间区的某个领域页签并选中它。 */
    void openMiddleTab(MiddleTabKind kind);

    /** 打开「选取点位」辅助页签（带 3D 场景与选区状态，与领域页签分开管理）。 */
    void openPickTab();

    /** 打开某个领域专属弹窗。 */
    void openDialog(EditorDialog dialog);

    /**
     * 打开物品选择器浮层。
     *
     * <p>由会话实现而不是面板自己弹：浮层与「当前有模态打开」这个状态都归会话管
     * （{@code overlayHost} / {@code dialogOpen}），面板碰不到它们。
     *
     * @param onPick    用户点中某个物品后回调它的 id；取消则不回调
     * @param allowTags 是否在列表末尾追加 item tag。工业要（NSUK 工业收 {@code #tag}），
     *                  商业不要（商业侧完全不认标签，选了会造出一笔永不成交的交易）。
     *                  证据见 {@link ItemRegistry} 的标签那一节。
     */
    void openItemPicker(java.util.function.Consumer<String> onPick, boolean allowTags);

    /** Open the shared registry picker in block/block-tag mode. */
    void openBlockPicker(java.util.function.Consumer<String> onPick);

    /**
     * 打开选点会话，把选好的结构坐标交给回调。
     *
     * <p>选点是通用能力（见 {@code PointPicker}）—— 工业点位、商业容器、
     * 将来的住宅都走这一个入口，各自只需要提供自己的落盘方式。
     *
     * @param initial  预填坐标（重选时用；新建传空表）
     * @param onPicked 用户按「完成选取」后回调；取消则不回调
     */
    /**
     * 当前建筑是否已经有结构文件。
     *
     * <p>选点要在结构上点方块，没有结构就没有可点的东西 —— 调用方据此把按钮置灰，
     * 而不是让用户进到选点页签才发现画布是空的。
     */
    boolean hasStructure();

    /** Current structure NBT, or null when the selected building has no structure. */
    default CompoundTag structureTag() {
        return null;
    }

    void openPointPicker(java.util.List<net.minecraft.core.BlockPos> initial,
                         java.util.function.Consumer<java.util.List<net.minecraft.core.BlockPos>> onPicked);

    /** Open a picker scoped to a domain object id. */
    default void openPointPicker(java.util.List<net.minecraft.core.BlockPos> initial,
                                 String subject,
                                 java.util.function.Consumer<java.util.List<net.minecraft.core.BlockPos>> onPicked) {
        openPointPicker(initial, onPicked);
    }
}