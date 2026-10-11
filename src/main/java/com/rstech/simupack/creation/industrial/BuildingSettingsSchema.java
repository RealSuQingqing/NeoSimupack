package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

import java.util.List;

/**
 * 「分类设定」的分发表：<b>每个建筑分类各有一份 per-building JSON 定义</b>，
 * 这里负责按分类给出字段表与注意事项。
 *
 * <p>结论来自逐个追 NSUK 的 loader：
 * <ul>
 *   <li>{@code industry} → {@code IndustrialDefinitionLoader}</li>
 *   <li>{@code commercial} → {@code CommercialDefinitionLoader}</li>
 *   <li>{@code public}（NSUK 里叫 medical）→ {@code MedicalDefinitionLoader}</li>
 *   <li>{@code residential} / {@code other} → <b>没有任何 loader</b>，
 *       所以这两个分类没有可填的 JSON 字段，界面上如实说明而不是凭空造字段。</li>
 * </ul>
 */
public final class BuildingSettingsSchema {

    private BuildingSettingsSchema() {
    }

    /**
     * 该分类有没有 per-building JSON 定义。
     *
     * <p>{@code residential} 与 {@code other} 返回 false：
     * <ul>
     *   <li>住宅的行为全部来自 {@code .sk} + NBT——租金取 {@code .sk} 的
     *       {@code amount:}/{@code price:}（{@code ResidentialRentService:237-245}），
     *       床位与户型来自 NBT 红床扫描与 {@code .sk} 的 {@code unit:}；
     *       全树不存在 {@code ResidentialDefinition*}。</li>
     *   <li>{@code other} 也没有专属 loader；它唯一的 JSON 读取是「公共类型嗅探」
     *       （{@code BuildingPackageCatalog:301-342}，因为该处把 {@code other} 当 public 处理），
     *       那是分类用途，不是业务定义。</li>
     * </ul>
     */
    public static boolean hasJsonDefinition(String category) {
        return switch (normalize(category)) {
            case "industry", "commercial", "public" -> true;
            default -> false;
        };
    }

    /** 该分类的分区列表；没有 JSON 定义的分类返回空列表。 */
    public static List<SettingSection> sectionsFor(String category) {
        return switch (normalize(category)) {
            case "industry" -> IndustrialDefinitionFields.SECTIONS;
            case "commercial" -> CommercialDefinitionFields.SECTIONS;
            case "public" -> MedicalDefinitionFields.SECTIONS;
            default -> List.of();
        };
    }

    /**
     * 该分类要额外展示的提醒（语言键），渲染成警告行。
     *
     * <p>都是「照文档写会踩坑」的地方：死键、被遮蔽的兜底键、只在特定条件下生效的格式。
     */
    public static List<String> notesFor(String category) {
        return switch (normalize(category)) {
            case "commercial" -> List.of(
                    "gui.simupack.settings.note.commercial_offers_pending",
                    "gui.simupack.settings.note.commercial_legacy",
                    "gui.simupack.settings.note.commercial_job_shadow",
                    "gui.simupack.settings.note.commercial_containers_pending");
            case "public" -> List.of(
                    "gui.simupack.settings.note.medical_id_dead",
                    "gui.simupack.settings.note.medical_missing_ok",
                    "gui.simupack.settings.note.medical_snake_case");
            case "residential" -> List.of("gui.simupack.settings.note.residential_no_json");
            case "other" -> List.of("gui.simupack.settings.note.other_no_json");
            default -> List.of();
        };
    }

    /** NSUK 会把 {@code commerce} 归一成 {@code commercial}、{@code medic*} 归一成 {@code public}。 */
    private static String normalize(String category) {
        if (category == null) {
            return "";
        }
        String value = category.trim().toLowerCase(java.util.Locale.ROOT);
        if ("commerce".equals(value)) {
            return "commercial";
        }
        return value.startsWith("medic") ? "public" : value;
    }
}
