package com.rstech.simupack.client.ui.editor;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.rstech.simupack.client.ui.SimuPackLayout;
import com.rstech.simupack.client.ui.SimuPackTheme;
import com.rstech.simupack.creation.residential.CreationUnit;
import com.rstech.simupack.client.ui.preview.StructurePreviewLoader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Residential settings that edit unit: metadata without using the JSON editor pipeline. */
@OnlyIn(Dist.CLIENT)
public final class ResidentialUnitPanel extends DomainPanel {
    public ResidentialUnitPanel(EditorHost host) {
        super(host);
    }

    @Override
    public boolean supports(String category) {
        return "residential".equals(category);
    }

    @Override
    public void renderSettingsInto(UIElement container) {
        container.clearAllChildren();
        if (building == null) {
            container.addChild(wrapped("gui.simupack.editor.no_building", SimuPackTheme.TEXT_MUTED_COLOR));
            return;
        }
        container.addChild(sectionHeader("gui.simupack.residential.section.units"));
        UIElement actions = SimuPackLayout.buttonRow(4);
        actions.addChild(textButton(Component.translatable("gui.simupack.residential.add_unit").getString(), 0F,
                this::addUnit, editable()));
        // 阵列页签的第二个入口。视图菜单里也有，但用户是在这张户型列表上想到「复制到每层」的，
        // 入口放在这里才符合操作路径。
        actions.addChild(textButton(Component.translatable(MiddleTabKind.RESIDENTIAL_ARRAY.labelKey()).getString(),
                0F, () -> host.openMiddleTab(MiddleTabKind.RESIDENTIAL_ARRAY),
                !building.units().isEmpty()));
        container.addChild(actions);

        if (building.units().isEmpty()) {
            container.addChild(wrapped("gui.simupack.residential.no_units", SimuPackTheme.TEXT_MUTED_COLOR));
            return;
        }
        for (int index = 0; index < building.units().size(); index++) {
            container.addChild(unitRow(index, building.units().get(index)));
        }
        int unassigned = unassignedBeds();
        if (unassigned > 0) {
            container.addChild(SimuPackLayout.wrappedLabel(Component.translatable(
                    "gui.simupack.residential.unassigned_beds", unassigned), SimuPackTheme.TEXT_WARNING_COLOR));
        }
    }

    private UIElement unitRow(int index, CreationUnit unit) {
        UIElement card = SimuPackLayout.column(3);
        card.layout(layout -> {
            layout.widthPercent(100F);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        card.addClass("simupack_card");

        UIElement header = SimuPackLayout.row(3);
        header.layout(layout -> {
            layout.widthPercent(100F);
            layout.height(EditorWidgets.ROW_HEIGHT);
            layout.flexShrink(0F);
        });
        header.addChild(SimuPackLayout.pinWidth(
                SimuPackLayout.label(Component.literal("#" + (index + 1)), SimuPackTheme.TEXT_MUTED_COLOR), 24F));
        TextField label = textField();
        label.setAnyString();
        label.setText(unit.label(), false);
        label.setTextResponder(value -> rename(unit, value));
        label.layout(layout -> {
            layout.flex(1F);
            layout.minWidth(0F);
            layout.height(EditorWidgets.ROW_HEIGHT);
        });
        header.addChild(label);
        header.addChild(textButton("✕", EditorWidgets.SMALL_BUTTON,
                () -> removeUnit(index), editable()));
        card.addChild(header);

        String range = unit.hasRange()
                ? format(unit.min()) + " ~ " + format(unit.max())
                : Component.translatable("gui.simupack.residential.no_range").getString();
        UIElement rangeRow = SimuPackLayout.row(2);
        rangeRow.layout(layout -> {
            layout.widthPercent(100F);
            layout.minWidth(0F);
            layout.flexShrink(0F);
        });
        rangeRow.addChild(SimuPackLayout.shrinkable(SimuPackLayout.label(Component.literal(range),
                unit.hasRange() ? SimuPackTheme.TEXT_MUTED_COLOR : SimuPackTheme.TEXT_WARNING_COLOR)));
        card.addChild(rangeRow);

        UIElement actions = SimuPackLayout.buttonRow(3);
        if (unit.hasRange()) {
            actions.addChild(SimuPackLayout.wrappedLabel(Component.translatable(
                    "gui.simupack.residential.capacity", countBeds(unit)), SimuPackTheme.TEXT_SECONDARY_COLOR));
        }
        boolean canPick = editable() && host.hasStructure();
        actions.addChild(textButton(Component.translatable("gui.simupack.residential.pick_range").getString(), 0F,
                () -> pickRange(unit), canPick));
        card.addChild(actions);
        return card;
    }

    @Override
    protected boolean editable() {
        return building != null && !readOnly;
    }

    private void pickRange(CreationUnit unit) {
        if (!editable() || unit == null || !host.hasStructure()) return;
        // 重新框选从空白开始；取消不会改旧范围，只有完成两点选择才替换。
        host.openPointPicker(java.util.List.of(), unit.id(), picked -> {
            if (picked.size() != 2) {
                host.notify(Component.translatable("gui.simupack.residential.need_two_points").getString());
                return;
            }
            host.pushHistory();
            unit.setRange(picked.get(0), picked.get(1));
            host.commit();
            host.refresh();
        });
    }

    private int countBeds(CreationUnit unit) {
        if (host.structureTag() == null) return 0;
        int count = 0;
        for (StructurePreviewLoader.Placed placed : StructurePreviewLoader.read(host.structureTag())) {
            var state = placed.state();
            if (!state.is(Blocks.RED_BED)) continue;
            if (state.hasProperty(BlockStateProperties.BED_PART)
                    && state.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) continue;
            if (unit.contains(placed.pos())) count++;
        }
        return count;
    }

    private int unassignedBeds() {
        if (host.structureTag() == null) return 0;
        int count = 0;
        for (StructurePreviewLoader.Placed placed : StructurePreviewLoader.read(host.structureTag())) {
            var state = placed.state();
            if (!state.is(Blocks.RED_BED)) continue;
            if (state.hasProperty(BlockStateProperties.BED_PART)
                    && state.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) continue;
            boolean assigned = building.units().stream().anyMatch(unit -> unit.contains(placed.pos()));
            if (!assigned) count++;
        }
        return count;
    }

    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private void addUnit() {
        if (!editable()) return;
        host.pushHistory();
        int number = building.units().size() + 1;
        building.units().add(new CreationUnit(
                Component.translatable("gui.simupack.residential.unit_default_name", number).getString(), null, null));
        host.commit();
        host.refresh();
    }

    private void removeUnit(int index) {
        if (!editable() || index < 0 || index >= building.units().size()) return;
        host.pushHistory();
        building.units().remove(index);
        host.commit();
        host.refresh();
    }

    private void rename(CreationUnit unit, String value) {
        if (!editable() || unit == null || value.equals(unit.label())) return;
        unit.setLabel(value);
        host.recordEdit("unit.label." + unit.id());
        host.commit();
    }

}
