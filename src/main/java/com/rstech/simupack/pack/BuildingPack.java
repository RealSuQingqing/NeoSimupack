package com.rstech.simupack.pack;

import com.rstech.simupack.creation.PackFormat;
import com.rstech.simupack.creation.PackFormat.Compatibility;

import java.nio.file.Path;
import java.util.List;

public record BuildingPack(
        String id,
        String name,
        String version,
        String author,
        String description,
        Path path,
        int buildingCount,
        List<String> errors,
        boolean official,
        /** 文件是否处于启用状态（即 NSUK 会读到它；禁用包文件名带 {@code .disabled}）。 */
        boolean enabled,
        /**
         * 这个包的内容有没有被成功读出来（ZIP 打得开、{@code pack.json} 解析得动）。
         *
         * <p>和 {@link #errors()} 不是一回事：{@code errors} 只记"哪栋建筑有问题"时，
         * 包本身仍然是可用的。这个字段记的是"整个包到底读没读懂"，界面靠它决定
         * 要不要把启用/禁用按钮禁掉。
         */
        boolean readable,
        /**
         * {@code pack.json} 里的 {@code format}：1.20.1=1、1.21.1=2、26.3=3。
         *
         * <p>{@link PackFormat#UNKNOWN}（0）表示这个包没声明格式号 —— 官方
         * 1.21.1 包是 2，而 1.20.1 时代的老包大多没写。见 {@link PackFormat}。
         */
        int format
) {
    /** 兼容旧调用点：默认按"已启用 + 读得出来 + 不声明格式"处理。 */
    public BuildingPack(String id, String name, String version, String author, String description,
                        Path path, int buildingCount, List<String> errors, boolean official) {
        this(id, name, version, author, description, path, buildingCount, errors, official, true, true,
                PackFormat.UNKNOWN);
    }

    /** 兼容重载：已知启用/可读状态，但包没声明格式号。 */
    public BuildingPack(String id, String name, String version, String author, String description,
                        Path path, int buildingCount, List<String> errors, boolean official,
                        boolean enabled, boolean readable) {
        this(id, name, version, author, description, path, buildingCount, errors, official, enabled, readable,
                PackFormat.UNKNOWN);
    }

    /** 这个包的格式号相对本版本线是否兼容。 */
    public Compatibility formatCompatibility() {
        return PackFormat.check(format);
    }

    /**
     * 格式号与本版本线不符（旧版线或新版线）。
     *
     * <p>只影响<b>提示与校验</b>，不影响 {@link #unusable()}：真正的可用性由
     * "读得出来 + 有建筑"决定，格式不符的包在游戏里可能仍然能加载 ——
     * 该做的是把话说清楚，而不是替用户把包判死。
     */
    public boolean formatMismatch() {
        return !formatCompatibility().usable();
    }

    /** 这个包声明的 Minecraft 版本名；未知格式返回 {@code null}。 */
    public String formatMinecraftVersion() {
        return PackFormat.minecraftVersion(format);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    public boolean hasVersion() {
        return version != null && !version.isBlank();
    }

    /**
     * 这个包的有效建筑数为零——读得出来，但一栋能用的建筑都没有。
     *
     * <p>和 {@link #valid()} 分开：包里缺 {@code pack.json} 或结构是"格式错误"，
     * 而这里是"格式没问题，只是内容空"。两者在界面上都是红灯，但原因文案不同。
     */
    public boolean hasNoBuildings() {
        return buildingCount <= 0;
    }

    /**
     * 这个包对游戏完全没用：读不出来（NSUK 根本不认），或者一栋建筑都加载不出来。
     *
     * <p>这类包既不该被启用（启用也不会有任何建筑出现），也不该被禁用（它不是"暂时不用"，
     * 而是坏的）。界面上按钮置灰，服务层也会拒绝——两条路径都要挡，避免绕过界面调用时
     * 把一个坏包写进建筑目录。
     */
    public boolean unusable() {
        return !readable || hasNoBuildings();
    }

    /**
     * 界面上该不该显示红灯：读不出来、格式有问题、或者没有任何可用建筑。
     *
     * <p>比 {@link #unusable()} 宽一格：包里个别建筑缺结构文件时仍有红灯提示，
     * 但这个包整体还是能用的，所以不锁按钮。
     */
    public boolean faulty() {
        return !valid() || !readable || hasNoBuildings();
    }

    /**
     * 在列表/树里唯一标识这个包<b>在磁盘上的这一个文件</b>。
     *
     * <p>{@link #id()} 是包自身的身份，启用与禁用两种状态相同；而磁盘上同一时刻只会存在
     * 其中一个文件，所以界面用它做节点 key 就够了。带上状态是为了让"刚被禁用的包"在
     * 界面重建时拿到新 key，不会被旧选中态认成同一个节点。
     */
    public String packId() {
        return enabled ? id : id + "#disabled";
    }
}
