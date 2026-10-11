package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.json.DefinitionField;

/**
 * 动作组静态校验发现的一条问题。
 *
 * <p>刻意<b>不</b>在这里拼好人类可读文案：数据层是纯逻辑、不依赖客户端，
 * 因此只产出稳定的 {@code code} 与 {@code detail}，由界面用
 * {@code Component.translatable("gui.simupack.industrial.issue." + code, detail)} 组装文案。
 *
 * @param severity 严重程度
 * @param code     稳定的问题代码，同时是语言键后缀
 * @param location 出错位置，形如 {@code recipes[0].steps[2]}，便于用户定位
 * @param detail   补充数据（未知 type 名、越界数值、引用的键名等），可为空串
 */
public record IndustrialActionIssue(Severity severity, String code, String location, String detail) {

    /** 问题严重程度。 */
    public enum Severity {
        /**
         * 一定会出问题。
         *
         * <p>NSUK 在加载或运行时会因此报错／卡住，必须修。
         */
        ERROR,
        /** 很可能不符合意图，建议修，但 NSUK 仍能加载。 */
        WARNING,
        /** 提示性信息。 */
        INFO
    }

    public static IndustrialActionIssue error(String code, String location, String detail) {
        return new IndustrialActionIssue(Severity.ERROR, code, location, detail == null ? "" : detail);
    }

    public static IndustrialActionIssue warning(String code, String location, String detail) {
        return new IndustrialActionIssue(Severity.WARNING, code, location, detail == null ? "" : detail);
    }

    public IndustrialActionIssue withLocation(String newLocation) {
        return new IndustrialActionIssue(severity, code, newLocation, detail);
    }

    /** 语言键。 */
    public String translationKey() {
        return "gui.simupack.industrial.issue." + code;
    }
}
