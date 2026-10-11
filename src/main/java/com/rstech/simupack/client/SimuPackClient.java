package com.rstech.simupack.client;

import com.rstech.simupack.SimuPackMod;
import com.rstech.simupack.client.attention.AttentionBadgeHook;
import com.rstech.simupack.client.screen.PackManagerScreen;
import com.rstech.simupack.client.screen.SingleplayerEditorScreen;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = SimuPackMod.MOD_ID, value = Dist.CLIENT)
public final class SimuPackClient {
    private static final KeyMapping OPEN_EDITOR = new KeyMapping(
            "key.simupack.open_editor", GLFW.GLFW_KEY_P, "key.categories.simupack");

    private SimuPackClient() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_EDITOR);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
            return;
        }
        while (OPEN_EDITOR.consumeClick()) {
            if (minecraft.hasSingleplayerServer()) {
                minecraft.setScreen(new SingleplayerEditorScreen(minecraft.screen));
            }
        }
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        int left = event.getScreen().width - 28;
        int top = 8;
        Button button = Button.builder(Component.literal("P"), clicked -> {
                // 启动阶段通常已经有扫描快照；这里先做指纹门控刷新，避免新界面构造后才发现数据过期。
                com.rstech.simupack.pack.BuildingPackManager.reload();
                Minecraft.getInstance().setScreen(new PackManagerScreen(event.getScreen()));
        })
                .bounds(left, top, 20, 20).build();
        event.addListener(button);

        // 把按钮交给红点钩子：只存引用，不做 IO、不触发刷新。
        AttentionBadgeHook.attach(event.getScreen(), button);
        // 标题界面上只读缓存；只有"还没算过 / 放了很久"才补一次后台刷新。
        // 刷新走后台线程——这里仍在界面初始化路径上，不能读盘。
        AttentionBadgeHook.maybeRefreshOnTitleScreen();
    }

    /**
     * 整屏最后一个绘制机会（连 tooltip 都在它之前），红点画在这里：
     * 既绝对最顶层，也不会把 SDF 改过的渲染状态泄漏给同屏其它控件。
     */
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        AttentionBadgeHook.onRenderPost(event);
    }
}
