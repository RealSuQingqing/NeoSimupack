/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Pivot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.SplitView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TabView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TreeList;
import com.lowdragmc.lowdraglib2.gui.util.ITreeNode;
import com.mojang.blaze3d.platform.Window;
import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.client.SimuPackClientSettings;
import com.rstech.simupack.client.screen.EditorTreeNode;
import com.rstech.simupack.client.selection.BuildingSelectionSession;
import com.rstech.simupack.client.ui.HelpDialog;
import com.rstech.simupack.client.ui.SimuPackDialog;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackMenuBar;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.client.ui.SimuPackWindowFrame;
import com.rstech.simupack.client.ui.editor.CommercialTradePanel;
import com.rstech.simupack.client.ui.editor.DomainPanel;
import com.rstech.simupack.client.ui.editor.EditorDialog;
import com.rstech.simupack.client.ui.editor.EditorHost;
import com.rstech.simupack.client.ui.editor.EditorWidgets;
import com.rstech.simupack.client.ui.editor.IndustrialActionPanel;
import com.rstech.simupack.client.ui.editor.IndustrialSimulationPanel;
import com.rstech.simupack.client.ui.editor.ItemPicker;
import com.rstech.simupack.client.ui.editor.ItemRegistry;
import com.rstech.simupack.client.ui.editor.MiddleTabKind;
import com.rstech.simupack.client.ui.editor.PickScope;
import com.rstech.simupack.client.ui.editor.PointPicker;
import com.rstech.simupack.client.ui.editor.ResidentialArrayPanel;
import com.rstech.simupack.client.ui.editor.ResidentialUnitPanel;
import com.rstech.simupack.client.ui.preview.StructurePreviewLoader;
import com.rstech.simupack.client.ui.preview.StructurePreviewScene;
import com.rstech.simupack.client.ui.preview.UnitBoxVisual;
import com.rstech.simupack.creation.CreationBuilding;
import com.rstech.simupack.creation.CreationCaptureService;
import com.rstech.simupack.creation.CreationProject;
import com.rstech.simupack.creation.CreationWorkspace;
import com.rstech.simupack.creation.InstalledPackReader;
import com.rstech.simupack.creation.StructureImportService;
import com.rstech.simupack.creation.StructureSizeReader;
import com.rstech.simupack.creation.commercial.CommercialContainersModel;
import com.rstech.simupack.creation.commercial.CommercialOfferModel;
import com.rstech.simupack.creation.json.BuildingJsonDocument;
import com.rstech.simupack.creation.json.DefinitionIssue;
import com.rstech.simupack.creation.residential.CreationUnit;
import com.rstech.simupack.pack.BuildingPack;
import com.rstech.simupack.pack.BuildingPackManager;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector2f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public final class SingleplayerEditorScreen
extends ModularUIScreen {
    private static float uiScale = SimuPackClientSettings.editorUiScale();
    private final Screen parent;
    private final Session session;

    public SingleplayerEditorScreen(Screen parent) {
        this(parent, Session.create(parent, () -> Minecraft.getInstance().setScreen(parent)));
    }

    private SingleplayerEditorScreen(Screen parent, Session session) {
        super(session.modularUI, (Component)Component.translatable((String)"gui.simupack.editor.title"));
        this.parent = parent;
        this.session = session;
        session.refreshAll(false);
    }

    public void onFilesDrop(List<Path> paths) {
        this.session.importDropped(paths);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean shift;
        if (keyCode == 257 && this.session.handleEnter()) {
            return true;
        }
        boolean ctrl = (modifiers & 2) != 0;
        boolean bl = shift = (modifiers & 1) != 0;
        if (ctrl && keyCode == 83) {
            this.session.saveProject();
            return true;
        }
        if (ctrl && keyCode == 90) {
            if (shift) {
                this.session.redo();
            } else {
                this.session.undo();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public void init() {
        super.init();
        this.session.onScreenShown();
    }

    public void onClose() {
        this.session.flushAutoSave(true);
        Minecraft.getInstance().setScreen(this.parent);
    }

    public void tick() {
        super.tick();
        this.session.flushAutoSave(false);
    }

    private static final class Session {
        private static final int TOOLBAR_HEIGHT = 20;
        private static final int STATUS_BAR_HEIGHT = 20;
        private static final int BUTTON_HEIGHT = 16;
        private static final int GAP = 4;
        private static final int COLUMN_GAP = 3;
        private static final float FIELD_LABEL_PERCENT = 34.0f;
        private static final int MAX_HISTORY = 32;
        private static final long EDIT_COALESCE_MS = 1200L;
        private final ModularUI modularUI;
        private final Screen parent;
        private CreationProject project;
        private CreationBuilding selectedBuilding;
        private String pendingProjectId;
        private String pendingCategory;
        private String pendingBuildingId;
        private String sourceKind = "workspace";
        private String sourceId;
        private InstalledPackReader.Result installedResult;
        private String notice = "";
        private final UIElement treeHost;
        private final UIElement previewHint;
        private StructurePreviewScene previewScene;
        private final UIElement materialHost;
        private final UIElement actionHost;
        private final UIElement simulationHost;
        private final UIElement tradeHost;
        private final UIElement statusHost;
        private final Label statusBadge;
        private final UIElement[] tabHosts = new UIElement[2];
        private SimuPackMenuBar menuBar;
        private TabView middleTabs;
        private Tab middlePreviewTab;
        private Tab middleMaterialTab;
        private Tab settingsTab;
        private Tab middleActionTab;
        private Tab middleSimulationTab;
        private IndustrialSimulationPanel industrialSimulationPanel;
        private Tab middleTradeTab;
        private Tab middleResidentialArrayTab;
        private Tab middlePickTab;
        private final PointPicker picker = new PointPicker(new PickHost());
        private String reselectName;
        private boolean reselectContainer;
        private String reselectMode;
        private PickScope pickScope;
        private List<BlockPos> reselectInitial = List.of();
        private boolean dialogOpen;
        private UIElement currentOverlay;
        private Map<BlockPos, BlockState> pickedStructureStates = Map.of();
        private DomainPanel panel;
        private String panelCategory = "";
        private IndustrialActionPanel industrialPanel;
        private CommercialTradePanel tradePanel;
        private ResidentialArrayPanel residentialArrayPanel;
        private UIElement overlayHost;
        private final Deque<String> undoStack = new ArrayDeque<String>();
        private final Deque<String> redoStack = new ArrayDeque<String>();
        private String lastEditTag = "";
        private long lastEditAt;
        private static float leftPanePercent = 26.0f;
        private static float middlePanePercent = 54.3f;
        private static float leftPanePercentMemory = 26.0f;
        private static float middlePanePercentMemory = 54.3f;
        private static boolean leftPaneCollapsed;
        private static boolean rightPaneCollapsed;
        private static String memoryProjectId;
        private static String memoryCategory;
        private static String memoryBuildingId;
        private static boolean memoryActionTabOpen;
        private static boolean memoryTradeTabOpen;
        private static final long AUTO_SAVE_IDLE_MS = 700L;
        private boolean autoSavePending;
        private boolean initialPreviewPending = true;
        private SplitView.Horizontal outerSplit;
        private SplitView.Horizontal innerSplit;
        private ScrollerView treeScroller;
        private Button packSelectorButton;
        private Button packEditButton;
        private static final float DIVIDER_GRAB = 4.0f;
        private static final float PANE_HANDLE_WIDTH = 12.0f;
        private static final float PANE_HANDLE_HEIGHT = 16.0f;
        private Button leftHandle;
        private Button rightHandle;
        private static final TagKey<Block> CHESTS_TAG;
        private static final int MATERIAL_ROW_LIMIT = 32;
        private CompoundTag structureTag;
        private String structureTagSource = "";
        private int[] structureTagSize;
        private String sceneShownSource = "";

        private boolean readOnly() {
            return "installed".equals(this.sourceKind);
        }

        private List<InstalledPackReader.Entry> installedEntries() {
            return this.installedResult == null ? List.of() : this.installedResult.entries();
        }

        private UIElement packSelector() {
            List<PackChoice> candidates = this.packChoices();
            if (candidates.isEmpty()) {
                return SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.no_project"), -4342339);
            }
            PackChoice current = this.currentChoice(candidates);
            this.packSelectorButton = new Button().setText((Component)Component.literal((String)this.selectorText(current)));
            this.packSelectorButton.addClass("simupack_menu_trigger");
            this.packSelectorButton.layout(layout -> {
                layout.flex(1.0f);
                layout.height(18.0f);
                layout.minWidth(0.0f);
                layout.flexShrink(1.0f);
            });
            this.packSelectorButton.setOnClick(event -> this.openPackMenu((UIElement)this.packSelectorButton));
            UIElement frame = new UIElement();
            frame.layout(layout -> {
                layout.flex(1.0f);
                layout.height(22.0f);
                layout.minWidth(0.0f);
                layout.paddingAll(2.0f);
                layout.flexShrink(1.0f);
            });
            frame.style(style -> style.background((IGuiTexture)new GuiTextureGroup(new IGuiTexture[]{new ColorRectTexture(-16777216), new ColorBorderTexture(-2039584, 2)})));
            frame.addChild((UIElement)this.packSelectorButton);
            this.packEditButton = new Button().noText();
            this.packEditButton.addPreIcon((IGuiTexture)Icons.EDIT_FILE);
            this.packEditButton.layout(layout -> {
                layout.width(22.0f);
                layout.height(22.0f);
                layout.flexShrink(0.0f);
            });
            this.packEditButton.setActive(!this.readOnly());
            this.packEditButton.setOnClick(event -> {
                if (!this.readOnly()) {
                    this.openProjectDialog(this.project);
                }
            });
            UIElement row = SimuPackLayout.row(4);
            row.addChild(frame);
            row.addChild((UIElement)this.packEditButton);
            return SimuPackLayout.shrinkable(row);
        }

        private List<PackChoice> packChoices() {
            ArrayList<PackChoice> candidates = new ArrayList<PackChoice>();
            for (String id : CreationWorkspace.listProjects()) {
                candidates.add(new PackChoice("workspace", id));
            }
            for (BuildingPack pack : BuildingPackManager.packs()) {
                candidates.add(new PackChoice("installed", pack.id()));
            }
            return candidates;
        }

        private PackChoice currentChoice(List<PackChoice> candidates) {
            return candidates.stream().filter(choice -> choice.kind().equals(this.sourceKind) && choice.id().equals(this.sourceId)).findFirst().orElseGet(() -> (PackChoice)candidates.get(0));
        }

        private String selectorText(PackChoice choice) {
            if (choice == null) {
                return "";
            }
            Object name = choice.id();
            if (((String)name).length() > 10) {
                name = ((String)name).substring(0, 9) + "\u2026";
            }
            return (String)name + "   \u25be";
        }

        private void openPackMenu(UIElement anchor) {
            List<PackChoice> candidates;
            if (this.menuBar != null && this.overlayHost != null && !(candidates = this.packChoices()).isEmpty()) {
                ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
                boolean hasWorkspace = candidates.stream().anyMatch(choice -> "workspace".equals(choice.kind()));
                boolean hasInstalled = candidates.stream().anyMatch(choice -> "installed".equals(choice.kind()));
                if (hasWorkspace) {
                    entries.add(this.headerEntry("gui.simupack.editor.group_workspace"));
                }
                for (PackChoice choice2 : candidates) {
                    if ("installed".equals(choice2.kind())) continue;
                    entries.add(this.choiceEntry(choice2));
                }
                if (hasWorkspace && hasInstalled) {
                    entries.add(SimuPackMenuBar.Entry.separator());
                }
                if (hasInstalled) {
                    entries.add(this.headerEntry("gui.simupack.editor.group_installed"));
                }
                for (PackChoice choicex : candidates) {
                    if (!"installed".equals(choicex.kind())) continue;
                    entries.add(this.choiceEntry(choicex));
                }
                float localX = anchor.getPositionX() - this.overlayHost.getPositionX();
                float localY = anchor.getPositionY() + anchor.getSizeHeight() - this.overlayHost.getPositionY();
                this.menuBar.openContextMenu(localX, localY, entries);
            }
        }

        private SimuPackMenuBar.Entry headerEntry(String text) {
            return SimuPackMenuBar.Entry.item(text, "", null, () -> false);
        }

        private SimuPackMenuBar.Entry choiceEntry(PackChoice choice) {
            boolean selected = choice.kind().equals(this.sourceKind) && choice.id().equals(this.sourceId);
            return SimuPackMenuBar.Entry.item(choice.id(), selected ? "\u2713" : "", () -> this.switchSource(choice), () -> true);
        }

        private void switchSource(PackChoice choice) {
            if (choice != null) {
                this.sourceKind = choice.kind();
                this.sourceId = choice.id();
                if (this.packSelectorButton != null) {
                    this.packSelectorButton.setText((Component)Component.literal((String)this.selectorText(choice)));
                }
                if (this.packEditButton != null) {
                    this.packEditButton.setActive(!choice.readOnly());
                }
                this.selectedBuilding = null;
                this.installedResult = null;
                if (choice.readOnly()) {
                    BuildingPackManager.packs().stream().filter(pack -> pack.id().equals(choice.id())).findFirst().ifPresent(pack -> {
                        this.installedResult = InstalledPackReader.listBuildings(pack);
                    });
                } else {
                    this.project = CreationWorkspace.load(choice.id());
                }
                this.refreshTree();
                this.refreshPreview();
                this.refreshInspector();
                this.refreshStatus();
            }
        }

        private Session(Screen parent, Runnable closeAction) {
            this.parent = parent;
            this.treeHost = SimuPackLayout.column(3);
            this.previewHint = SimuPackLayout.column(2);
            this.materialHost = SimuPackLayout.column(3);
            this.actionHost = SimuPackLayout.column(3);
            SimuPackLayout.fill(this.actionHost);
            this.simulationHost = SimuPackLayout.column(3);
            SimuPackLayout.fill(this.simulationHost);
            this.industrialSimulationPanel = new IndustrialSimulationPanel(new EditorSessionHost());
            this.tradeHost = SimuPackLayout.column(3);
            SimuPackLayout.fill(this.tradeHost);
            this.statusHost = SimuPackLayout.column(2);
            this.statusBadge = SimuPackLayout.label((Component)Component.literal((String)""), -10027009);
            for (int index = 0; index < this.tabHosts.length; ++index) {
                this.tabHosts[index] = SimuPackLayout.column(3);
            }
            this.panel = new DomainPanel(new EditorSessionHost());
            this.modularUI = this.buildUi(closeAction);
        }

        static Session create(Screen parent, Runnable closeAction) {
            return new Session(parent, closeAction);
        }

        private ModularUI buildUi(Runnable closeAction) {
            SimuPackLayout.ScreenSize size = SimuPackLayout.screenSize();
            MutableComponent title = Component.translatable((String)"gui.simupack.editor.title");
            UIElement content = SimuPackLayout.pageColumn(4);
            content.addChild(this.workspace());
            this.overlayHost = content;
            float menuStartX = 8 + SimuPackWindowFrame.titleWidthFor((Component)title) + 6;
            this.menuBar = new SimuPackMenuBar(content, menuStartX);
            this.menuBar.addMenu("file", "gui.simupack.menu.file", List.of(SimuPackMenuBar.Entry.item("gui.simupack.editor.new_project", () -> this.openProjectDialog(null)), SimuPackMenuBar.Entry.item("gui.simupack.editor.import", this::chooseProjectZip), SimuPackMenuBar.Entry.separator(), SimuPackMenuBar.Entry.item("gui.simupack.editor.save", "Ctrl+S", this::saveProject, () -> this.project != null), SimuPackMenuBar.Entry.item("gui.simupack.editor.export", "", this::exportProject, () -> this.project != null), SimuPackMenuBar.Entry.separator(), SimuPackMenuBar.Entry.item("gui.simupack.menu.open_workspace", this::openWorkspaceFolder)));
            this.menuBar.addMenu("edit", "gui.simupack.menu.edit", List.of(SimuPackMenuBar.Entry.item("gui.simupack.editor.undo", "Ctrl+Z", this::undo, () -> !this.undoStack.isEmpty()), SimuPackMenuBar.Entry.item("gui.simupack.editor.redo", "Ctrl+Shift+Z", this::redo, () -> !this.redoStack.isEmpty()), SimuPackMenuBar.Entry.separator(), SimuPackMenuBar.Entry.item("gui.simupack.editor.new_building", "", this::newBuilding, () -> this.project != null), SimuPackMenuBar.Entry.item("gui.simupack.editor.select_region", "", this::startRegionSelection, () -> this.project != null && this.selectedBuilding != null)));
            this.menuBar.addMenu("help", "gui.simupack.menu.help", List.of(SimuPackMenuBar.Entry.item("gui.simupack.help.open", () -> HelpDialog.open(this.overlayHost))));
            this.menuBar.addMenu("view", "gui.simupack.menu.view", List.of(SimuPackMenuBar.Entry.item("gui.simupack.editor.preview", () -> this.selectMiddleTab(this.middlePreviewTab)), SimuPackMenuBar.Entry.item("gui.simupack.editor.materials", () -> this.selectMiddleTab(this.middleMaterialTab)), SimuPackMenuBar.Entry.item(MiddleTabKind.INDUSTRIAL_ACTION.labelKey(), "", this::openActionTab, () -> this.selectedBuilding != null && "industry".equals(this.selectedBuilding.category())), SimuPackMenuBar.Entry.item(MiddleTabKind.INDUSTRIAL_SIMULATION.labelKey(), "", this::openSimulationTab, () -> this.selectedBuilding != null && "industry".equals(this.selectedBuilding.category())), SimuPackMenuBar.Entry.item(MiddleTabKind.COMMERCIAL_TRADE.labelKey(), "", this::openTradeTab, () -> this.selectedBuilding != null && "commercial".equals(this.selectedBuilding.category())), SimuPackMenuBar.Entry.separator(), SimuPackMenuBar.Entry.item(MiddleTabKind.RESIDENTIAL_ARRAY.labelKey(), "", this::openResidentialArrayTab, () -> this.selectedBuilding != null && "residential".equals(this.selectedBuilding.category())), SimuPackMenuBar.Entry.separator(), SimuPackMenuBar.Entry.submenu("gui.simupack.menu.ui_scale", List.of(SimuPackMenuBar.Entry.item("gui.simupack.scale.auto", uiScale <= 0.0f ? "\u2713" : "", () -> this.setUiScale(0.0f), () -> true), this.scaleEntry(1.0f), this.scaleEntry(2.0f), this.scaleEntry(3.0f), this.scaleEntry(4.0f), this.scaleEntry(5.0f)))));
            UIElement rightActions = SimuPackLayout.shrinkable(SimuPackLayout.buttonRow(4));
            rightActions.layout(layout -> layout.flexShrink(0.0f));
            Button toggleLeft = new Button().setText((Component)Component.literal((String)"\u25e7"));
            SimuPackLayout.pinWidth((UIElement)toggleLeft, 16.0f);
            toggleLeft.layout(layout -> layout.height(16.0f));
            toggleLeft.setOnClick(event -> this.toggleLeftPane());
            rightActions.addChild((UIElement)toggleLeft);
            Button toggleRight = new Button().setText((Component)Component.literal((String)"\u25e8"));
            SimuPackLayout.pinWidth((UIElement)toggleRight, 16.0f);
            toggleRight.layout(layout -> layout.height(16.0f));
            toggleRight.setOnClick(event -> this.toggleRightPane());
            rightActions.addChild((UIElement)toggleRight);
            rightActions.addChild(SimuPackLayout.shrinkable((UIElement)this.statusBadge));
            float zoom = this.resolveZoom();
            SimuPackLayout.ScreenSize layoutSize = zoom == 1.0f ? size : this.zoomedLayoutSize(zoom);
            UIElement root = SimuPackWindowFrame.create(layoutSize, (Component)title, this.menuBar.bar(), rightActions, content, closeAction);
            if (zoom != 1.0f) {
                root.layout(layout -> {
                    layout.positionType(TaffyPosition.ABSOLUTE);
                    layout.left(0.0f);
                    layout.top(0.0f);
                    layout.widthPercent(100.0f / zoom);
                    layout.heightPercent(100.0f / zoom);
                });
                root.transform(transform -> transform.pivot(Pivot.TOP_LEFT).scale(zoom));
            }
            return new ModularUI(SimuPackTheme.createUi(root)).shouldCloseOnEsc(true);
        }

        private float resolveZoom() {
            Window window = Minecraft.getInstance().getWindow();
            double current = window.getGuiScale();
            if (!(current <= 0.0) && !(uiScale <= 0.0f)) {
                float zoom = (float)((double)uiScale / current);
                return Math.abs(zoom - 1.0f) < 0.001f ? 1.0f : zoom;
            }
            return 1.0f;
        }

        private SimuPackLayout.ScreenSize zoomedLayoutSize(float zoom) {
            SimuPackLayout.ScreenSize size = SimuPackLayout.screenSize();
            return new SimuPackLayout.ScreenSize(Math.max(1, Math.round((float)size.width() / zoom)), Math.max(1, Math.round((float)size.height() / zoom)));
        }

        private SimuPackMenuBar.Entry scaleEntry(float scale) {
            boolean selected = Math.abs(uiScale - scale) < 0.001f;
            return SimuPackMenuBar.Entry.item("gui.simupack.scale." + (int)scale, selected ? "\u2713" : "", () -> this.setUiScale(scale), () -> true);
        }

        private void setUiScale(float scale) {
            this.rememberViewState();
            uiScale = scale;
            SimuPackClientSettings.setEditorUiScale(scale);
            this.flushAutoSave(true);
            Minecraft.getInstance().setScreen((Screen)new SingleplayerEditorScreen(this.parent));
        }

        private Tab closableTab(String labelKey, Runnable onRemove) {
            Tab tab = new Tab().setText((Component)Component.translatable((String)labelKey));
            tab.layout(layout -> {
                layout.flexDirection(FlexDirection.ROW);
                layout.alignItems(AlignItems.CENTER);
                layout.gapAll(2.0f);
            });
            Button close = new Button().setText((Component)Component.literal((String)"x"));
            close.addClass("simupack_menu_item");
            close.layout(layout -> {
                layout.width(9.0f);
                layout.height(9.0f);
                layout.flexShrink(0.0f);
            });
            close.setOnClick(event -> onRemove.run());
            tab.addChild((UIElement)close);
            return tab;
        }

        private void selectMiddleTab(Tab tab) {
            Tab target;
            if (this.middleTabs == null) {
                return;
            }
            Tab tab2 = target = tab != null && this.middleTabs.getTabContents().containsKey((Object)tab) ? tab : null;
            if (target == null) {
                target = this.middleTabs.getTabContents().keySet().stream().findFirst().orElse(null);
            }
            if (target == null) {
                return;
            }
            if (target == this.middleActionTab && this.industrialPanel != null) {
                this.industrialPanel.requestFitView();
            }
            this.middleTabs.selectTab(target);
        }

        private void openWorkspaceFolder() {
            Util.getPlatform().openPath(CreationWorkspace.root());
        }

        void onScreenShown() {
            if (this.initialPreviewPending) {
                this.initialPreviewPending = false;
                Minecraft.getInstance().tell(this::refreshPreview);
            }
            if (BuildingSelectionSession.consumeFinished()) {
                BlockPos from = BuildingSelectionSession.point1();
                BlockPos to = BuildingSelectionSession.point2();
                if (this.project != null && this.selectedBuilding != null && from != null && to != null) {
                    this.pushHistory();
                    this.selectedBuilding.setRegion(from, to);
                    Vec3i size = this.selectedBuilding.regionSize();
                    try {
                        CreationWorkspace.save(this.project);
                        SimuPackMod.LOGGER.info("\u5df2\u8bb0\u5f55\u9009\u533a {}\uff1a{}x{}x{}\uff0c\u968f\u5373\u6355\u83b7", new Object[]{this.selectedBuilding.id(), size.getX(), size.getY(), size.getZ()});
                    }
                    catch (IOException var5) {
                        SimuPackMod.LOGGER.error("\u4fdd\u5b58\u9009\u533a\u5931\u8d25", (Throwable)var5);
                        this.notice = "\u4fdd\u5b58\u5931\u8d25: " + var5.getMessage();
                    }
                    this.refreshAll();
                    this.captureStructure();
                } else {
                    SimuPackMod.LOGGER.warn("\u9009\u533a\u7ed3\u679c\u65e0\u6cd5\u5e94\u7528\uff1aproject={} building={} from={} to={}", new Object[]{this.project == null ? "null" : this.project.id(), this.selectedBuilding == null ? "null" : this.selectedBuilding.id(), from, to});
                }
            }
        }

        private void startRegionSelection() {
            if (this.project != null && this.selectedBuilding != null) {
                try {
                    CreationWorkspace.save(this.project);
                }
                catch (IOException var2) {
                    SimuPackMod.LOGGER.error("\u9009\u70b9\u524d\u4fdd\u5b58\u5de5\u7a0b\u5931\u8d25", (Throwable)var2);
                }
                BuildingSelectionSession.start(Minecraft.getInstance().screen);
            } else {
                this.notice = Component.translatable((String)"gui.simupack.editor.no_building").getString();
                this.refreshStatus();
            }
        }

        private void captureStructure() {
            if (this.project == null || this.selectedBuilding == null) {
                this.notice = Component.translatable((String)"gui.simupack.editor.no_building").getString();
                this.refreshStatus();
            } else if (!this.selectedBuilding.hasRegion()) {
                this.notice = Component.translatable((String)"gui.simupack.editor.no_region").getString();
                this.refreshStatus();
            } else {
                CreationCaptureService.capture(this.project, this.selectedBuilding, result -> {
                    this.notice = result.message();
                    try {
                        CreationWorkspace.save(this.project);
                    }
                    catch (IOException var3) {
                        SimuPackMod.LOGGER.error("\u6355\u83b7\u540e\u4fdd\u5b58\u5de5\u7a0b\u5931\u8d25", (Throwable)var3);
                    }
                    this.refreshAll();
                });
            }
        }

        private void openImportMenu(float mouseX, float mouseY) {
            if (this.menuBar != null && this.overlayHost != null) {
                this.openMenuAt(mouseX, mouseY, List.of(SimuPackMenuBar.Entry.item("gui.simupack.editor.import_from_world", this::startRegionSelection), SimuPackMenuBar.Entry.item("gui.simupack.editor.import_from_file", this::chooseStructureFile)));
            }
        }

        private void chooseStructureFile() {
            Minecraft.getInstance().tell(() -> {
                String picked;
                try {
                    picked = Session.chooseFileNative("\u9009\u62e9\u7ed3\u6784\u6587\u4ef6", "\u7ed3\u6784\u6587\u4ef6\uff08.nbt / .litematic / .schem\uff09", "*.nbt", "*.litematic", "*.schem");
                }
                catch (Throwable var7) {
                    SimuPackMod.LOGGER.error("\u539f\u751f\u6587\u4ef6\u5bf9\u8bdd\u6846\u4e0d\u53ef\u7528", var7);
                    this.notice = "\u65e0\u6cd5\u6253\u5f00\u6587\u4ef6\u5bf9\u8bdd\u6846: " + var7.getMessage();
                    this.refreshStatus();
                    return;
                }
                if (picked != null && !picked.isBlank()) {
                    this.importStructure(Path.of(picked, new String[0]));
                } else {
                    SimuPackMod.LOGGER.info("\u6587\u4ef6\u9009\u62e9\u88ab\u53d6\u6d88\u6216\u7cfb\u7edf\u4e0d\u652f\u6301\u539f\u751f\u5bf9\u8bdd\u6846");
                }
            });
        }

        private void chooseProjectZip() {
            Minecraft.getInstance().tell(() -> {
                String picked;
                try {
                    picked = Session.chooseFileNative("\u9009\u62e9\u5efa\u7b51\u62d3\u5c55\u5305", "\u62d3\u5c55\u5305\uff08.zip\uff09", "*.zip");
                }
                catch (Throwable exception) {
                    SimuPackMod.LOGGER.error("\u539f\u751f\u6587\u4ef6\u5bf9\u8bdd\u6846\u4e0d\u53ef\u7528", exception);
                    this.notice = "\u65e0\u6cd5\u6253\u5f00\u6587\u4ef6\u5bf9\u8bdd\u6846: " + exception.getMessage();
                    this.refreshStatus();
                    return;
                }
                if (picked == null || picked.isBlank()) {
                    SimuPackMod.LOGGER.info("\u62d3\u5c55\u5305\u9009\u62e9\u88ab\u53d6\u6d88\u6216\u7cfb\u7edf\u4e0d\u652f\u6301\u539f\u751f\u5bf9\u8bdd\u6846");
                    return;
                }
                this.importZipAsProject(Path.of(picked, new String[0]));
                this.refreshAll();
            });
        }

        private static String chooseFileNative(String title, String filterLabel, String ... patterns) {
            try (MemoryStack stack = MemoryStack.stackPush();){
                PointerBuffer filters = stack.mallocPointer(patterns.length);
                for (String pattern : patterns) {
                    filters.put(stack.UTF8((CharSequence)pattern));
                }
                filters.flip();
                String string = TinyFileDialogs.tinyfd_openFileDialog((CharSequence)title, (CharSequence)"", (PointerBuffer)filters, (CharSequence)filterLabel, (boolean)false);
                return string;
            }
        }

        private void importZipAsProject(Path path) {
            try {
                this.pushHistory();
                String base = CreationWorkspace.sanitizeId(path.getFileName().toString().replaceAll("(?i)\\.zip$", ""));
                Object id = base.isBlank() ? "imported" : base;
                int index = 2;
                while (CreationWorkspace.listProjects().contains(id)) {
                    id = base + "_" + index;
                    ++index;
                }
                this.project = CreationWorkspace.importZip(path, (String)id);
                this.selectedBuilding = null;
                this.notice = Component.translatable((String)"gui.simupack.editor.imported").getString();
            }
            catch (IOException exception) {
                SimuPackMod.LOGGER.error("\u65e0\u6cd5\u5bfc\u5165 ZIP {}", (Object)path, (Object)exception);
                this.notice = "\u5bfc\u5165\u5931\u8d25: " + exception.getMessage();
            }
        }

        private void importStructure(Path source) {
            if (this.project != null && this.selectedBuilding != null) {
                try {
                    Path target = this.selectedBuilding.nbtPath(CreationWorkspace.projectDirectory(this.project.id()));
                    StructureImportService.Imported result = StructureImportService.convert(source, target);
                    this.pushHistory();
                    this.selectedBuilding.setHasStructure(true);
                    this.selectedBuilding.setMaterials(result.materials());
                    this.selectedBuilding.setSkField("size", result.sizeX() + "x" + result.sizeY() + "x" + result.sizeZ());
                    CreationWorkspace.save(this.project);
                    this.notice = Component.translatable((String)"gui.simupack.editor.import_done", (Object[])new Object[]{result.format(), result.blockCount()}).getString();
                }
                catch (Exception var4) {
                    SimuPackMod.LOGGER.error("\u5bfc\u5165\u7ed3\u6784\u5931\u8d25: {}", (Object)source, (Object)var4);
                    this.notice = Component.translatable((String)"gui.simupack.editor.import_failed", (Object[])new Object[]{String.valueOf(var4.getMessage())}).getString();
                }
                this.refreshAll();
            } else {
                this.notice = Component.translatable((String)"gui.simupack.editor.no_building").getString();
                this.refreshStatus();
            }
        }

        private UIElement readOnlyField(String labelKey, String value) {
            UIElement row = SimuPackLayout.row(4);
            row.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.height(16.0f);
                layout.minWidth(0.0f);
                layout.flexShrink(0.0f);
            });
            row.addChild(SimuPackLayout.pinWidthPercent(SimuPackLayout.shrinkable((UIElement)SimuPackLayout.label(this.tr(labelKey), -4342339)), 34.0f));
            SimuPackLayout.ReadOnlyField text = new SimuPackLayout.ReadOnlyField();
            text.setAnyString();
            text.setText(value == null ? "" : value, false);
            text.layout(layout -> {
                layout.flex(1.0f);
                layout.flexShrink(1.0f);
                layout.height(16.0f);
                layout.minWidth(0.0f);
            });
            row.addChild((UIElement)text);
            return row;
        }

        private void openRenameDialog() {
            if (this.project != null && this.selectedBuilding != null) {
                CreationBuilding building = this.selectedBuilding;
                String[] values = new String[]{building.id()};
                UIElement body = SimuPackLayout.column(3);
                body.addChild(SimuPackDialog.idField("gui.simupack.field.building_id", values[0], value -> {
                    values[0] = value;
                }, 34.0f));
                SimuPackDialog.open(this.overlayHost, (Component)Component.translatable((String)"gui.simupack.dialog.rename_building"), body, overlay -> {
                    this.overlayHost.removeChild(overlay);
                    String newId = CreationWorkspace.sanitizeId(values[0]);
                    if (!newId.isBlank() && !newId.equals(building.id())) {
                        this.pushHistory();
                        if (CreationWorkspace.renameBuilding(this.project, building, newId)) {
                            this.pendingProjectId = this.project.id();
                            this.pendingCategory = building.category();
                            this.pendingBuildingId = building.id();
                            this.notice = Component.translatable((String)"gui.simupack.editor.id_renamed").getString();
                        } else {
                            this.notice = Component.translatable((String)"gui.simupack.dialog.invalid_id").getString();
                        }
                        this.refreshAll();
                    }
                }, () -> {});
            } else {
                this.notice = Component.translatable((String)"gui.simupack.editor.no_building").getString();
                this.refreshStatus();
            }
        }

        private UIElement workspace() {
            UIElement tree = SimuPackLayout.asSplitPane(this.treePanel(), 3);
            UIElement middle = SimuPackLayout.asSplitPane(this.previewPanel(), 3);
            UIElement inspector = SimuPackLayout.asSplitPane(this.inspectorPanel(), 3);
            this.installPaneHandles(middle);
            this.innerSplit = SimuPackLayout.splitHorizontal(middle, inspector, rightPaneCollapsed ? 100.0f : middlePanePercent);
            this.outerSplit = SimuPackLayout.splitHorizontal(tree, (UIElement)this.innerSplit, leftPaneCollapsed ? 0.0f : leftPanePercent);
            this.attachDividerClick(this.outerSplit, this::toggleLeftPane);
            this.attachDividerClick(this.innerSplit, this::toggleRightPane);
            return this.outerSplit;
        }

        private void installPaneHandles(UIElement middle) {
            middle.layout(layout -> layout.positionType(TaffyPosition.RELATIVE));
            this.leftHandle = this.paneHandle(true, this::toggleLeftPane);
            this.rightHandle = this.paneHandle(false, this::toggleRightPane);
            middle.addChild((UIElement)this.leftHandle);
            middle.addChild((UIElement)this.rightHandle);
            middle.addEventListener("layoutChanged", event -> this.centerPaneHandles(middle));
            this.updatePaneHandles();
        }

        private void centerPaneHandles(UIElement middle) {
            float top = Math.max(0.0f, (middle.getSizeHeight() - 16.0f) / 2.0f);
            if (this.leftHandle != null) {
                this.leftHandle.layout(layout -> layout.top(top));
            }
            if (this.rightHandle != null) {
                this.rightHandle.layout(layout -> layout.top(top));
            }
        }

        private Button paneHandle(boolean leftEdge, Runnable onToggle) {
            Button handle = new Button();
            handle.addClass("simupack_pane_handle");
            handle.style(style -> style.tooltips(new Component[]{Component.translatable((String)(leftEdge ? "gui.simupack.editor.pane.left" : "gui.simupack.editor.pane.right"))}));
            handle.layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.top(0.0f);
                layout.width(12.0f);
                layout.height(16.0f);
                if (leftEdge) {
                    layout.left(0.0f);
                } else {
                    layout.right(0.0f);
                }
            });
            handle.setOnClick(event -> {
                if (event.button != 0) {
                    return;
                }
                event.stopPropagation();
                onToggle.run();
            });
            return handle;
        }

        private void updatePaneHandles() {
            if (this.leftHandle != null) {
                this.leftHandle.setText((Component)Component.literal((String)(leftPaneCollapsed ? ">" : "<")));
            }
            if (this.rightHandle != null) {
                this.rightHandle.setText((Component)Component.literal((String)(rightPaneCollapsed ? "<" : ">")));
            }
        }

        private void attachDividerClick(SplitView.Horizontal split, Runnable onToggle) {
            float[] pressX = new float[]{0.0f};
            float[] pressY = new float[]{0.0f};
            boolean[] onDivider = new boolean[]{false};
            boolean[] dragged = new boolean[]{false};
            split.addEventListener("mouseDown", event -> {
                pressX[0] = event.x;
                pressY[0] = event.y;
                dragged[0] = false;
                boolean onSplitItself = event.target == split;
                float dividerX = split.getPositionX() + split.first.getSizeWidth();
                boolean nearDivider = Math.abs(event.x - dividerX) <= 7.0f;
                onDivider[0] = onSplitItself && nearDivider;
            });
            split.addEventListener("mouseMove", event -> {
                if (Math.abs(event.x - pressX[0]) > 3.0f || Math.abs(event.y - pressY[0]) > 3.0f) {
                    dragged[0] = true;
                }
            });
            split.addEventListener("mouseUp", event -> {
                if (onDivider[0] && !dragged[0]) {
                    onDivider[0] = false;
                    onToggle.run();
                } else {
                    onDivider[0] = false;
                    if (split == this.outerSplit) {
                        leftPanePercent = split.getPercentage();
                    } else if (split == this.innerSplit) {
                        middlePanePercent = split.getPercentage();
                    }
                }
            });
        }

        private void toggleLeftPane() {
            if (this.outerSplit != null) {
                if (leftPaneCollapsed) {
                    leftPaneCollapsed = false;
                    this.outerSplit.setPercentage(leftPanePercentMemory);
                } else {
                    leftPaneCollapsed = true;
                    leftPanePercentMemory = Math.max(1.0f, this.outerSplit.getPercentage());
                    this.outerSplit.setPercentage(0.0f);
                }
                this.updatePaneHandles();
            }
        }

        private void toggleRightPane() {
            if (this.innerSplit != null) {
                if (rightPaneCollapsed) {
                    rightPaneCollapsed = false;
                    this.innerSplit.setPercentage(middlePanePercentMemory);
                } else {
                    rightPaneCollapsed = true;
                    middlePanePercentMemory = Math.min(99.0f, this.innerSplit.getPercentage());
                    this.innerSplit.setPercentage(100.0f);
                }
                this.updatePaneHandles();
            }
        }

        private void refreshInstalledTree() {
            List<InstalledPackReader.Entry> installed = this.installedEntries();
            if (installed.isEmpty()) {
                this.treeHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.installed_empty"), -4342339));
            } else {
                EditorTreeNode root = EditorTreeNode.root(this.sourceId == null ? "installed" : this.sourceId);
                EditorTreeNode pendingNode = null;
                LinkedHashMap<String, EditorTreeNode> categories = new LinkedHashMap<String, EditorTreeNode>();
                for (InstalledPackReader.Entry entry : installed) {
                    EditorTreeNode categoryNode = (EditorTreeNode)categories.get(entry.category());
                    if (categoryNode == null) {
                        categoryNode = EditorTreeNode.category(this.sourceId, entry.category(), this.categoryLabel(entry.category()));
                        categories.put(entry.category(), categoryNode);
                        root.attach(categoryNode);
                    }
                    EditorTreeNode node = EditorTreeNode.building(this.sourceId, entry.category(), entry.id(), entry.building().displayName() + (entry.hasStructure() ? "" : "  (\u65e0\u7ed3\u6784)"));
                    categoryNode.attach(node);
                    if (!entry.category().equals(this.pendingCategory) || !entry.id().equals(this.pendingBuildingId)) continue;
                    pendingNode = node;
                }
                this.addTree(root, pendingNode);
            }
        }

        private UIElement buildNodeRow(EditorTreeNode node) {
            int textColor = node.kind() == EditorTreeNode.Kind.CATEGORY ? -1 : -1644826;
            UIElement row = new UIElement();
            row.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.heightPercent(100.0f);
                layout.minHeight(14.0f);
                layout.minWidth(0.0f);
                layout.flexDirection(FlexDirection.ROW);
                layout.alignItems(AlignItems.CENTER);
                layout.gapAll(4.0f);
            });
            UIElement dot = new UIElement();
            dot.layout(layout -> {
                layout.width(5.0f);
                layout.height(5.0f);
                layout.flexShrink(0.0f);
            });
            dot.style(style -> style.background((IGuiTexture)new ColorRectTexture(this.nodeDotColor(node))));
            row.addChild(dot);
            UIElement text = SimuPackLayout.shrinkable((UIElement)SimuPackLayout.label((Component)Component.literal((String)node.getContent()), textColor));
            text.layout(layout -> {
                layout.flex(1.0f);
                layout.flexShrink(1.0f);
                layout.minWidth(0.0f);
            });
            row.addChild(text);
            return row;
        }

        private int nodeDotColor(EditorTreeNode node) {
            return node.kind() != EditorTreeNode.Kind.CATEGORY && node.kind() != EditorTreeNode.Kind.BUILDING ? -10177034 : this.categoryColor(node.category());
        }

        private int categoryColor(String category) {
            String var2;
            return switch (var2 = category == null ? "" : category) {
                case "residential" -> -10044566;
                case "commercial" -> -12409355;
                case "industry" -> -13784;
                case "public" -> -1;
                default -> -6381922;
            };
        }

        private void addTree(EditorTreeNode root, EditorTreeNode pendingNode) {
            TreeList<EditorTreeNode> tree = new TreeList<>(root, true);
            tree.setFlattenRoot(true);
            tree.setClickToExpand(true);
            tree.menuStyle(style -> style.nodeTexture((IGuiTexture)new ColorRectTexture(0)).hoverTexture((IGuiTexture)new ColorRectTexture(0x33FFFFFF)));
            tree.setNodeUISupplier(this::buildNodeRow);
            tree.setOnNodeUICreated((node, element) -> {
                this.attachNodeListeners((EditorTreeNode)node, (UIElement)element, (TreeList<EditorTreeNode>)tree);
                if (node == pendingNode && this.treeScroller != null) {
                    this.treeScroller.scrollToChildDelayed(element);
                }
            });
            tree.setOnSelectedChanged(this::onTreeSelection);
            this.treeScroller = SimuPackLayout.scroller();
            SimuPackLayout.fill((UIElement)this.treeScroller);
            this.treeHost.addChild((UIElement)this.treeScroller);
            this.treeScroller.addScrollViewChild((UIElement)tree);
            if (pendingNode != null) {
                tree.expandNodeAlongPath(pendingNode);
                tree.setSelected(List.of(pendingNode), true);
                this.pendingProjectId = null;
                this.pendingCategory = null;
                this.pendingBuildingId = null;
            }
        }

        private void installTreeBackgroundMenu() {
            this.treeHost.addEventListener("mouseDown", event -> {
                if (event.button == 1 && !this.readOnly() && this.menuBar != null && this.overlayHost != null) {
                    float localX = event.x - this.overlayHost.getPositionX();
                    float localY = event.y - this.overlayHost.getPositionY();
                    this.menuBar.openContextMenu(localX, localY, List.of(SimuPackMenuBar.Entry.item("gui.simupack.editor.new_building", this::newBuilding)));
                }
            });
        }

        private UIElement treePanel() {
            UIElement panel = SimuPackLayout.asSplitPane(SimuPackTheme.panel(), 3);
            panel.addChild(this.packSelector());
            SimuPackLayout.fill(this.treeHost);
            panel.addChild(this.treeHost);
            this.installTreeBackgroundMenu();
            return panel;
        }

        private UIElement previewPanel() {
            UIElement panel = SimuPackLayout.asSplitPane(SimuPackTheme.panel(), 3);
            this.middleTabs = SimuPackLayout.constrainedTabs();
            SimuPackLayout.fill((UIElement)this.middleTabs);
            UIElement previewTab = SimuPackLayout.column(3);
            SimuPackLayout.fill(previewTab);
            this.previewScene = new StructurePreviewScene();
            this.previewScene.addClass("simupack_preview_canvas");
            SimuPackLayout.fill((UIElement)this.previewScene);
            previewTab.addChild((UIElement)this.previewScene);
            previewTab.addChild(this.previewHint);
            UIElement materialTab = SimuPackLayout.column(3);
            SimuPackLayout.fill(materialTab);
            materialTab.addChild(this.materialHost);
            materialTab.addChild(this.statusHost);
            this.middlePreviewTab = this.closableTab("gui.simupack.editor.preview", () -> this.middleTabs.removeTab(this.middlePreviewTab));
            this.middleMaterialTab = this.closableTab("gui.simupack.editor.materials", () -> this.middleTabs.removeTab(this.middleMaterialTab));
            this.middleTabs.addTab(this.middlePreviewTab, previewTab);
            this.middleTabs.addTab(this.middleMaterialTab, materialTab);
            this.selectMiddleTab(this.middlePreviewTab);
            panel.addChild((UIElement)this.middleTabs);
            return panel;
        }

        private UIElement inspectorPanel() {
            UIElement panel = SimuPackLayout.asSplitPane(SimuPackTheme.panel(), 3);
            TabView tabs = SimuPackLayout.constrainedTabs();
            SimuPackLayout.fill((UIElement)tabs);
            Tab basicTab = new Tab().setText((Component)Component.translatable((String)"gui.simupack.editor.tab_sk"));
            this.settingsTab = new Tab().setText((Component)Component.translatable((String)"gui.simupack.editor.tab_settings"));
            UIElement[] hosts = new UIElement[]{this.tabHosts[0], this.tabHosts[1]};
            Tab[] tabList = new Tab[]{basicTab, this.settingsTab};
            for (int index = 0; index < tabList.length; ++index) {
                ScrollerView scroller = SimuPackLayout.scroller();
                scroller.addScrollViewChild(hosts[index]);
                tabs.addTab(tabList[index], (UIElement)scroller);
            }
            tabs.selectTab(basicTab);
            panel.addChild((UIElement)tabs);
            return panel;
        }

        private void updateSettingsTabLabel() {
            if (this.settingsTab != null) {
                String category = this.selectedBuilding == null ? "" : this.selectedBuilding.category();
                this.settingsTab.setText((Component)Component.translatable((String)(category.isBlank() ? "gui.simupack.editor.tab_settings" : "gui.simupack.editor.tab_settings." + category)));
            }
        }

        void refreshAll() {
            this.refreshAll(true);
        }

        private void refreshAll(boolean includePreview) {
            this.ensureProjectLoaded();
            this.refreshTree();
            if (includePreview) {
                this.refreshPreview();
            }
            this.refreshInspector();
            this.refreshStatus();
        }

        private void ensureProjectLoaded() {
            if (this.project == null) {
                List<String> ids = CreationWorkspace.listProjects();
                if (!memoryProjectId.isBlank() && ids.contains(memoryProjectId)) {
                    this.project = CreationWorkspace.load(memoryProjectId);
                }
                if (this.project == null && !ids.isEmpty()) {
                    this.project = CreationWorkspace.load(ids.get(0));
                }
            }
            if (this.project != null && this.selectedBuilding == null && !memoryBuildingId.isBlank()) {
                this.selectedBuilding = this.project.findBuilding(memoryCategory, memoryBuildingId);
            }
            if (this.project != null) {
                for (CreationBuilding building : this.project.buildings()) {
                    if (!building.category().equals(this.selectedBuilding == null ? "" : this.selectedBuilding.category()) || !building.id().equals(this.selectedBuilding == null ? "" : this.selectedBuilding.id())) continue;
                    this.selectedBuilding = building;
                    break;
                }
            }
            if (memoryActionTabOpen && this.middleActionTab == null && this.selectedBuilding != null && "industry".equals(this.selectedBuilding.category())) {
                this.openActionTab();
            }
            if (memoryTradeTabOpen && this.middleTradeTab == null && this.selectedBuilding != null && "commercial".equals(this.selectedBuilding.category())) {
                this.openTradeTab();
            }
        }

        private void rememberViewState() {
            memoryProjectId = this.project == null ? "" : this.project.id();
            memoryCategory = this.selectedBuilding == null ? "" : this.selectedBuilding.category();
            memoryBuildingId = this.selectedBuilding == null ? "" : this.selectedBuilding.id();
            memoryActionTabOpen = this.middleActionTab != null;
            memoryTradeTabOpen = this.middleTradeTab != null;
        }

        private void markDirty() {
            this.autoSavePending = true;
        }

        private void flushAutoSave(boolean force) {
            if (this.autoSavePending && this.project != null && (force || System.currentTimeMillis() - this.lastEditAt >= 700L)) {
                this.autoSavePending = false;
                try {
                    CreationWorkspace.save(this.project);
                }
                catch (IOException var3) {
                    SimuPackMod.LOGGER.error("\u81ea\u52a8\u4fdd\u5b58\u5de5\u7a0b {} \u5931\u8d25", (Object)this.project.id(), (Object)var3);
                }
            }
        }

        private void refreshTree() {
            this.treeHost.clearAllChildren();
            EditorTreeNode pendingNode = null;
            if (this.readOnly()) {
                this.refreshInstalledTree();
            } else {
                String id = this.sourceId != null ? this.sourceId : (this.project == null ? null : this.project.id());
                if (id == null) {
                    this.treeHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.no_project"), -4342339));
                } else {
                    CreationProject loaded;
                    CreationProject creationProject = loaded = id.equals(this.project == null ? "" : this.project.id()) ? this.project : CreationWorkspace.load(id);
                    if (loaded == null) {
                        this.treeHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.no_project"), -4342339));
                    } else if (loaded.buildings().isEmpty()) {
                        this.treeHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.empty_buildings"), -4342339));
                    } else {
                        EditorTreeNode root = EditorTreeNode.root(id);
                        LinkedHashMap<String, EditorTreeNode> categories = new LinkedHashMap<String, EditorTreeNode>();
                        for (CreationBuilding building : loaded.buildings()) {
                            EditorTreeNode categoryNode = (EditorTreeNode)categories.get(building.category());
                            if (categoryNode == null) {
                                categoryNode = EditorTreeNode.category(id, building.category(), this.categoryLabel(building.category()));
                                categories.put(building.category(), categoryNode);
                                root.attach(categoryNode);
                            }
                            EditorTreeNode buildingNode = EditorTreeNode.building(id, building.category(), building.id(), building.displayName() + (building.hasStructure() ? "" : "  (\u65e0\u7ed3\u6784)"));
                            categoryNode.attach(buildingNode);
                            if (!building.category().equals(this.pendingCategory) || !building.id().equals(this.pendingBuildingId)) continue;
                            pendingNode = buildingNode;
                        }
                        this.addTree(root, pendingNode);
                    }
                }
            }
        }

        private void attachNodeListeners(EditorTreeNode node, UIElement element, TreeList<EditorTreeNode> tree) {
            element.addEventListener("mouseDown", event -> {
                if (event.button == 0) {
                    tree.expandNodeAlongPath(node);
                    tree.setSelected(List.of(node), true);
                } else if (event.button == 1) {
                    event.stopPropagation();
                    List<SimuPackMenuBar.Entry> entries = this.nodeContextEntries(node);
                    if (entries != null) {
                        this.openMenuAt(event.x, event.y, entries);
                    }
                }
            });
            for (UIElement child : element.getChildren()) {
                this.attachNodeListeners(node, child, tree);
            }
        }

        private void onTreeSelection(Set<EditorTreeNode> selected) {
            if (selected != null && !selected.isEmpty()) {
                this.flushAutoSave(true);
                EditorTreeNode node = selected.iterator().next();
                if (node.kind() == EditorTreeNode.Kind.PROJECT) {
                    this.openProject(node.projectId());
                } else if (node.kind() == EditorTreeNode.Kind.BUILDING) {
                    if (this.readOnly()) {
                        this.selectedBuilding = this.installedEntries().stream().filter(entry -> entry.category().equals(node.category()) && entry.id().equals(node.buildingId())).map(InstalledPackReader.Entry::building).findFirst().orElse(null);
                    } else {
                        if (!node.projectId().equals(this.project == null ? "" : this.project.id())) {
                            this.openProject(node.projectId());
                        }
                        this.selectedBuilding = this.project == null ? null : this.project.findBuilding(node.category(), node.buildingId());
                    }
                    this.refreshPreview();
                    this.refreshInspector();
                    this.refreshStatus();
                }
            }
        }

        private List<SimuPackMenuBar.Entry> nodeContextEntries(EditorTreeNode node) {
            ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
            if (node.kind() == EditorTreeNode.Kind.CATEGORY) {
                if (!this.readOnly()) {
                    entries.add(SimuPackMenuBar.Entry.item("gui.simupack.editor.new_building", () -> this.newBuilding(node.category())));
                }
            } else {
                if (node.kind() != EditorTreeNode.Kind.BUILDING) {
                    return null;
                }
                entries.add(SimuPackMenuBar.Entry.item("gui.simupack.menu.edit_building_info", () -> {
                    if (!node.projectId().equals(this.project == null ? "" : this.project.id())) {
                        this.openProject(node.projectId());
                    }
                    this.selectedBuilding = this.project == null ? null : this.project.findBuilding(node.category(), node.buildingId());
                    this.refreshPreview();
                    this.refreshInspector();
                    this.refreshStatus();
                    this.selectMiddleTab(this.middlePreviewTab);
                }));
                if (!this.readOnly()) {
                    entries.add(SimuPackMenuBar.Entry.item("gui.simupack.menu.delete_building", () -> this.deleteBuilding(node.projectId(), node.category(), node.buildingId())));
                }
            }
            return entries;
        }

        private void deleteProject(String projectId) {
            this.pushHistory();
            CreationWorkspace.delete(projectId);
            if (projectId.equals(this.project == null ? "" : this.project.id())) {
                this.project = null;
                this.selectedBuilding = null;
                this.ensureProjectLoaded();
            }
            this.notice = Component.translatable((String)"gui.simupack.editor.deleted").getString();
            this.refreshAll();
        }

        private void deleteBuilding(String projectId, String category, String buildingId) {
            CreationProject target;
            if (this.readOnly()) {
                return;
            }
            CreationProject creationProject = target = projectId.equals(this.project == null ? "" : this.project.id()) ? this.project : CreationWorkspace.load(projectId);
            if (target != null) {
                this.pushHistory();
                CreationBuilding building = target.findBuilding(category, buildingId);
                if (building != null) {
                    target.buildings().remove(building);
                    if (building == this.selectedBuilding) {
                        this.selectedBuilding = null;
                    }
                    try {
                        CreationWorkspace.save(target);
                    }
                    catch (IOException var7) {
                        SimuPackMod.LOGGER.error("\u65e0\u6cd5\u4fdd\u5b58\u5de5\u7a0b {}", (Object)target.id(), (Object)var7);
                    }
                }
                this.notice = Component.translatable((String)"gui.simupack.editor.deleted").getString();
                this.refreshAll();
            }
        }

        private void openProject(String projectId) {
            this.project = CreationWorkspace.load(projectId);
            this.selectedBuilding = null;
            this.refreshPreview();
            this.refreshInspector();
            this.refreshStatus();
        }

        private void refreshPreview() {
            this.previewHint.clearAllChildren();
            this.refreshMaterials();
            if (this.previewScene == null) {
                return;
            }
            CreationBuilding building = this.selectedBuilding;
            if (building == null) {
                this.showSceneStructure("");
                return;
            }
            String source = this.structureSourceId(building);
            CompoundTag tag = this.structureTagOf(building);
            if (!source.equals(this.sceneShownSource)) {
                this.previewScene.showStructure(tag);
                this.sceneShownSource = source;
            }
            if (this.previewScene.blockCount() <= 0) {
                this.previewHint.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.no_structure"), -171));
            }
        }

        private void showSceneStructure(String source) {
            if (this.sceneShownSource.equals(source)) {
                return;
            }
            this.previewScene.showStructure(null);
            this.sceneShownSource = source;
        }

        private void refreshMaterials() {
            this.materialHost.clearAllChildren();
            CreationBuilding building = this.selectedBuilding;
            if (building == null) {
                this.materialHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.no_building"), -4342339));
                return;
            }
            Map<String, Integer> materials = building.materials();
            if (materials.isEmpty()) {
                this.materialHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)(building.hasStructure() ? "gui.simupack.editor.materials_unknown" : "gui.simupack.editor.materials_pending")), -4342339));
                return;
            }
            int distinct = materials.size();
            int total = 0;
            for (Integer count : materials.values()) {
                total += count == null ? 0 : count;
            }
            this.materialHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.materials_summary", (Object[])new Object[]{distinct, total}), -1644826));
            ArrayList<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(materials.entrySet());
            sorted.sort((left, right) -> {
                int byCount = Integer.compare(Session.countOf(right), Session.countOf(left));
                return byCount != 0 ? byCount : ((String)left.getKey()).compareTo((String)right.getKey());
            });
            int shown = Math.min(sorted.size(), 32);
            for (int index = 0; index < shown; ++index) {
                Map.Entry entry = (Map.Entry)sorted.get(index);
                this.materialHost.addChild(this.materialRow((String)entry.getKey(), Session.countOf(entry)));
            }
            if (sorted.size() > shown) {
                this.materialHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.materials_more", (Object[])new Object[]{sorted.size() - shown}), -4342339));
            }
        }

        private UIElement materialRow(String itemId, int count) {
            boolean known = ItemRegistry.isRegistered(itemId);
            UIElement row = SimuPackLayout.row(3);
            row.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.height(18.0f);
                layout.minWidth(0.0f);
                layout.flexShrink(0.0f);
            });
            row.addChild((UIElement)EditorWidgets.plainSlot(ItemRegistry.stackOf(itemId), 18.0f));
            row.addChild(SimuPackLayout.shrinkable((UIElement)SimuPackLayout.label((Component)Component.literal((String)(itemId + " \u00d7 " + count)), known ? -1 : -171)).style(style -> style.tooltips(new Component[]{Component.literal((String)(itemId + (String)(known ? "" : "  \u00b7  " + Component.translatable((String)"gui.simupack.item_picker.unknown", (Object[])new Object[]{itemId}).getString())))})));
            return row;
        }

        private static int countOf(Map.Entry<String, Integer> entry) {
            return entry.getValue() == null ? 0 : entry.getValue();
        }

        private String structureSourceId(CreationBuilding building) {
            if (building == null) {
                return "";
            }
            if (this.readOnly()) {
                return BuildingPackManager.packs().stream().filter(pack -> pack.id().equals(this.sourceId)).findFirst().map(pack -> "zip:" + String.valueOf(pack.path().toAbsolutePath()) + "#" + building.key() + "@" + String.valueOf(pack.path().getFileName())).orElse("");
            }
            if (this.project == null) {
                return "";
            }
            Path nbt = building.nbtPath(CreationWorkspace.projectDirectory(this.project.id()));
            String base = "file:" + String.valueOf(nbt.toAbsolutePath()) + "#" + building.key();
            if (!Files.isRegularFile(nbt, new LinkOption[0])) {
                return base + "@missing";
            }
            try {
                return base + "@" + Files.getLastModifiedTime(nbt, new LinkOption[0]).toMillis() + ":" + Files.size(nbt);
            }
            catch (IOException exception) {
                return base + "@" + System.nanoTime();
            }
        }

        private CompoundTag structureTagOf(CreationBuilding building) {
            CompoundTag tag;
            String source = this.structureSourceId(building);
            if (source.equals(this.structureTagSource)) {
                return this.structureTag;
            }
            this.structureTag = tag = this.readStructureTag(building);
            this.structureTagSize = StructureSizeReader.sizeOf(tag);
            this.structureTagSource = source;
            return tag;
        }

        private CompoundTag readStructureTag(CreationBuilding building) {
            if (building == null) {
                return null;
            }
            if (this.readOnly()) {
                return BuildingPackManager.packs().stream().filter(pack -> pack.id().equals(this.sourceId)).findFirst().map(pack -> StructureSizeReader.readRootTagFromZip(pack.path(), building.category(), building.id())).orElse(null);
            }
            return this.project != null ? StructureSizeReader.readRootTag(building.nbtPath(CreationWorkspace.projectDirectory(this.project.id()))) : null;
        }

        private void refreshInspector() {
            for (UIElement host : this.tabHosts) {
                host.clearAllChildren();
            }
            this.buildSkForm(this.tabHosts[0]);
            this.refreshSettings();
        }

        private void buildProjectForm(UIElement host) {
            if (this.project == null) {
                host.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.no_project"), -4342339));
            } else {
                host.addChild(this.field("gui.simupack.field.id", this.project.id(), value -> {
                    this.recordEdit("project.id");
                    this.project.setId(CreationWorkspace.sanitizeId(value));
                }, "[a-z0-9_]*"));
                host.addChild(this.field("gui.simupack.field.name", this.project.name(), value -> {
                    this.recordEdit("project.name");
                    this.project.setName((String)value);
                }));
                host.addChild(this.field("gui.simupack.field.version", this.project.version(), value -> {
                    this.recordEdit("project.version");
                    this.project.setVersion((String)value);
                }, "[0-9A-Za-z.\\-_]*"));
                host.addChild(this.field("gui.simupack.field.author", this.project.author(), value -> {
                    this.recordEdit("project.author");
                    this.project.setAuthor((String)value);
                }));
                host.addChild(this.field("gui.simupack.field.description", this.project.description(), value -> {
                    this.recordEdit("project.description");
                    this.project.setDescription((String)value);
                }));
                host.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)(Component.translatable((String)"gui.simupack.editor.buildings").getString() + ": " + this.project.buildings().size())), -4342339));
            }
        }

        private void buildSkForm(UIElement host) {
            CreationBuilding building = this.selectedBuilding;
            if (building == null) {
                host.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.no_building"), -4342339));
            } else {
                boolean readOnlySource = this.readOnly();
                host.addChild(this.categoryRow(building, readOnlySource));
                host.addChild(this.readOnlyField("gui.simupack.field.building_id", building.id()));
                for (String key : CreationBuilding.SK_FIELDS) {
                    if ("size".equals(key)) continue;
                    if (readOnlySource) {
                        host.addChild(this.readOnlyField("gui.simupack.field.sk." + key, building.skField(key)));
                        continue;
                    }
                    UIElement row = this.field("gui.simupack.field.sk." + key, building.skField(key), value -> {
                        this.recordEdit("sk." + key);
                        building.setSkField(key, (String)value);
                    }, null);
                    if ("amount".equals(key)) {
                        host.addChild(this.moneyField(row));
                        continue;
                    }
                    if ("unlockLevel".equals(key)) {
                        host.addChild(this.positiveIntegerField(row));
                        continue;
                    }
                    host.addChild(row);
                }
                host.addChild(this.readOnlyField("gui.simupack.field.sk.size", this.resolveStructureSize(building)));
                if (readOnlySource) {
                    host.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.installed_readonly"), -171));
                } else {
                    Button importButton = new Button().setText((Component)Component.translatable((String)"gui.simupack.editor.import_building"));
                    SimuPackLayout.pinWidth((UIElement)importButton, 72.0f);
                    importButton.layout(layout -> layout.height(16.0f));
                    importButton.setOnClick(event -> this.openImportMenu(event.x, event.y));
                    UIElement actionRow = SimuPackLayout.row(4);
                    actionRow.addChild((UIElement)importButton);
                    Button renameButton = new Button().setText((Component)Component.translatable((String)"gui.simupack.editor.rename_building"));
                    SimuPackLayout.pinWidth((UIElement)renameButton, 56.0f);
                    renameButton.layout(layout -> layout.height(16.0f));
                    renameButton.setOnClick(event -> this.openRenameDialog());
                    actionRow.addChild((UIElement)renameButton);
                    host.addChild(actionRow);
                }
            }
        }

        private UIElement categoryRow(CreationBuilding building, boolean readOnlySource) {
            UIElement row = SimuPackLayout.row(4);
            row.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.height(16.0f);
                layout.minWidth(0.0f);
                layout.flexShrink(0.0f);
            });
            row.addChild(SimuPackLayout.pinWidthPercent(SimuPackLayout.shrinkable((UIElement)SimuPackLayout.label((Component)Component.translatable((String)"gui.simupack.editor.category"), -4342339)), 34.0f));
            Button button = new Button().setText((Component)Component.literal((String)(this.categoryLabel(building.category()) + "   \u25be")));
            button.layout(layout -> {
                layout.flex(1.0f);
                layout.height(16.0f);
                layout.minWidth(0.0f);
                layout.flexShrink(1.0f);
            });
            if (readOnlySource) {
                button.setActive(false);
            } else {
                button.setOnClick(event -> this.openCategoryMenu(event.x, event.y, building));
            }
            row.addChild((UIElement)button);
            return row;
        }

        private void openCategoryMenu(float mouseX, float mouseY, CreationBuilding building) {
            ArrayList<SimuPackMenuBar.Entry> entries = new ArrayList<SimuPackMenuBar.Entry>();
            for (String category : CreationBuilding.CATEGORIES) {
                boolean selected = category.equals(building.category());
                entries.add(SimuPackMenuBar.Entry.item(this.categoryLabel(category), selected ? "\u2713" : "", () -> this.changeCategory(building, category), () -> true));
            }
            this.openMenuAt(mouseX, mouseY, entries);
        }

        private void changeCategory(CreationBuilding building, String category) {
            if (this.project != null && building != null && !category.equals(building.category())) {
                this.pushHistory();
                this.notice = CreationWorkspace.moveBuilding(this.project, building, category) ? Component.translatable((String)"gui.simupack.editor.category_changed", (Object[])new Object[]{this.categoryLabel(category)}).getString() : Component.translatable((String)"gui.simupack.editor.category_failed").getString();
                this.pendingProjectId = this.project.id();
                this.pendingCategory = building.category();
                this.pendingBuildingId = building.id();
                this.refreshAll();
            }
        }

        private String categoryLabel(String category) {
            return category != null && !category.isBlank() ? Component.translatable((String)("gui.simupack.category." + category)).getString() : "";
        }

        private String resolveStructureSize(CreationBuilding building) {
            String formatted = StructureSizeReader.format(this.resolveStructureSizeArray(building));
            return formatted.isEmpty() ? Component.translatable((String)"gui.simupack.editor.size_unknown").getString() : formatted;
        }

        private int[] resolveStructureSizeArray(CreationBuilding building) {
            this.structureTagOf(building);
            return this.structureTagSize;
        }

        private BuildingJsonDocument.StructureBounds structureBounds(CreationBuilding building) {
            int[] size = this.resolveStructureSizeArray(building);
            return size != null && size.length >= 3 && size[0] > 0 && size[1] > 0 && size[2] > 0 ? new BuildingJsonDocument.StructureBounds(size[0], size[1], size[2]) : BuildingJsonDocument.StructureBounds.UNKNOWN;
        }

        private String exportBlocker(CreationBuilding building) {
            try {
                String residentialProblem;
                if ("residential".equals(Session.normalizedCategory(building)) && !(residentialProblem = this.residentialExportBlocker(building)).isEmpty()) {
                    return residentialProblem;
                }
                BuildingJsonDocument document = BuildingJsonDocument.parse(building.jsonConfig());
                String var4 = Session.normalizedCategory(building);
                List<DefinitionIssue> var10000_1 = "commercial".equals(var4) ? this.commercialIssues(document) : List.of();
                for (DefinitionIssue issue : var10000_1) {
                    if (issue.severity() != DefinitionIssue.Severity.ERROR) continue;
                    return issue.location() + " / " + issue.code();
                }
                return "";
            }
            catch (IllegalArgumentException var6) {
                return var6.getMessage();
            }
        }

        private String residentialExportBlocker(CreationBuilding building) {
            int[] size = this.resolveStructureSizeArray(building);
            for (int index = 0; index < building.units().size(); ++index) {
                CreationUnit unit = building.units().get(index);
                if (!unit.hasRange()) {
                    return "unit[" + index + "] / no_range";
                }
                if (size == null || unit.min().getX() >= 0 && unit.min().getY() >= 0 && unit.min().getZ() >= 0 && unit.max().getX() < size[0] && unit.max().getY() < size[1] && unit.max().getZ() < size[2]) continue;
                return "unit[" + index + "] / out_of_bounds";
            }
            return "";
        }

        private List<DefinitionIssue> commercialIssues(BuildingJsonDocument document) {
            CommercialOfferModel model = CommercialOfferModel.wrap(document.root());
            ArrayList<DefinitionIssue> issues = new ArrayList<DefinitionIssue>(model.validate());
            issues.addAll(CommercialContainersModel.wrap(document.root()).validate());
            for (CommercialOfferModel.ItemRef ref : ItemRegistry.unknown(model.itemIds())) {
                issues.add(DefinitionIssue.error("unknown_item", ref.location(), ref.itemId()));
            }
            return issues;
        }

        private static String normalizedCategory(CreationBuilding building) {
            String category = building != null && building.category() != null ? building.category().trim().toLowerCase(Locale.ROOT) : "";
            return "commerce".equals(category) ? "commercial" : category;
        }

        private void openResidentialArrayTab() {
            if (this.middleTabs == null || this.selectedBuilding == null || !"residential".equals(this.selectedBuilding.category())) {
                return;
            }
            this.refreshSettings();
            if (this.middleResidentialArrayTab == null) {
                this.residentialArrayPanel = new ResidentialArrayPanel(new EditorSessionHost());
                UIElement content = SimuPackLayout.column(3);
                SimuPackLayout.fill(content);
                content.addChild(this.actionHost);
                this.middleResidentialArrayTab = this.closableTab(MiddleTabKind.RESIDENTIAL_ARRAY.labelKey(), this::closeResidentialArrayTab);
                this.middleTabs.addTab(this.middleResidentialArrayTab, content);
            }
            this.refreshSettings();
            this.selectMiddleTab(this.middleResidentialArrayTab);
        }

        private void closeResidentialArrayTab() {
            if (this.middleTabs != null && this.middleResidentialArrayTab != null) {
                this.middleTabs.removeTab(this.middleResidentialArrayTab);
                this.middleResidentialArrayTab = null;
                this.residentialArrayPanel = null;
                this.selectMiddleTab(this.middlePreviewTab);
            }
        }

        private void refreshSettings() {
            this.ensurePanel(this.selectedBuilding);
            this.updateSettingsTabLabel();
            this.panel.sync(this.selectedBuilding, this.readOnly(), this.structureBounds(this.selectedBuilding));
            this.panel.renderSettingsInto(this.tabHosts[1]);
            if (this.middleActionTab != null && this.actionHost != null && this.panel == this.industrialPanel) {
                this.panel.renderMiddleInto(this.actionHost);
            }
            if (this.middleTradeTab != null && this.tradeHost != null && this.panel == this.tradePanel) {
                this.panel.renderMiddleInto(this.tradeHost);
            }
            if (this.middleResidentialArrayTab != null && this.residentialArrayPanel != null) {
                this.residentialArrayPanel.sync(this.selectedBuilding, this.readOnly(), this.structureBounds(this.selectedBuilding));
                this.residentialArrayPanel.renderMiddleInto(this.actionHost);
            }
            // 仿真页签开着的时候跟着一起刷新：否则在动作组里改完配方，仿真还是旧的那份。
            if (this.middleSimulationTab != null) {
                this.industrialSimulationPanel.sync(this.selectedBuilding);
                this.industrialSimulationPanel.setStructure(this.structureSourceId(this.selectedBuilding),
                        this.structureTagOf(this.selectedBuilding));
                this.industrialSimulationPanel.renderInto(this.simulationHost);
            }
        }

        private void ensurePanel(CreationBuilding building) {
            String category;
            String string = category = building == null ? "" : building.category();
            if (this.panel == null || !category.equals(this.panelCategory)) {
                this.closeTradeTab();
                this.closeActionTab();
                this.closeSimulationTab();
                this.closeResidentialArrayTab();
                this.closePickTab();
                if ("industry".equals(category)) {
                    this.industrialPanel = new IndustrialActionPanel(new EditorSessionHost());
                    this.tradePanel = null;
                    this.panel = this.industrialPanel;
                } else if ("commercial".equals(category)) {
                    this.industrialPanel = null;
                    this.tradePanel = new CommercialTradePanel(new EditorSessionHost());
                    this.panel = this.tradePanel;
                } else if ("residential".equals(category)) {
                    this.industrialPanel = null;
                    this.tradePanel = null;
                    this.panel = new ResidentialUnitPanel(new EditorSessionHost());
                } else {
                    this.industrialPanel = null;
                    this.tradePanel = null;
                    this.panel = new DomainPanel(new EditorSessionHost());
                }
                this.panelCategory = category;
            }
        }

        private void openSimulationTab() {
            if (this.middleTabs == null || this.selectedBuilding == null || !"industry".equals(this.selectedBuilding.category())) return;
            if (this.middleSimulationTab == null) {
                UIElement content = SimuPackLayout.column(3);
                SimuPackLayout.fill(content);
                content.addChild(this.simulationHost);
                this.middleSimulationTab = this.closableTab(MiddleTabKind.INDUSTRIAL_SIMULATION.labelKey(), this::closeSimulationTab);
                this.middleTabs.addTab(this.middleSimulationTab, content);
            }
            this.industrialSimulationPanel.sync(this.selectedBuilding);
            this.industrialSimulationPanel.setStructure(this.structureSourceId(this.selectedBuilding),
                    this.structureTagOf(this.selectedBuilding));
            this.industrialSimulationPanel.renderInto(this.simulationHost);
            this.selectMiddleTab(this.middleSimulationTab);
        }

        private void closeSimulationTab() {
            if (this.middleTabs != null && this.middleSimulationTab != null) {
                this.middleTabs.removeTab(this.middleSimulationTab);
                this.middleSimulationTab = null;
                this.selectMiddleTab(this.middlePreviewTab);
            }
        }

        private void openActionTab() {
            if (this.middleTabs != null) {
                this.refreshSettings();
                memoryActionTabOpen = true;
                if (this.middleActionTab == null) {
                    UIElement content = SimuPackLayout.column(3);
                    SimuPackLayout.fill(content);
                    content.addChild(this.actionHost);
                    this.middleActionTab = this.closableTab(MiddleTabKind.INDUSTRIAL_ACTION.labelKey(), this::closeActionTab);
                    this.middleTabs.addTab(this.middleActionTab, content);
                }
                if (this.industrialPanel != null) {
                    this.industrialPanel.requestFitView();
                }
                this.refreshSettings();
                this.selectMiddleTab(this.middleActionTab);
            }
        }

        private void closeActionTab() {
            if (this.middleTabs != null && this.middleActionTab != null) {
                this.middleTabs.removeTab(this.middleActionTab);
                this.middleActionTab = null;
                memoryActionTabOpen = false;
                this.selectMiddleTab(this.middlePreviewTab);
            }
        }

        private void openTradeTab() {
            if (this.middleTabs != null) {
                this.refreshSettings();
                memoryTradeTabOpen = true;
                if (this.middleTradeTab == null) {
                    UIElement content = SimuPackLayout.column(3);
                    SimuPackLayout.fill(content);
                    content.addChild(this.tradeHost);
                    this.middleTradeTab = this.closableTab(MiddleTabKind.COMMERCIAL_TRADE.labelKey(), this::closeTradeTab);
                    this.middleTabs.addTab(this.middleTradeTab, content);
                }
                this.refreshSettings();
                this.selectMiddleTab(this.middleTradeTab);
            }
        }

        private void closeTradeTab() {
            if (this.middleTabs != null && this.middleTradeTab != null) {
                this.middleTabs.removeTab(this.middleTradeTab);
                this.middleTradeTab = null;
                memoryTradeTabOpen = false;
                this.selectMiddleTab(this.middlePreviewTab);
            }
        }

        private void openPointPicker(List<BlockPos> initial, Consumer<List<BlockPos>> onPicked) {
            this.openPointPicker(initial, onPicked, null);
        }

        private boolean hasStructure() {
            return this.structureTagOf(this.selectedBuilding) != null;
        }

        private boolean guardStructure() {
            if (this.hasStructure()) {
                return true;
            }
            this.notice = Component.translatable((String)"gui.simupack.editor.need_structure").getString();
            this.refreshStatus();
            return false;
        }

        private void openPointPicker(List<BlockPos> initial, Consumer<List<BlockPos>> onPicked, String subject) {
            if (this.middleTabs != null && this.selectedBuilding != null && this.guardStructure()) {
                this.closeDialog();
                this.closePickTab();
                this.reselectName = null;
                this.reselectInitial = initial == null ? List.of() : List.copyOf(initial);
                this.pickScope = new PickScope(this.selectedBuilding, subject, this.selectedBuilding.jsonConfig());
                this.picker.open(subject == null ? new ContainerPickTarget(onPicked) : new ResidentialPickTarget(subject, onPicked));
            }
        }

        private void openPickTab() {
            if (this.middleTabs != null && this.selectedBuilding != null && this.industrialPanel != null && this.guardStructure()) {
                this.pickScope = new PickScope(this.selectedBuilding, this.industrialPanel.recipeId(), this.selectedBuilding.jsonConfig());
                this.picker.open(new IndustrialPickTarget());
            }
        }

        private void openReselect(String name, boolean container) {
            JsonObject original = this.industrialPanel.pointDefinition(name, container);
            if (original != null) {
                this.closeDialog();
                this.closePickTab();
                this.reselectName = name;
                this.reselectContainer = container;
                this.reselectMode = original.has("select") && original.get("select").isJsonPrimitive() ? original.get("select").getAsString() : "nearest";
                ArrayList<BlockPos> prefill = new ArrayList<BlockPos>();
                for (JsonArray position : BuildingJsonDocument.positionList(original)) {
                    if (!BuildingJsonDocument.validPosition((JsonElement)position)) continue;
                    prefill.add(new BlockPos(position.get(0).getAsInt(), position.get(1).getAsInt(), position.get(2).getAsInt()));
                }
                this.reselectInitial = List.copyOf(prefill);
                this.openPickTab();
            }
        }

        private void closePickTab() {
            this.picker.close();
        }

        private static List<int[]> toPositionArrays(List<BlockPos> positions) {
            ArrayList<int[]> arrays = new ArrayList<int[]>();
            for (BlockPos pos : positions) {
                arrays.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
            }
            return arrays;
        }

        private void openPointNamingDialog(List<BlockPos> positions) {
            if (this.overlayHost != null && this.industrialPanel != null) {
                String[] name = new String[]{""};
                int containerLooking = 0;
                for (BlockPos pos : positions) {
                    if (!this.looksLikeContainer(pos)) continue;
                    ++containerLooking;
                }
                boolean[] container = new boolean[]{containerLooking > 0 && containerLooking == positions.size()};
                boolean[] ordered = new boolean[]{false};
                UIElement body = SimuPackLayout.column(4);
                body.addChild(SimuPackDialog.field("gui.simupack.industrial.point_name", "", value -> {
                    name[0] = value;
                }, 40.0f));
                Label hint = SimuPackLayout.label((Component)Component.literal((String)""), -34953);
                body.addChild((UIElement)hint);
                body.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)this.picker.pickedText()), -1644826));
                if (containerLooking > 0) {
                    body.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.industrial.point_autodetect", (Object[])new Object[]{containerLooking, positions.size()}), -4342339));
                }
                UIElement containerRow = SimuPackLayout.row(4);
                containerRow.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.industrial.point_is_container"), -4342339));
                Button containerButton = Session.pickButton(Session.containerText(container[0]), () -> {});
                containerButton.setOnClick(event -> {
                    container[0] = !container[0];
                    containerButton.setText((Component)Component.literal((String)Session.containerText(container[0])));
                });
                containerRow.addChild((UIElement)containerButton);
                body.addChild(containerRow);
                if (positions.size() > 1) {
                    UIElement selectRow = SimuPackLayout.row(4);
                    selectRow.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.industrial.point_select"), -4342339));
                    Button selectButton = Session.pickButton(Session.selectText(false), () -> {});
                    selectButton.setOnClick(event -> {
                        ordered[0] = !ordered[0];
                        selectButton.setText((Component)Component.literal((String)Session.selectText(ordered[0])));
                    });
                    selectRow.addChild((UIElement)selectButton);
                    body.addChild(selectRow);
                }
                boolean[] accepted = new boolean[]{false};
                this.openDialog((Component)Component.translatable((String)"gui.simupack.industrial.point_dialog_title"), body, (UIElement overlay) -> {
                    if (!this.picker.checkScope()) {
                        accepted[0] = true;
                    } else {
                        String chosen;
                        String string = chosen = name[0] == null ? "" : name[0].trim();
                        if (chosen.isEmpty()) {
                            hint.setValue((Component)Component.translatable((String)"gui.simupack.industrial.point_name_required"));
                        } else if (!this.industrialPanel.definePoint(chosen, container[0], Session.toPositionArrays(positions), ordered[0] ? "ordered" : "nearest")) {
                            hint.setValue((Component)Component.translatable((String)"gui.simupack.industrial.point_name_taken", (Object[])new Object[]{chosen}));
                        } else {
                            accepted[0] = true;
                            this.notice = Component.translatable((String)"gui.simupack.industrial.point_created", (Object[])new Object[]{chosen}).getString();
                            this.picker.close();
                            this.refreshStatus();
                        }
                    }
                }, () -> accepted[0]);
            }
        }

        private static Button pickButton(String text, Runnable action) {
            Button button = new Button().setText((Component)Component.literal((String)text));
            float width = SimuPackLayout.textWidth(text) + 12.0f;
            button.layout(layout -> {
                layout.width(width);
                layout.minWidth(width);
                layout.maxWidth(width);
                layout.height(16.0f);
                layout.flexShrink(0.0f);
            });
            button.setOnClick(event -> action.run());
            return button;
        }

        private static Map<BlockPos, BlockState> structureStates(CompoundTag tag) {
            HashMap<BlockPos, BlockState> states = new HashMap<BlockPos, BlockState>();
            for (StructurePreviewLoader.Placed placed : StructurePreviewLoader.read(tag)) {
                states.put(placed.pos(), placed.state());
            }
            return states;
        }

        private boolean looksLikeContainer(BlockPos pos) {
            BlockState state = this.pickedStructureStates.get(pos);
            if (state == null) {
                return false;
            }
            try {
                EntityBlock entityBlock;
                Block block = state.getBlock();
                return block instanceof EntityBlock && (entityBlock = (EntityBlock)block).newBlockEntity(pos, state) instanceof Container;
            }
            catch (Throwable var5) {
                return false;
            }
        }

        private static String containerText(boolean container) {
            return (container ? "\u2611 " : "\u2610 ") + Component.translatable((String)(container ? "gui.simupack.industrial.point_kind_container" : "gui.simupack.industrial.point_kind_plain")).getString();
        }

        private static String selectText(boolean ordered) {
            return (ordered ? "\u2611 " : "\u2610 ") + Component.translatable((String)(ordered ? "gui.simupack.industrial.point_select_ordered" : "gui.simupack.industrial.point_select_nearest")).getString();
        }

        private void openPointSettings() {
            if (this.overlayHost != null && this.industrialPanel != null) {
                List<IndustrialActionPanel.PointEntry> entries = this.industrialPanel.pointEntriesForHost();
                if (entries.isEmpty()) {
                    this.notice = Component.translatable((String)"gui.simupack.industrial.points_none_short").getString();
                    this.refreshStatus();
                } else {
                    UIElement body = SimuPackLayout.column(3);
                    for (IndustrialActionPanel.PointEntry entry : entries) {
                        body.addChild(this.pointManageRow(entry.name(), entry.container()));
                    }
                    this.openDialog((Component)Component.translatable((String)"gui.simupack.industrial.points_manage"), body, overlay -> this.closeDialog());
                }
            }
        }

        private UIElement pointManageRow(String name, boolean container) {
            UIElement row = SimuPackLayout.row(4);
            row.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.literal((String)(name + (container ? " \u00b7 \u5bb9\u5668" : "") + " \u00b7 \u00d7" + this.industrialPanel.positionCount(name, container))), -1));
            row.addChild(SimuPackLayout.spacer(1.0f));
            row.addChild((UIElement)Session.pickButton(Component.translatable((String)"gui.simupack.industrial.point_rename").getString(), () -> this.openRenameDialog(name, container)));
            row.addChild((UIElement)Session.pickButton(Component.translatable((String)"gui.simupack.industrial.point_delete").getString(), () -> this.confirmDeletePoint(name, container)));
            row.addChild((UIElement)Session.pickButton(Component.translatable((String)"gui.simupack.industrial.point_reselect").getString(), () -> this.openReselect(name, container)));
            return row;
        }

        private void openRenameDialog(String oldName, boolean container) {
            String[] value = new String[]{oldName};
            UIElement body = SimuPackLayout.column(4);
            body.addChild(SimuPackDialog.field("gui.simupack.industrial.point_name", oldName, input -> {
                value[0] = input;
            }, 40.0f));
            this.closeDialog();
            this.openDialog((Component)Component.translatable((String)"gui.simupack.industrial.point_rename"), body, overlay -> {
                String chosen;
                this.closeDialog();
                String string = chosen = value[0] == null ? "" : value[0].trim();
                if (!chosen.isEmpty() && !chosen.equals(oldName)) {
                    this.notice = this.industrialPanel.renamePoint(oldName, chosen, container) ? Component.translatable((String)"gui.simupack.industrial.point_renamed", (Object[])new Object[]{oldName, chosen}).getString() : Component.translatable((String)"gui.simupack.industrial.point_name_taken", (Object[])new Object[]{chosen}).getString();
                    this.refreshStatus();
                }
            });
        }

        private void confirmDeletePoint(String name, boolean container) {
            int references = this.industrialPanel.referenceCount(name, container);
            this.closeDialog();
            if (references == 0) {
                this.industrialPanel.deletePoint(name, container, false);
                this.notice = Component.translatable((String)"gui.simupack.industrial.point_deleted", (Object[])new Object[]{name}).getString();
                this.refreshStatus();
            } else {
                UIElement body = SimuPackLayout.column(3);
                body.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.industrial.point_in_use", (Object[])new Object[]{name, references}), -171));
                body.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.industrial.point_delete_hint"), -4342339));
                for (BuildingJsonDocument.Reference reference : this.industrialPanel.pointReferences(name, container)) {
                    body.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)(reference.recipeId() + " / " + reference.path().display() + " / " + reference.fieldKey())), -1644826));
                }
                this.openDialog((Component)Component.translatable((String)"gui.simupack.industrial.point_delete"), body, overlay -> {
                    this.closeDialog();
                    this.industrialPanel.deletePoint(name, container, true);
                    this.notice = Component.translatable((String)"gui.simupack.industrial.point_deleted_forced", (Object[])new Object[]{name, references}).getString();
                    this.refreshStatus();
                });
            }
        }

        private void openDialog(Component title, UIElement body, Consumer<UIElement> onConfirm) {
            this.openDialog(title, body, onConfirm, () -> true);
        }

        private void openDialog(Component title, UIElement body, Consumer<UIElement> onConfirm, BooleanSupplier closeAfter) {
            this.dialogOpen = true;
            this.currentOverlay = SimuPackDialog.open(this.overlayHost, title, body, overlay -> {
                onConfirm.accept((UIElement)overlay);
                if (closeAfter.getAsBoolean()) {
                    this.closeDialog();
                }
            }, this::closeDialog);
        }

        private void closeDialog() {
            if (this.currentOverlay != null && this.overlayHost != null) {
                this.overlayHost.removeChild(this.currentOverlay);
            }
            this.currentOverlay = null;
            this.dialogOpen = false;
        }

        private void openItemPicker(Consumer<String> onPick, boolean allowTags) {
            if (this.overlayHost != null) {
                UIElement[] pickerOverlay = new UIElement[1];
                UIElement body = new ItemPicker(picked -> {
                    if (this.currentOverlay != pickerOverlay[0]) {
                        return;
                    }
                    this.closeDialog();
                    onPick.accept((String)picked);
                }, allowTags).build(this.overlayHost.getSizeHeight());
                this.openDialog((Component)Component.translatable((String)"gui.simupack.item_picker.title"), body, (UIElement overlay) -> {}, (String)null);
                pickerOverlay[0] = this.currentOverlay;
            }
        }

        private void openBlockPicker(Consumer<String> onPick) {
            if (this.overlayHost == null) {
                return;
            }
            UIElement[] pickerOverlay = new UIElement[1];
            UIElement body = ItemPicker.forBlocks(picked -> {
                if (this.currentOverlay != pickerOverlay[0]) {
                    return;
                }
                this.closeDialog();
                onPick.accept((String)picked);
            }).build(this.overlayHost.getSizeHeight());
            this.openDialog((Component)Component.translatable((String)"gui.simupack.item_picker.title"), body, (UIElement overlay) -> {}, (String)null);
            pickerOverlay[0] = this.currentOverlay;
        }

        private void openDialog(Component title, UIElement body, Consumer<UIElement> onConfirm, String confirmKey) {
            this.dialogOpen = true;
            this.currentOverlay = SimuPackDialog.open(this.overlayHost, title, body, overlay -> {
                this.closeDialog();
                onConfirm.accept((UIElement)overlay);
            }, this::closeDialog, null, "gui.simupack.dialog.delete", "gui.simupack.dialog.delete_confirm", confirmKey, "gui.simupack.dialog.cancel");
        }

        boolean handleEnter() {
            if (this.middlePickTab != null && this.middleTabs.getSelectedTab() == this.middlePickTab && !this.dialogOpen) {
                this.picker.finish();
                return true;
            }
            return false;
        }

        private void openMenuAt(float mouseX, float mouseY, List<SimuPackMenuBar.Entry> entries) {
            if (this.menuBar != null && this.overlayHost != null) {
                Vector2f local = this.overlayHost.worldToLocal(new Vector2f(mouseX, mouseY));
                float contentLeft = local.x - this.overlayHost.getContentX();
                float contentTop = local.y - this.overlayHost.getContentY();
                float maxX = Math.max(2.0f, this.overlayHost.getSizeWidth() - 96.0f);
                float maxY = Math.max(2.0f, this.overlayHost.getSizeHeight() - 120.0f);
                float left = Math.max(2.0f, Math.min(contentLeft, maxX));
                float top = Math.max(2.0f, Math.min(contentTop, maxY));
                this.menuBar.openContextMenu(left, top, entries);
            }
        }

        private void refreshStatus() {
            this.statusHost.clearAllChildren();
            String displayed = this.notice.isBlank() ? "" : this.notice;
            this.notice = "";
            if (!displayed.isEmpty()) {
                this.statusHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)displayed), -10027009));
            }
            if (this.project == null) {
                this.statusBadge.setValue((Component)Component.literal((String)""));
            } else {
                String scaleHint;
                List<String> problems = CreationWorkspace.validate(this.project);
                String string = scaleHint = uiScale > 0.0f ? (int)uiScale + "x" : "";
                this.statusBadge.setValue((Component)Component.literal((String)(!displayed.isEmpty() ? displayed : scaleHint + (scaleHint.isEmpty() ? "" : "  ") + (String)(problems.isEmpty() ? "\u2713" : "\u26a0 " + problems.size()))));
                if (problems.isEmpty()) {
                    this.statusHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.translatable((String)"gui.simupack.editor.validation_ok"), -10027162));
                } else {
                    for (String problem : problems) {
                        this.statusHost.addChild((UIElement)SimuPackLayout.wrappedLabel((Component)Component.literal((String)("\u00b7 " + problem)), -171));
                    }
                }
            }
        }

        private void openProjectDialog(CreationProject existing) {
            boolean creating = existing == null;
            String[] values = new String[]{creating ? this.suggestProjectId() : existing.id(), creating ? "" : existing.name(), creating ? "1.0.0" : existing.version(), creating ? Minecraft.getInstance().getUser().getName() : existing.author(), creating ? "" : existing.description()};
            UIElement body = SimuPackLayout.column(3);
            body.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.minWidth(0.0f);
                layout.flexDirection(FlexDirection.COLUMN);
                layout.alignItems(AlignItems.STRETCH);
                layout.gapAll(3.0f);
            });
            body.addChild(SimuPackDialog.idField("gui.simupack.field.id", values[0], value -> {
                values[0] = value;
            }, 34.0f));
            body.addChild(SimuPackDialog.field("gui.simupack.field.name", values[1], value -> {
                values[1] = value;
            }, 34.0f));
            body.addChild(SimuPackDialog.field("gui.simupack.field.version", values[2], value -> {
                values[2] = value;
            }, 34.0f));
            body.addChild(SimuPackDialog.field("gui.simupack.field.author", values[3], value -> {
                values[3] = value;
            }, 34.0f));
            body.addChild(SimuPackDialog.field("gui.simupack.field.description", values[4], value -> {
                values[4] = value;
            }, 34.0f));
            MutableComponent title = Component.translatable((String)(creating ? "gui.simupack.dialog.new_project" : "gui.simupack.dialog.edit_project"));
            SimuPackDialog.open(this.overlayHost, (Component)title, body, overlay -> {
                String id = CreationWorkspace.sanitizeId(values[0]);
                if (!id.isBlank() && (creating || id.equals(existing.id()) || !CreationWorkspace.listProjects().contains(id))) {
                    this.overlayHost.removeChild(overlay);
                    this.pushHistory();
                    if (!(creating || id.equals(existing.id()) || CreationWorkspace.renameProject(existing.id(), id))) {
                        this.notice = Component.translatable((String)"gui.simupack.dialog.invalid_id").getString();
                        this.refreshStatus();
                    } else {
                        CreationProject target = creating ? new CreationProject(id, values[1]) : existing;
                        target.setId(id);
                        target.setName(values[1].isBlank() ? id : values[1]);
                        target.setVersion(values[2]);
                        target.setAuthor(values[3]);
                        target.setDescription(values[4]);
                        if (creating) {
                            this.project = target;
                            this.selectedBuilding = null;
                        }
                        try {
                            CreationWorkspace.save(target);
                            this.notice = Component.translatable((String)"gui.simupack.editor.saved").getString();
                        }
                        catch (IOException var8) {
                            SimuPackMod.LOGGER.error("\u65e0\u6cd5\u4fdd\u5b58\u5de5\u7a0b {}", (Object)id, (Object)var8);
                            this.notice = "\u4fdd\u5b58\u5931\u8d25: " + var8.getMessage();
                        }
                        this.refreshAll();
                    }
                } else {
                    this.notice = Component.translatable((String)"gui.simupack.dialog.invalid_id").getString();
                    this.refreshStatus();
                }
            }, () -> {}, creating ? null : overlay -> {
                this.overlayHost.removeChild(overlay);
                this.deleteProject(existing.id());
            });
        }

        private String suggestProjectId() {
            String base = "my_pack";
            Object candidate = base;
            int index = 2;
            while (CreationWorkspace.listProjects().contains(candidate)) {
                candidate = base + "_" + index;
                ++index;
            }
            return candidate == null ? "" : candidate.toString();
        }

        private void newBuilding() {
            this.newBuilding(CreationBuilding.CATEGORIES[0]);
        }

        private void newBuilding(String category) {
            this.openNewBuildingDialog(category);
        }

        private void openNewBuildingDialog(String category) {
            if (this.project == null) {
                this.notice = Component.translatable((String)"gui.simupack.editor.no_project").getString();
                this.refreshStatus();
            } else {
                String initialCategory = category != null && !category.isBlank() ? category : CreationBuilding.CATEGORIES[0];
                String[] values = new String[]{this.project.nextBuildingId("new_building"), "", initialCategory, this.project.author() == null ? "" : this.project.author()};
                UIElement body = SimuPackLayout.column(3);
                body.addChild(SimuPackDialog.idField("gui.simupack.field.building_id", values[0], value -> {
                    values[0] = value;
                }, 34.0f));
                body.addChild(SimuPackDialog.field("gui.simupack.field.building_name", "", value -> {
                    values[1] = value;
                }, 34.0f));
                UIElement categoryRow = SimuPackLayout.row(4);
                categoryRow.addChild((UIElement)SimuPackLayout.pinnedLabel((Component)Component.translatable((String)"gui.simupack.field.category"), -4342339));
                ArrayList<Button> categoryButtons = new ArrayList<Button>();
                for (String candidate : CreationBuilding.CATEGORIES) {
                    Button button = new Button();
                    button.layout(layout -> {
                        layout.height(16.0f);
                        layout.flexShrink(0.0f);
                    });
                    button.setOnClick(event -> {
                        values[2] = candidate;
                        this.refreshCategoryButtons(categoryButtons, values[2]);
                    });
                    categoryButtons.add(button);
                    categoryRow.addChild((UIElement)button);
                }
                this.refreshCategoryButtons(categoryButtons, values[2]);
                body.addChild(categoryRow);
                body.addChild(SimuPackDialog.field("gui.simupack.field.author", values[3], value -> {
                    values[3] = value;
                }, 34.0f));
                Label hint = SimuPackLayout.label((Component)Component.literal((String)""), -34953);
                body.addChild((UIElement)hint);
                boolean[] accepted = new boolean[]{false};
                this.openDialog((Component)Component.translatable((String)"gui.simupack.dialog.new_building"), body, (UIElement overlay) -> {
                    String id = CreationWorkspace.sanitizeId(values[0]);
                    if (id.isBlank()) {
                        hint.setValue((Component)Component.translatable((String)"gui.simupack.dialog.invalid_id"));
                    } else if (this.project.findBuildingById(id) != null) {
                        hint.setValue((Component)Component.translatable((String)"gui.simupack.dialog.id_taken", (Object[])new Object[]{id}));
                    } else {
                        accepted[0] = true;
                        this.pushHistory();
                        CreationBuilding building = new CreationBuilding(id, values[2]);
                        String name = values[1] != null && !values[1].isBlank() ? values[1] : id;
                        building.setSkField("name", name);
                        if (values[3] != null && !values[3].isBlank()) {
                            building.setSkField("author", values[3]);
                        }
                        this.project.buildings().add(building);
                        this.selectedBuilding = building;
                        this.pendingProjectId = this.project.id();
                        this.pendingCategory = values[2];
                        this.pendingBuildingId = id;
                        this.refreshTree();
                        this.refreshPreview();
                        this.refreshInspector();
                        this.refreshStatus();
                    }
                }, () -> accepted[0]);
            }
        }

        private void refreshCategoryButtons(List<Button> buttons, String selected) {
            for (int index = 0; index < buttons.size() && index < CreationBuilding.CATEGORIES.length; ++index) {
                String candidate = CreationBuilding.CATEGORIES[index];
                String label = (candidate.equals(selected) ? "\u2713 " : "") + this.categoryLabel(candidate);
                buttons.get(index).setText((Component)Component.literal((String)label));
            }
        }

        private void importDropped(List<Path> paths) {
            for (Path path : paths) {
                if (path == null || !Files.isRegularFile(path, new LinkOption[0]) || !path.getFileName().toString().toLowerCase().endsWith(".zip")) continue;
                this.importZipAsProject(path);
            }
            this.refreshAll();
        }

        private void saveProject() {
            if (this.project == null) {
                this.notice = Component.translatable((String)"gui.simupack.editor.no_project").getString();
                this.refreshStatus();
            } else {
                try {
                    CreationWorkspace.save(this.project);
                    this.notice = Component.translatable((String)"gui.simupack.editor.saved").getString();
                }
                catch (IOException var2) {
                    SimuPackMod.LOGGER.error("\u65e0\u6cd5\u4fdd\u5b58\u5de5\u7a0b {}", (Object)this.project.id(), (Object)var2);
                    this.notice = "\u4fdd\u5b58\u5931\u8d25: " + var2.getMessage();
                }
                this.refreshTree();
                this.refreshStatus();
            }
        }

        private void exportProject() {
            if (this.project == null) {
                this.notice = Component.translatable((String)"gui.simupack.editor.no_project").getString();
                this.refreshStatus();
            } else {
                try {
                    CreationWorkspace.save(this.project);
                    for (CreationBuilding building : this.project.buildings()) {
                        String problem = this.exportBlocker(building);
                        if (problem.isEmpty()) continue;
                        this.notice = Component.translatable((String)"gui.simupack.editor.export_blocked", (Object[])new Object[]{building.id() + " / " + problem}).getString();
                        this.refreshStatus();
                        return;
                    }
                    Path target = CreationWorkspace.export(this.project);
                    this.notice = Component.translatable((String)"gui.simupack.editor.exported").getString() + ": " + String.valueOf(target.getFileName());
                    Session.openContainingFolder(target);
                }
                catch (IOException var4) {
                    SimuPackMod.LOGGER.error("\u65e0\u6cd5\u5bfc\u51fa\u5de5\u7a0b {}", (Object)this.project.id(), (Object)var4);
                    this.notice = "\u5bfc\u51fa\u5931\u8d25: " + var4.getMessage();
                }
                this.refreshStatus();
            }
        }

        private static void openContainingFolder(Path file) {
            Path folder;
            Path path = folder = file == null ? null : file.getParent();
            if (folder == null) {
                return;
            }
            try {
                Util.getPlatform().openPath(folder);
            }
            catch (RuntimeException exception) {
                SimuPackMod.LOGGER.warn("\u65e0\u6cd5\u6253\u5f00\u5bfc\u51fa\u76ee\u5f55 {}\uff08\u5bfc\u51fa\u672c\u8eab\u5df2\u5b8c\u6210\uff09", (Object)folder, (Object)exception);
            }
        }

        private void recordEdit(String tag) {
            long now = System.currentTimeMillis();
            if (!tag.equals(this.lastEditTag) || now - this.lastEditAt > 1200L) {
                this.pushHistory();
            }
            this.lastEditTag = tag;
            this.lastEditAt = now;
            this.markDirty();
        }

        private void pushHistory() {
            if (this.project != null) {
                this.lastEditTag = "";
                this.undoStack.push(this.project.toJson().toString());
                while (this.undoStack.size() > 32) {
                    this.undoStack.removeLast();
                }
                this.redoStack.clear();
                this.markDirty();
            }
        }

        private void undo() {
            this.restoreFrom(this.undoStack, this.redoStack);
        }

        private void redo() {
            this.restoreFrom(this.redoStack, this.undoStack);
        }

        private void restoreFrom(Deque<String> from, Deque<String> to) {
            if (this.project != null && !from.isEmpty()) {
                this.closePickTab();
                this.lastEditTag = "";
                to.push(this.project.toJson().toString());
                this.project = CreationProject.fromJson(JsonParser.parseString((String)from.pop()).getAsJsonObject());
                this.selectedBuilding = this.project.buildings().isEmpty() ? null : this.project.buildings().get(0);
                this.notice = Component.translatable((String)"gui.simupack.editor.restored").getString();
                this.refreshAll();
            } else {
                this.notice = Component.translatable((String)"gui.simupack.editor.nothing_to_undo").getString();
                this.refreshStatus();
            }
        }

        private Component tr(String key) {
            return Component.translatable((String)key);
        }

        private UIElement sectionTitle(String translationKey) {
            return SimuPackLayout.shrinkable((UIElement)SimuPackLayout.label(this.tr(translationKey), -1));
        }

        private Button button(String translationKey, float width, Runnable action) {
            return this.pinButton(translationKey, width, action);
        }

        private Button pinButton(String translationKey, float width, Runnable action) {
            Button button = new Button().setText(this.tr(translationKey));
            button.setOnClick(event -> action.run());
            SimuPackLayout.pinWidth((UIElement)button, width);
            button.layout(layout -> layout.height(16.0f));
            return button;
        }

        private UIElement field(String labelKey, String value, Consumer<String> responder) {
            UIElement row = SimuPackLayout.row(4);
            row.layout(layout -> {
                layout.widthPercent(100.0f);
                layout.height(16.0f);
                layout.minWidth(0.0f);
                layout.flexShrink(0.0f);
            });
            row.addChild(SimuPackLayout.pinWidthPercent(SimuPackLayout.shrinkable((UIElement)SimuPackLayout.label(this.tr(labelKey), -4342339)), 34.0f));
            EditorWidgets.CommitOnBlurField textField = new EditorWidgets.CommitOnBlurField();
            textField.setAnyString();
            textField.setText(value == null ? "" : value, false);
            textField.setTextResponder(responder);
            textField.layout(layout -> {
                layout.flex(1.0f);
                layout.flexShrink(1.0f);
                layout.height(16.0f);
                layout.minWidth(0.0f);
            });
            row.addChild((UIElement)textField);
            return row;
        }

        private UIElement field(String labelKey, String value, Consumer<String> responder, String regex) {
            UIElement row = this.field(labelKey, value, responder);
            for (UIElement child : row.getChildren()) {
                if (!(child instanceof TextField)) continue;
                TextField text = (TextField)child;
                if (regex == null) {
                    text.setAnyString();
                    break;
                }
                text.setTextRegexValidator(regex);
                break;
            }
            return row;
        }

        private UIElement moneyField(UIElement row) {
            for (UIElement child : row.getChildren()) {
                if (!(child instanceof TextField)) continue;
                TextField text = (TextField)child;
                text.setTextRegexValidator("[\u5143\uffe5$\u00a5]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)[\u5143\uffe5$\u00a5]?");
                break;
            }
            return row;
        }

        private UIElement positiveIntegerField(UIElement row) {
            for (UIElement child : row.getChildren()) {
                if (!(child instanceof TextField)) continue;
                TextField text = (TextField)child;
                text.setTextRegexValidator("0*[1-9][0-9]*");
                break;
            }
            return row;
        }

        static {
            memoryProjectId = "";
            memoryCategory = "";
            memoryBuildingId = "";
            CHESTS_TAG = TagKey.create((ResourceKey)Registries.BLOCK, (ResourceLocation)ResourceLocation.fromNamespaceAndPath((String)"c", (String)"chests"));
        }

        private record PackChoice(String kind, String id) {
            static final String WORKSPACE = "workspace";
            static final String INSTALLED = "installed";

            boolean readOnly() {
                return INSTALLED.equals(this.kind);
            }
        }

        private final class PickHost
        implements PointPicker.Host {
            private PickHost() {
            }

            @Override
            public CompoundTag structureTag() {
                CompoundTag tag = Session.this.structureTagOf(Session.this.selectedBuilding);
                Session.this.pickedStructureStates = Session.structureStates(tag);
                return tag;
            }

            @Override
            public void notify(String message) {
                Session.this.notice = message;
                this.refreshStatus();
            }

            @Override
            public void refreshStatus() {
                Session.this.refreshStatus();
            }

            @Override
            public boolean dialogOpen() {
                return Session.this.dialogOpen;
            }

            @Override
            public void openDialog(Component title, UIElement body, Consumer<UIElement> onConfirm) {
                Session.this.openDialog(title, body, onConfirm);
            }

            @Override
            public void showPickTab(String titleKey, UIElement content, Runnable onClose) {
                Session.this.middlePickTab = Session.this.closableTab(titleKey, onClose);
                Session.this.middleTabs.addTab(Session.this.middlePickTab, content);
                Session.this.selectMiddleTab(Session.this.middlePickTab);
            }

            @Override
            public void selectPickTab() {
                Session.this.selectMiddleTab(Session.this.middlePickTab);
            }

            @Override
            public boolean pickTabActive() {
                return Session.this.middleTabs != null && Session.this.middlePickTab != null && Session.this.middleTabs.getSelectedTab() == Session.this.middlePickTab;
            }

            @Override
            public void hidePickTab() {
                if (Session.this.middleTabs != null && Session.this.middlePickTab != null) {
                    Session.this.middleTabs.removeTab(Session.this.middlePickTab);
                }
                Session.this.middlePickTab = null;
                Session.this.reselectName = null;
                Session.this.reselectMode = null;
                Session.this.reselectInitial = List.of();
                Session.this.pickedStructureStates = Map.of();
                if (Session.this.middleActionTab != null) {
                    Session.this.selectMiddleTab(Session.this.middleActionTab);
                } else {
                    Session.this.selectMiddleTab(Session.this.middlePreviewTab);
                }
            }
        }

        private final class EditorSessionHost
        implements EditorHost {
            private EditorSessionHost() {
            }

            @Override
            public void openMenu(float mouseX, float mouseY, List<SimuPackMenuBar.Entry> entries) {
                Session.this.openMenuAt(mouseX, mouseY, entries);
            }

            @Override
            public void pushHistory() {
                Session.this.pushHistory();
            }

            @Override
            public void recordEdit(String tag) {
                Session.this.recordEdit(tag);
            }

            @Override
            public void commit() {
                Session.this.refreshStatus();
                Session.this.markDirty();
                Session.this.flushAutoSave(false);
            }

            @Override
            public void refresh() {
                Minecraft.getInstance().tell(Session.this::refreshSettings);
            }

            @Override
            public void openMiddleTab(MiddleTabKind kind) {
                switch (kind) {
                    case INDUSTRIAL_ACTION: {
                        Session.this.openActionTab();
                        break;
                    }
                    case INDUSTRIAL_SIMULATION: {
                        Session.this.openSimulationTab();
                        break;
                    }
                    case COMMERCIAL_TRADE: {
                        Session.this.openTradeTab();
                        break;
                    }
                    case RESIDENTIAL_ARRAY: {
                        Session.this.openResidentialArrayTab();
                    }
                }
            }

            @Override
            public void openPickTab() {
                Session.this.openPickTab();
            }

            @Override
            public void notify(String message) {
                Session.this.notice = message;
                Session.this.refreshStatus();
            }

            @Override
            public void openDialog(EditorDialog dialog) {
                if (dialog == EditorDialog.INDUSTRIAL_POINT_SETTINGS) {
                    Session.this.openPointSettings();
                }
            }

            @Override
            public void openItemPicker(Consumer<String> onPick, boolean allowTags) {
                Session.this.openItemPicker(onPick, allowTags);
            }

            @Override
            public void openBlockPicker(Consumer<String> onPick) {
                Session.this.openBlockPicker(onPick);
            }

            @Override
            public boolean hasStructure() {
                return Session.this.hasStructure();
            }

            @Override
            public CompoundTag structureTag() {
                return Session.this.structureTagOf(Session.this.selectedBuilding);
            }

            @Override
            public void openPointPicker(List<BlockPos> initial, Consumer<List<BlockPos>> onPicked) {
                Session.this.openPointPicker(initial, onPicked);
            }

            @Override
            public void openPointPicker(List<BlockPos> initial, String subject, Consumer<List<BlockPos>> onPicked) {
                Session.this.openPointPicker(initial, onPicked, subject);
            }
        }

        private final class ContainerPickTarget
        implements PointPicker.Target {
            private final Consumer<List<BlockPos>> onPicked;

            ContainerPickTarget(Consumer<List<BlockPos>> onPicked) {
                this.onPicked = onPicked;
            }

            @Override
            public String titleKey() {
                return "gui.simupack.editor.pick_point";
            }

            @Override
            public List<BlockPos> initialPositions() {
                return Session.this.reselectInitial;
            }

            @Override
            public boolean stillValid() {
                return Session.this.pickScope != null && Session.this.selectedBuilding != null && Session.this.pickScope.matches(Session.this.selectedBuilding, Session.this.pickScope.subject(), Session.this.selectedBuilding.jsonConfig());
            }

            @Override
            public String scopeChangedKey() {
                return "gui.simupack.editor.pick_scope_changed";
            }

            @Override
            public boolean accepts(BlockPos pos) {
                BlockState state = Session.this.pickedStructureStates.get(pos);
                return state != null && state.is(CHESTS_TAG);
            }

            @Override
            public String rejectKey() {
                return "gui.simupack.commercial.container_pick_rejected";
            }

            @Override
            public boolean commit(List<BlockPos> positions) {
                if (!Session.this.picker.checkScope()) {
                    return false;
                }
                this.onPicked.accept(positions);
                return true;
            }
        }

        private final class ResidentialPickTarget
        implements PointPicker.Target {
            private final String unitId;
            private final Consumer<List<BlockPos>> onPicked;
            private static final float CONTEXT_BOX_RED = 0.45f;
            private static final float CONTEXT_BOX_GREEN = 0.85f;
            private static final float CONTEXT_BOX_BLUE = 1.0f;
            private static final float CONTEXT_BOX_ALPHA = 0.1f;

            ResidentialPickTarget(String unitId, Consumer<List<BlockPos>> onPicked) {
                this.unitId = unitId;
                this.onPicked = onPicked;
            }

            @Override
            public String titleKey() {
                return "gui.simupack.residential.pick_range";
            }

            @Override
            public List<BlockPos> initialPositions() {
                return List.of();
            }

            @Override
            public boolean stillValid() {
                if (Session.this.pickScope == null || Session.this.selectedBuilding == null || !Session.this.pickScope.matches(Session.this.selectedBuilding, this.unitId, Session.this.selectedBuilding.jsonConfig())) {
                    return false;
                }
                return Session.this.selectedBuilding.units().stream().anyMatch(unit -> unit.id().equals(this.unitId));
            }

            @Override
            public String scopeChangedKey() {
                return "gui.simupack.residential.pick_scope_changed";
            }

            @Override
            public List<UnitBoxVisual> contextBoxes() {
                ArrayList<UnitBoxVisual> boxes = new ArrayList<UnitBoxVisual>();
                if (Session.this.selectedBuilding == null) {
                    return boxes;
                }
                for (CreationUnit unit : Session.this.selectedBuilding.units()) {
                    if (unit.id().equals(this.unitId) || !unit.hasRange()) continue;
                    boxes.add(UnitBoxVisual.boxed(unit.min().getX(), unit.min().getY(), unit.min().getZ(), unit.max().getX(), unit.max().getY(), unit.max().getZ(), 0.45f, 0.85f, 1.0f, 0.1f, true));
                }
                return boxes;
            }

            @Override
            public boolean commit(List<BlockPos> positions) {
                if (positions.size() != 2) {
                    Session.this.notice = Component.translatable((String)"gui.simupack.residential.need_two_points").getString();
                    Session.this.refreshStatus();
                    return false;
                }
                this.onPicked.accept(positions);
                return true;
            }
        }

        private final class IndustrialPickTarget
        implements PointPicker.Target {
            private IndustrialPickTarget() {
            }

            @Override
            public String titleKey() {
                return "gui.simupack.industrial.pick_point";
            }

            @Override
            public List<BlockPos> initialPositions() {
                return Session.this.reselectInitial;
            }

            @Override
            public boolean stillValid() {
                return Session.this.pickScope != null && Session.this.selectedBuilding != null && Session.this.industrialPanel != null && Session.this.pickScope.matches(Session.this.selectedBuilding, Session.this.industrialPanel.recipeId(), Session.this.selectedBuilding.jsonConfig());
            }

            @Override
            public String scopeChangedKey() {
                return "gui.simupack.industrial.pick_scope_changed";
            }

            @Override
            public boolean commit(List<BlockPos> positions) {
                if (Session.this.reselectName != null) {
                    Session.this.industrialPanel.reselectPoint(Session.this.reselectName, Session.this.reselectContainer, Session.toPositionArrays(positions), Session.this.reselectMode);
                    Session.this.notice = Component.translatable((String)"gui.simupack.industrial.point_reselected", (Object[])new Object[]{Session.this.reselectName}).getString();
                    Session.this.refreshStatus();
                    return true;
                }
                Session.this.openPointNamingDialog(positions);
                return false;
            }
        }
    }
}

