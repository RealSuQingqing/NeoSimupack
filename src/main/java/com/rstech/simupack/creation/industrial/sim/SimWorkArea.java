package com.rstech.simupack.creation.industrial.sim;

/**
 * 成簇收获的作业范围。
 *
 * <p>真实实现（NSUK {@code IndustrialWorkAreaService} + {@code IndustrialBlockClusterHarvestService}）
 * 是「建筑外框向外扩的**环**」，再按 {@code minYOffset}/{@code maxYOffset} 调上下界：
 *
 * <ul>
 *   <li>扫描的是**环**，不是整个盒子：`cursor.ring()` 从 {@code max(1, startOffset)} 走到
 *       {@code radius}（{@code IndustrialBlockClusterHarvestService:952}、`:960-965`、`:987`）
 *       —— 所以贴着建筑的 `startOffset - 1` 圈**永远不扫**；</li>
 *   <li>{@code radius == 0}（连 `startOffset` 也没配）时环集合为空 ⇒ 首次执行就 {@code AREA_EMPTY}，
 *       **什么都不收**（不是「收建筑自己」）；</li>
 *   <li>{@code excludeBuilding} 为真时，x/z 落在建筑投影内的整列跳过（`：980`，只看 XZ 不看 Y）——
 *       配 {@code radius: 0} 时这条就等于「不砍建筑自己」。**它不是惰性字段**（我早先搞错过）。</li>
 * </ul>
 *
 * @param min            作业盒最小角（含）
 * @param max            作业盒最大角（含）
 * @param buildingMin    建筑外框最小角；为 null 表示不做建筑排除
 * @param buildingMax    建筑外框最大角
 * @param exclusionMin   排除列的最小角（x/z 投影，通常是建筑外框）
 * @param exclusionMax   排除列的最大角
 * @param startOffset    从建筑外框往外数，前这么多圈不扫
 * @param radius         向外扫到第几圈；0 = 不扫（AREA_EMPTY）
 */
public record SimWorkArea(SimPos min, SimPos max, SimPos buildingMin, SimPos buildingMax,
                          SimPos exclusionMin, SimPos exclusionMax, int startOffset, int radius) {

    /** 拿不到几何时用：所有判断都返回 false，调用方据此标注「需要真实世界」。 */
    public static final SimWorkArea UNKNOWN =
            new SimWorkArea(null, null, null, null, null, null, 1, 0);

    /**
     * 旧的四参构造：**不做环规则**，盒子里的坐标全算作业区（没有排除列）。
     *
     * <p>{@code startOffset = -1} 就是「无环规则」的哨兵 —— 保留这个构造是为了少改调用方
     * （单元测试里手搭的作业盒都是这个形态）；真实配置一律走八参构造，环规则由
     * {@code workArea.startOffset}/{@code radius} 决定。
     */
    public SimWorkArea(SimPos min, SimPos max, SimPos buildingMin, SimPos buildingMax) {
        this(min, max, buildingMin, buildingMax, null, null, -1, Integer.MAX_VALUE);
    }

    public boolean known() {
        return this.min != null && this.max != null;
    }

    public boolean contains(SimPos pos) {
        if (!known() || pos == null) {
            return false;
        }
        if (pos.x() < this.min.x() || pos.x() > this.max.x()
                || pos.y() < this.min.y() || pos.y() > this.max.y()
                || pos.z() < this.min.z() || pos.z() > this.max.z()) {
            return false;
        }
        return inScanRings(pos) && !inExcludedColumn(pos);
    }

    /** 这一格在第几圈（贴着**建筑外框**是 0 圈，往外递增）。 */
    public int ringOf(SimPos pos) {
        if (!known() || pos == null) {
            return -1;
        }
        // 必须用**建筑外框**量，不能用作业盒：作业盒本身就是按 radius 扩出来的，
        // 拿它量的话盒内的格子永远是 0 圈，环规则就形同虚设（写测试时抓到的）。
        SimPos rectMin = this.buildingMin != null ? this.buildingMin : this.min;
        SimPos rectMax = this.buildingMax != null ? this.buildingMax : this.max;
        int outX = Math.max(0, Math.max(rectMin.x() - pos.x(), pos.x() - rectMax.x()));
        int outZ = Math.max(0, Math.max(rectMin.z() - pos.z(), pos.z() - rectMax.z()));
        return Math.max(outX, outZ);
    }

    /** 环集合是 {@code [max(1, startOffset), radius]} —— 贴着建筑的前几圈不扫。 */
    private boolean inScanRings(SimPos pos) {
        int ring = ringOf(pos);
        if (ring < 0) {
            return false;
        }
        if (this.startOffset < 0) {
            // 哨兵：这条作业区不做环规则（旧的四参构造 / 手搭的测试盒）。
            return true;
        }
        return ring >= Math.max(1, this.startOffset) && ring <= this.radius;
    }

    /** 排除列：x/z 落在排除矩形内就整列跳过（不看 y）。 */
    private boolean inExcludedColumn(SimPos pos) {
        if (this.exclusionMin == null || this.exclusionMax == null) {
            return false;
        }
        return pos.x() >= this.exclusionMin.x() && pos.x() <= this.exclusionMax.x()
                && pos.z() >= this.exclusionMin.z() && pos.z() <= this.exclusionMax.z();
    }

    /** 是否落在建筑外框内（落在建筑内的**簇**会被整个否决）。 */
    public boolean insideBuilding(SimPos pos) {
        if (pos == null || this.buildingMin == null || this.buildingMax == null) {
            return false;
        }
        return pos.x() >= this.buildingMin.x() && pos.x() <= this.buildingMax.x()
                && pos.y() >= this.buildingMin.y() && pos.y() <= this.buildingMax.y()
                && pos.z() >= this.buildingMin.z() && pos.z() <= this.buildingMax.z();
    }
}
