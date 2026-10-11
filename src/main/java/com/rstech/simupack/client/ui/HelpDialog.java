package com.rstech.simupack.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 创作空间帮助：按工作流组织的可滚动说明，不改变当前编辑状态。 */
@OnlyIn(Dist.CLIENT)
public final class HelpDialog {
    private static final int SECTION_GAP = 3;

    private HelpDialog() {
    }

    public static void open(UIElement host) {
        if (host == null) {
            return;
        }

        UIElement content = SimuPackLayout.column(SECTION_GAP);
        ScrollerView scroller = SimuPackLayout.scroller();
        scroller.layout(layout -> {
            layout.height(154);
            layout.minHeight(80);
            layout.flexShrink(1);
        });

        UIElement body = SimuPackLayout.column(SECTION_GAP);
        body.addChild(section("gui.simupack.help.getting_started",
                "gui.simupack.help.getting_started.text"));
        body.addChild(section("gui.simupack.help.workspace",
                "gui.simupack.help.workspace.text"));
        body.addChild(section("gui.simupack.help.building",
                "gui.simupack.help.building.text"));
        body.addChild(section("gui.simupack.help.settings",
                "gui.simupack.help.settings.text"));
        body.addChild(section("gui.simupack.help.shortcuts",
                "gui.simupack.help.shortcuts.text"));
        body.addChild(section("gui.simupack.help.files",
                "gui.simupack.help.files.text"));
        scroller.addScrollViewChild(body);
        content.addChild(scroller);

        SimuPackDialog.open(host,
                Component.translatable("gui.simupack.help.title"),
                content,
                overlay -> host.removeChild(overlay),
                () -> { },
                null,
                null,
                null,
                null,
                "gui.simupack.help.close");
    }

    private static UIElement section(String titleKey, String textKey) {
        UIElement section = SimuPackLayout.column(1);
        section.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable(titleKey), SimuPackTheme.TEXT_INFO_COLOR));
        section.addChild(SimuPackLayout.wrappedLabel(
                Component.translatable(textKey), SimuPackTheme.TEXT_SECONDARY_COLOR));
        return section;
    }
}
