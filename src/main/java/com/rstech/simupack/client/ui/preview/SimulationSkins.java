package com.rstech.simupack.client.ui.preview;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 仿真市民的皮肤库。
 *
 * <p>每次**启动一轮仿真**时从库里随机挑一张，让同一个动作组跑两遍看起来不是同一个「人」。
 *
 * <p>加新皮肤只要两步：把 64×64 的玩家皮肤 PNG（**文件名用 ASCII**）放进
 * {@code assets/simupack/textures/entity/}，然后在 {@link #SKINS} 里加一行。
 *
 * <p>模型用的是**细手臂（slim / Alex）**布局，皮肤图请按 3 像素宽的手臂画。
 */
@OnlyIn(Dist.CLIENT)
public final class SimulationSkins {
    /**
     * 一张皮肤：贴图 + 显示名。
     *
     * <p><b>文件名必须是 ASCII。</b> Minecraft 的 {@code ResourceLocation} 只接受
     * {@code [a-z0-9/._-]}，中文、空格、全角冒号都是非法字符 —— 写进去会在<b>静态初始化</b>时抛异常，
     * 表现就是游戏直接闪退（踩过一次）。所以「显示名」可以随便起，「文件名」不行，
     * 两者分开存正是因为这一点。
     */
    public record Skin(ResourceLocation texture, String name) {
    }

    /**
     * 皮肤库。加新皮肤：把 ASCII 文件名的 64×64 皮肤放进
     * {@code assets/simupack/textures/entity/}，再在这里加一行。
     */
    private static final List<Skin> SKINS = List.of(
            new Skin(ResourceLocation.fromNamespaceAndPath("simupack", "textures/entity/sqq.png"),
                    "\u4f5c\u8005\uff1a\u82cf\u6674\u6674")
    );

    private SimulationSkins() {
    }

    /** 显示名默认取文件名；只有名字不能做文件名时才在这里覆盖。 */
    public static String nameOf(ResourceLocation skin) {
        String path = skin == null ? "" : skin.getPath();
        int slash = path.lastIndexOf(47);
        String file = slash < 0 ? path : path.substring(slash + 1);
        return file.endsWith(".png") ? file.substring(0, file.length() - 4) : file;
    }

    /** tooltip 用的名字：库里登记的优先，查不到才退回文件名。 */
    public static String displayName(ResourceLocation skin) {
        for (Skin entry : SKINS) {
            if (entry.texture().equals(skin)) {
                return entry.name();
            }
        }
        return nameOf(skin);
    }

    /** 库里第一张，作为默认值（模型还没开跑时也会用到）。 */
    public static ResourceLocation defaultSkin() {
        return SKINS.get(0).texture();
    }

    public static int size() {
        return SKINS.size();
    }

    /** 随机挑一张。库里只有一张时就是它本身。 */
    public static ResourceLocation random(RandomSource random) {
        return SKINS.isEmpty() ? defaultSkin() : SKINS.get(random.nextInt(SKINS.size())).texture();
    }
}
