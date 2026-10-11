package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

import java.util.List;

/**
 * 「工业」分类的定义字段表：顶层标量 + {@code spawnEntity} + {@code workArea}。
 *
 * <p>字段全部照 NSUK 的 {@code IndustrialDefinitionLoader} 逐键确认：只列加载器真的会读的键，
 * 并保留它接受的别名与缺省值。
 *
 * <p>配方（{@code recipes[]}）不在这里：一个工业建筑可以有<b>多个配方</b>，
 * 每个配方各有一个动作组，所以配方以列表形式单独渲染，而不是扁平的字段。
 */
public final class IndustrialDefinitionFields {

    private IndustrialDefinitionFields() {
    }

    public static final List<SettingSection> SECTIONS = List.of(
            new SettingSection("basic", SettingSection.ROOT, List.of(
                    // 缺省回落：id → 文件名，name → id。
                    DefinitionField.text("id", ""),
                    DefinitionField.text("name", ""),
                    // 加载器接受 JobType / job_type；缺省 industrial_worker。
                    DefinitionField.text("jobType", "industrial_worker", "JobType", "job_type"),
                    // 缺省回落成 name。
                    DefinitionField.text("jobName", "", "JobName", "job_name"),
                    DefinitionField.text("heldItem", ""))),

            new SettingSection("spawn", "spawnEntity", List.of(
                    DefinitionField.flag("enabled", false),
                    DefinitionField.text("type", ""),
                    DefinitionField.integer("count", 0, 0, Integer.MAX_VALUE))),

            new SettingSection("work_area", "workArea", List.of(
                    // 0 表示不向外辐射，直接用建筑自身外框（IndustrialWorkAreaService:14）。
                    DefinitionField.integer("radius", 0, 0, Integer.MAX_VALUE),
                    // 裁剪扫描的起始偏移（IndustrialBlockClusterHarvestService:965）。
                    DefinitionField.integer("startOffset", 0, 0, Integer.MAX_VALUE, "start_offset"),
                    DefinitionField.integer("minYOffset", -8, Integer.MIN_VALUE, Integer.MAX_VALUE,
                            "min_y_offset"),
                    DefinitionField.integer("maxYOffset", 24, Integer.MIN_VALUE, Integer.MAX_VALUE,
                            "max_y_offset"),
                    DefinitionField.flag("excludeBuilding", true, "exclude_building"),
                    DefinitionField.integer("scanColumnsPerTick", 64, 1, Integer.MAX_VALUE,
                            "scan_columns_per_tick"))));

    /**
     * {@code workArea} 里被加载器解析、但<b>全代码库没有读取者</b>的键。
     *
     * <p>{@code type}：{@code parseWorkArea} 会把它读进 record
     * （{@code IndustrialDefinitionLoader:237}），但没有任何地方比较过
     * {@code workArea.type()}。
     *
     * <p>{@code excludeBuilding}：NSUK 里同样是**惰性的** —— 真实的成簇收获对「建筑以内」
     * 是**无条件**否决的（不砍建筑本身），和这个开关无关。本模组照此实现：无条件否决，
     * 所以它不再是可编辑的设定，只在表单里作只读提示（老 JSON 里留着也不影响）。
     */
    public static final List<String> UNUSED_WORK_AREA_KEYS = List.of("type");
}
