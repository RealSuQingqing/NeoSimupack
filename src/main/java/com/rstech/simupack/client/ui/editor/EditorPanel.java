package com.rstech.simupack.client.ui.editor;

import com.google.gson.JsonObject;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackMenuBar;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.creation.CreationBuilding;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import com.rstech.simupack.creation.json.BuildingSettingsSchema;
import com.rstech.simupack.creation.json.JsonEditTransaction;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/**
 * 编辑器面板的共享状态与写回管线。
 *
 * <p><b>存在理由（一条不变量）：</b>右栏「分类设定」和中间区的领域页签必须改<b>同一棵
 * {@code JsonObject}</b>。重构前这件事是靠「{@code IndustrialEditor} 单实例同时持有模型」
 * 隐式保证的；拆成多个面板之后如果各自 {@code parse} 一份，就会退化成
 * 「各持快照、后保存的覆盖先保存的」——那是会把已有功能改坏的退化。
 *
 * <p>所以这里把状态与管线收在一个基类里，<b>面板继承它而不是持有它</b>：
 * <ul>
 *   <li>「一个面板 = 一个上下文 = 一棵 JSON」由类型结构直接保证，不靠约定；</li>
 *   <li>会话同一时刻只持有一个面板（换分类时替换），因此不会有第二份状态存活；</li>
 *   <li>好处之三纯属工程上的：{@code model} / {@code building} / {@code host} 这些
 *       字段名在子类里<b>原样可解析</b>，于是从 {@code IndustrialEditor} 搬过来的
 *       约 360 处字段引用<b>一行都不用改</b>。这是选择继承而非组合的主要动机。</li>
 * </ul>
 *
 * <p>与具体分类相关的行为通过下面几个钩子外置，基类保持分类无关：
 * {@link #resetViewState()}、{@link #afterModelParsed(List)}、
 * {@link #editScope()}、{@link #afterEdit(String, JsonObject)}。
 */
@OnlyIn(Dist.CLIENT)
public abstract class EditorPanel {

    /**
     * 「前置检查」要渲染哪个视图。
     *
     * <p>重构前这里是一个 {@code boolean forSettings}，表达不了三个视图，
     * 于是「动作组只有工业有」这个判断被写成
     * {@code if (!"industry".equals(category)) { 报错; return false; }} ——
     * 商业交易页签会被它直接挡在门外（连注释都点名了「商业的报价」）。
     * 改成枚举后每个视图各自守自己的分类，互不影响。
     */
    public enum View {
        /** 右栏分类设定：任何有 per-building JSON 定义的分类都可用。 */
        SETTINGS,
        /** 中间区动作组：只有工业。 */
        STEPS,
        /** 中间区商业交易：只有商业。 */
        OFFERS
    }

    protected final EditorHost host;
    /** 通用控件工厂。 */
    protected final EditorWidgets widgets;

    // ── 当前建筑与解析状态 ─────────────────────────────────────────────
    protected CreationBuilding building;
    protected boolean readOnly;
    protected BuildingJsonDocument.StructureBounds bounds = BuildingJsonDocument.StructureBounds.UNKNOWN;
    protected BuildingJsonDocument model;
    /** 上一次解析（或写回）时的 JSON 文本，用来检测 jsonConfig 被别处改过（撤销/重做、换来源）。 */
    protected String parsedText = "";
    protected String parseError = "";

    protected EditorPanel(EditorHost host) {
        this.host = host;
        this.widgets = new EditorWidgets(new EditorWidgets.Host() {
            @Override
            public boolean readOnly() {
                return EditorPanel.this.readOnly;
            }

            @Override
            public void openMenu(float mouseX, float mouseY, List<SimuPackMenuBar.Entry> entries) {
                EditorPanel.this.host.openMenu(mouseX, mouseY, entries);
            }
        });
    }

    // ── 给协作对象（非子类）的只读访问 ─────────────────────────────────
    //
    // 字段本身是 protected，好让子类里的既有代码原样编译；这里的访问器是给
    // 同包协作对象用的，避免它们去读受保护字段。
    // 方法名刻意不与字段同名（document/target），免得读代码时分不清是哪个。

    /** 当前建筑。 */
    public CreationBuilding target() {
        return building;
    }

    /** 当前建筑的功能 JSON 文档模型；没解析成功时为 null。 */
    public BuildingJsonDocument document() {
        return model;
    }

    /** 当前建筑是否来自只读来源（拓展包内的定义）。 */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** 通用控件工厂。 */
    public EditorWidgets widgets() {
        return widgets;
    }

    /** 解析失败的原文；没有失败时是空串。 */
    public String parseFailure() {
        return parseError;
    }

    // ── 同步 ───────────────────────────────────────────────────────────

    /**
     * 与当前建筑对齐。
     *
     * <p>只在「换了建筑」或「JSON 文本被别处改过」时重新解析：文本是唯一真相源，
     * 但也不能每次刷新都解析，否则用户当前的选择会被清掉。
     */
    public void sync(CreationBuilding target, boolean readOnlySource,
                     BuildingJsonDocument.StructureBounds structureBounds) {
        this.readOnly = readOnlySource;
        this.bounds = structureBounds == null
                ? BuildingJsonDocument.StructureBounds.UNKNOWN : structureBounds;
        String text = target == null ? "" : target.jsonConfig();
        boolean switched = target != building;
        this.building = target;
        if (switched || !text.equals(parsedText)) {
            reload(text);
        }
    }

    private void reload(String text) {
        parseError = "";
        model = null;
        resetViewState();
        parsedText = text == null ? "" : text;
        if (building == null) {
            return;
        }
        try {
            model = BuildingJsonDocument.parse(parsedText);
        } catch (IllegalArgumentException exception) {
            parseError = exception.getMessage();
            return;
        }
        afterModelParsed(model.recipes());
    }

    /**
     * 模型重建前清掉界面状态。
     *
     * <p>模型重建后旧的步骤对象全部失效，凡按对象身份记录的界面状态（例如展开态、
     * 已选步骤）必须一起清掉 —— 否则会指向已经不存在的对象。
     */
    protected void resetViewState() {
    }

    /** 模型解析成功之后补齐界面状态（例如把已有动作的配方视为已建链）。 */
    protected void afterModelParsed(List<JsonObject> recipes) {
    }

    // ── 写回 ───────────────────────────────────────────────────────────

    /**
     * 把模型写回建筑；不触发本页签重建（由调用方决定）。
     *
     * <p><b>只读来源直接返回</b>：已安装拓展包的建筑是包内 {@code .sk}/{@code .json}
     * 临时解析出来的对象，不是工程里的数据。写回虽然不会立刻落盘（只读时 {@code project}
     * 为空），但会让「内存里的包内容」与 zip 里的真实内容不一致——一旦之后有别的路径
     * 拿它去写盘或做校验，就会写出作者从没写过的值。所有写入口本来就都过了
     * {@link #editable()}，这里再挡一道是因为写入口太多（工业面板里就有十几处
     * {@code persist()}），漏一处就是静默污染官方包的数据。
     */
    protected void persist() {
        if (readOnly || model == null || building == null) {
            return;
        }
        parsedText = model.toJsonString();
        building.setJsonConfig(parsedText);
    }

    /**
     * 标量编辑：入撤销栈（可合并）→ 写回 → 刷新其它页签；不重建本页签。
     *
     * @param tag 用于「连续输入合并入栈」的标识
     */
    protected boolean edit(String tag, JsonObject target, Consumer<JsonObject> mutation) {
        if (!editable()) return false;
        String scope = editScope() + ":" + System.identityHashCode(target) + ":" + tag;
        boolean changed = JsonEditTransaction.apply(target, mutation,
                () -> host.recordEdit(scope), this::persist, host::commit);
        if (changed) {
            afterEdit(tag, target);
        }
        return changed;
    }

    /** 编辑合并键的前缀（工业用当前配方 id，让不同配方的同名编辑不被合并）。 */
    protected String editScope() {
        return "";
    }

    /** 编辑成功之后的视图刷新钩子（工业用来重跑「就地可读」的句子与提示）。 */
    protected void afterEdit(String tag, JsonObject target) {
    }

    /** 结构性编辑：写回、刷新其它页签，并重建本页签。 */
    protected void restructured() {
        persist();
        host.commit();
        host.refresh();
    }

    protected boolean editable() {
        return !readOnly && model != null;
    }

    // ── 前置检查 ───────────────────────────────────────────────────────

    /**
     * 渲染前的统一检查：建筑存在、分类与视图匹配、JSON 能解析。
     *
     * @return true 表示可以继续渲染
     */
    public boolean prepare(UIElement container, View view) {
        if (building == null) {
            container.addChild(wrapped("gui.simupack.editor.no_building", SimuPackTheme.TEXT_MUTED_COLOR));
            return false;
        }
        String category = building.category();
        switch (view) {
            case SETTINGS -> {
                // 分类设定是「一个页签位服务所有分类」：NSUK 只为工业/商业/公共提供了
                // per-building JSON 定义，住宅和「其他」没有——这时如实说明，不凭空造字段。
                if (!BuildingSettingsSchema.hasJsonDefinition(category) && !"residential".equals(category)) {
                    container.addChild(SimuPackLayout.wrappedLabel(
                            Component.translatable("gui.simupack.settings.no_json",
                                    categoryLabel(category)),
                            SimuPackTheme.TEXT_WARNING_COLOR));
                    return false;
                }
            }
            case STEPS -> {
                // 动作组只有工业有（商业的报价、医疗的服务范围都不是「步骤链」）。
                if (!"industry".equals(category)) {
                    container.addChild(SimuPackLayout.wrappedLabel(
                            Component.translatable("gui.simupack.industrial.not_industry",
                                    categoryLabel(category)),
                            SimuPackTheme.TEXT_WARNING_COLOR));
                    return false;
                }
            }
            case OFFERS -> {
                // 交易与容器都只有商业有。
                if (!"commercial".equals(category)) {
                    container.addChild(SimuPackLayout.wrappedLabel(
                            Component.translatable("gui.simupack.commercial.not_commercial",
                                    categoryLabel(category)),
                            SimuPackTheme.TEXT_WARNING_COLOR));
                    return false;
                }
            }
        }
        if (!parseError.isEmpty()) {
            container.addChild(wrapped("gui.simupack.industrial.json_invalid",
                    SimuPackTheme.TEXT_ERROR_COLOR));
            container.addChild(SimuPackLayout.wrappedLabel(
                    Component.literal(parseError), SimuPackTheme.TEXT_MUTED_COLOR));
            container.addChild(wrapped("gui.simupack.industrial.json_invalid_hint",
                    SimuPackTheme.TEXT_MUTED_COLOR));
            // 原始 JSON 文本框已经取消，所以语法坏掉时界面里没有别的地方能改。
            // 至少给一条出路：清空重建（可撤销）。
            Button reset = textButton(Component.translatable(
                    "gui.simupack.industrial.json_reset").getString(), 0F, this::resetConfig);
            reset.layout(layout -> layout.height(EditorWidgets.ROW_HEIGHT));
            container.addChild(reset);
            return false;
        }
        return model != null;
    }

    /** 清空整份功能 JSON，回到空骨架。有撤销栈兜底，所以是安全操作。 */
    private void resetConfig() {
        if (building == null) {
            return;
        }
        host.pushHistory();
        building.setJsonConfig("");
        reload("");
        persist();
        host.commit();
        host.refresh();
    }

    /** 分类的显示名（与左栏树、基本信息里的写法一致）。 */
    protected static String categoryLabel(String category) {
        return Component.translatable("gui.simupack.category." + category).getString();
    }

    // ── 控件转发 ───────────────────────────────────────────────────────
    //
    // 子类里的既有代码直接写 textButton(...) / wrapped(...) 等；这些转发让那些调用点
    // 一个字都不用改。实现都在 EditorWidgets（商业/公共面板也能直接用）。

    protected UIElement labelRow(String labelKey) {
        return widgets.labelRow(labelKey);
    }

    protected UIElement textRow(String labelKey, String value, Consumer<String> responder) {
        return widgets.textRow(labelKey, value, responder);
    }

    protected Button textButton(String text, float width, Runnable action) {
        return widgets.textButton(text, width, action);
    }

    protected Button textButton(String text, float width, Runnable action, boolean enabled) {
        return widgets.textButton(text, width, action, enabled);
    }

    protected UIElement wrapped(String key, int color) {
        return widgets.wrapped(key, color);
    }

    protected UIElement sectionHeader(String translationKey) {
        return widgets.sectionHeader(translationKey);
    }

    protected TextField textField() {
        return widgets.textField();
    }

    protected void installDropdown(Button button, java.util.function.Supplier<List<SimuPackMenuBar.Entry>> supplier) {
        widgets.installDropdown(button, supplier);
    }
}
