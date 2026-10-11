/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.client.ui.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.RectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.DragHandler;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.mojang.blaze3d.platform.Window;
import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackMenuBar;
import com.rstech.simupack.client.ui.editor.CanvasZoomMath;
import com.rstech.simupack.client.ui.editor.DomainPanel;
import com.rstech.simupack.client.ui.editor.EditorDialog;
import com.rstech.simupack.client.ui.editor.EditorHost;
import com.rstech.simupack.client.ui.editor.EditorPanel;
import com.rstech.simupack.client.ui.editor.EditorWidgets;
import com.rstech.simupack.client.ui.editor.MiddleTabKind;
import com.rstech.simupack.creation.industrial.IndustrialSentence;
import com.rstech.simupack.creation.industrial.IndustrialStepCategory;
import com.rstech.simupack.creation.industrial.IndustrialStepType;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import com.rstech.simupack.creation.json.DefinitionField;
import com.rstech.simupack.creation.json.DefinitionIssue;
import com.rstech.simupack.creation.json.IndustrialDefinitionFields;
import com.rstech.simupack.creation.json.JsonEditTransaction;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.lang.invoke.CallSite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.appliedenergistics.yoga.YogaOverflow;
import org.joml.Vector2f;
import org.joml.Vector2fc;

@OnlyIn(value=Dist.CLIENT)
public final class IndustrialActionPanel
extends DomainPanel {
    private static final float TOOLBAR_HEIGHT = 16.0f;
    private static final float CATEGORY_ROW_HEIGHT = 18.0f;
    private static final float ROW_HEIGHT = 14.0f;
    private static final float SMALL_BUTTON = 14.0f;
    private static final int ACTIVE_CATEGORY_COLOR = -13660337;
    private static final int POINT_CHIP_COLOR = -11904406;
    private static final float ZOOM_MIN = 0.02f;
    private static final float ZOOM_MAX = 50.0f;
    private static final float DROP_SLOT_HEIGHT = 5.0f;
    private static final float EMPTY_DROP_SLOT_HEIGHT = 22.0f;
    private static final float DRAG_THRESHOLD_SQ = 9.0f;
    private static final float CHAIN_WIDTH = 330.0f;
    private static final float CANVAS_HEIGHT = 6000.0f;
    private static final float BLOCK_ESTIMATED_HEIGHT = 44.0f;
    private static final float READ_ONLY_BLOCK_ESTIMATED_HEIGHT = 86.0f;
    private static final float CHAIN_GAP = 18.0f;
    private static final float READ_ONLY_CHAIN_GAP = 42.0f;
    private static final float CHAIN_COLUMN_MAX = 900.0f;
    private static final float CHAIN_AUTO_GAP = 300.0f;
    private static boolean paletteCollapsed;
    private static IndustrialStepCategory paletteCategory;
    private static float blockZoom;
    private static float canvasOffsetX;
    private static float canvasOffsetY;
    private UIElement contentRoot;
    private UIElement canvasHost;
    private final Map<JsonObject, List<Runnable>> liveHints = new IdentityHashMap<JsonObject, List<Runnable>>();
    private final Map<JsonObject, Runnable> positionSentences = new IdentityHashMap<JsonObject, Runnable>();
    private final Map<JsonObject, String> focusAfterRefresh = new IdentityHashMap<JsonObject, String>();
    private final Set<JsonObject> expandedOptions = Collections.newSetFromMap(new IdentityHashMap());
    private final Set<JsonObject> pointModeSteps = Collections.newSetFromMap(new IdentityHashMap());
    private final List<String> chainOrder = new ArrayList<String>();
    private final Map<String, float[]> chainPositions = new LinkedHashMap<String, float[]>();
    private Vector2f lastAnchor;
    private float lastAnchorExpectedX;
    private float lastAnchorExpectedY;
    private boolean focusIssue;
    private static final DefinitionField ITEM_FIELD;
    private DragState pendingDrag;
    private DragState lastDrag;
    private boolean pendingFitZoom;
    private ChainMove pendingChainMove;
    private String recipeId = "";
    private BuildingJsonDocument.StepPath selectedStep;
    private BuildingJsonDocument.StepPath insertParent;
    private int insertIndex = Integer.MAX_VALUE;
    private static final String FIELD_CLASS_PREFIX = "simupack_ifield_";

    public IndustrialActionPanel(EditorHost host) {
        super(host);
    }

    private static float[] liveMouse() {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        double scaleX = (double)window.getGuiScaledWidth() / (double)Math.max(1, window.getScreenWidth());
        double scaleY = (double)window.getGuiScaledHeight() / (double)Math.max(1, window.getScreenHeight());
        return new float[]{(float)(minecraft.mouseHandler.xpos() * scaleX), (float)(minecraft.mouseHandler.ypos() * scaleY)};
    }

    public void requestFitView() {
        this.pendingFitZoom = true;
    }

    @Override
    public void renderMiddleInto(UIElement container) {
        this.liveHints.clear();
        this.positionSentences.clear();
        this.focusAfterRefresh.clear();
        container.clearAllChildren();
        if (!this.prepare(container, EditorPanel.View.STEPS)) {
            return;
        }
        container.addChild(this.toolbar());
        UIElement stage = new UIElement();
        stage.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.flex(1.0f);
            layout.flexShrink(1.0f);
            layout.minWidth(0.0f);
            layout.minHeight(0.0f);
            layout.positionType(TaffyPosition.RELATIVE);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        });
        this.canvasHost = new UIElement().addClass("simupack_icanvas_host");
        this.canvasHost.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.flex(1.0f);
            layout.flexShrink(1.0f);
            layout.minWidth(0.0f);
            layout.minHeight(0.0f);
            layout.positionType(TaffyPosition.RELATIVE);
            layout.overflow(YogaOverflow.HIDDEN);
        });
        this.contentRoot = SimuPackLayout.column(2);
        this.contentRoot.addClass("simupack_icanvas");
        this.contentRoot.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.minHeight(0.0f);
        });
        this.contentRoot.addChild(this.canvasContent());
        this.canvasHost.addChild(this.contentRoot);
        this.refreshContentTransform();
        UIElement palette = null;
        if (paletteCollapsed) {
            stage.addChild(this.collapsedPaletteBar());
        } else {
            palette = this.paletteStrip();
            stage.addChild(palette);
        }
        stage.addChild(this.canvasHost);
        this.installCanvasNavigation(stage, palette, this.canvasHost);
        container.addChild(stage);
        this.restoreFocusAfterRefresh(container);
    }

    private void restoreFocusAfterRefresh(UIElement container) {
        String field = this.focusAfterRefresh.remove(this.selectedStep);
        if (field == null || field.isBlank()) {
            return;
        }
        TextField target = IndustrialActionPanel.findField(container, FIELD_CLASS_PREFIX + field);
        if (target == null) {
            return;
        }
        try {
            target.focus();
            String text = target.getText();
            target.setSelection(0, text == null ? 0 : text.length());
        }
        catch (RuntimeException exception) {
            SimuPackMod.LOGGER.debug("\u6062\u590d\u8f93\u5165\u6846\u7126\u70b9\u5931\u8d25\uff1a{}", (Object)field, (Object)exception);
        }
    }

    private static TextField findField(UIElement element, String className) {
        for (UIElement child : element.getChildren()) {
            TextField nested;
            if (child instanceof TextField) {
                TextField text = (TextField)child;
                if (child.hasClass(className)) {
                    return text;
                }
            }
            if ((nested = IndustrialActionPanel.findField(child, className)) == null) continue;
            return nested;
        }
        return null;
    }

    private UIElement collapsedPaletteBar() {
        UIElement bar = new UIElement().addClass("simupack_ipalette_bar");
        bar.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(3.0f);
        });
        bar.addChild((UIElement)this.textButton("\u25be", 16.0f, () -> {
            paletteCollapsed = false;
            this.host.refresh();
        }));
        bar.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.industrial.palette_collapsed"), -4342339));
        return bar;
    }

    private void refreshContentTransform() {
        if (this.contentRoot == null) {
            return;
        }
        this.contentRoot.transform(transform -> transform.setIdentity().pivot(0.0f, 0.0f).scale(blockZoom).translate(canvasOffsetX, canvasOffsetY));
    }

    private void zoomAtPointer(float mouseX, float mouseY, float next) {
        float anchorX = 0.0f;
        float anchorY = 0.0f;
        if (this.contentRoot != null) {
            Vector2f pointer = this.canvasHost.getLocalMouse(mouseX, mouseY);
            anchorX = pointer.x - this.contentRoot.getPositionX();
            anchorY = pointer.y - this.contentRoot.getPositionY();
            this.checkLastAnchor();
        }
        canvasOffsetX = CanvasZoomMath.anchoredOffset(anchorX, canvasOffsetX, blockZoom, next);
        canvasOffsetY = CanvasZoomMath.anchoredOffset(anchorY, canvasOffsetY, blockZoom, next);
        blockZoom = next;
        this.refreshContentTransform();
        if (this.contentRoot != null) {
            this.rememberAnchor(anchorX, anchorY, mouseX, mouseY);
        }
    }

    private void checkLastAnchor() {
        if (this.lastAnchor == null || this.contentRoot == null) {
            return;
        }
        Vector2f actual = this.contentRoot.localToWorld(new Vector2f((Vector2fc)this.lastAnchor));
        SimuPackMod.LOGGER.info("[zoom] expected=({}, {}) actual=({}, {}) residual=({}, {})", new Object[]{Float.valueOf(this.lastAnchorExpectedX), Float.valueOf(this.lastAnchorExpectedY), Float.valueOf(actual.x), Float.valueOf(actual.y), Float.valueOf(actual.x - this.lastAnchorExpectedX), Float.valueOf(actual.y - this.lastAnchorExpectedY)});
    }

    private void rememberAnchor(float localX, float localY, float screenX, float screenY) {
        this.lastAnchor = this.contentRoot.worldToLocal(new Vector2f(screenX, screenY));
        this.lastAnchorExpectedX = screenX;
        this.lastAnchorExpectedY = screenY;
    }

    private void installCanvasNavigation(UIElement stage, UIElement palette, UIElement canvasHost) {
        stage.addEventListener("dragEnter", event -> {
            if (IndustrialActionPanel.pendingStartDrop(event)) {
                stage.addClass("simupack_idrop_hot");
            }
        });
        stage.addEventListener("dragLeave", event -> stage.removeClass("simupack_idrop_hot"));
        stage.addEventListener("dragPerform", event -> {
            stage.removeClass("simupack_idrop_hot");
            if (!IndustrialActionPanel.pendingStartDrop(event) || !this.editable()) {
                return;
            }
            if (palette != null && event.target != null && palette.isAncestorOf(event.target)) {
                return;
            }
            this.addChain(null, this.avoidOverlap(this.mouseInCanvas(), null));
            event.stopPropagation();
        });
        float[] origin = new float[2];
        float[] startOffset = new float[2];
        boolean[] panning = new boolean[]{false};
        stage.addEventListener("mouseWheel", event -> {
            if (palette != null && event.target != null && palette.isAncestorOf(event.target)) {
                return;
            }
            float factor = event.deltaY > 0.0f ? 0.9f : 1.1f;
            float next = Math.max(0.02f, Math.min(50.0f, blockZoom * factor));
            if (Math.abs(next - blockZoom) < 0.001f) {
                return;
            }
            this.zoomAtPointer(IndustrialActionPanel.liveMouse()[0], IndustrialActionPanel.liveMouse()[1], next);
            event.stopPropagation();
        }, true);
        stage.addEventListener("mouseDown", event -> {
            if (event.button != 0 || IndustrialActionPanel.isInsideBlock(event.target) || palette != null && event.target != null && palette.isAncestorOf(event.target)) {
                return;
            }
            panning[0] = true;
            origin[0] = event.x;
            origin[1] = event.y;
            startOffset[0] = canvasOffsetX;
            startOffset[1] = canvasOffsetY;
        });
        stage.addEventListener("mouseMove", event -> {
            this.advancePendingDrag(event);
            this.advanceChainMove(event);
            if (!panning[0]) {
                return;
            }
            canvasOffsetX = startOffset[0] + (event.x - origin[0]);
            canvasOffsetY = startOffset[1] + (event.y - origin[1]);
            this.refreshContentTransform();
            event.stopPropagation();
        });
        canvasHost.addEventListener("layoutChanged", event -> {
            if (this.pendingFitZoom) {
                this.pendingFitZoom = false;
                this.fitChainsToView();
            }
        });
        Minecraft.getInstance().tell(() -> {
            if (canvasHost != this.canvasHost) {
                return;
            }
            if (this.pendingFitZoom && canvasHost.getContentWidth() > 40.0f && canvasHost.getContentHeight() > 40.0f) {
                this.pendingFitZoom = false;
                this.fitChainsToView();
            }
        });
        stage.addEventListener("mouseUp", event -> {
            panning[0] = false;
            this.finishChainMove();
            if (this.pendingDrag != null) {
                DragState state = this.pendingDrag;
                this.pendingDrag = null;
                if (state.started) {
                    state.dragged[0] = true;
                }
            }
        });
        stage.addEventListener("mouseLeave", event -> {
            panning[0] = false;
        });
    }

    private static boolean isInsideBlock(UIElement target) {
        for (UIElement element = target; element != null; element = element.getParent()) {
            if (!element.hasClass("simupack_iblock") && !element.hasClass("simupack_iblock_selected") && !element.hasClass("simupack_islot")) continue;
            return true;
        }
        return false;
    }

    @Override
    protected void afterDefinitionForm(UIElement parent, JsonObject root) {
        if (!root.has("workArea") || !root.get("workArea").isJsonObject()) {
            return;
        }
        JsonObject workArea = root.getAsJsonObject("workArea");
        for (String key : IndustrialDefinitionFields.UNUSED_WORK_AREA_KEYS) {
            if (!workArea.has(key)) continue;
            parent.addChild(this.textRow("gui.simupack.settings.field.work_area." + key, IndustrialActionPanel.string(workArea, key), ignored -> {}));
        }
    }

    @Override
    public void renderSettingsExtraInto(UIElement parent) {
        if (!"industry".equals(this.building.category())) {
            return;
        }
        parent.addChild(this.sectionHeader("gui.simupack.industrial.recipes"));
        UIElement actionsRow = SimuPackLayout.row(2);
        actionsRow.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.action_group").getString(), 0.0f, () -> this.host.openMiddleTab(MiddleTabKind.INDUSTRIAL_ACTION)));
        actionsRow.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.simulation").getString(), 0.0f, () -> this.host.openMiddleTab(MiddleTabKind.INDUSTRIAL_SIMULATION)));
        actionsRow.addChild(SimuPackLayout.spacer(1.0f));
        parent.addChild(actionsRow);
        List<JsonObject> recipes = this.model.recipes();
        if (recipes.isEmpty()) {
            parent.addChild(this.wrapped("gui.simupack.industrial.recipes_empty", -4342339));
        }
        for (int i = 0; i < recipes.size(); ++i) {
            JsonObject recipe = recipes.get(i);
            String id = IndustrialActionPanel.string(recipe, "id");
            if (id.isBlank()) continue;
            boolean current = id.equals(this.recipeId);
            UIElement row = SimuPackLayout.row(2);
            row.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.height(14.0f);
                layout.minWidth(0.0f);
                layout.flexShrink(0.0f);
            });
            TextField name = this.textField();
            name.setAnyString();
            name.setText(IndustrialActionPanel.string(recipe, "name"), false);
            name.setTextResponder(value -> this.edit("recipe.name", recipe, target -> IndustrialActionPanel.setOrRemove(target, "name", value)));
            name.layout(layout -> {
                layout.flex(1.0f);
                layout.height(14.0f);
                layout.minWidth(0.0f);
            });
            row.addChild((UIElement)name);
            row.addChild((UIElement)this.textButton("\u2715", 14.0f, () -> this.deleteRecipe(id), this.editable()));
            parent.addChild(row);
            parent.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)("id: " + id)), -4342339));
            if (!current) continue;
            this.buildRecipeForm(parent);
        }
        UIElement actions = SimuPackLayout.row(2);
        actions.addChild((UIElement)this.textButton("\uff0b " + Component.translatable((String)"gui.simupack.industrial.new_recipe").getString(), 0.0f, this::newRecipe, this.editable()));
        actions.addChild((UIElement)this.textButton("\u29c9 " + Component.translatable((String)"gui.simupack.industrial.duplicate_recipe").getString(), 0.0f, this::duplicateRecipe, this.editable() && this.model.recipe(this.recipeId) != null));
        parent.addChild(actions);
    }

    public void selectRecipe(String id) {
        if (this.model == null || this.model.recipe(id) == null) {
            return;
        }
        this.recipeId = id;
        this.model.selectRecipe(id);
        this.selectedStep = null;
        this.insertParent = null;
        this.insertIndex = Integer.MAX_VALUE;
    }

    public String recipeId() {
        return this.recipeId;
    }

    private UIElement toolbar() {
        int issueCount;
        UIElement row = SimuPackLayout.row(3);
        row.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.height(16.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
        });
        JsonObject recipe = this.model.recipe(this.recipeId);
        row.addChild(SimuPackLayout.spacer(1.0f));
        if (recipe != null) {
            int expanded = this.model.expandedStepCount(this.recipeId);
            boolean over = expanded >= 256;
            row.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.industrial.expanded_steps", (Object[])new Object[]{expanded}), over ? -34953 : -4342339));
        }
        row.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.literal((String)((issueCount = this.model.validate(this.bounds).size()) == 0 ? "\u2713" : "\u26a0 " + issueCount)), issueCount == 0 ? -10027162 : -171));
        return row;
    }

    private void newRecipe() {
        if (!this.editable()) {
            return;
        }
        this.host.pushHistory();
        JsonObject created = this.model.addRecipe("recipe");
        String createdId = IndustrialActionPanel.string(created, "id");
        this.chainOrder.add(createdId);
        this.writeLayout();
        this.selectRecipe(createdId);
        this.restructured();
    }

    private void duplicateRecipe() {
        if (!this.editable() || this.model.recipe(this.recipeId) == null) {
            return;
        }
        this.host.pushHistory();
        JsonObject copy = this.model.duplicateRecipe(this.recipeId);
        if (copy != null) {
            this.chainOrder.add(IndustrialActionPanel.string(copy, "id"));
            this.writeLayout();
            this.selectRecipe(this.model.selectedRecipeId());
        }
        this.restructured();
    }

    private void deleteRecipe() {
        this.deleteRecipe(this.recipeId);
    }

    private void deleteRecipe(String id) {
        if (!this.editable() || this.model.recipe(id) == null) {
            return;
        }
        this.host.pushHistory();
        this.model.removeRecipe(id);
        this.chainOrder.remove(id);
        this.chainPositions.remove(id);
        this.writeLayout();
        if (id.equals(this.recipeId)) {
            this.selectRecipe(this.model.selectedRecipeId());
        }
        this.restructured();
    }

    private UIElement paletteStrip() {
        UIElement panel = new UIElement().addClass("simupack_ipalette_strip");
        panel.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
            layout.gapAll(2.0f);
        });
        UIElement categories = SimuPackLayout.hRow(2);
        for (IndustrialStepCategory category : IndustrialStepCategory.values()) {
            if (IndustrialStepType.of(category).isEmpty()) continue;
            String text = Component.translatable((String)IndustrialActionPanel.shortCategoryKey(category)).getString();
            Button button = new Button().setText((Component)Component.literal((String)text));
            float width = SimuPackLayout.textWidth(text) + 12.0f;
            button.layout(layout -> {
                layout.width(width);
                layout.minWidth(width);
                layout.maxWidth(width);
                layout.height(18.0f);
                layout.flexShrink(0.0f);
            });
            if (category == paletteCategory) {
                IndustrialActionPanel.paintButton(button, -13660337);
            }
            button.setOnClick(event -> {
                paletteCategory = category;
                this.host.refresh();
            });
            categories.addChild((UIElement)button);
        }
        categories.addChild(SimuPackLayout.spacer(1.0f));
        categories.addChild((UIElement)this.textButton(paletteCollapsed ? "\u25be" : "\u25b4", 16.0f, () -> {
            paletteCollapsed = !paletteCollapsed;
            this.host.refresh();
        }));
        panel.addChild(categories);
        List<IndustrialStepType> types = IndustrialStepType.of(paletteCategory);
        UIElement row = SimuPackLayout.buttonRow(3);
        if (paletteCategory == IndustrialStepCategory.FLOW) {
            row.addChild((UIElement)this.startAddButton());
        }
        for (IndustrialStepType type : types) {
            row.addChild((UIElement)this.paletteButton(type));
        }
        if (paletteCategory == IndustrialStepCategory.FLOW) {
            for (PointEntry entry : this.pointEntriesForHost()) {
                row.addChild(this.pointChip(entry.name(), entry.container()));
            }
            row.addChild((UIElement)this.pointAddButton());
            row.addChild((UIElement)this.pointSettingsButton());
        }
        panel.addChild(row);
        return panel;
    }

    private Button startAddButton() {
        String text = Component.translatable((String)"gui.simupack.industrial.start").getString();
        Button button = new Button().setText((Component)Component.literal((String)text));
        float width = SimuPackLayout.textWidth(text) + 12.0f;
        button.layout(layout -> {
            layout.width(width);
            layout.minWidth(width);
            layout.maxWidth(width);
            layout.height(14.0f);
            layout.flexShrink(0.0f);
        });
        IndustrialActionPanel.paintButton(button, IndustrialStepCategory.FLOW.color());
        if (this.editable()) {
            this.installDragSource((UIElement)button, DragPayload::ofStart, () -> IndustrialStepCategory.FLOW.color(), () -> {
                paletteCategory = IndustrialStepCategory.FLOW;
                this.addChain(null, null);
            });
        } else {
            button.setActive(false);
        }
        return button;
    }

    private void addChain(String preferredRecipe, float[] dropPosition) {
        if (!this.editable()) {
            return;
        }
        this.host.pushHistory();
        String target = preferredRecipe;
        if (target == null || target.isBlank() || this.chainOrder.contains(target) || this.model.recipe(target) == null) {
            target = this.firstFreeRecipe();
        }
        if (target == null) {
            JsonObject created = this.model.addRecipe("recipe");
            target = IndustrialActionPanel.string(created, "id");
        }
        if (!this.chainOrder.contains(target)) {
            this.chainOrder.add(target);
        }
        if (dropPosition != null) {
            this.chainPositions.put(target, new float[]{dropPosition[0], dropPosition[1]});
        } else {
            this.chainPositions.remove(target);
        }
        this.writeLayout();
        this.selectRecipe(target);
        this.selectedStep = null;
        this.restructured();
    }

    private String firstFreeRecipe() {
        for (JsonObject recipe : this.model.recipes()) {
            String id = IndustrialActionPanel.string(recipe, "id");
            if (id.isBlank() || this.chainOrder.contains(id)) continue;
            return id;
        }
        return null;
    }

    private List<String> chainRecipes() {
        ArrayList<String> result = new ArrayList<String>();
        for (String id : this.chainOrder) {
            if (this.model.recipe(id) == null) continue;
            result.add(id);
        }
        return result;
    }

    private UIElement startBlockView(String chainRecipe, UIElement chainColumn, float[] position) {
        boolean selected = chainRecipe.equals(this.recipeId) && this.selectedStep == null;
        UIElement shell = new UIElement().addClass("simupack_iblock");
        if (selected) {
            shell.addClass("simupack_iblock_selected");
        }
        shell.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        });
        UIElement plate = new UIElement().addClass("simupack_iblock_plate").addClass("simupack_iblock_plate_" + IndustrialStepCategory.FLOW.id());
        plate.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        });
        shell.addChild(plate);
        UIElement topEdge = new UIElement().addClass("simupack_iblock_top");
        topEdge.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.height(1.0f);
            layout.flexShrink(0.0f);
        });
        plate.addChild(topEdge);
        UIElement head = new UIElement().addClass("simupack_iblock_head");
        head.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(2.0f);
        });
        head.addChild(SimuPackLayout.pinnedLabel((Component)Component.literal((String)"\u25a3 "), -1).setAllowHitTest(false));
        head.addChild(SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.industrial.start_with"), -1).setAllowHitTest(false));
        String title = this.model.recipeDisplayName(this.model.recipe(chainRecipe));
        Button picker = this.textButton(title + " \u25be", SimuPackLayout.textWidth(title) + 14.0f, null);
        this.installDropdown(picker, () -> this.startBlockEntries(chainRecipe));
        head.addChild((UIElement)picker);
        head.addChild(SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.industrial.start_begin"), -1).setAllowHitTest(false));
        head.addChild(SimuPackLayout.spacer(1.0f));
        this.installChainMove(head, chainRecipe, chainColumn, position);
        plate.addChild(head);
        UIElement actions = SimuPackLayout.row(2);
        if (this.editable()) {
            actions.addChild((UIElement)this.textButton("\u2715", 14.0f, () -> this.host.openMenu(IndustrialActionPanel.liveMouse()[0], IndustrialActionPanel.liveMouse()[1], this.clearChainEntries(chainRecipe))));
        }
        plate.addChild(actions);
        UIElement bottomEdge = new UIElement().addClass("simupack_iblock_bottom");
        bottomEdge.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.height(1.0f);
            layout.flexShrink(0.0f);
        });
        plate.addChild(bottomEdge);
        return shell;
    }

    private void installChainMove(UIElement handle, String chainRecipe, UIElement chainColumn, float[] position) {
        handle.addEventListener("mouseDown", event -> {
            if (event.button != 0 || this.readOnly) {
                return;
            }
            ChainMove move = new ChainMove(chainRecipe, chainColumn, position);
            move.pressX = event.x;
            move.pressY = event.y;
            move.startX = position[0];
            move.startY = position[1];
            this.pendingChainMove = move;
        });
    }

    private void advanceChainMove(UIEvent event) {
        ChainMove move = this.pendingChainMove;
        if (move == null) {
            return;
        }
        if (!move.moving) {
            float dx = event.x - move.pressX;
            float dy = event.y - move.pressY;
            if (dx * dx + dy * dy < 9.0f) {
                return;
            }
            move.moving = true;
            this.selectRecipe(move.recipe);
            this.selectedStep = null;
            this.insertParent = null;
            this.insertIndex = 0;
        }
        float[] delta = this.canvasDelta(event.x - move.pressX, event.y - move.pressY);
        move.position[0] = Math.max(0.0f, move.startX + delta[0]);
        move.position[1] = Math.max(0.0f, move.startY + delta[1]);
        this.chainPositions.put(move.recipe, move.position);
        move.column.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(move.position[0]);
            layout.top(move.position[1]);
            layout.width(330.0f);
            layout.minWidth(0.0f);
        });
        event.stopPropagation();
    }

    private void finishChainMove() {
        ChainMove move = this.pendingChainMove;
        this.pendingChainMove = null;
        if (move == null) {
            return;
        }
        if (move.moving) {
            this.host.pushHistory();
            this.writeLayout();
            this.persist();
            this.host.commit();
        } else {
            this.selectRecipe(move.recipe);
            this.selectedStep = null;
            this.insertParent = null;
            this.insertIndex = 0;
        }
        this.host.refresh();
    }

    private void fitChainsToView() {
        float contentHeight;
        if (this.canvasHost == null || this.contentRoot == null) {
            return;
        }
        List<String> chains = this.chainRecipes();
        float viewWidth = this.canvasHost.getContentWidth() - 12.0f;
        float viewHeight = this.canvasHost.getContentHeight() - 12.0f;
        if (viewWidth < 40.0f || viewHeight < 40.0f) {
            this.pendingFitZoom = true;
            return;
        }
        if (chains.isEmpty()) {
            blockZoom = 1.0f;
            canvasOffsetX = 0.0f;
            canvasOffsetY = 0.0f;
            this.refreshContentTransform();
            return;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -3.4028235E38f;
        float maxY = -3.4028235E38f;
        for (int i = 0; i < chains.size(); ++i) {
            String chain = chains.get(i);
            float[] pos = this.chainPosition(chain, i);
            float height = this.estimatedChainHeight(chain);
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxX = Math.max(maxX, pos[0] + 330.0f);
            maxY = Math.max(maxY, pos[1] + height);
        }
        float contentWidth = Math.max(1.0f, maxX - minX);
        float zoom = Math.min(1.0f, Math.min(viewWidth / contentWidth, viewHeight / (contentHeight = Math.max(1.0f, maxY - minY))) * 0.92f);
        if (!Float.isFinite(zoom) || zoom <= 0.0f) {
            return;
        }
        blockZoom = Math.max(0.02f, Math.min(50.0f, zoom));
        canvasOffsetX = 6.0f - minX * blockZoom;
        canvasOffsetY = 6.0f - minY * blockZoom;
        this.refreshContentTransform();
    }

    private float estimatedChainHeight(String chainRecipe) {
        return 52.0f + this.estimatedStepsHeight(this.model.steps(chainRecipe), 0);
    }

    private float estimatedStepsHeight(List<JsonObject> steps, int depth) {
        if (depth > 16) {
            return 0.0f;
        }
        float height = 0.0f;
        for (JsonObject step : steps) {
            height += (this.readOnly ? 86.0f : 44.0f) + 5.0f;
            IndustrialStepType type = IndustrialStepType.byRawType(IndustrialActionPanel.string(step, "type"));
            if (type == null || !type.isRepeat()) continue;
            height += 26.0f + this.estimatedStepsHeight(this.model.childrenOf(step), depth + 1);
        }
        return height;
    }

    private float[] canvasDelta(float guiDx, float guiDy) {
        if (this.canvasHost == null || this.contentRoot == null) {
            return new float[]{guiDx, guiDy};
        }
        float[] now = IndustrialActionPanel.liveMouse();
        Vector2f from = this.contentRoot.getLocalMouse(now[0], now[1]);
        Vector2f to = this.contentRoot.getLocalMouse(now[0] + guiDx, now[1] + guiDy);
        return new float[]{to.x - from.x, to.y - from.y};
    }

    private float[] mouseInCanvas() {
        if (this.contentRoot == null) {
            return new float[]{6.0f, 6.0f};
        }
        float[] now = IndustrialActionPanel.liveMouse();
        Vector2f local = this.contentRoot.getLocalMouse(now[0], now[1]);
        return new float[]{Math.max(0.0f, local.x), Math.max(0.0f, local.y)};
    }

    private List<SimuPackMenuBar.Entry> startBlockEntries(String chainRecipe) {
        ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
        for (JsonObject recipe : this.model.recipes()) {
            String id = IndustrialActionPanel.string(recipe, "id");
            if (id.isBlank()) continue;
            boolean mine = id.equals(chainRecipe);
            boolean takenByOther = !mine && this.chainOrder.contains(id);
            entries.add(SimuPackMenuBar.Entry.item(this.model.recipeDisplayName(recipe), mine ? "\u2713" : (takenByOther ? Component.translatable((String)"gui.simupack.industrial.start_taken").getString() : ""), () -> {
                if (mine) {
                    return;
                }
                int slot = this.chainOrder.indexOf(chainRecipe);
                this.chainOrder.remove(chainRecipe);
                if (slot >= 0 && slot <= this.chainOrder.size()) {
                    this.chainOrder.add(slot, id);
                } else {
                    this.chainOrder.add(id);
                }
                float[] moved = this.chainPositions.remove(chainRecipe);
                if (moved != null) {
                    this.chainPositions.put(id, moved);
                }
                this.writeLayout();
                this.selectRecipe(id);
                this.selectedStep = null;
                this.restructured();
            }, () -> !takenByOther));
        }
        entries.add(SimuPackMenuBar.Entry.separator());
        entries.add(SimuPackMenuBar.Entry.item("gui.simupack.industrial.new_recipe", "", () -> this.addChain(null, null), this::editable));
        return entries;
    }

    private List<SimuPackMenuBar.Entry> clearChainEntries(String chainRecipe) {
        ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
        entries.add(SimuPackMenuBar.Entry.item(Component.translatable((String)"gui.simupack.industrial.start_clear_confirm", (Object[])new Object[]{this.model.recipeDisplayName(this.model.recipe(chainRecipe))}).getString(), "", () -> this.clearChain(chainRecipe), this::editable));
        entries.add(SimuPackMenuBar.Entry.separator());
        entries.add(SimuPackMenuBar.Entry.item("gui.simupack.industrial.cancel", "", () -> {}, () -> false));
        return entries;
    }

    private void clearChain(String chainRecipe) {
        if (!this.editable()) {
            return;
        }
        this.host.pushHistory();
        JsonObject recipe = this.model.recipe(chainRecipe);
        if (recipe != null) {
            recipe.add("steps", (JsonElement)new JsonArray());
        }
        this.chainOrder.remove(chainRecipe);
        this.chainPositions.remove(chainRecipe);
        this.writeLayout();
        if (chainRecipe.equals(this.recipeId)) {
            this.selectedStep = null;
            this.insertParent = null;
            this.insertIndex = 0;
        }
        this.persist();
        this.host.commit();
        this.host.refresh();
    }

    private Button pointAddButton() {
        String text = Component.translatable((String)"gui.simupack.industrial.points_add").getString();
        Button button = new Button().setText((Component)Component.literal((String)text));
        float width = SimuPackLayout.textWidth(text) + 10.0f;
        button.layout(layout -> {
            layout.width(width);
            layout.minWidth(width);
            layout.maxWidth(width);
            layout.height(14.0f);
            layout.flexShrink(0.0f);
        });
        IndustrialActionPanel.paintButton(button, -11904406);
        button.setOnClick(event -> this.host.openPickTab());
        if (!this.editable()) {
            button.setActive(false);
        }
        return button;
    }

    private Button pointSettingsButton() {
        String text = Component.translatable((String)"gui.simupack.industrial.points_manage_short").getString();
        Button button = new Button().setText((Component)Component.literal((String)text));
        float width = SimuPackLayout.textWidth(text) + 10.0f;
        button.layout(layout -> {
            layout.width(width);
            layout.minWidth(width);
            layout.maxWidth(width);
            layout.height(14.0f);
            layout.flexShrink(0.0f);
        });
        IndustrialActionPanel.paintButton(button, -11904406);
        button.setOnClick(event -> this.host.openDialog(EditorDialog.INDUSTRIAL_POINT_SETTINGS));
        if (!this.editable()) {
            button.setActive(false);
        }
        return button;
    }

    private UIElement pointChip(String name, boolean container) {
        BuildingJsonDocument.DefinitionKind kind = container ? BuildingJsonDocument.DefinitionKind.CONTAINER : BuildingJsonDocument.DefinitionKind.POINT;
        JsonObject definition = this.model.definitionEntry(name, kind);
        int count = definition == null ? 0 : BuildingJsonDocument.positionList(definition).size();
        String text = name + (container ? " \u00b7 \u5bb9\u5668" : "") + " \u00b7 \u00d7" + count;
        UIElement chip = new UIElement().addClass("simupack_iref");
        chip.layout(layout -> {
            layout.height(14.0f);
            layout.flexShrink(0.0f);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.paddingLeft(4.0f);
            layout.paddingRight(4.0f);
        });
        chip.addChild(SimuPackLayout.pinnedLabel((Component)Component.literal((String)text), -1).setAllowHitTest(false));
        if (this.editable()) {
            this.installDragSource(chip, () -> DragPayload.ofDefinition(name, container), () -> -11904406, () -> {});
        }
        return chip;
    }

    private static String shortCategoryKey(IndustrialStepCategory category) {
        return "gui.simupack.industrial.cat_short." + category.id();
    }

    public boolean definePoint(String name, boolean container, List<int[]> positions, String select) {
        BuildingJsonDocument.DefinitionKind kind;
        if (this.readOnly || this.model == null || name == null || name.isBlank() || positions.isEmpty()) {
            return false;
        }
        BuildingJsonDocument.DefinitionKind definitionKind = kind = container ? BuildingJsonDocument.DefinitionKind.CONTAINER : BuildingJsonDocument.DefinitionKind.POINT;
        if (this.model.hasDefinition(name, BuildingJsonDocument.DefinitionKind.POINT) || this.model.hasDefinition(name, BuildingJsonDocument.DefinitionKind.CONTAINER)) {
            return false;
        }
        return this.modelEdit(draft -> draft.definePoint(name, kind, positions, select));
    }

    private boolean wouldChange(Consumer<BuildingJsonDocument> mutation) {
        if (!this.editable()) {
            return false;
        }
        BuildingJsonDocument draft = BuildingJsonDocument.parse(this.model.toJsonString());
        mutation.accept(draft);
        return !draft.root().equals((Object)this.model.root());
    }

    private boolean modelEdit(Consumer<BuildingJsonDocument> mutation) {
        if (!this.editable()) {
            return false;
        }
        BuildingJsonDocument draft = BuildingJsonDocument.parse(this.model.toJsonString());
        mutation.accept(draft);
        if (draft.root().equals((Object)this.model.root())) {
            return false;
        }
        this.host.pushHistory();
        mutation.accept(this.model);
        this.persist();
        this.host.commit();
        this.host.refresh();
        return true;
    }

    public List<PointEntry> pointEntriesForHost() {
        ArrayList<PointEntry> result = new ArrayList<PointEntry>();
        if (this.model != null) {
            for (String name : this.model.pointKeys()) {
                result.add(new PointEntry(name, false));
            }
            for (String name : this.model.containerKeys()) {
                result.add(new PointEntry(name, true));
            }
        }
        return result;
    }

    private static BuildingJsonDocument.DefinitionKind kind(boolean container) {
        return container ? BuildingJsonDocument.DefinitionKind.CONTAINER : BuildingJsonDocument.DefinitionKind.POINT;
    }

    public JsonObject pointDefinition(String name, boolean container) {
        JsonObject value = this.model == null ? null : this.model.definitionEntry(name, IndustrialActionPanel.kind(container));
        return value == null ? null : value.deepCopy();
    }

    public List<BuildingJsonDocument.Reference> pointReferences(String name, boolean container) {
        return this.model == null ? List.of() : this.model.findReferences(name, IndustrialActionPanel.kind(container));
    }

    public int referenceCount(String name, boolean container) {
        return this.pointReferences(name, container).size();
    }

    public boolean renamePoint(String oldName, String newName, boolean container) {
        return this.modelEdit(draft -> draft.renameDefinition(oldName, newName, IndustrialActionPanel.kind(container)));
    }

    public boolean deletePoint(String name, boolean container, boolean force) {
        return this.modelEdit(draft -> draft.deleteDefinition(name, IndustrialActionPanel.kind(container), force));
    }

    public boolean reselectPoint(String name, boolean container, List<int[]> positions, String select) {
        return this.modelEdit(draft -> draft.setDefinitionPositions(name, IndustrialActionPanel.kind(container), positions, select));
    }

    public int positionCount(String name, boolean container) {
        if (this.model == null) {
            return 0;
        }
        JsonObject definition = this.model.definitionEntry(name, container ? BuildingJsonDocument.DefinitionKind.CONTAINER : BuildingJsonDocument.DefinitionKind.POINT);
        return definition == null ? 0 : BuildingJsonDocument.positionList(definition).size();
    }

    private static void paintButton(Button button, int base) {
        button.buttonStyle(style -> style.baseTexture(IndustrialActionPanel.plateTexture(base)).hoverTexture(IndustrialActionPanel.plateTexture(IndustrialActionPanel.lighten(base, 0.22f))).pressedTexture(IndustrialActionPanel.plateTexture(IndustrialActionPanel.darken(base, 0.28f))));
        button.textStyle(text -> text.textColor(-1).textShadow(false));
    }

    private static IGuiTexture plateTexture(int base) {
        return RectTexture.of((int)base).setStroke(1.0f).setBorderColor(IndustrialActionPanel.darken(base, 0.45f));
    }

    private static int lighten(int argb, float amount) {
        return IndustrialActionPanel.blend(argb, 255, amount);
    }

    private static int darken(int argb, float amount) {
        return IndustrialActionPanel.blend(argb, 0, amount);
    }

    private static int blend(int argb, int target, float amount) {
        int alpha = argb >>> 24 & 0xFF;
        int red = argb >> 16 & 0xFF;
        int green = argb >> 8 & 0xFF;
        int blue = argb & 0xFF;
        red = (int)((float)red + (float)(target - red) * amount);
        green = (int)((float)green + (float)(target - green) * amount);
        blue = (int)((float)blue + (float)(target - blue) * amount);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private Button paletteButton(IndustrialStepType type) {
        String text = Component.translatable((String)type.translationKey()).getString();
        Button button = new Button().setText((Component)Component.literal((String)text));
        IndustrialActionPanel.paintButton(button, type.category().color());
        float width = SimuPackLayout.textWidth(text) + 10.0f;
        button.layout(layout -> {
            layout.width(width);
            layout.minWidth(width);
            layout.maxWidth(width);
            layout.height(14.0f);
            layout.flexShrink(0.0f);
        });
        if (this.editable()) {
            this.installDragSource((UIElement)button, () -> DragPayload.ofType(type), () -> type.category().color(), () -> {
                paletteCategory = type.category();
                this.insertType(type);
            });
        } else {
            button.setActive(false);
        }
        return button;
    }

    private UIElement canvasContent() {
        UIElement content = SimuPackLayout.column(2);
        content.addClass("simupack_icanvas");
        if (this.focusIssue && this.selectedStep != null && this.model.step(this.recipeId, this.selectedStep) != null) {
            content.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.show_all_steps").getString(), 0.0f, () -> {
                this.focusIssue = false;
                this.host.refresh();
            }));
            content.addChild(this.blockView(this.model.step(this.recipeId, this.selectedStep), this.selectedStep, this.recipeId));
            return content;
        }
        UIElement board = new UIElement().addClass("simupack_icanvas_board");
        board.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.height(6000.0f);
            layout.minWidth(0.0f);
            layout.positionType(TaffyPosition.RELATIVE);
        });
        List<String> chains = this.chainRecipes();
        if (chains.isEmpty()) {
            board.addChild(this.wrapped("gui.simupack.industrial.no_chain", -4342339));
            if (this.editable()) {
                board.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.start_add").getString(), 0.0f, () -> this.addChain(null, null)));
            }
        }
        for (int i = 0; i < chains.size(); ++i) {
            String chain = chains.get(i);
            float[] pos = this.chainPosition(chain, i);
            UIElement column = SimuPackLayout.column(2);
            column.layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(pos[0]);
                layout.top(pos[1]);
                layout.width(330.0f);
                layout.minWidth(0.0f);
            });
            column.addChild(this.startBlockView(chain, column, pos));
            this.renderChain(column, this.model.steps(chain), null, false, chain);
            board.addChild(column);
        }
        if (this.editable()) {
            board.addEventListener("dragPerform", event -> {
                DragPayload drag;
                if (event.dragHandler == null) {
                    return;
                }
                Object patt0$temp = event.dragHandler.getDraggingObject();
                if (patt0$temp instanceof DragPayload && (drag = (DragPayload)patt0$temp).isStart()) {
                    this.addChain(null, this.avoidOverlap(this.mouseInCanvas(), null));
                    event.stopPropagation();
                }
            });
        }
        content.addChild(board);
        return content;
    }

    private void readLayout() {
        this.chainOrder.clear();
        this.chainPositions.clear();
        JsonObject layout = this.model.editorLayout(false);
        if (layout != null) {
            JsonObject positions;
            JsonArray order;
            JsonArray jsonArray = order = layout.has("order") && layout.get("order").isJsonArray() ? layout.getAsJsonArray("order") : null;
            if (order != null) {
                for (JsonElement element : order) {
                    String id = element.isJsonPrimitive() ? element.getAsString() : "";
                    if (id.isBlank() || this.model.recipe(id) == null || this.chainOrder.contains(id)) continue;
                    this.chainOrder.add(id);
                }
            }
            JsonObject jsonObject = positions = layout.has("positions") && layout.get("positions").isJsonObject() ? layout.getAsJsonObject("positions") : null;
            if (positions != null) {
                for (Map.Entry entry : positions.entrySet()) {
                    JsonArray xy;
                    JsonElement value = (JsonElement)entry.getValue();
                    if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() < 2 || !IndustrialActionPanel.isNumberValue((xy = value.getAsJsonArray()).get(0)) || !IndustrialActionPanel.isNumberValue(xy.get(1))) continue;
                    this.chainPositions.put((String)entry.getKey(), new float[]{xy.get(0).getAsFloat(), xy.get(1).getAsFloat()});
                }
            }
        }
        for (JsonObject recipe : this.model.recipes()) {
            String id = IndustrialActionPanel.string(recipe, "id");
            if (id.isBlank() || this.chainOrder.contains(id) || this.model.steps(id).isEmpty()) continue;
            this.chainOrder.add(id);
        }
    }

    private static boolean isNumberValue(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private void writeLayout() {
        if (this.model == null) {
            return;
        }
        JsonObject layout = this.model.editorLayout(true);
        JsonArray order = new JsonArray();
        for (String id : this.chainOrder) {
            order.add(id);
        }
        layout.add("order", (JsonElement)order);
        JsonObject positions = new JsonObject();
        for (Map.Entry<String, float[]> entry : this.chainPositions.entrySet()) {
            JsonArray xy = new JsonArray();
            xy.add((Number)Float.valueOf(entry.getValue()[0]));
            xy.add((Number)Float.valueOf(entry.getValue()[1]));
            positions.add(entry.getKey(), (JsonElement)xy);
        }
        layout.add("positions", (JsonElement)positions);
    }

    private float[] chainPosition(String chainRecipe, int index) {
        float[] stored = this.chainPositions.get(chainRecipe);
        if (stored != null) {
            return stored;
        }
        float[] auto = this.freeChainPosition(chainRecipe);
        this.chainPositions.put(chainRecipe, auto);
        return auto;
    }

    private float[] freeChainPosition(String ignore) {
        float x = 6.0f;
        float y = 6.0f;
        for (int guard = 0; guard < 400; ++guard) {
            String hit = this.chainAt(x, y, ignore);
            if (hit == null) {
                return new float[]{x, y};
            }
            y = this.chainPositions.get(hit)[1] + this.estimatedChainHeight(hit) + (this.readOnly ? 42.0f : 18.0f);
            if (!(y > 900.0f)) continue;
            x += 330.0f + (this.readOnly ? 42.0f : 18.0f);
            y = 6.0f;
        }
        return new float[]{x, y};
    }

    private String chainAt(float x, float y, String ignore) {
        for (String other : this.chainRecipes()) {
            boolean overlapY;
            float[] pos;
            if (other.equals(ignore) || (pos = this.chainPositions.get(other)) == null) continue;
            float height = this.estimatedChainHeight(other);
            boolean overlapX = x < pos[0] + 330.0f + 6.0f && x + 330.0f + 6.0f > pos[0];
            boolean bl = overlapY = y < pos[1] + height + 8.0f && y + height + 8.0f > pos[1];
            if (!overlapX || !overlapY) continue;
            return other;
        }
        return null;
    }

    private float[] avoidOverlap(float[] desired, String ignore) {
        float x = Math.max(0.0f, desired[0]);
        float y = Math.max(0.0f, desired[1]);
        for (int i = 0; i < 8; ++i) {
            if (this.chainAt(x, y, ignore) == null) {
                return new float[]{x, y};
            }
            x += 26.0f;
            y += 26.0f;
        }
        return new float[]{x, y};
    }

    private void renderChain(UIElement parent, List<JsonObject> steps, BuildingJsonDocument.StepPath chainParent) {
        this.renderChain(parent, steps, chainParent, false, this.recipeId);
    }

    private void renderChain(UIElement parent, List<JsonObject> steps, BuildingJsonDocument.StepPath chainParent, boolean roomy, String chainRecipe) {
        float slotHeight = steps.isEmpty() ? 22.0f : 5.0f;
        for (int i = 0; i < steps.size(); ++i) {
            parent.addChild(this.dropSlot(chainParent, i, slotHeight, chainRecipe));
            BuildingJsonDocument.StepPath path = chainParent == null ? BuildingJsonDocument.StepPath.root(i) : chainParent.down(i);
            parent.addChild(this.blockView(steps.get(i), path, chainRecipe));
        }
        parent.addChild(this.dropSlot(chainParent, steps.size(), slotHeight, chainRecipe));
        if (steps.isEmpty()) {
            parent.addChild(this.wrapped("gui.simupack.industrial.empty_chain", -4342339));
        }
    }

    private static DragPayload definitionPayload(UIEvent event) {
        DragPayload payload;
        if (event.dragHandler == null) {
            return null;
        }
        Object object = event.dragHandler.getDraggingObject();
        return object instanceof DragPayload && (payload = (DragPayload)object).isDefinition() ? payload : null;
    }

    private static boolean pendingStartDrop(UIEvent event) {
        DragPayload drag;
        Object object;
        return event.dragHandler != null && (object = event.dragHandler.getDraggingObject()) instanceof DragPayload && (drag = (DragPayload)object).isStart();
    }

    private void installDragSource(UIElement element, Supplier<DragPayload> payload, Supplier<Integer> color, Runnable onClick) {
        element.addEventListener("mouseDown", event -> {
            if (event.button != 0 || this.readOnly) {
                return;
            }
            if (event.target != element && event.target instanceof Button) {
                return;
            }
            DragState state = new DragState(element, payload, color);
            state.pressX = event.x;
            state.pressY = event.y;
            this.pendingDrag = state;
            this.lastDrag = state;
        });
        element.addEventListener("mouseUp", event -> {
            if (this.pendingDrag != null && this.pendingDrag.element == element) {
                this.pendingDrag = null;
            }
        });
        element.addEventListener("dragEnd", event -> {
            if (this.pendingDrag != null && this.pendingDrag.element == element) {
                this.pendingDrag = null;
            }
        });
        if (onClick != null) {
            element.addEventListener("mouseClick", event -> {
                DragState state = this.lastDrag;
                if (state != null && state.element == element && state.dragged[0]) {
                    state.dragged[0] = false;
                    return;
                }
                if (event.button == 0) {
                    onClick.run();
                }
            });
        }
    }

    private void advancePendingDrag(UIEvent event) {
        DragState state = this.pendingDrag;
        if (state == null || state.started) {
            return;
        }
        float dx = event.x - state.pressX;
        float dy = event.y - state.pressY;
        if (dx * dx + dy * dy < 9.0f) {
            return;
        }
        DragPayload data = state.payload.get();
        if (data == null) {
            this.pendingDrag = null;
            return;
        }
        state.started = true;
        state.dragged[0] = true;
        DragHandler handler = state.element.startDrag((Object)data, (IGuiTexture)new ColorRectTexture(state.color.get().intValue()));
        if (handler != null) {
            handler.setDragTexture(-32.0f, -6.0f, 64.0f, 12.0f);
        }
    }

    private void installDefinitionDrop(UIElement slot, JsonObject step, DefinitionField field) {
        boolean wantsContainer = field.kind() == DefinitionField.Kind.CONTAINER_REF;
        slot.addEventListener("dragEnter", event -> {
            DragPayload payload = IndustrialActionPanel.definitionPayload(event);
            if (payload != null && (payload.containerDefinition() || !wantsContainer)) {
                slot.addClass("simupack_idrop_hot");
            }
        });
        slot.addEventListener("dragLeave", event -> slot.removeClass("simupack_idrop_hot"));
        slot.addEventListener("dragPerform", event -> {
            JsonObject existing;
            slot.removeClass("simupack_idrop_hot");
            DragPayload payload = IndustrialActionPanel.definitionPayload(event);
            if (payload == null || !this.editable()) {
                return;
            }
            if (wantsContainer && !payload.containerDefinition()) {
                this.host.notify(Component.translatable((String)"gui.simupack.industrial.drop_needs_container", (Object[])new Object[]{payload.definition()}).getString());
                return;
            }
            if (!wantsContainer && payload.containerDefinition() && (existing = this.model.definitionEntry(payload.definition(), BuildingJsonDocument.DefinitionKind.POINT)) != null && !payload.definition().equals(IndustrialActionPanel.string(existing, "_simupackContainerPoint"))) {
                this.host.notify(Component.translatable((String)"gui.simupack.industrial.point_name_taken", (Object[])new Object[]{payload.definition()}).getString());
                return;
            }
            if (payload.definition().equals(BuildingJsonDocument.readString(step, field)) && (wantsContainer || !payload.containerDefinition() || this.model.hasDefinition(payload.definition(), BuildingJsonDocument.DefinitionKind.POINT))) {
                return;
            }
            this.host.pushHistory();
            if (!wantsContainer && payload.containerDefinition()) {
                this.model.ensurePointEntry(payload.definition());
            }
            BuildingJsonDocument.writeString(step, field, payload.definition());
            this.persist();
            this.host.commit();
            this.host.refresh();
            event.stopPropagation();
        });
    }

    private UIElement dropSlot(BuildingJsonDocument.StepPath parent, int index, float height, String chainRecipe) {
        UIElement slot = new UIElement().addClass("simupack_idrop");
        slot.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.height(height);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
        });
        UIElement divider = new UIElement().addClass("simupack_idrop_divider");
        divider.setAllowHitTest(false);
        divider.layout(layout -> {
            layout.positionType(TaffyPosition.ABSOLUTE);
            layout.left(0.0f);
            layout.right(0.0f);
            layout.top((height - 1.0f) / 2.0f);
            layout.height(1.0f);
        });
        slot.addChild(divider);
        slot.addEventListener("dragEnter", event -> slot.addClass("simupack_idrop_hot"));
        slot.addEventListener("dragLeave", event -> slot.removeClass("simupack_idrop_hot"));
        slot.addEventListener("dragPerform", event -> {
            slot.removeClass("simupack_idrop_hot");
            if (event.dragHandler == null) {
                return;
            }
            Object patt0$temp = event.dragHandler.getDraggingObject();
            if (patt0$temp instanceof DragPayload) {
                DragPayload drag = (DragPayload)patt0$temp;
                if (drag.isStart() || drag.chain() != null) {
                    this.host.notify(Component.translatable((String)"gui.simupack.industrial.start_not_a_step").getString());
                    event.stopPropagation();
                    return;
                }
                this.handleDrop(drag, parent, index, chainRecipe);
            }
        });
        return slot;
    }

    private void handleDrop(DragPayload payload, BuildingJsonDocument.StepPath parent, int index, String chainRecipe) {
        if (!this.editable() || this.model == null || this.model.recipe(chainRecipe) == null) {
            return;
        }
        if (payload.isNew()) {
            if (!this.wouldChange(draft -> draft.insertStep(chainRecipe, parent, index, payload.type()))) {
                return;
            }
            this.host.pushHistory();
            BuildingJsonDocument.StepPath created = this.model.insertStep(chainRecipe, parent, index, payload.type());
            if (created != null) {
                this.selectedStep = created;
                this.insertParent = created.up();
                this.insertIndex = created.leaf() + 1;
            }
            this.restructuredAfterEvent();
            return;
        }
        BuildingJsonDocument.StepPath from = payload.path();
        if (from == null) {
            return;
        }
        if (!this.wouldChange(draft -> draft.moveStepTo(chainRecipe, from, parent, index))) {
            return;
        }
        this.host.pushHistory();
        BuildingJsonDocument.StepPath moved = this.model.moveStepTo(chainRecipe, from, parent, index);
        if (moved != null) {
            this.selectedStep = moved;
            this.insertParent = moved.up();
            this.insertIndex = moved.leaf() + 1;
        }
        this.restructuredAfterEvent();
    }

    private void restructuredAfterEvent() {
        this.persist();
        this.host.commit();
        Minecraft.getInstance().tell(this.host::refresh);
    }

    private UIElement blockView(JsonObject step, BuildingJsonDocument.StepPath path, String chainRecipe) {
        IndustrialStepType type = IndustrialStepType.byRawType(IndustrialActionPanel.string(step, "type"));
        boolean selected = path.equals(this.selectedStep);
        UIElement shell = new UIElement().addClass(selected ? "simupack_iblock_selected" : "simupack_iblock");
        shell.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        });
        IndustrialStepCategory category = type == null ? IndustrialStepCategory.FLOW : type.category();
        UIElement plate = new UIElement().addClass("simupack_iblock_plate").addClass((String)(type == null ? "simupack_iblock_plate_unknown" : "simupack_iblock_plate_" + category.id()));
        plate.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        });
        shell.addChild(plate);
        UIElement topEdge = new UIElement().addClass("simupack_iblock_top");
        topEdge.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.height(1.0f);
            layout.flexShrink(0.0f);
        });
        plate.addChild(topEdge);
        UIElement head = new UIElement().addClass("simupack_iblock_head");
        head.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(2.0f);
        });
        head.addChild(SimuPackLayout.pinnedLabel((Component)Component.literal((String)("\u25a3 " + this.typeLabel(type, step))), -1).setAllowHitTest(false));
        head.addChild(SimuPackLayout.spacer(1.0f));
        String summary = BuildingJsonDocument.summary(step);
        if (!summary.isBlank()) {
            head.addChild(SimuPackLayout.pinnedLabel((Component)Component.literal((String)summary), -1).setAllowHitTest(false));
        }
        this.installDragSource(head, () -> DragPayload.ofStep(path), category::color, () -> this.select(chainRecipe, path));
        plate.addChild(head);
        UIElement issueHost = SimuPackLayout.column(1);
        Runnable showIssues = () -> {
            issueHost.clearAllChildren();
            String location = this.stepLocation(this.recipeId, path);
            List<DefinitionIssue> issues = this.model.validate(this.bounds).stream().filter(issue -> issue.location().equals(location) || issue.location().startsWith(location + ".")).toList();
            if (!issues.isEmpty()) {
                DefinitionIssue issue2 = issues.get(0);
                issueHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)(IndustrialActionPanel.severityMark(issue2.severity()) + " " + issue2.location())).append(" \u00b7 ").append((Component)Component.translatable((String)issue2.translationKey(), (Object[])new Object[]{issue2.detail()})), IndustrialActionPanel.severityColor(issue2.severity())));
            }
        };
        showIssues.run();
        this.liveHints.computeIfAbsent(step, ignored -> new ArrayList()).add(showIssues);
        plate.addChild(issueHost);
        plate.addChild(this.blockBody(step, type, path, selected));
        plate.addChild(this.blockActionRow(type, path, selected));
        if (type != null && type.isRepeat()) {
            UIElement inner = new UIElement().addClass("simupack_iblock_inner");
            inner.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.minWidth(0.0f);
                layout.flexDirection(FlexDirection.COLUMN);
                layout.alignItems(AlignItems.STRETCH);
                layout.gapAll(2.0f);
            });
            this.renderChain(inner, this.model.childrenOf(step), path, false, chainRecipe);
            plate.addChild(inner);
        }
        UIElement bottomEdge = new UIElement().addClass("simupack_iblock_bottom");
        bottomEdge.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.height(1.0f);
            layout.flexShrink(0.0f);
        });
        plate.addChild(bottomEdge);
        return shell;
    }

    private UIElement blockBody(JsonObject step, IndustrialStepType type, BuildingJsonDocument.StepPath path, boolean selected) {
        UIElement body = SimuPackLayout.column(2);
        body.addClass("simupack_iblock_body");
        if (type == null) {
            body.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.industrial.unknown_type_hint"), -34953));
            return body;
        }
        String prefix = "gui.simupack.industrial.";
        String sentenceTemplate = Component.translatable((String)(prefix + "sentence." + type.canonical())).getString();
        if (sentenceTemplate.contains("{rangeDetail}")) {
            UIElement rangeRow = SimuPackLayout.buttonRow(2);
            rangeRow.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)(prefix + "range_mode.label")), -4342339));
            rangeRow.addChild(this.rangeModeButton(step));
            body.addChild(rangeRow);
        }
        UIElement main = SimuPackLayout.buttonRow(2);
        LinkedHashSet used = new LinkedHashSet();
        Runnable updateSentence = () -> this.renderSentence(main, step, type, used);
        updateSentence.run();
        this.positionSentences.put(step, updateSentence);
        body.addChild(main);
        String hintKey = prefix + "behavior." + type.canonical();
        String hint = Component.translatable((String)hintKey).getString();
        if (!"harvest_block_clusters".equals(type.canonical()) && !hint.equals(hintKey)) {
            body.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)hint), -4342339));
        }
        UIElement advanced = SimuPackLayout.column(2);
        UIElement shortFields = SimuPackLayout.buttonRow(3);
        for (DefinitionField field : IndustrialSentence.fields(type)) {
            if (IndustrialActionPanel.isChipField(field) && !used.contains(field.key())) {
                shortFields.addChild(this.slotChip(step, field));
            }
            switch (field.kind()) {
                case POSITIONS: {
                    advanced.addChild(this.positionsRow(step, field));
                    break;
                }
                case ITEM_LIST: {
                    advanced.addChild(this.itemListRow(step, field));
                    break;
                }
            }
        }
        advanced.addChild(shortFields);
        UIElement advancedHost = SimuPackLayout.column(2);
        boolean[] expanded = new boolean[]{this.expandedOptions.contains(step)};
        if (expanded[0]) {
            advancedHost.addChild(advanced);
        }
        Button toggle = this.textButton((expanded[0] ? "\u25be " : "\u25b8 ") + Component.translatable((String)(prefix + "advanced")).getString(), 0.0f, null);
        toggle.setOnClick(event -> {
            boolean bl = expanded[0] = !expanded[0];
            if (expanded[0]) {
                this.expandedOptions.add(step);
            } else {
                this.expandedOptions.remove(step);
            }
            advancedHost.clearAllChildren();
            if (expanded[0]) {
                advancedHost.addChild(advanced);
            }
            toggle.setText((Component)Component.literal((String)((expanded[0] ? "\u25be " : "\u25b8 ") + Component.translatable((String)(prefix + "advanced")).getString())));
        });
        int legacyPositions = BuildingJsonDocument.positionList(step).size();
        if (legacyPositions > 0) {
            UIElement legacy = SimuPackLayout.column(1);
            legacy.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)(prefix + "legacy_positions"), (Object[])new Object[]{legacyPositions}), -171));
            if (!this.readOnly) {
                legacy.addChild((UIElement)this.textButton(Component.translatable((String)(prefix + "clear_positions")).getString(), 0.0f, () -> {
                    if (this.edit("step.positions.clear", step, target -> target.remove("positions"))) {
                        this.host.refresh();
                    }
                }));
            }
            advanced.addChild(legacy);
        }
        body.addChild((UIElement)toggle);
        body.addChild(advancedHost);
        if (type.field("inputs") != null) {
            this.buildInputsSection(body, BuildingJsonDocument.InputOwner.ofStep(this.recipeId, path));
        }
        if (type.field("outputs") != null) {
            this.buildOutputsSection(body, BuildingJsonDocument.InputOwner.ofStep(this.recipeId, path));
        }
        return body;
    }

    private void renderSentence(UIElement main, JsonObject step, IndustrialStepType type, Set<String> used) {
        boolean explicit;
        main.clearAllChildren();
        used.clear();
        String prefix = "gui.simupack.industrial.";
        String sentence = Component.translatable((String)(prefix + "sentence." + type.canonical())).getString();
        int positions = BuildingJsonDocument.positionList(step).size();
        if (type.isRepeat() && positions > 0) {
            sentence = Component.translatable((String)(prefix + "repeat_positions"), (Object[])new Object[]{positions}).getString();
        }
        boolean bl = explicit = positions > 0 && (type == IndustrialStepType.PLACE_BLOCK || type == IndustrialStepType.PLACE_FLUID || type == IndustrialStepType.DESTROY_BLOCK || type == IndustrialStepType.REQUIRE_BLOCK);
        if (explicit) {
            sentence = sentence.replace("{point}", Component.translatable((String)(prefix + "explicit_positions"), (Object[])new Object[]{positions}).getString());
        }
        if (sentence.contains("{rangeDetail}")) {
            boolean pointMode = !BuildingJsonDocument.readString(step, type.field("point")).isBlank() || this.pointModeSteps.contains(step);
            sentence = sentence.replace("{rangeDetail}", (CharSequence)(pointMode ? Component.translatable((String)(prefix + "range_detail_point")).getString() : " " + Component.translatable((String)(prefix + "range_mode.structure")).getString()));
        }
        if (sentence.contains("{outputTail}")) {
            boolean extract = "extract_to_output".equalsIgnoreCase(BuildingJsonDocument.readString(step, type.field("outputPolicy")));
            sentence = sentence.replace("{outputTail}", extract ? Component.translatable((String)(prefix + "output_tail")).getString() : "");
        }
        for (IndustrialSentence.Part part : IndustrialSentence.parse(sentence)) {
            if (part.field() == null) {
                main.addChild(SimuPackLayout.pinnedLabel((Component)Component.literal((String)part.text()), -1).setAllowHitTest(false));
                continue;
            }
            DefinitionField field = type.field(part.field());
            if (field == null || !IndustrialActionPanel.isChipField(field) || !used.add(field.key())) continue;
            main.addChild(this.slotChip(step, field, false));
        }
    }

    private UIElement rangeModeButton(JsonObject step) {
        String prefix = "gui.simupack.industrial.";
        boolean pointMode = !BuildingJsonDocument.readString(step, IndustrialStepType.BREED_ENTITIES.field("point")).isBlank() || this.pointModeSteps.contains(step);
        String label = Component.translatable((String)(prefix + (pointMode ? "range_mode.point" : "range_mode.structure"))).getString();
        Button button = this.textButton(label + " \u25be", SimuPackLayout.textWidth(label) + 14.0f, null);
        ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
        entries.add(SimuPackMenuBar.Entry.item(Component.translatable((String)(prefix + "range_mode.structure")).getString(), pointMode ? "" : "\u2713", () -> {
            boolean changed = this.pointModeSteps.remove(step);
            if (BuildingJsonDocument.hasField(step, IndustrialStepType.BREED_ENTITIES.field("point"))) {
                changed |= this.edit("step.point", step, target -> target.remove("point"));
            }
            if (changed) {
                this.host.refresh();
            }
        }, () -> true));
        entries.add(SimuPackMenuBar.Entry.item(Component.translatable((String)(prefix + "range_mode.point")).getString(), pointMode ? "\u2713" : "", () -> {
            if (this.pointModeSteps.add(step)) {
                this.host.refresh();
            }
        }, () -> true));
        this.installDropdown(button, () -> entries);
        if (this.readOnly) {
            button.setActive(false);
        }
        return button;
    }

    private static boolean isBlockTagField(JsonObject step, DefinitionField field) {
        if (!"harvest_block_clusters".equals(IndustrialActionPanel.string(step, "type")) || field.kind() != DefinitionField.Kind.TEXT) {
            return false;
        }
        return switch (field.key()) {
            case "targetBlockTag", "attachedBlockTag", "supportBlockTag", "plantItemTag" -> true;
            default -> false;
        };
    }

    private static boolean isWorkAreaRadiusField(JsonObject step, DefinitionField field) {
        return "harvest_block_clusters".equals(IndustrialActionPanel.string(step, "type")) && "radius".equals(field.key());
    }

    private boolean hasDeclaredWorkAreaRadius() {
        if (this.model == null || !this.model.root().has("workArea") || !this.model.root().get("workArea").isJsonObject()) {
            return false;
        }
        JsonObject workArea = this.model.root().getAsJsonObject("workArea");
        if (!(workArea.has("radius") && workArea.get("radius").isJsonPrimitive() && workArea.getAsJsonPrimitive("radius").isNumber())) {
            return false;
        }
        double value = workArea.get("radius").getAsDouble();
        return Double.isFinite(value) && value >= 0.0;
    }

    private UIElement blockTagCell(JsonObject step, DefinitionField field) {
        String value = BuildingJsonDocument.hasField(step, field) ? BuildingJsonDocument.readString(step, field) : "";
        return this.widgets.blockCell(value, (onPick, ignoredAllowTags) -> this.host.openBlockPicker(onPick), picked -> {
            String stored = switch (field.key()) {
                case "targetBlockTag", "attachedBlockTag", "supportBlockTag" -> picked.startsWith("#") ? picked : "#" + picked;
                default -> picked.startsWith("#") ? picked.substring(1) : picked;
            };
            if (this.edit("step." + field.key(), step, target -> BuildingJsonDocument.writeString(target, field, stored))) {
                this.host.refresh();
            }
        });
    }

    private UIElement workAreaRadiusField(JsonObject step) {
        JsonObject workArea;
        UIElement holder = new UIElement();
        holder.layout(layout -> {
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(1.0f);
            layout.flexShrink(0.0f);
        });
        int radius = 0;
        if (this.model != null && this.model.root().has("workArea") && this.model.root().get("workArea").isJsonObject() && (workArea = this.model.root().getAsJsonObject("workArea")).has("radius") && workArea.get("radius").isJsonPrimitive()) {
            radius = Math.max(0, workArea.get("radius").getAsInt());
        }
        TextField text = this.textField();
        text.setNumbersOnlyInt(0, Integer.MAX_VALUE);
        text.setText(Integer.toString(radius), false);
        text.setActive(false);
        text.style(style -> style.tooltips(new Component[]{Component.translatable((String)"gui.simupack.industrial.harvest_radius_tooltip")}));
        text.layout(layout -> {
            layout.width(48.0f);
            layout.height(14.0f);
            layout.flexShrink(0.0f);
        });
        holder.addChild((UIElement)text);
        return holder;
    }

    private static boolean isChipField(DefinitionField field) {
        return switch (field.kind()) {
            case DefinitionField.Kind.POSITIONS, DefinitionField.Kind.ITEM_LIST, DefinitionField.Kind.INPUTS, DefinitionField.Kind.OUTPUTS, DefinitionField.Kind.STEPS -> false;
            default -> true;
        };
    }

    private UIElement slotChip(JsonObject step, DefinitionField field) {
        return this.slotChip(step, field, true);
    }

    private UIElement slotChip(JsonObject step, DefinitionField field, boolean showLabel) {
        UIElement chip = new UIElement().addClass("simupack_islot");
        chip.layout(layout -> {
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(2.0f);
            layout.flexShrink(0.0f);
        });
        if (showLabel) {
            chip.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)field.translationKey()), -4342339));
        }
        if (IndustrialActionPanel.isBlockTagField(step, field)) {
            chip.addChild(this.blockTagCell(step, field));
        } else if (IndustrialActionPanel.isWorkAreaRadiusField(step, field) && this.hasDeclaredWorkAreaRadius()) {
            chip.addChild(this.workAreaRadiusField(step));
        } else {
            switch (field.kind()) {
                case FLAG: {
                    chip.addChild(this.flagField(step, field));
                    break;
                }
                case CHOICE: {
                    String current = BuildingJsonDocument.hasField(step, field) ? BuildingJsonDocument.readString(step, field) : field.fallback();
                    String label = IndustrialActionPanel.choiceLabel(field, current) + " \u25be";
                    Button button = this.textButton(label, SimuPackLayout.textWidth(label) + 10.0f, null);
                    ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
                    for (String choice : field.choices()) {
                        entries.add(SimuPackMenuBar.Entry.item(IndustrialActionPanel.choiceLabel(field, choice), choice.equals(current) ? "\u2713" : "", () -> {
                            if (this.edit("step." + field.key(), step, target -> {
                                BuildingJsonDocument.writeString(target, field, choice);
                                IndustrialActionPanel.materializeSentenceDefaults(target);
                            })) {
                                this.host.refresh();
                            }
                        }, () -> true));
                    }
                    this.installDropdown(button, () -> entries);
                    if (this.readOnly) {
                        button.setActive(false);
                    }
                    chip.addChild((UIElement)button);
                    break;
                }
                case POINT_REF: {
                    chip.addChild(this.refField(step, field, this.model.pointKeys(), "gui.simupack.industrial.no_points"));
                    this.installDefinitionDrop(chip, step, field);
                    break;
                }
                case CONTAINER_REF: {
                    chip.addChild(this.refField(step, field, this.model.containerKeys(), "gui.simupack.industrial.no_containers"));
                    this.installDefinitionDrop(chip, step, field);
                    break;
                }
                case ITEM: {
                    if (IndustrialActionPanel.isAdvancedItemSpec(step)) {
                        chip.addChild(SimuPackLayout.pinnedLabel((Component)Component.literal((String)BuildingJsonDocument.readItemId(step)), -171).setAllowHitTest(false));
                        break;
                    }
                    chip.addChild(this.widgets.itemCell(BuildingJsonDocument.readItemId(step), this.host::openItemPicker, true, picked -> {
                        if (this.edit("step." + field.key(), step, target -> BuildingJsonDocument.writeString(target, field, picked))) {
                            this.host.refresh();
                        }
                    }));
                    break;
                }
                case INTEGER: 
                case DECIMAL: {
                    chip.addChild((UIElement)this.numberField(step, field, 34.0f));
                    break;
                }
                default: {
                    chip.addChild((UIElement)this.numberField(step, field, 72.0f));
                }
            }
        }
        if (field.kind() != DefinitionField.Kind.FLAG && field.kind() != DefinitionField.Kind.CHOICE) {
            UIElement hint = new UIElement();
            hint.layout(layout -> layout.flexShrink(0.0f));
            Runnable update = () -> {
                hint.clearAllChildren();
                if (IndustrialActionPanel.requiresExplicitValue(field) && !BuildingJsonDocument.hasField(step, field)) {
                    hint.addChild(SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.industrial.repeat_count_required"), -171).setAllowHitTest(false));
                }
            };
            update.run();
            this.liveHints.computeIfAbsent(step, ignored -> new ArrayList()).add(update);
            chip.addChild(hint);
        }
        return chip;
    }

    private static boolean materializesDefault(DefinitionField field) {
        if (IndustrialActionPanel.requiresExplicitValue(field)) {
            return false;
        }
        return switch (field.kind()) {
            case DefinitionField.Kind.FLAG, DefinitionField.Kind.CHOICE, DefinitionField.Kind.INTEGER, DefinitionField.Kind.DECIMAL -> {
                if (!field.fallback().isBlank()) {
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    private static boolean requiresExplicitValue(DefinitionField field) {
        return "count".equals(field.key()) && "0".equals(field.fallback());
    }

    private UIElement flagField(JsonObject step, DefinitionField field) {
        boolean current = BuildingJsonDocument.readFlag(step, field);
        String label = IndustrialActionPanel.flagLabel(field, current) + " \u25be";
        Button button = this.textButton(label, SimuPackLayout.textWidth(label) + 10.0f, null);
        ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
        for (boolean value : new boolean[]{false, true}) {
            entries.add(SimuPackMenuBar.Entry.item(IndustrialActionPanel.flagLabel(field, value), value == current ? "\u2713" : "", () -> {
                if (this.edit("step." + field.key(), step, target -> {
                    BuildingJsonDocument.writeFlag(target, field, value);
                    IndustrialActionPanel.materializeSentenceDefaults(target);
                })) {
                    this.host.refresh();
                }
            }, () -> true));
        }
        this.installDropdown(button, () -> entries);
        if (this.readOnly) {
            button.setActive(false);
        }
        return button;
    }

    private static String flagLabel(DefinitionField field, boolean value) {
        String key = switch (field.key()) {
            case "swing" -> {
                if (value) {
                    yield "flag.swing_on";
                }
                yield "flag.swing_off";
            }
            case "consume", "replace", "dropItems" -> {
                if (value) {
                    yield "flag.allow";
                }
                yield "flag.deny";
            }
            case "requireFood" -> {
                if (value) {
                    yield "flag.need";
                }
                yield "flag.need_not";
            }
            case "skipOnTimeout" -> {
                if (value) {
                    yield "flag.skip";
                }
                yield "flag.stay";
            }
            default -> value ? "on" : "off";
        };
        return Component.translatable((String)("gui.simupack.industrial." + key)).getString();
    }

    private static String choiceLabel(DefinitionField field, String value) {
        String key = "gui.simupack.industrial.choice." + value;
        String text = Component.translatable((String)key).getString();
        return text.equals(key) ? value : text;
    }

    private TextField numberField(JsonObject step, DefinitionField field, float width) {
        TextField text = this.textField();
        text.addClass(FIELD_CLASS_PREFIX + field.key());
        if (field.kind() == DefinitionField.Kind.INTEGER) {
            text.setNumbersOnlyInt((int)field.min(), (int)field.max());
        } else if (field.kind() == DefinitionField.Kind.DECIMAL) {
            double max = field.hasMax() ? field.max() : Double.MAX_VALUE;
            text.setNumbersOnlyDouble(field.min(), max);
        } else {
            text.setAnyString();
        }
        boolean present = BuildingJsonDocument.hasField(step, field);
        if (!present && !field.fallback().isBlank()) {
            text.textFieldStyle(style -> style.placeholder((Component)Component.literal((String)field.fallback())).textColor(-1));
        }
        text.setText(present ? BuildingJsonDocument.readString(step, field) : "", false);
        text.setTextResponder(value -> this.writeScalar(step, field, (String)value));
        this.refreshOnBlur(text);
        text.layout(layout -> {
            layout.width(width);
            layout.height(14.0f);
            layout.flexShrink(0.0f);
        });
        return text;
    }

    private UIElement refField(JsonObject step, DefinitionField field, List<String> candidates, String emptyKey) {
        UIElement holder = new UIElement();
        holder.layout(layout -> {
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(1.0f);
            layout.flexShrink(0.0f);
        });
        String[] current = new String[]{BuildingJsonDocument.hasField(step, field) ? BuildingJsonDocument.readString(step, field) : ""};
        TextField text = this.textField();
        text.addClass(FIELD_CLASS_PREFIX + field.key());
        text.setAnyString();
        text.setText(current[0], false);
        text.setTextResponder(value -> {
            current[0] = value;
            this.edit("step." + field.key(), step, target -> BuildingJsonDocument.writeString(target, field, value));
        });
        this.refreshOnBlur(text);
        text.layout(layout -> {
            layout.width(64.0f);
            layout.height(14.0f);
            layout.flexShrink(0.0f);
        });
        holder.addChild((UIElement)text);
        Button pick = this.textButton("\u25be", 14.0f, null);
        ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
        for (String candidate : candidates) {
            entries.add(SimuPackMenuBar.Entry.item(candidate, candidate.equals(current[0]) ? "\u2713" : "", () -> {
                this.edit("step." + field.key(), step, target -> BuildingJsonDocument.writeString(target, field, candidate));
                this.host.refresh();
            }, () -> true));
        }
        if (entries.isEmpty()) {
            entries.add(SimuPackMenuBar.Entry.item(emptyKey, "", () -> {}, () -> false));
        }
        this.installDropdown(pick, () -> entries);
        if (this.readOnly) {
            pick.setActive(false);
        }
        holder.addChild((UIElement)pick);
        return holder;
    }

    private UIElement blockActionRow(IndustrialStepType type, BuildingJsonDocument.StepPath path, boolean selected) {
        if (!selected) {
            UIElement empty = new UIElement();
            SimuPackLayout.pinHeight(empty, 0.0f);
            empty.layout(layout -> layout.widthPercent(100.0f));
            return empty;
        }
        UIElement row = SimuPackLayout.row(2);
        row.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
        });
        row.addChild((UIElement)this.textButton("\u25b2", 14.0f, () -> this.moveStep(path, -1)));
        row.addChild((UIElement)this.textButton("\u25bc", 14.0f, () -> this.moveStep(path, 1)));
        row.addChild((UIElement)this.textButton("\u29c9", 14.0f, () -> this.duplicateStep(path)));
        if (type != null && type.isRepeat()) {
            row.addChild((UIElement)this.textButton("\u21e5", 14.0f, () -> {
                this.insertParent = path;
                this.insertIndex = Integer.MAX_VALUE;
                this.host.refresh();
            }));
        }
        row.addChild((UIElement)this.textButton("\u2715", 14.0f, () -> this.deleteStep(path)));
        row.addChild(SimuPackLayout.spacer(1.0f));
        return row;
    }

    private String typeLabel(IndustrialStepType type, JsonObject step) {
        if (type == null) {
            String raw = IndustrialActionPanel.string(step, "type");
            return raw.isBlank() ? Component.translatable((String)"gui.simupack.industrial.missing_type").getString() : raw;
        }
        return Component.translatable((String)type.translationKey()).getString();
    }

    private String stepLocation(String id, BuildingJsonDocument.StepPath path) {
        int recipeIndex = this.model.recipes().indexOf(this.model.recipe(id));
        StringBuilder location = new StringBuilder("recipes[" + recipeIndex + "]");
        for (int index : path.indices()) {
            location.append(".steps[").append(index).append("]");
        }
        return location.toString();
    }

    @Override
    public void locateIssue(DefinitionIssue issue) {
        Matcher matcher = Pattern.compile("recipes[\\[]([0-9]+)]").matcher(issue.location());
        if (!matcher.find()) {
            return;
        }
        int index = Integer.parseInt(matcher.group(1));
        if (index >= this.model.recipes().size()) {
            return;
        }
        this.selectRecipe(IndustrialActionPanel.string(this.model.recipes().get(index), "id"));
        Matcher steps = Pattern.compile("[.]steps[\\[]([0-9]+)]").matcher(issue.location());
        ArrayList<Integer> path = new ArrayList<Integer>();
        while (steps.find()) {
            path.add(Integer.parseInt(steps.group(1)));
        }
        if (!path.isEmpty()) {
            this.selectedStep = new BuildingJsonDocument.StepPath(path);
            this.focusIssue = true;
        }
        canvasOffsetX = 0.0f;
        canvasOffsetY = 0.0f;
        this.host.refresh();
        this.host.openMiddleTab(MiddleTabKind.INDUSTRIAL_ACTION);
    }

    private void select(String chainRecipe, BuildingJsonDocument.StepPath path) {
        if (chainRecipe != null && !chainRecipe.equals(this.recipeId) && this.model.recipe(chainRecipe) != null) {
            this.selectRecipe(chainRecipe);
        }
        this.selectedStep = path;
        this.insertParent = path.up();
        this.insertIndex = path.leaf() + 1;
        this.host.refresh();
    }

    private void insertType(IndustrialStepType type) {
        if (!this.editable()) {
            return;
        }
        if (this.model.recipe(this.recipeId) == null || !this.chainOrder.contains(this.recipeId)) {
            this.addChain(null, null);
            if (this.model.recipe(this.recipeId) == null || !this.chainOrder.contains(this.recipeId)) {
                return;
            }
        }
        if (this.insertParent == null) {
            this.insertIndex = 0;
        }
        if (!this.wouldChange(draft -> draft.insertStep(this.recipeId, this.insertParent, this.insertIndex, type))) {
            return;
        }
        this.host.pushHistory();
        BuildingJsonDocument.StepPath created = this.model.insertStep(this.recipeId, this.insertParent, this.insertIndex, type);
        if (created == null) {
            this.restructured();
            return;
        }
        this.selectedStep = created;
        this.insertParent = created.up();
        this.insertIndex = created.leaf() + 1;
        this.restructured();
    }

    private void moveStep(BuildingJsonDocument.StepPath path, int delta) {
        BuildingJsonDocument.StepPath moved;
        if (!this.editable()) {
            return;
        }
        if (!this.wouldChange(draft -> draft.moveStep(this.recipeId, path, delta))) {
            return;
        }
        this.host.pushHistory();
        this.selectedStep = moved = this.model.moveStep(this.recipeId, path, delta);
        this.insertParent = moved.up();
        this.insertIndex = moved.leaf() + 1;
        this.restructured();
    }

    private void duplicateStep(BuildingJsonDocument.StepPath path) {
        if (!this.editable()) {
            return;
        }
        if (!this.wouldChange(draft -> draft.duplicateStep(this.recipeId, path))) {
            return;
        }
        this.host.pushHistory();
        BuildingJsonDocument.StepPath copy = this.model.duplicateStep(this.recipeId, path);
        if (copy != null) {
            this.selectedStep = copy;
            this.insertParent = copy.up();
            this.insertIndex = copy.leaf() + 1;
        }
        this.restructured();
    }

    private void deleteStep(BuildingJsonDocument.StepPath path) {
        if (!this.editable()) {
            return;
        }
        if (!this.wouldChange(draft -> draft.removeStep(this.recipeId, path))) {
            return;
        }
        this.host.pushHistory();
        this.model.removeStep(this.recipeId, path);
        this.selectedStep = null;
        this.insertParent = null;
        this.insertIndex = Integer.MAX_VALUE;
        this.restructured();
    }

    private void buildRecipeForm(UIElement parent) {
        JsonObject recipe = this.model.recipe(this.recipeId);
        if (recipe == null) {
            parent.addChild(this.wrapped("gui.simupack.industrial.no_recipe", -4342339));
            return;
        }
        parent.addChild((UIElement)SimuPackLayout.label((Component)Component.translatable((String)"gui.simupack.industrial.recipe_basics"), -1));
        parent.addChild(this.textRow("gui.simupack.industrial.recipe_id", IndustrialActionPanel.string(recipe, "id"), value -> {
            if (value == null || value.isBlank() || !value.equals(this.recipeId) && this.model.recipe((String)value) != null) {
                return;
            }
            String oldId = this.recipeId;
            if (oldId.equals(value)) {
                return;
            }
            this.host.recordEdit("recipe.id:" + System.identityHashCode(recipe));
            if (this.model.renameRecipe(oldId, (String)value)) {
                this.recipeId = value;
                this.persist();
                this.host.commit();
            }
        }));
        parent.addChild(this.textRow("gui.simupack.industrial.recipe_name", IndustrialActionPanel.string(recipe, "name"), value -> this.edit("recipe.name", recipe, target -> IndustrialActionPanel.setOrRemove(target, "name", value))));
        parent.addChild(this.textRow("gui.simupack.industrial.recipe_held", IndustrialActionPanel.string(recipe, "heldItem"), value -> this.edit("recipe.heldItem", recipe, target -> IndustrialActionPanel.setOrRemove(target, "heldItem", value))));
        parent.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.industrial.recipe_name_note"), -4342339));
        BuildingJsonDocument.InputOwner owner = BuildingJsonDocument.InputOwner.ofRecipe(this.recipeId);
        this.buildInputsSection(parent, owner);
        this.buildOutputsSection(parent, owner);
    }

    private void writeScalar(JsonObject step, DefinitionField field, String value) {
        String effective = (value == null || value.isBlank()) && IndustrialActionPanel.materializesDefault(field) ? field.fallback() : value;
        this.edit("step." + field.key(), step, target -> {
            JsonEditTransaction.writeScalar(target, field, effective);
            IndustrialActionPanel.materializeSentenceDefaults(target);
        });
    }

    private static void materializeSentenceDefaults(JsonObject step) {
        IndustrialStepType type = IndustrialStepType.byRawType(IndustrialActionPanel.string(step, "type"));
        if (type == null) {
            return;
        }
        String template = Component.translatable((String)("gui.simupack.industrial.sentence." + type.canonical())).getString();
        for (IndustrialSentence.Part part : IndustrialSentence.parse(template)) {
            DefinitionField field;
            if (part.field() == null || (field = type.field(part.field())) == null || !IndustrialActionPanel.materializesDefault(field) || BuildingJsonDocument.hasField(step, field)) continue;
            switch (field.kind()) {
                case INTEGER: 
                case DECIMAL: {
                    BuildingJsonDocument.writeNumber(step, field, Double.parseDouble(field.fallback()));
                    break;
                }
                case FLAG: {
                    BuildingJsonDocument.writeFlag(step, field, Boolean.parseBoolean(field.fallback()));
                    break;
                }
                case CHOICE: {
                    BuildingJsonDocument.writeString(step, field, field.fallback());
                    break;
                }
            }
        }
    }

    private UIElement itemListRow(JsonObject step, DefinitionField field) {
        JsonArray array;
        UIElement column = SimuPackLayout.column(1);
        column.addChild((UIElement)SimuPackLayout.label((Component)Component.translatable((String)field.translationKey()), -4342339));
        String effective = BuildingJsonDocument.effectiveKey(step, field);
        JsonArray jsonArray = array = step.has(effective) && step.get(effective).isJsonArray() ? step.getAsJsonArray(effective) : null;
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject() || !IndustrialActionPanel.isAdvancedItemSpec(element.getAsJsonObject())) continue;
                return column;
            }
        }
        if (array == null || array.isEmpty()) {
            column.addChild(this.wrapped("gui.simupack.industrial.item_list_empty", -4342339));
        } else {
            JsonArray list = array;
            for (int i = 0; i < list.size(); ++i) {
                int index = i;
                UIElement row = SimuPackLayout.row(2);
                JsonElement entry = list.get(index);
                if (entry.isJsonObject() && IndustrialActionPanel.isAdvancedItemSpec(entry.getAsJsonObject())) {
                    row.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)IndustrialActionPanel.itemListEntryId(list.get(index))), -171));
                } else {
                    row.addChild(this.widgets.itemCell(IndustrialActionPanel.itemListEntryId(list.get(index)), this.host::openItemPicker, true, picked -> {
                        if (this.edit("step." + field.key() + "." + index, step, target -> target.getAsJsonArray(effective).set(index, (JsonElement)new JsonPrimitive(picked)))) {
                            this.host.refresh();
                        }
                    }));
                }
                row.addChild((UIElement)this.textButton("\u2715", 14.0f, () -> {
                    this.host.pushHistory();
                    list.remove(index);
                    this.restructured();
                }));
                column.addChild(row);
            }
        }
        if (!this.readOnly) {
            column.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.add_item").getString(), 0.0f, () -> {
                this.host.pushHistory();
                JsonArray target = step.has(effective) && step.get(effective).isJsonArray() ? step.getAsJsonArray(effective) : new JsonArray();
                if (!step.has(effective)) {
                    step.add(effective, (JsonElement)target);
                }
                target.add((JsonElement)new JsonPrimitive("minecraft:stone"));
                this.restructured();
            }));
        }
        return column;
    }

    private UIElement positionsRow(JsonObject step, DefinitionField field) {
        UIElement column = SimuPackLayout.column(1);
        column.addChild((UIElement)SimuPackLayout.label((Component)Component.translatable((String)field.translationKey()), -4342339));
        ArrayList<String> lines = new ArrayList<String>();
        for (JsonArray position : BuildingJsonDocument.positionList(step)) {
            if (!BuildingJsonDocument.validPosition((JsonElement)position)) continue;
            lines.add(position.get(0).getAsInt() + "," + position.get(1).getAsInt() + "," + position.get(2).getAsInt());
        }
        if (lines.isEmpty()) {
            column.addChild(this.wrapped("gui.simupack.industrial.positions_hint", -4342339));
        }
        TextArea area = this.readOnly ? new SimuPackLayout.ReadOnlyTextArea() : new TextArea();
        area.setLines(lines);
        area.layout(layout -> {
            layout.widthPercent(100.0f);
            layout.height(46.0f);
            layout.minWidth(0.0f);
            layout.flexShrink(0.0f);
        });
        area.setLinesResponder(updated -> {
            JsonArray positions = new JsonArray();
            for (String line : updated) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split("[,\\s]+");
                if (parts.length != 3) {
                    return;
                }
                try {
                    JsonArray position = new JsonArray();
                    position.add((Number)Integer.parseInt(parts[0]));
                    position.add((Number)Integer.parseInt(parts[1]));
                    position.add((Number)Integer.parseInt(parts[2]));
                    positions.add((JsonElement)position);
                }
                catch (NumberFormatException ignored) {
                    return;
                }
            }
            this.edit("step.positions", step, target -> {
                BuildingJsonDocument.clearField(target, field);
                if (!positions.isEmpty()) {
                    target.add("positions", (JsonElement)positions.deepCopy());
                }
            });
        });
        column.addChild((UIElement)area);
        return column;
    }

    private void buildInputsSection(UIElement parent, BuildingJsonDocument.InputOwner owner) {
        IndustrialStepType ownerType;
        JsonObject object = this.model.ownerObject(owner);
        if (object == null) {
            return;
        }
        JsonArray array = this.model.inputArray(object, "", false);
        parent.addChild((UIElement)SimuPackLayout.label((Component)Component.translatable((String)"gui.simupack.industrial.inputs"), -1));
        if (array == null || array.isEmpty()) {
            boolean inherits;
            ownerType = IndustrialStepType.byRawType(IndustrialActionPanel.string(object, "type"));
            boolean bl = inherits = ownerType == IndustrialStepType.CRAFT_RECIPE || ownerType == IndustrialStepType.CRAFT_AVAILABLE_RECIPE || ownerType == IndustrialStepType.REAL_MACHINE_RECIPE;
            String key = owner.stepPath() != null && inherits ? (object.has("inputs") ? "empty_override" : "inherit_inputs") : (ownerType == IndustrialStepType.FILL_ITEM ? "fill_candidates" : "inputs_empty");
            parent.addChild(this.wrapped("gui.simupack.industrial." + key, -4342339));
        } else {
            this.buildInputNodes(parent, owner, array, null, "");
        }
        if (!this.readOnly) {
            ownerType = IndustrialStepType.byRawType(IndustrialActionPanel.string(object, "type"));
            if (owner.stepPath() != null && ownerType != IndustrialStepType.FILL_ITEM && object.has("inputs")) {
                parent.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.restore_inheritance").getString(), 0.0f, () -> {
                    if (this.edit("inputs.inherit", object, draft -> draft.remove("inputs"))) {
                        this.host.refresh();
                    }
                }));
            }
            UIElement row = SimuPackLayout.row(2);
            row.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.add_condition").getString(), 0.0f, () -> this.addInput(owner, null, false)));
            row.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.add_group").getString(), 0.0f, () -> this.addInput(owner, null, true)));
            parent.addChild(row);
        }
    }

    private void buildInputNodes(UIElement parent, BuildingJsonDocument.InputOwner owner, JsonArray array, BuildingJsonDocument.InputPath parentPath, String arrayKey) {
        for (int i = 0; i < array.size(); ++i) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject node = element.getAsJsonObject();
            BuildingJsonDocument.InputPath path = parentPath == null ? BuildingJsonDocument.InputPath.root(i) : parentPath.down(arrayKey, i);
            UIElement box = SimuPackLayout.column(1);
            box.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.minWidth(0.0f);
                layout.flexDirection(FlexDirection.COLUMN);
                layout.alignItems(AlignItems.STRETCH);
                layout.gapAll(1.0f);
                layout.marginLeft(parentPath == null ? 0.0f : 6.0f);
                layout.flexShrink(0.0f);
            });
            if (BuildingJsonDocument.isGroup(node)) {
                boolean any = BuildingJsonDocument.isAnyGroup(node);
                UIElement head = SimuPackLayout.row(2);
                head.addChild((UIElement)this.textButton(Component.translatable((String)(any ? "gui.simupack.industrial.any" : "gui.simupack.industrial.all")).getString(), 0.0f, () -> {
                    this.host.pushHistory();
                    this.model.setInputGroupAny(owner, path, !any);
                    this.restructured();
                }));
                head.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.add_inside").getString(), 0.0f, () -> this.addInput(owner, path, false)));
                head.addChild((UIElement)this.textButton("\u2715", 14.0f, () -> this.removeInput(owner, path)));
                box.addChild(head);
                String childKey = any ? "any" : "all";
                JsonArray children = node.has(childKey) && node.get(childKey).isJsonArray() ? node.getAsJsonArray(childKey) : new JsonArray();
                this.buildInputNodes(box, owner, children, path, childKey);
            } else {
                UIElement head = SimuPackLayout.row(2);
                if (IndustrialActionPanel.isAdvancedItemSpec(node)) {
                    head.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)(String.valueOf(ITEM_FIELD) + ": " + IndustrialActionPanel.itemListEntryId((JsonElement)node))), -171));
                } else {
                    head.addChild(this.widgets.itemCell(IndustrialActionPanel.itemListEntryId((JsonElement)node), this.host::openItemPicker, true, picked -> {
                        if (this.edit("input.item", node, target -> BuildingJsonDocument.writeString(target, ITEM_FIELD, picked))) {
                            this.host.refresh();
                        }
                    }));
                }
                head.addChild((UIElement)this.textButton("\u2715", 14.0f, () -> this.removeInput(owner, path)));
                box.addChild(head);
                UIElement counts = SimuPackLayout.row(2);
                counts.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.industrial.input_count"), -4342339));
                TextField count = this.textField();
                count.setNumbersOnlyInt(1, Integer.MAX_VALUE);
                count.setText(node.has("count") ? IndustrialActionPanel.string(node, "count") : "1", false);
                count.setTextResponder(value -> this.edit("input.count", node, target -> JsonEditTransaction.writeScalar(target, DefinitionField.integer("count", 1, 1, Integer.MAX_VALUE, new String[0]), value)));
                count.layout(layout -> {
                    layout.width(32.0f);
                    layout.height(14.0f);
                    layout.flexShrink(0.0f);
                });
                counts.addChild((UIElement)count);
                boolean consumeValue = BuildingJsonDocument.readFlag(node, DefinitionField.flag("consume", true, new String[0]));
                Button consume = this.textButton(IndustrialActionPanel.consumeText(consumeValue), 0.0f, null);
                consume.layout(layout -> {
                    layout.flex(1.0f);
                    layout.height(14.0f);
                    layout.minWidth(0.0f);
                });
                consume.setOnClick(event -> {
                    boolean next = !BuildingJsonDocument.readFlag(node, DefinitionField.flag("consume", true, new String[0]));
                    this.edit("input.consume", node, target -> target.addProperty("consume", Boolean.valueOf(next)));
                    consume.setText((Component)Component.literal((String)IndustrialActionPanel.consumeText(next)));
                });
                if (this.readOnly) {
                    consume.setActive(false);
                }
                counts.addChild((UIElement)consume);
                box.addChild(counts);
            }
            parent.addChild(box);
        }
    }

    private static String consumeText(boolean consume) {
        return (consume ? "\u2611 " : "\u2610 ") + Component.translatable((String)"gui.simupack.industrial.consume").getString();
    }

    private void addInput(BuildingJsonDocument.InputOwner owner, BuildingJsonDocument.InputPath parent, boolean group) {
        if (!this.editable()) {
            return;
        }
        this.host.pushHistory();
        if (group) {
            this.model.addInputGroup(owner, parent, true);
        } else {
            this.model.addInputItem(owner, parent);
        }
        this.restructured();
    }

    private void removeInput(BuildingJsonDocument.InputOwner owner, BuildingJsonDocument.InputPath path) {
        if (!this.editable()) {
            return;
        }
        this.host.pushHistory();
        this.model.removeInput(owner, path);
        this.restructured();
    }

    private void buildOutputsSection(UIElement parent, BuildingJsonDocument.InputOwner owner) {
        JsonArray array = this.model.outputArray(owner, false);
        JsonObject outputOwner = this.model.ownerObject(owner);
        if (!this.readOnly && owner.stepPath() != null && outputOwner != null && outputOwner.has("outputs")) {
            parent.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.restore_inheritance").getString(), 0.0f, () -> {
                if (this.edit("outputs.inherit", outputOwner, draft -> draft.remove("outputs"))) {
                    this.host.refresh();
                }
            }));
        }
        parent.addChild((UIElement)SimuPackLayout.label((Component)Component.translatable((String)"gui.simupack.industrial.outputs"), -1));
        if (array == null || array.isEmpty()) {
            JsonObject object = this.model.ownerObject(owner);
            String key = owner.stepPath() != null ? (object != null && object.has("outputs") ? "empty_override" : "inherit_outputs") : "outputs_empty";
            parent.addChild(this.wrapped("gui.simupack.industrial." + key, -4342339));
        } else {
            for (int i = 0; i < array.size(); ++i) {
                JsonElement element = array.get(i);
                if (!element.isJsonObject()) continue;
                int index = i;
                JsonObject output = element.getAsJsonObject();
                UIElement box = SimuPackLayout.column(1);
                UIElement head = SimuPackLayout.row(2);
                if (IndustrialActionPanel.isAdvancedItemSpec(output)) {
                    head.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)BuildingJsonDocument.readItemId(output)), -171));
                } else {
                    head.addChild(this.widgets.itemCell(BuildingJsonDocument.readItemId(output), this.host::openItemPicker, true, picked -> {
                        if (this.edit("output.item", output, target -> IndustrialActionPanel.setOrRemove(target, "item", picked))) {
                            this.host.refresh();
                        }
                    }));
                }
                head.addChild((UIElement)this.textButton("\u2715", 14.0f, () -> {
                    this.host.pushHistory();
                    this.model.removeOutput(owner, index);
                    this.restructured();
                }));
                box.addChild(head);
                UIElement numbers = SimuPackLayout.row(2);
                numbers.addChild(this.outputNumber("baseAmount", output, 1.0));
                numbers.addChild(this.outputNumber("randomRange", output, 0.0));
                numbers.addChild(this.outputNumber("probability", output, 1.0));
                box.addChild(numbers);
                parent.addChild(box);
            }
        }
        if (!this.readOnly) {
            parent.addChild((UIElement)this.textButton(Component.translatable((String)"gui.simupack.industrial.add_output").getString(), 0.0f, () -> {
                if (!this.editable()) {
                    return;
                }
                this.host.pushHistory();
                this.model.addOutput(owner);
                this.restructured();
            }));
        }
    }

    private UIElement outputNumber(String key, JsonObject output, double fallback) {
        UIElement cell = SimuPackLayout.row(1);
        cell.layout(layout -> {
            layout.flex(1.0f);
            layout.minWidth(0.0f);
            layout.height(14.0f);
        });
        cell.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)("gui.simupack.industrial.output." + key)), -4342339));
        TextField field = this.textField();
        if ("probability".equals(key)) {
            field.setNumbersOnlyDouble(0.0, 1.0);
        } else {
            field.setNumbersOnlyInt("baseAmount".equals(key) ? 1 : 0, Integer.MAX_VALUE);
        }
        field.setText(output.has(key) ? IndustrialActionPanel.string(output, key) : IndustrialActionPanel.trimNumber(fallback), false);
        field.setTextResponder(value -> {
            DefinitionField spec = "probability".equals(key) ? DefinitionField.decimal(key, fallback, 0.0, 1.0, new String[0]) : DefinitionField.integer(key, (int)fallback, "baseAmount".equals(key) ? 1 : 0, Integer.MAX_VALUE, new String[0]);
            this.edit("output." + key, output, target -> JsonEditTransaction.writeScalar(target, spec, value));
        });
        field.layout(layout -> {
            layout.flex(1.0f);
            layout.height(14.0f);
            layout.minWidth(0.0f);
        });
        cell.addChild((UIElement)field);
        return cell;
    }

    private static void setOrRemove(JsonObject object, String key, String value) {
        if (value == null || value.isBlank()) {
            object.remove(key);
        } else {
            object.addProperty(key, value);
        }
    }

    private static boolean isAdvancedItemSpec(JsonObject node) {
        if (node == null) {
            return false;
        }
        for (String key : IndustrialStepType.ITEM_SPEC_KEYS) {
            if ("item".equals(key) || !node.has(key)) continue;
            return true;
        }
        return false;
    }

    private static String itemListEntryId(JsonElement element) {
        if (element == null) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return element.isJsonObject() ? BuildingJsonDocument.readItemId(element.getAsJsonObject()) : "";
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        }
        catch (RuntimeException exception) {
            return "";
        }
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long)value) : Double.toString(value);
    }

    @Override
    public boolean supports(String category) {
        return "industry".equals(category);
    }

    @Override
    protected void resetViewState() {
        this.recipeId = "";
        this.selectedStep = null;
        this.insertParent = null;
        this.insertIndex = Integer.MAX_VALUE;
        this.expandedOptions.clear();
        this.pointModeSteps.clear();
    }

    @Override
    protected void afterModelParsed(List<JsonObject> recipes) {
        this.readLayout();
        if (!recipes.isEmpty()) {
            this.recipeId = this.model.selectedRecipeId();
            this.model.selectRecipe(this.recipeId);
        }
    }

    @Override
    protected String editScope() {
        return this.recipeId;
    }

    private void refreshOnBlur(TextField text) {
        if (text instanceof EditorWidgets.CommitOnBlurField) {
            EditorWidgets.CommitOnBlurField editableField = (EditorWidgets.CommitOnBlurField)text;
            editableField.setBlurAction(this.host::refresh);
        }
    }

    private static boolean isInlineTextEdit(String tag, JsonObject step) {
        DefinitionField field;
        if (tag == null || step == null || !tag.startsWith("step.")) {
            return false;
        }
        String key = tag.substring("step.".length());
        if (key.isBlank() || key.indexOf(46) >= 0) {
            return false;
        }
        IndustrialStepType type = IndustrialStepType.byRawType(IndustrialActionPanel.string(step, "type"));
        DefinitionField definitionField = field = type == null ? null : type.field(key);
        if (field == null) {
            return false;
        }
        return switch (field.kind()) {
            case DefinitionField.Kind.POINT_REF, DefinitionField.Kind.CONTAINER_REF, DefinitionField.Kind.INTEGER, DefinitionField.Kind.DECIMAL, DefinitionField.Kind.TEXT -> true;
            default -> false;
        };
    }

    @Override
    protected void afterEdit(String tag, JsonObject target) {
        for (List<Runnable> hints : this.liveHints.values()) {
            for (Runnable hint : hints) {
                hint.run();
            }
        }
        if (!tag.startsWith("step.") || !this.positionSentences.containsKey(target)) {
            return;
        }
        if (IndustrialActionPanel.isInlineTextEdit(tag, target)) {
            return;
        }
        if (!"step.positions".equals(tag) && !"step.positions.clear".equals(tag)) {
            this.focusAfterRefresh.put(target, tag.substring("step.".length()));
        }
        this.host.refresh();
    }

    @Override
    public boolean hasValidation() {
        return this.model != null;
    }

    @Override
    public List<DefinitionIssue> validateForDisplay() {
        return this.model == null ? List.of() : this.model.validate(this.bounds);
    }

    @Override
    public boolean canLocateIssue(DefinitionIssue issue) {
        return issue.location().startsWith("recipes[");
    }

    static {
        paletteCategory = IndustrialStepCategory.FLOW;
        blockZoom = 1.0f;
        ITEM_FIELD = DefinitionField.item(new String[0]);
    }

    public record PointEntry(String name, boolean container) {
    }

    private static final class ChainMove {
        final String recipe;
        final UIElement column;
        final float[] position;
        float pressX;
        float pressY;
        float startX;
        float startY;
        boolean moving;

        ChainMove(String recipe, UIElement column, float[] position) {
            this.recipe = recipe;
            this.column = column;
            this.position = position;
        }
    }

    private record DragPayload(IndustrialStepType type, BuildingJsonDocument.StepPath path, String definition, boolean containerDefinition, boolean start, String chain) {
        static DragPayload ofType(IndustrialStepType type) {
            return new DragPayload(type, null, null, false, false, null);
        }

        static DragPayload ofStep(BuildingJsonDocument.StepPath path) {
            return new DragPayload(null, path, null, false, false, null);
        }

        static DragPayload ofDefinition(String name, boolean container) {
            return new DragPayload(null, null, name, container, false, null);
        }

        static DragPayload ofStart() {
            return new DragPayload(null, null, null, false, true, null);
        }

        static DragPayload ofChain(String recipe) {
            return new DragPayload(null, null, null, false, false, recipe);
        }

        boolean isNew() {
            return this.type != null;
        }

        boolean isDefinition() {
            return this.definition != null;
        }

        boolean isStart() {
            return this.start;
        }
    }

    private static final class DragState {
        final UIElement element;
        final Supplier<DragPayload> payload;
        final Supplier<Integer> color;
        final boolean[] dragged = new boolean[1];
        float pressX;
        float pressY;
        boolean started;

        DragState(UIElement element, Supplier<DragPayload> payload, Supplier<Integer> color) {
            this.element = element;
            this.payload = payload;
            this.color = color;
        }
    }
}

