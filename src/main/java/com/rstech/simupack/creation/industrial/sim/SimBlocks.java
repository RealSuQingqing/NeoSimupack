package com.rstech.simupack.creation.industrial.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仿真**自己放下去的方块**的台账（坐标 → 方块 id）。
 *
 * <p>为什么必须有它：结构文件里那些「操作台」位置本来就是空气，方块是配方里的
 * {@code place_block} 一步一步放上去的（砖厂：放泥土 → 放泥巴 → 放黏土 → 挖掉）。
 * 挖的时候如果**按坐标**去问预览世界「这个方块掉什么」，得到的答案是「空气，什么都不掉」，
 * 于是后面 {@code require_drops} 永远判 MISSING_DROPS —— 砖厂卡在第 21 步就是这么来的。
 *
 * <p>所以按坐标问不到东西时，先查这本台账拿方块 id，再按 id 问掉落
 * （{@link SimLootProvider#blockDropsForBlock}）。
 *
 * <p>只记「本轮的虚拟改动」，不改结构文件本身；一轮仿真一份，重播即重置。
 */
public final class SimBlocks {

    private final Map<String, String> placed = new LinkedHashMap<>();

    /** 记下这个坐标现在是什么方块（{@code place_block} / {@code place_fluid} 之后调用）。 */
    public void place(SimPos pos, String blockId) {
        if (pos == null || blockId == null || blockId.isBlank()) {
            return;
        }
        this.placed.put(key(pos), blockId.trim());
    }

    /** 这个坐标上「仿真认为」是什么方块；不是仿真放的返回 {@code null}。 */
    public String blockAt(SimPos pos) {
        return pos == null ? null : this.placed.get(key(pos));
    }

    /** 这个坐标已经被挖掉了（{@code destroy_block} / 收获之后调用）。 */
    public void remove(SimPos pos) {
        if (pos != null) {
            this.placed.remove(key(pos));
        }
    }

    public int size() {
        return this.placed.size();
    }

    private static String key(SimPos pos) {
        return pos.x() + "," + pos.y() + "," + pos.z();
    }
}
