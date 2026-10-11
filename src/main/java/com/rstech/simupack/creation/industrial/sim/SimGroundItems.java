package com.rstech.simupack.creation.industrial.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「地上的掉落物池」—— 剪毛、屠宰、以及背包溢出时掉下来的东西先落在这里。
 *
 * <p>为什么要单独立一个池：NSUK 的产物**不是直接进容器的**。剪毛是
 * {@code IShearable.onSheared(...)} 拿到掉落再 {@code Block.popResource(...)}
 * （`IndustrialEntityActionService:124-129`），屠宰是动物自然掉落（`:95-98`），
 * 背包塞不下时也会 {@code popResource} 到工人脚下（`IndustrialCarriedItemService:148/155/189`、
 * `IndustrialBlockClusterHarvestService:408-415`）。而 `collect_drops` 是**走过去把地上的
 * ItemEntity 捡进工人背包**（`:146-173`），`require_drops` 查的也是地上**真实存在**的掉落
 * （`:104-106`、`:175-177`）。
 *
 * <p>预览里没有真实的 ItemEntity，所以这里用一本「地上有什么」的账代替 ——
 * 和 {@link SimBackpack} 用账本代替 14 格背包是同一个思路：**只记物品与数量**。
 * 真正的空间判定（背包格、容器格）留给各自的类去做。
 *
 * <p>顺序保持插入序：同一份配置跑两遍，捡的顺序要一样。
 */
public final class SimGroundItems {

    /** 物品 id → 数量（插入序）。 */
    private final Map<String, Integer> items = new LinkedHashMap<>();

    /** 往地上掉一批东西。 */
    public void drop(String item, int count) {
        if (item == null || item.isBlank() || count <= 0) {
            return;
        }
        this.items.merge(item, count, Integer::sum);
    }

    public void dropAll(Map<String, Integer> contents) {
        contents.forEach(this::drop);
    }

    /** 地上某个物品有多少。 */
    public int count(String item) {
        return this.items.getOrDefault(item, 0);
    }

    /** 地上有几个物品（不同 id 的数量，不是件数）。 */
    public int kinds() {
        return this.items.size();
    }

    public boolean isEmpty() {
        return this.items.isEmpty();
    }

    /** 地上现有什么（插入序，只读视图）。 */
    public Map<String, Integer> contents() {
        return Map.copyOf(this.items);
    }

    /**
     * 从地上捡走一件东西（捡多少由调用方按「捡到的数量」定）。
     *
     * @return 实际捡走的数量（不够就捡现有的）
     */
    public int take(String item, int count) {
        if (item == null || count <= 0) {
            return 0;
        }
        int available = this.items.getOrDefault(item, 0);
        if (available <= 0) {
            return 0;
        }
        int taken = Math.min(available, count);
        int left = available - taken;
        if (left <= 0) {
            this.items.remove(item);
        } else {
            this.items.put(item, left);
        }
        return taken;
    }

    public void clear() {
        this.items.clear();
    }

    @Override
    public String toString() {
        return this.items.toString();
    }
}
