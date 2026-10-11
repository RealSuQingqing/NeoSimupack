package com.rstech.simupack.creation.industrial.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仿真用的容器库存模型：容器名 → 物品键 → 数量。
 *
 * <p>三点刻意的设计，都是为了不再出现旧版那种「看着对、其实是假的」：
 * <ol>
 *   <li><b>按容器名记账</b>。游戏里库存挂在具体方块坐标上，而点位定义里多个容器名可以指向
 *       同一格方块。编辑器场景里没有真实方块实体，按名字记账是唯一能自洽的模型；
 *       这一点会显示在界面上，不会假装它是方块级精度。</li>
 *   <li><b>容量显式建模</b>。默认不限量（与旧版行为一致），但 {@code require_output_space}
 *       与 {@code craft_recipe} 会真的走一次容量检查 —— 容量被设成有限值时，
 *       「输出箱满了」这条分支在预览里就能跑出来，而不是永远报成功。</li>
 *   <li><b>取用是原子的</b>。{@link #take} 要么扣满、要么一点都不扣，
 *       这样调用方不需要自己写回滚（旧版 {@code craft_recipe} 缺第二样材料时
 *       已经把第一样扣掉了，状态从此错到底）。</li>
 * </ol>
 */
public final class SimInventory {

    public static final String DEFAULT_INPUT = "input";
    public static final String DEFAULT_OUTPUT = "output";

    /** 不限量。 */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /** 默认每格堆叠上限（原版多数物品是 64）。 */
    public static final int DEFAULT_STACK_LIMIT = 64;

    private final Map<String, LinkedHashMap<String, Integer>> containers = new LinkedHashMap<>();
    private final Map<String, Integer> capacities = new LinkedHashMap<>();
    /** 容器 → 每格自己的上限（客户端从 IItemHandler.getSlotLimit 读；没读到时为空）。 */
    private final Map<String, int[]> slotLimits = new LinkedHashMap<>();

    /**
     * 物品 → 每格堆叠上限。默认 64，客户端会用真实物品数据注入
     * （不可堆叠物是 1，那么一个 27 格的箱子就只能放 27 个 —— 以前按「总件数」算，
     * 27 格箱子能放 1728 个非堆叠物，是审计点名的错）。
     */
    private java.util.function.Function<String, Integer> stackLimits =
            item -> DEFAULT_STACK_LIMIT;
    /**
     * 别名 → 正式名。
     *
     * <p>点位的容器名可以**多个名字指向同一格方块**（最常见的就是 input 和 output 是同一个箱子）。
     * 游戏里库存挂在方块上，所以这些名字必须共用一本账 —— 否则「往 input 放、从 output 看」
     * 会看到两个互不相干的箱子。这里把别名解析到同一个名字上，内核其余部分不用改。
     */
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public SimInventory() {
    }

    public SimInventory copy() {
        SimInventory copy = new SimInventory();
        containers.forEach((name, items) -> copy.containers.put(name, new LinkedHashMap<>(items)));
        copy.capacities.putAll(capacities);
        copy.aliases.putAll(aliases);
        // 堆叠上限也要带过去：不带的话，事务复制出来的那份退回默认 64，
        // 「不可堆叠物占 1 格」的判定在复制之后就失效了。
        copy.stackLimits = stackLimits;
        slotLimits.forEach((name, limits) -> copy.slotLimits.put(name, limits.clone()));
        return copy;
    }

    /** 把另一份库存的内容整体覆盖到本对象上（用于事务回滚）。 */
    public void restore(SimInventory snapshot) {
        containers.clear();
        snapshot.containers.forEach((name, items) -> containers.put(name, new LinkedHashMap<>(items)));
        capacities.clear();
        capacities.putAll(snapshot.capacities);
        aliases.clear();
        aliases.putAll(snapshot.aliases);
        stackLimits = snapshot.stackLimits;
        slotLimits.clear();
        snapshot.slotLimits.forEach((name, limits) -> slotLimits.put(name, limits.clone()));
    }

    public static String containerName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // ── 同一格方块的多个名字 ─────────────────────────────────────────────

    /** 让 {@code alias} 与 {@code canonical} 共用一本账。 */
    public void link(String alias, String canonical) {
        String target = resolve(canonical);
        String source = containerName(alias, DEFAULT_INPUT);
        if (source.equals(target)) {
            return;
        }
        aliases.put(source, target);
    }

    /** 把名字解析到实际记账用的那个名字（跟着别名链走，带防环）。 */
    public String resolve(String container) {
        String name = containerName(container, DEFAULT_INPUT);
        for (int guard = 0; guard < 16 && aliases.containsKey(name); guard++) {
            name = aliases.get(name);
        }
        return name;
    }

    // ── 容量（单位是**格**） ─────────────────────────────────────────────
    //
    // 真实实现永远是「真实槽位 + 每格堆叠上限」（GenericContainerAccess.simulateInsert）。
    // 所以 capacity 表示**格数**，占用格数按逐物品的堆叠上限折算：
    // 一堆 64 根原木占 1 格，64 个不可堆叠的桶占 64 格。

    public void setCapacity(String container, int capacity) {
        capacities.put(resolve(container), Math.max(0, capacity));
    }

    /** 容器的格数（{@link #UNLIMITED} = 不限量）。 */
    public int capacity(String container) {
        return capacities.getOrDefault(resolve(container), UNLIMITED);
    }

    /** 注入「物品 → 每格堆叠上限」。 */
    public void setStackLimits(java.util.function.Function<String, Integer> limits) {
        this.stackLimits = limits == null ? item -> DEFAULT_STACK_LIMIT : limits;
    }

    /**
     * 注入**容器每一格自己的上限**（客户端从 {@code IItemHandler.getSlotLimit(slot)} 读）。
     *
     * <p>为什么不能只用「物品的堆叠上限」：模组容器常常限制某些格位
     * （机器输入格只收 1 个、罐子格只收 16 个……），原版也有 64 以外的格子。
     * 一格能装多少 = {@code min(该格上限, 物品自己的堆叠上限)}。
     *
     * @param limits 从第 0 格开始的上限；null/空数组 = 不知道，按物品自己的上限算
     */
    public void setSlotLimits(String container, int[] limits) {
        if (limits == null || limits.length == 0) {
            slotLimits.remove(resolve(container));
            return;
        }
        slotLimits.put(resolve(container), limits.clone());
    }

    private int stackLimit(String item) {
        Integer limit = this.stackLimits.apply(item);
        return limit == null || limit <= 0 ? 1 : limit;
    }

    /** 这个容器现在占用了多少格。 */
    public int usedSlots(String container) {
        Map<String, Integer> items = containers.get(resolve(container));
        if (items == null) {
            return 0;
        }
        int slots = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            slots += slotsFor(entry.getKey(), entry.getValue());
        }
        return slots;
    }

    /** 这个物品每格最多堆几个（认不出就按默认值）。 */
    public int stackLimitFor(String item) {
        Integer limit = stackLimits.apply(item == null ? "" : item.trim());
        return limit == null || limit <= 0 ? DEFAULT_STACK_LIMIT : limit;
    }

    /** 某个物品若干个要占几格（向上取整，至少 1 格）。 */
    private int slotsFor(String item, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int limit = stackLimit(item);
        return (amount + limit - 1) / limit;
    }

    // ── 读 ─────────────────────────────────────────────────────────────

    public int count(String container, String item) {
        if (item == null || item.isBlank()) {
            return 0;
        }
        Map<String, Integer> items = containers.get(resolve(container));
        return items == null ? 0 : items.getOrDefault(item, 0);
    }

    /** 一格里的东西（{@code count == 0} 表示空格）。 */
    public record Slot(String item, int count) {
    }

    /**
     * 把容器内容按**逐格**摊开，规则和容量判定完全一致：
     * 一格装 {@code min(该格上限, 物品自己的堆叠上限)}，同类物品顺序占格。
     *
     * <p>为什么要有它：{@code add(container, item, 1728)} 记的是**总数** ——
     * 界面直接画一个「1728」的图标是错的，真实容器里那是 27 格 × 64。
     * 超出格数的部分也会作为「多出来的那格」列出来，界面一眼能看出放不下。
     */
    public List<Slot> slotLayout(String container) {
        return slotLayout(container, contents(container));
    }

    /** 同上，但按**给定内容**摊（播放到某一刻时界面拿的是当时的快照，不是初始内容）。 */
    public List<Slot> slotLayout(String container, Map<String, Integer> items) {
        List<Slot> layout = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return layout;
        }
        String name = resolve(container);
        int capacity = capacity(container);
        int[] limits = slotLimits.get(name);
        if (capacity == UNLIMITED) {
            // 读不出格数的容器（模组容器没注册能力）：没有「第几格」可言，按物品各一格。
            items.forEach((item, count) -> {
                if (count > 0) {
                    layout.add(new Slot(item, count));
                }
            });
            return layout;
        }
        int slot = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            int left = entry.getValue() == null ? 0 : entry.getValue();
            int perStack = stackLimitFor(entry.getKey());
            while (left > 0 && slot < capacity) {
                int limit = limits != null && slot < limits.length ? limits[slot] : DEFAULT_STACK_LIMIT;
                int room = Math.max(1, Math.min(perStack, limit));
                int count = Math.min(room, left);
                layout.add(new Slot(entry.getKey(), count));
                left -= count;
                slot++;
            }
            if (left > 0) {
                // 格数不够：真实容器装不下，界面别假装它不在 —— 单列一格显示剩余量。
                layout.add(new Slot(entry.getKey(), left));
            }
        }
        return layout;
    }
    public int countAll(String container) {
        Map<String, Integer> items = containers.get(resolve(container));
        if (items == null) {
            return 0;
        }
        int total = 0;
        for (int value : items.values()) {
            total = (int) Math.min(Integer.MAX_VALUE, (long) total + value);
        }
        return total;
    }

    /** 某个物品描述在该容器里可用的数量（标签按库存键匹配，见 {@link SimItemSpec}）。 */
    public int countSpec(String container, SimItemSpec spec) {
        return count(container, spec.key());
    }

    /** 把「库存键 → 数量」按一个物品描述列表聚合成需求表。 */
    public Map<String, Integer> contents(String container) {
        Map<String, Integer> items = containers.get(resolve(container));
        // **不要用 Map.copyOf**：它不保证顺序，而「先背包后容器、按槽位取第一件匹配」
        // 这类取材顺序要靠插入序（NSUK 的 consumeInputStacks 就是按槽位顺序）。
        return items == null ? Map.of() : java.util.Collections.unmodifiableMap(items);
    }

    public Set<String> containerNames() {
        return Set.copyOf(containers.keySet());
    }

    // ── 写 ─────────────────────────────────────────────────────────────

    public void add(String container, String item, int amount) {
        if (item == null || item.isBlank() || amount <= 0) {
            return;
        }
        containers.computeIfAbsent(resolve(container), ignored -> new LinkedHashMap<>())
                .merge(item.trim(), amount, Integer::sum);
    }

    public void set(String container, String item, int amount) {
        String name = resolve(container);
        if (item == null || item.isBlank() || amount <= 0) {
            LinkedHashMap<String, Integer> items = containers.get(name);
            if (items != null) {
                items.remove(item == null ? "" : item.trim());
            }
            return;
        }
        containers.computeIfAbsent(name, ignored -> new LinkedHashMap<>()).put(item.trim(), amount);
    }

    /** 原子取用：数量不够时返回 false 且不做任何修改。 */
    public boolean take(String container, String item, int amount) {
        if (amount <= 0) {
            return true;
        }
        String name = resolve(container);
        LinkedHashMap<String, Integer> items = containers.get(name);
        if (items == null || item == null) {
            return false;
        }
        String key = item.trim();
        int available = items.getOrDefault(key, 0);
        if (available < amount) {
            return false;
        }
        if (available == amount) {
            items.remove(key);
        } else {
            items.put(key, available - amount);
        }
        return true;
    }

    /** 还能再放几格（不看物品，只看格数）—— 只用于显示，判定请用 {@link #canAccept}。 */
    public int freeSpace(String container) {
        int capacity = capacity(container);
        if (capacity == UNLIMITED) {
            return UNLIMITED;
        }
        return Math.max(0, capacity - usedSlots(container));
    }

    /**
     * 放得下吗 —— **按格**判定：把物品按它自己的堆叠上限折算成格数，看会不会超出容器格数。
     *
     * <p>{@code capacity} 是不限量时永远放得下；否则逐物品折算（不可堆叠物 1 个 1 格）。
     */
    public boolean canAccept(String container, String item, int amount) {
        return canAcceptAll(container, Map.of(item == null ? "" : item.trim(), amount));
    }

    /**
     * 一批物品**一起**放得下吗。
     *
     * <p>为什么需要「一起」：NSUK 的 `require_output_space` 是把整张最坏情况产出表
     * 一次性 `simulateInsert`（`IndustrialInventoryService:35-42`）——
     * 容量 100、两条各 60 的产出必须判失败，逐条比会判成功。
     */
    public boolean canAcceptAll(String container, Map<String, Integer> additions) {
        if (additions == null || additions.isEmpty()) {
            return true;
        }
        int capacity = capacity(container);
        if (capacity == UNLIMITED) {
            return true;
        }
        if (capacity <= 0) {
            return false;
        }
        String name = resolve(container);
        // **逐格**模拟装填（这是最接近真实 simulateInsert 的算法）：
        // 一种物品占若干格，每格装 min(该格上限, 物品堆叠上限)；
        // 现有内容也一起排进去（相当于「把现有内容倒出来重新按最优顺序装」——
        // 对「装得下吗」这个判定来说是最优情形，不会把放得下的判成放不下）。
        Map<String, Integer> pending = new LinkedHashMap<>();
        Map<String, Integer> existing = containers.get(name);
        if (existing != null) {
            existing.forEach((item, count) -> {
                if (count > 0) {
                    pending.merge(item, count, Integer::sum);
                }
            });
        }
        additions.forEach((item, count) -> {
            if (item != null && count != null && count > 0) {
                pending.merge(item.trim(), count, Integer::sum);
            }
        });
        int[] limits = slotLimits.get(name);
        int slot = 0;
        for (Map.Entry<String, Integer> entry : pending.entrySet()) {
            int perStack = stackLimitFor(entry.getKey());
            int left = entry.getValue();
            while (left > 0) {
                if (slot >= capacity) {
                    return false;
                }
                int slotLimit = limits != null && slot < limits.length ? limits[slot] : DEFAULT_STACK_LIMIT;
                left -= Math.max(1, Math.min(perStack, slotLimit));
                slot++;
            }
        }
        return true;
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        containers.forEach((name, items) -> items.forEach((item, count) -> {
            if (count > 0) {
                lines.add(name + " / " + item + " x" + count);
            }
        }));
        return lines;
    }

    /** 用于测试与调试的快照比较。 */
    public Map<String, Map<String, Integer>> snapshot() {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        containers.forEach((name, items) -> result.put(name, Map.copyOf(items)));
        // 别名各出一项（共用同一份内容）：界面是按「悬浮到的那个名字」去查快照的，
        // 少了别名就会出现「同一个箱子换个名字看就空了」。
        aliases.forEach((alias, target) -> {
            Map<String, Integer> items = result.get(target);
            if (items != null) {
                result.put(alias, items);
            }
        });
        return Map.copyOf(result);
    }

    public Set<String> itemsIn(String container) {
        return new LinkedHashSet<>(contents(container).keySet());
    }
}
