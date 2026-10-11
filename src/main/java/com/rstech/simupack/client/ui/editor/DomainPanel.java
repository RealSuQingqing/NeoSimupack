package com.rstech.simupack.client.ui.editor;

import com.google.gson.JsonObject;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import com.rstech.simupack.creation.json.BuildingSettingsSchema;
import com.rstech.simupack.creation.json.DefinitionField;
import com.rstech.simupack.creation.json.DefinitionIssue;
import com.rstech.simupack.creation.json.JsonEditTransaction;
import com.rstech.simupack.creation.json.SettingSection;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 领域面板：在共享状态（{@link EditorPanel}）之上补两件事 ——
 * <b>分类无关的右栏设定表单</b>，以及各分类自己「多出来」的界面。
 *
 * <p>为什么通用表单放在这里而不是单独一个 {@code SettingsPanel} 类：
 * 它渲染的是 {@link BuildingSettingsSchema} 按分类给出的字段表，本身就与分类无关，
 * 而且它必须读写<b>同一个</b>上下文。放在面板基类里既少一个协作对象，
 * 也不用把 {@code model}/{@code building} 这些受保护状态暴露给外部类。
 *
 * <p>右栏的渲染顺序（与原实现一致，只是把「工业专属」两处改成了钩子）：
 * <pre>
 * 导语 → 通用字段表单 → {@link #afterDefinitionForm} → 该分类的注意事项
 *      → {@link #renderSettingsExtraInto}（工业：配方与动作组入口）
 *      → {@link #renderIssuesInto}（只有声明了校验的分类才渲染）
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public class DomainPanel extends EditorPanel {

    public DomainPanel(EditorHost host) {
        super(host);
    }

    // ── 会话用来挑面板 / 通知刷新 ──────────────────────────────────────

    /** 本面板是否服务该分类。会话靠它决定用哪个面板。 */
    public boolean supports(String category) {
        return false;
    }

    // ── 右栏：分类设定 ─────────────────────────────────────────────────

    /**
     * 渲染右栏「分类设定」。
     *
     * <p>目标是「像基本信息那样一行一个字段」，所以只是纵向堆叠，不铺三栏。
     */
    public void renderSettingsInto(UIElement container) {
        container.clearAllChildren();
        if (!prepare(container, View.SETTINGS)) {
            return;
        }

        buildDefinitionForm(container);
        renderSettingsExtraInto(container);
        if (hasValidation()) {
            container.addChild(sectionHeader("gui.simupack.industrial.validation_section"));
            renderIssuesInto(container);
        }
    }

    private void buildDefinitionForm(UIElement parent) {
        JsonObject root = model.root();
        for (SettingSection section : BuildingSettingsSchema.sectionsFor(building.category())) {
            parent.addChild(sectionHeader(section.translationKey()));
            JsonObject owner = sectionObject(root, section, false);
            for (DefinitionField field : section.fields()) {
                parent.addChild(definitionRow(section, root, owner, field));
            }
        }
        afterDefinitionForm(parent, root);
    }

    /**
     * 通用字段表单之后、分类注意事项之前的补充。
     *
     * <p>工业用它提示 {@code workArea.type} 这个「加载器会解析、运行时没人读」的死键。
     */
    protected void afterDefinitionForm(UIElement parent, JsonObject root) {
    }

    /** 取（必要时创建）分区对应的嵌套对象；根分区直接返回根。 */
    private static JsonObject sectionObject(JsonObject root,
                                            SettingSection section,
                                            boolean create) {
        if (section.objectKey().isEmpty()) {
            return root;
        }
        if (root.has(section.objectKey()) && root.get(section.objectKey()).isJsonObject()) {
            return root.getAsJsonObject(section.objectKey());
        }
        if (!create) {
            return null;
        }
        JsonObject created = new JsonObject();
        root.add(section.objectKey(), created);
        return created;
    }

    /**
     * 分类设定的一行：只支持文本框与开关。
     *
     * <p>写入时若分区对象还不存在就新建——这样「改了一个值」才会真正落下 {@code spawnEntity} /
     * {@code workArea}，而不是把键平铺到顶层。
     */
    private UIElement definitionRow(SettingSection section, JsonObject root,
                                    JsonObject owner, DefinitionField field) {
        String labelKey = section.labelKey(field);
        if (field.kind() == DefinitionField.Kind.FLAG) {
            UIElement row = labelRow(labelKey);
            boolean current = owner != null && BuildingJsonDocument.readFlag(owner, field);
            Button button = textButton(flagText(current), 0F, null);
            button.layout(layout -> {
                layout.flex(1);
                layout.height(EditorWidgets.ROW_HEIGHT);
                layout.minWidth(0);
            });
            button.setOnClick(event -> {
                JsonObject existing = sectionObject(root, section, false);
                boolean next = !(existing == null ? Boolean.parseBoolean(field.fallback())
                        : BuildingJsonDocument.readFlag(existing, field));
                edit("def." + section.id() + "." + field.key(), root, draft ->
                        BuildingJsonDocument.writeFlag(sectionObject(draft, section, true), field, next));
                button.setText(Component.literal(flagText(next)));
            });
            if (readOnly) {
                button.setActive(false);
            }
            row.addChild(button);
            return row;
        }
        String value = owner != null && BuildingJsonDocument.hasField(owner, field)
                ? BuildingJsonDocument.readString(owner, field) : "";
        return textRow(labelKey, value, input -> {
            edit("def." + section.id() + "." + field.key(), root, draft -> {
                JsonObject target = sectionObject(draft, section, false);
                if (target == null && (input == null || input.isBlank())) return;
                JsonEditTransaction.writeScalar(
                        sectionObject(draft, section, true), field, input);
            });
        });
    }

    /** 布尔字段在设定表单里的显示（与原实现一致：☑/☐ + 开/关）。 */
    private static String flagText(boolean value) {
        return (value ? "☑ " : "☐ ") + Component.translatable(value
                ? "gui.simupack.industrial.on" : "gui.simupack.industrial.off").getString();
    }

    // ── 右栏：静态校验 ─────────────────────────────────────────────────
    //
    // 校验是按分类的，而且由各分类面板<b>自己实现</b>，基类这里一律返回"没有校验"：
    //   · 工业（{@link IndustrialActionPanel}）→ 校验 recipes / steps；
    //   · 商业（{@link CommercialTradePanel}）→ 校验 offers 与 containers。
    // 之所以不给基类一份默认实现，是因为工业那套形状的 validate() 用在公共（医疗）建筑上
    // 会误报 missing_recipes —— 医疗根本没有配方这个概念。
    // 所以「没有校验」是默认值，不是"还没做"。

    /** 该分类是否有可显示的静态校验。 */
    public boolean hasValidation() {
        return false;
    }

    /**
     * 校验问题码的语言键命名空间（工业 {@code industrial}、商业 {@code commercial}）。
     *
     * <p>问题码本身是各分类自己的（工业 {@code recipes/steps}、商业 {@code offers}），
     * 所以文案也必须跟着分类走。
     */
    protected String issueNamespace() {
        return "industrial";
    }

    /** 静态校验结果（没有校验的分类返回空列表）。 */
    public List<DefinitionIssue> validateForDisplay() {
        return List.of();
    }

    /** 这条问题能不能定位到具体位置（决定要不要画「定位」按钮）。 */
    public boolean canLocateIssue(DefinitionIssue issue) {
        return false;
    }

    /** 把一条校验问题定位到具体位置；默认什么都不做。 */
    public void locateIssue(DefinitionIssue issue) {
    }

    private void renderIssuesInto(UIElement parent) {
        List<DefinitionIssue> issues = validateForDisplay();
        if (issues.isEmpty()) {
            parent.addChild(SimuPackLayout.wrappedLabel(
                    Component.translatable("gui.simupack.industrial.validation_ok"),
                    SimuPackTheme.TEXT_SUCCESS_COLOR));
            return;
        }
        parent.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable("gui.simupack.industrial.issues", issues.size()),
                SimuPackTheme.TEXT_WARNING_COLOR));
        for (DefinitionIssue issue : issues) {
            UIElement row = new UIElement().addClass("simupack_iissue");
            row.layout(layout -> {
                layout.widthPercent(100);
                layout.minWidth(0);
                layout.flexShrink(0);
                layout.flexDirection(FlexDirection.ROW);
                layout.alignItems(AlignItems.CENTER);
                layout.gapAll(3);
            });
            row.addChild(SimuPackLayout.pinnedLabel(Component.literal(severityMark(issue.severity())),
                    severityColor(issue.severity())));
            Component text = issue.detail().isBlank()
                    ? Component.translatable(issue.translationKey(issueNamespace()))
                    : Component.translatable(issue.translationKey(issueNamespace()), issue.detail());
            row.addChild(SimuPackLayout.flexWrappedLabel(
                    Component.literal(issue.location() + "  ").append(text),
                    severityColor(issue.severity())));
            if (canLocateIssue(issue)) {
                row.addChild(textButton(
                        Component.translatable("gui.simupack.industrial.locate_issue").getString(),
                        0F, () -> locateIssue(issue)));
            }
            parent.addChild(row);
        }
    }

    protected static String severityMark(DefinitionIssue.Severity severity) {
        return switch (severity) {
            case ERROR -> "✕";
            case WARNING -> "⚠";
            case INFO -> "ℹ";
        };
    }

    protected static int severityColor(DefinitionIssue.Severity severity) {
        return switch (severity) {
            case ERROR -> SimuPackTheme.TEXT_ERROR_COLOR;
            case WARNING -> SimuPackTheme.TEXT_WARNING_COLOR;
            case INFO -> SimuPackTheme.TEXT_MUTED_COLOR;
        };
    }

    // ── 领域内容 ───────────────────────────────────────────────────────

    /**
     * 渲染中间区页签的内容。
     *
     * <p>实现里必须先调 {@link #prepare(UIElement, View)} 做分类与解析检查。
     */
    public void renderMiddleInto(UIElement container) {
    }

    /**
     * 渲染右栏分类设定里属于本领域的补充小节（工业：配方与动作组入口）。
     *
     * <p>由 {@link #renderSettingsInto} 在通用字段表单之后调用。
     */
    public void renderSettingsExtraInto(UIElement container) {
    }
}