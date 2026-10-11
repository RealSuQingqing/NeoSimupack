package com.rstech.simupack.creation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 结构尺寸的校验逻辑。
 *
 * <p>这段逻辑「改错了不会崩」——只会静默显示错尺寸、或让预览取景飞到看不见的地方，
 * 所以必须靠测试钉住。
 */
class StructureDimensionsTest {

    @Test
    @DisplayName("三段正整数原样返回，顺序就是 x y z")
    void threePositiveSegmentsAreSize() {
        assertArrayEquals(new int[]{24, 55, 24}, StructureDimensions.of(List.of(24, 55, 24)));
        assertArrayEquals(new int[]{62, 24, 40}, StructureDimensions.of(List.of(62, 24, 40)));
        assertArrayEquals(new int[]{19, 81, 19}, StructureDimensions.of(List.of(19, 81, 19)));
    }

    @Test
    @DisplayName("不足三段 / 含 0 或负数都返回 null（不能让退化尺寸混进包围盒）")
    void invalidSizesAreNull() {
        assertNull(StructureDimensions.of(null));
        assertNull(StructureDimensions.of(List.of()));
        assertNull(StructureDimensions.of(List.of(4)));
        assertNull(StructureDimensions.of(List.of(4, 5)));
        assertNull(StructureDimensions.of(List.of(4, 5, 0)));
        assertNull(StructureDimensions.of(List.of(0, 5, 6)));
        assertNull(StructureDimensions.of(List.of(4, -5, 6)));
        assertNull(StructureDimensions.of(Arrays.asList(4, null, 6)));
    }

    @Test
    @DisplayName("多余段忽略")
    void extraSegmentsAreIgnored() {
        assertArrayEquals(new int[]{1, 2, 3}, StructureDimensions.of(List.of(1, 2, 3, 99)));
    }

    @Test
    @DisplayName("展示格式与 .sk 的写法一致：X x Y x Z；缺尺寸给空串")
    void formatKeepsTheSkConvention() {
        assertEquals("24 x 55 x 24", StructureDimensions.format(new int[]{24, 55, 24}));
        assertEquals("", StructureDimensions.format(null));
        assertEquals("", StructureDimensions.format(new int[]{1, 2}));
    }
}
