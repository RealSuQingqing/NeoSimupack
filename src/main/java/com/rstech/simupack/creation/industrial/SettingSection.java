package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

import java.util.List;

/**
 * 「分类设定」表单里的一个分区：一段标题 + 一组字段。
 *
 * <p>各个分类共用这一种描述：分区自己的字段挂在 {@code objectKey} 指向的对象上，
 * {@link #ROOT} 表示直接挂在建筑 JSON 的根上。
 *
 * @param id        稳定标识，同时是语言键与编辑标签的一部分
 * @param objectKey 该分区对应的嵌套对象键；{@link #ROOT} 表示根对象
 * @param fields    字段列表
 */
public record SettingSection(String id, String objectKey, List<DefinitionField> fields) {

    /** 顶层：字段直接挂在根对象上。 */
    public static final String ROOT = "";

    public SettingSection {
        fields = List.copyOf(fields);
    }

    /** 分区标题的语言键。 */
    public String translationKey() {
        return "gui.simupack.settings.section." + id;
    }

    /**
     * 字段标签的语言键。
     *
     * <p>刻意带上分区：{@code type} 在各分类里含义完全不同（工业的 {@code spawnEntity.type} 是实体、
     * 公共的 {@code type} 是建筑类型），共用一个键会串味。
     */
    public String labelKey(DefinitionField field) {
        return "gui.simupack.settings.field." + id + "." + field.key();
    }
}
