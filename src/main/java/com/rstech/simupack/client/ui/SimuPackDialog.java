package com.rstech.simupack.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.Consumer;

/**
 * 模态弹窗：全屏遮罩 + 居中面板 + 确认/取消。
 *
 * <p>遮罩会拦截鼠标事件，因此弹窗打开时不会误触后面的界面。
 * 调用方拿到遮罩元素后，在确认/取消时用 {@code host.removeChild(overlay)} 关闭。
 */
@OnlyIn(Dist.CLIENT)
public final class SimuPackDialog {
    private static final int BUTTON_HEIGHT = 16;
    private static final float BUTTON_WIDTH = 48F;

    private SimuPackDialog() {
    }

    /**
     * 在当前容器上打开一个弹窗。
     *
     * @param host    承载遮罩的容器（通常是窗口内容区）
     * @param title   弹窗标题
     * @param body    弹窗内容
     * @param onConfirm 点击确认时的回调
     * @param onCancel  点击取消时的回调
     * @return 遮罩元素；调用方负责在回调里移除它
     */
    public static UIElement open(UIElement host, Component title, UIElement body,
                                 Consumer<UIElement> onConfirm, Runnable onCancel) {
        return open(host, title, body, onConfirm, onCancel, null);
    }

    /**
     * 打开弹窗，可选一个左下角的「附加动作」按钮。
     *
     * <p>做成<b>两段式确认</b>：第一次点击把文字换成确认文案，再点一次才真正执行。
     * 这样既避免误触，又不用再叠一层确认弹窗。
     *
     * @param onDelete 为 null 时不显示这个按钮
     */
    public static UIElement open(UIElement host, Component title, UIElement body,
                                 Consumer<UIElement> onConfirm, Runnable onCancel,
                                 Consumer<UIElement> onDelete) {
        return open(host, title, body, onConfirm, onCancel, onDelete,
                "gui.simupack.dialog.delete", "gui.simupack.dialog.delete_confirm");
    }

    /**
     * 同上，但可以指定左下角按钮的两段式文案。
     *
     * <p>默认文案是「删除 / 确认？」，只适合真正的删除动作；像「不再提示」这种
     * 非破坏性动作必须有自己的文案，否则按钮会撒谎。
     */
    public static UIElement open(UIElement host, Component title, UIElement body,
                                 Consumer<UIElement> onConfirm, Runnable onCancel,
                                 Consumer<UIElement> onDelete,
                                 String actionKey, String actionConfirmKey) {
        return open(host, title, body, onConfirm, onCancel, onDelete,
                actionKey, actionConfirmKey,
                "gui.simupack.dialog.confirm", "gui.simupack.dialog.cancel");
    }

    /**
     * 同上，并且可以覆盖右下角两个按钮的文案。
     *
     * <p><b>为什么需要它</b>：默认的「确定 / 取消」在更新类弹窗里语义不清——
     * 官方包那个"确定"到底是"现在就更新"还是"只是把这框关掉"？
     * 用户读不出这个区别，所以更新弹窗要把它写成「立即更新」「稍后」。
     *
     * <p>做成<b>向后兼容的重载</b>：默认值仍是现有那两个键，所以现有调用点
     * （重命名、删除、新建工程、旧包转换）一行都不用改。
     */
    public static UIElement open(UIElement host, Component title, UIElement body,
                                 Consumer<UIElement> onConfirm, Runnable onCancel,
                                 Consumer<UIElement> onDelete,
                                 String actionKey, String actionConfirmKey,
                                 String confirmKey, String cancelKey) {
        UIElement overlay = new UIElement().addClass("simupack_overlay");
        overlay.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(0);
            layout.top(0);
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.CENTER);
        });
        overlay.style(style -> style.zIndex(400));

        UIElement panel = new UIElement().addClass("simupack_dialog");
        panel.layout(layout -> {
            layout.widthPercent(78);
            layout.maxWidthPercent(92);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(4);
        });

        panel.addChild(SimuPackLayout.shrinkable(
                SimuPackLayout.label(title, SimuPackTheme.TEXT_PRIMARY_COLOR)));
        panel.addChild(body);

        UIElement buttons = SimuPackLayout.shrinkable(SimuPackLayout.buttonRow(4));
        buttons.layout(layout -> {
            layout.flexShrink(0);
            layout.justifyContent(AlignContent.FLEX_END);
        });
        if (onDelete != null) {
            Button deleteButton = pinButton(Component.translatable(actionKey));
            final boolean[] armed = {false};
            deleteButton.setOnClick(event -> {
                if (!armed[0]) {
                    // 第一下只进入确认态，不改任何数据。
                    armed[0] = true;
                    deleteButton.setText(Component.translatable(actionConfirmKey));
                    return;
                }
                onDelete.accept(overlay);
            });
            buttons.addChild(deleteButton);
            // 撑开中间，把这个动作留在左下角，确定/取消仍在右下角。
            UIElement spacer = new UIElement();
            spacer.layout(layout -> {
                layout.flex(1);
                layout.minWidth(0);
            });
            buttons.addChild(spacer);
        }
        // confirmKey 为 null 时**不画**确认按钮。
        //
        // 需要它的场景：物品选择器。那里点一个物品就已经完成选择了，
        // 「关闭」和「取消」是同一件事 —— 摆两个同义按钮只会让用户停下来想
        // 「这俩有什么区别」，然后猜。能省掉的歧义就不要留着。
        if (confirmKey != null) {
            buttons.addChild(pinButton(Component.translatable(confirmKey),
                    () -> onConfirm.accept(overlay)));
        }
        buttons.addChild(pinButton(Component.translatable(cancelKey), () -> {
            host.removeChild(overlay);
            onCancel.run();
        }));
        panel.addChild(buttons);

        overlay.addChild(panel);
        host.addChild(overlay);
        return overlay;
    }

    private static Button pinButton(Component text) {
        Button button = new Button().setText(text);
        SimuPackLayout.pinWidth(button, BUTTON_WIDTH);
        button.layout(layout -> layout.height(BUTTON_HEIGHT));
        return button;
    }

    private static Button pinButton(Component text, Runnable action) {
        Button button = pinButton(text);
        button.setOnClick(event -> action.run());
        return button;
    }

    /** 弹窗里的一行输入框。 */
    public static UIElement field(String labelKey, String value,
                                  Consumer<String> responder, float labelPercent) {
        UIElement row = SimuPackLayout.row(4);
        row.layout(layout -> {
            layout.widthPercent(100);
            layout.height(BUTTON_HEIGHT);
            layout.minWidth(0);
            layout.flexShrink(0);
        });
        row.addChild(SimuPackLayout.pinWidthPercent(
                SimuPackLayout.shrinkable(SimuPackLayout.label(
                        Component.translatable(labelKey), SimuPackTheme.TEXT_MUTED_COLOR)),
                labelPercent));

        var textField = new com.lowdragmc.lowdraglib2.gui.ui.elements.TextField();
        textField.setAnyString();
        textField.setText(value == null ? "" : value);
        textField.setTextResponder(responder);
        textField.layout(layout -> {
            layout.flex(1);
            layout.flexShrink(1);
            layout.height(BUTTON_HEIGHT);
            layout.minWidth(0);
        });
        row.addChild(textField);
        return row;
    }

    /**
     * id 类文本的接受集：<b>小写字母、数字、下划线</b>。
     *
     * <p>与 {@code CreationWorkspace.sanitizeId} 落盘时的清洗规则一致 —— 输入框先拦住，
     * 用户就不会先打一串中文、提交时才被悄悄改掉（那种「我打的字去哪了」最难查）。
     * 也顺手挡掉大写：id 会变成文件名与 NSUK 的索引键，大小写混用迟早出问题。
     */
    public static final String ID_REGEX = "[a-z0-9_]*";

    /**
     * 与 {@link #field} 相同，但输入被限制为 {@link #ID_REGEX}（id / 键名类字段用）。
     */
    public static UIElement idField(String labelKey, String value,
                                    Consumer<String> responder, float labelPercent) {
        UIElement row = field(labelKey, value, responder, labelPercent);
        // field() 已经建好了行，这里只需把里面那个文本框换掉限制器 ——
        // 复制一份布局代码容易和 field() 走偏，所以直接找出那个 TextField。
        for (UIElement child : row.getChildren()) {
            if (child instanceof TextField textField) {
                textField.setTextRegexValidator(ID_REGEX);
                break;
            }
        }
        return row;
    }
}
