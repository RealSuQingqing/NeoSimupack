package com.rstech.simupack;

import com.rstech.simupack.attention.AttentionCenter;
import com.rstech.simupack.attention.JsonSelfUpdateVersionSource;
import com.rstech.simupack.attention.NsukOfficialPackVersionSource;
import com.rstech.simupack.pack.BuildingPackManager;
import com.rstech.simupack.pack.NsukCatalogBridge;
import com.rstech.simupack.pack.OfficialPackUpdateService;
import com.rstech.simupack.pack.PackActivation;
import net.minecraft.Util;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SimuPackMod.MOD_ID)
public final class SimuPackMod {
    public static final String MOD_ID = "simupack";
    public static final String MOD_NAME = "NeoSimupack";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public SimuPackMod(IEventBus modBus) {
        modBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // PackActivation / NsukCatalogBridge 默认不写日志（它们的纯逻辑要能在没有 slf4j 的
        // 单元测试里跑），进了游戏再打开。红点这一套同理。
        PackActivation.enableLogging();
        NsukCatalogBridge.enableLogging();
        AttentionCenter.enableLogging();
        NsukOfficialPackVersionSource.enableLogging();
        JsonSelfUpdateVersionSource.enableLogging();
        OfficialPackUpdateService.enableLogging();

        event.enqueueWork(BuildingPackManager::reload);

        // 首次算一遍红点快照：走后台线程，不拖慢启动。
        // AttentionCenter 的初始快照是空的（不亮），所以"还没算完"时红点保持熄灭——
        // 宁可晚一会儿亮，也不能先亮一下再灭。
        event.enqueueWork(() -> AttentionCenter.refreshAsync(Util.backgroundExecutor()));
    }
}
