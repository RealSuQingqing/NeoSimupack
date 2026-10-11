package com.rstech.simupack.creation.residential;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 阵列生成的静态校验（纯逻辑，不依赖 Minecraft）。
 *
 * <p>判据与 NSUK 的语义对齐：
 * <ul>
 *   <li><b>越界</b>：{@code unit:} 的坐标是结构局部坐标，超出结构 NBT 尺寸的范围
 *       在游戏里永远指不到方块。尺寸未知（任一轴 &le; 0）时跳过这一项。</li>
 *   <li><b>重叠</b>：NSUK 归户是「第一个命中的 {@code unit:} 行赢」（见方案 §2.3），
 *       所以重叠不是「共享」而是「后面的永远拿不到」。因此重叠算 ERROR 并阻止确认 ——
 *       让用户在写进 {@code .sk} 之前就看见，而不是等到游戏里发现某户没人。</li>
 *   <li><b>重复 label</b>：<b>不</b>以「unitId 必然冲突」为依据 ——
 *       已核实的 unitId 拼接式是 {@code buildingId + index + label}，含 index，
 *       同建筑内同名不足以证明撞 ID。这里的规则是产品判断：
 *       同一批生成内部重名（例如 PLAIN 模式）会让户型列表无法分辨，判 ERROR；
 *       与已有户型重名只是容易看混，判 WARNING。</li>
 * </ul>
 */
public final class ResidentialArrayValidator {

    /** 单次阵列允许生成的最大户型数（含源户型）。 */
    public static final int MAX_TOTAL = 256;
    /** 最多回报多少条问题：列表是给人看的，几十条一样的越界没有意义。 */
    public static final int MAX_REPORTED = 6;

    private ResidentialArrayValidator() {
    }

    /** 问题级别。只有 ERROR 会阻止确认生成。 */
    public enum Severity {
        ERROR,
        WARNING
    }

    /**
     * @param severity 级别
     * @param code     稳定的问题码，同时是语言键后缀
     * @param detail   补充数据（户型 label、数量），可为空串
     */
    public record Issue(Severity severity, String code, String detail) {
        public static Issue error(String code, String detail) {
            return new Issue(Severity.ERROR, code, detail == null ? "" : detail);
        }

        public static Issue warning(String code, String detail) {
            return new Issue(Severity.WARNING, code, detail == null ? "" : detail);
        }

        /** 语言键：{@code gui.simupack.residential.array.issue.<code>}。 */
        public String translationKey() {
            return "gui.simupack.residential.array.issue." + code;
        }
    }

    /** 一个待检查的户型：范围 + label。 */
    public record Placed(UnitBox box, String label) {
    }

    /**
     * 校验一批将要追加的户型。
     *
     * @param generated 本次要追加的户型（已按偏移算好坐标）
     * @param existing  建筑里已有的户型（含源户型本身）
     * @param sizeX/sizeY/sizeZ 结构尺寸；任一非正表示未知，跳过越界判定
     * @return 问题列表；空表示可以生成
     */
    public static List<Issue> validate(List<Placed> generated, List<Placed> existing,
                                       int sizeX, int sizeY, int sizeZ) {
        List<Issue> issues = new ArrayList<>();
        List<Placed> candidates = generated == null ? List.of() : generated;
        List<Placed> present = existing == null ? List.of() : existing;

        if (candidates.isEmpty()) {
            issues.add(Issue.error("empty", ""));
            return issues;
        }
        if (candidates.size() > MAX_TOTAL) {
            issues.add(Issue.error("too_many", Integer.toString(candidates.size())));
            return issues;
        }

        // 越界
        for (Placed placed : candidates) {
            if (!placed.box().within(sizeX, sizeY, sizeZ)) {
                issues.add(Issue.error("out_of_bounds", placed.label()));
                if (issues.size() >= MAX_REPORTED) return issues;
            }
        }

        // 与已有户型重叠
        for (Placed placed : candidates) {
            for (Placed other : present) {
                if (placed.box().overlaps(other.box())) {
                    issues.add(Issue.error("overlap_existing", placed.label() + " / " + other.label()));
                    if (issues.size() >= MAX_REPORTED) return issues;
                }
            }
        }

        // 生成结果之间互相重叠
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                if (candidates.get(i).box().overlaps(candidates.get(j).box())) {
                    issues.add(Issue.error("overlap_self",
                            candidates.get(i).label() + " / " + candidates.get(j).label()));
                    if (issues.size() >= MAX_REPORTED) return issues;
                }
            }
        }

        // 重名：批次内部 ERROR，与已有户型重名 WARNING
        Set<String> existingLabels = new LinkedHashSet<>();
        for (Placed placed : present) {
            existingLabels.add(placed.label());
        }
        Set<String> batchLabels = new HashSet<>();
        for (Placed placed : candidates) {
            if (!batchLabels.add(placed.label())) {
                issues.add(Issue.error("duplicate_in_batch", placed.label()));
            } else if (existingLabels.contains(placed.label())) {
                issues.add(Issue.warning("duplicate_with_existing", placed.label()));
            }
            if (issues.size() >= MAX_REPORTED) return issues;
        }

        return issues;
    }

    /** 只要有一条 ERROR 就不能生成。 */
    public static boolean hasError(List<Issue> issues) {
        if (issues == null) {
            return false;
        }
        for (Issue issue : issues) {
            if (issue.severity() == Severity.ERROR) {
                return true;
            }
        }
        return false;
    }
}
