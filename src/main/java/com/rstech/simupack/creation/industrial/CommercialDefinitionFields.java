package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

import java.util.List;

/**
 * 「商业」分类的定义字段表。
 *
 * <p>依据 {@code CommercialDefinitionLoader.loadText}（CDL:104-124）逐键确认。
 * 位置 {@code buildings/commercial/<id>.json}，显式声明走 {@code .sk} 的 {@code commercial:} 行，
 * 失败时**仍会回退同名 JSON**（这点与工业一致）。
 *
 * <p>本表只覆盖<b>设定部分</b>：顶层标量、{@code job}、{@code workTime}。
 * 报价（{@code offers}）是一组嵌套数组（每条报价还带 {@code cost}/{@code result}/{@code stock}），
 * 体量与工业的「配方 + 动作组」相当，另行处理；{@code containers} 与工业的 {@code points}
 * 一样属于「等建筑预览能用了再在预览里点方块定位」那一类。
 */
public final class CommercialDefinitionFields {

    private CommercialDefinitionFields() {
    }

    public static final List<SettingSection> SECTIONS = List.of(
            new SettingSection("commercial_basic", SettingSection.ROOT, List.of(
                    // 兜底：JSON 文件名去扩展名后转小写；record 再兜底 "commercial"。
                    DefinitionField.text("id", "", "buildingId", "building_id"),
                    // 缺省回落成 id。
                    DefinitionField.text("name", "", "buildingName", "building_name"))),

            new SettingSection("commercial_job", "job", List.of(
                    DefinitionField.text("id", "commercial_worker"),
                    // 唯一被读的 job 子键之一（CWS:122 applyHeldItem）；别名 held_item。
                    DefinitionField.text("heldItem", "", "held_item"))),

            new SettingSection("commercial_work_time", "workTime", List.of(
                    // 两个值都会先 Math.floorMod(x, 24000)；start == end 视为全天营业。
                    // 这里用 0..23999 卡住，避免写出无意义的负数或超过一天的 tick。
                    DefinitionField.integer("start", 0, 0, 23999),
                    DefinitionField.integer("end", 0, 0, 23999))));

    /**
     * 无 {@code job} 对象时会被当成 job 兜底的顶层键。
     *
     * <p>{@code CommercialDefinitionLoader:126-140}：没有 {@code job} 对象时，
     * 顶层 {@code jobType}/{@code job_type}、{@code jobName}/{@code job_name}、
     * {@code heldItem}/{@code held_item} 会合成一个 JobDefinition。
     *
     * <p>但<b>一旦存在 {@code job} 对象，这三个顶层键就被完全忽略</b>——
     * 所以「既有顶层 jobType、又在表单里改了 job.id」会让顶层那个键静默失效。这是个坑。
     */
    public static final List<String> JOB_FALLBACK_KEYS =
            List.of("jobType", "job_type", "jobName", "job_name", "heldItem", "held_item");
}
