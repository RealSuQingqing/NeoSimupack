package com.rstech.simupack.creation.residential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 闭区间盒：归一化、平移、重叠与越界。 */
class UnitBoxTest {

    @Test
    @DisplayName("of 逐轴归一化，写反的角点等价")
    void ofNormalizesCorners() {
        assertEquals(UnitBox.of(0, 1, 0, 5, 4, 8), UnitBox.of(5, 4, 8, 0, 1, 0));
        UnitBox box = UnitBox.of(5, 4, 8, 0, 1, 0);
        assertEquals(0, box.minX());
        assertEquals(1, box.minY());
        assertEquals(0, box.minZ());
        assertEquals(5, box.maxX());
        assertEquals(4, box.maxY());
        assertEquals(8, box.maxZ());
    }

    @Test
    @DisplayName("厚度是闭区间长度（各轴 +1）")
    void extentsIncludeBothEnds() {
        UnitBox box = UnitBox.of(0, 1, 0, 5, 4, 8);
        assertEquals(6, box.extentX());
        assertEquals(4, box.extentY());
        assertEquals(9, box.extentZ());
    }

    @Test
    @DisplayName("translate 只做整体平移，不改尺寸")
    void translateKeepsSize() {
        UnitBox moved = UnitBox.of(0, 0, 0, 1, 1, 1).translate(0, 4, 0);
        assertEquals(UnitBox.of(0, 4, 0, 1, 5, 1), moved);
        assertEquals(2, moved.extentY());
    }

    @Test
    @DisplayName("重叠：共面贴合也算重叠（与 NSUK 闭区间语义一致）")
    void overlapIncludesTouchingFaces() {
        UnitBox a = UnitBox.of(0, 0, 0, 5, 4, 8);
        assertTrue(a.overlaps(UnitBox.of(5, 0, 0, 10, 4, 8)), "maxX == other.minX 算重叠");
        assertTrue(a.overlaps(UnitBox.of(2, 2, 2, 3, 3, 3)), "完全包含算重叠");
        assertFalse(a.overlaps(UnitBox.of(6, 0, 0, 10, 4, 8)), "差一格不算重叠");
        assertFalse(a.overlaps(null));
    }

    @Test
    @DisplayName("越界：尺寸未知（任一轴 <= 0）时跳过判定")
    void withinSkipsUnknownSize() {
        UnitBox box = UnitBox.of(0, 0, 0, 5, 4, 8);
        assertTrue(box.within(6, 5, 9), "贴边仍算在范围内");
        assertTrue(box.within(0, 0, 0), "尺寸未知不判定");
        assertFalse(box.within(5, 5, 9), "maxX 超出");
        assertFalse(box.within(6, 4, 9), "maxY 超出");
        assertFalse(UnitBox.of(-1, 0, 0, 3, 3, 3).within(10, 10, 10), "负坐标越界");
    }
}
