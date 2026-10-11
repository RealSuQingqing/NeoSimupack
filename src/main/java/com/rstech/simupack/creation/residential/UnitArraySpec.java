package com.rstech.simupack.creation.residential;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次户型阵列的参数与展开规则。
 *
 * <p><b>{@code newCount} 是「新增数量」，不含源户型。</b>
 * 填 3 就是<b>再生成 3 个</b>户型；源户型始终是列表里那一个，不参与计数。
 * （早先的版本把总数算作「含源户型」，用户填 10 会得到 9 个新户型，是个很容易踩的坑。）
 *
 * <p>{@code offsetX/Y/Z} 是<b>每一步</b>的增量（不是总位移），
 * 所以第 {@code n} 个新增户型的位移是 {@code offset * n}。
 *
 * <p>命名交给 {@link UnitLabelTemplate}：一个模板里的 {@code {n}} 就是新增序号。
 * 这里刻意没有「方向」这个概念 —— 只填三个偏移数字就够，
 * 方向只是「按户型厚度自动填 Y」的快捷方式，多一个下拉反而多一层要理解的东西。
 */
public record UnitArraySpec(int offsetX, int offsetY, int offsetZ, int newCount, String nameTemplate) {

    /** 单次阵列允许新增的最大户型数。 */
    public static final int MAX_NEW_COUNT = ResidentialArrayValidator.MAX_TOTAL;

    /** 不带模板的构造：用默认模板。 */
    public UnitArraySpec(int offsetX, int offsetY, int offsetZ, int newCount) {
        this(offsetX, offsetY, offsetZ, newCount, UnitLabelTemplate.DEFAULT_TEMPLATE);
    }

    /** 三轴偏移是否至少有一个非零：全零会生成一堆完全重叠的户型，必须拒绝。 */
    public boolean hasOffset() {
        return offsetX != 0 || offsetY != 0 || offsetZ != 0;
    }

    /** 参数本身是否可展开（不含与已有户型的冲突判定）。 */
    public boolean valid() {
        return newCount >= 1 && newCount <= MAX_NEW_COUNT && hasOffset();
    }

    /**
     * 展开成新增户型列表；参数非法或源户型没有范围时返回空表。
     *
     * <p>顺序即写进 {@code .sk} 的顺序，也即 NSUK 重叠时的优先级顺序，
     * 所以调用方必须保序追加（见方案 I3）。
     */
    public List<CreationUnit> expand(CreationUnit source) {
        List<CreationUnit> result = new ArrayList<>();
        if (source == null || !source.hasRange() || !valid()) {
            return result;
        }
        for (int n = 1; n <= newCount; n++) {
            int dx = offsetX * n;
            int dy = offsetY * n;
            int dz = offsetZ * n;
            BlockPos min = source.min().offset(dx, dy, dz);
            BlockPos max = source.max().offset(dx, dy, dz);
            String label = UnitLabelTemplate.label(nameTemplate, source.label(), n);
            result.add(new CreationUnit(label, min, max));
        }
        return result;
    }
}
