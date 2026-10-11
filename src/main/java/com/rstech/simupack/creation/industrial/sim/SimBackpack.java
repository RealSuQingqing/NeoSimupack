package com.rstech.simupack.creation.industrial.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工人随身背包 —— **真实的 14 格 × 堆叠上限**模型。
 *
 * <p>为什么需要它：真实的成簇收获、剪毛、屠宰**都不把掉落放进容器**，而是落在地上、
 * 由 {@code collect_drops} 捡进工人的随身背包（{@code IndustrialCarriedItemService.addItems}），
 * 再由 {@code deposit_carried_items} 搬进输出容器。它跨步骤存活：在 {@code run} 里建一次。
 *
 * <p><b>格子是真格子</b>（原来是「一种物品算一格」的近似，那会让 100000 根原木只占 1 格，
 * 于是 `CARRY_FULL` 永远触发不了）：14 格，每格最多 {@value #STACK_LIMIT} 个；
 * 同一种物品超过一格就再占一格。这与 {@code occupiedBackpackSlots()} 的口径一致。
 *
 * <p><b>近似之处</b>：真实实现按**物品自己的最大堆叠数**（不可堆叠物 1 个 1 格）算，
 * 内核没有物品元数据，所以一律按 64 算 —— 这会让「27 格箱子装 1728 个非堆叠物」这类
 * 边界偏乐观，已记在审计清单里（`SIMUPACK_SIMULATION_REVIEW.md` 23.4）。
 */
public final class SimBackpack {

    /** 真实背包的格数上限 —— 写再大的 maxCarryStacks 也超不过它。 */
    public static final int SLOTS = 14;

    /** 每格堆叠上限。原版多数物品是 64；内核拿不到物品元数据，一律按 64（见类注释）。 */
    public static final int STACK_LIMIT = 64;

    /** 一格：物品 id + 里面的数量（{@code public}：界面要按格画背包）。 */
    public record Slot(String item, int count) {
        Slot with(int newCount) {
            return new Slot(this.item, newCount);
        }
    }

    private final List<Slot> slots = new ArrayList<>();

    /**
     * 往背包里放，放不下就少放。
     *
     * @return **没放进去的数量**（0 = 全放下了）。NSUK 的 {@code insertBackpackAll}
     *         返回的就是剩余量，调用方据此判定 {@code CARRY_FULL}。
     */
    public int add(String item, int count) {
        if (item == null || item.isBlank() || count <= 0) {
            return 0;
        }
        int left = count;
        // 先补已有格子（原版 insert 也是先补同 id 的格子）
        for (int index = 0; index < this.slots.size() && left > 0; index++) {
            Slot slot = this.slots.get(index);
            if (!slot.item().equals(item) || slot.count() >= STACK_LIMIT) {
                continue;
            }
            int room = STACK_LIMIT - slot.count();
            int placed = Math.min(room, left);
            this.slots.set(index, slot.with(slot.count() + placed));
            left -= placed;
        }
        // 再开新格
        while (left > 0 && this.slots.size() < SLOTS) {
            int placed = Math.min(STACK_LIMIT, left);
            this.slots.add(new Slot(item, placed));
            left -= placed;
        }
        return left;
    }

    /** 逐格内容（只含非空格，按格位顺序）—— 界面按它一格一个图标画，不再自己摊。 */
    public java.util.List<Slot> slots() {
        return java.util.List.copyOf(this.slots);
    }

    /** 占用格数（真格子数）。 */
    public int occupiedSlots() {
        return this.slots.size();
    }

    public boolean isEmpty() {
        return this.slots.isEmpty();
    }

    /** 背包里有什么（物品 id → 数量，按首次出现的顺序）。 */
    public Map<String, Integer> contents() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Slot slot : this.slots) {
            totals.merge(slot.item(), slot.count(), Integer::sum);
        }
        return Map.copyOf(totals);
    }

    /** 按选择器（物品 id 或 {@code #标签}）找一个匹配的物品 id；找不到返回 null。 */
    public String findItem(SimLootProvider loot, String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }
        for (Slot slot : this.slots) {
            if (Boolean.TRUE.equals(loot.itemMatches(selector, slot.item()))) {
                return slot.item();
            }
        }
        return null;
    }

    /** 取走若干（不够就返回 false 且不动）。 */
    public boolean take(String item, int count) {
        int have = 0;
        for (Slot slot : this.slots) {
            if (slot.item().equals(item)) {
                have += slot.count();
            }
        }
        if (have < count) {
            return false;
        }
        int left = count;
        for (int index = this.slots.size() - 1; index >= 0 && left > 0; index--) {
            Slot slot = this.slots.get(index);
            if (!slot.item().equals(item)) {
                continue;
            }
            int taken = Math.min(slot.count(), left);
            left -= taken;
            if (slot.count() == taken) {
                this.slots.remove(index);
            } else {
                this.slots.set(index, slot.with(slot.count() - taken));
            }
        }
        return true;
    }

    public void clear() {
        this.slots.clear();
    }

    @Override
    public String toString() {
        return "背包" + this.slots;
    }
}
