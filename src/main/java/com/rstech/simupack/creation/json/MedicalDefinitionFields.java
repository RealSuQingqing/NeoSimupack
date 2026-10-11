package com.rstech.simupack.creation.json;

import java.util.List;

/**
 * 「公共」分类（NSUK 里叫 medical / 医疗）的定义字段表。
 *
 * <p><b>这是所有分类里最小的 schema</b>：整个 JSON 只有三个被解析的键。
 * 依据 {@code MedicalDefinitionLoader.parse}（MDL:54-72）与
 * {@code MedicalDefinitionSourceResolver}（MDSR:17-52）。
 *
 * <p>位置：{@code buildings/public/<id>.json}（{@code medical} 经
 * {@code BuildingPackageCatalog:551-553} 归一为 {@code public}）。
 * 显式声明走 {@code .sk} 的 {@code medical:} 行——注意它<b>不校验文件是否存在、也不回退同名</b>。
 */
public final class MedicalDefinitionFields {

    private MedicalDefinitionFields() {
    }

    public static final List<SettingSection> SECTIONS = List.of(
            new SettingSection("medical", SettingSection.ROOT, List.of(
                    // 只在建筑包扫描期被 BPC:359-380 读，用来把建筑判成 MEDICAL / BANK / EXCHANGE。
                    // 取值大小写不敏感；其它值一律「非 MEDICAL」。
                    DefinitionField.choice("type", "",
                            "hospital", "medical", "bank", "exchange", "stock",
                            "simukraft:hospital", "simukraft:bank", "simukraft:exchange"),
                    // 唯一被读的展示用字段：MCS:42 → 医疗控制盒标题。
                    DefinitionField.text("name", ""),
                    // 服务范围环数：缺省 3，读进来后被 Math.clamp(x, 1, 6) 夹住（MDL:63）。
                    DefinitionField.integer("serviceRangeRings", 3, 1, 6))));

    /**
     * 容易被误写、要显式提醒的键。
     *
     * <p>{@code service_range_rings} 看起来像 {@code serviceRangeRings} 的蛇形别名，
     * 但 {@code MedicalDefinitionLoader:62} <b>只读驼峰拼写</b>——写了蛇形键 rings 仍是 3。
     * 它唯一的读取者是 {@code BuildingPackageCatalog:376}，且只用它把建筑判为 MEDICAL。
     */
    public static final String SNAKE_CASE_TRAP = "service_range_rings";
}
