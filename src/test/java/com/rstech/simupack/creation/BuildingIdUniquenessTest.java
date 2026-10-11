package com.rstech.simupack.creation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 建筑 id 在整个包内必须唯一 —— 这是一条真出过 bug 的约束。
 *
 * <p>原先的 {@code nextBuildingId(category, base)} 只在<b>同一个分类内</b>查重，
 * 于是每个分类新建出来的第一个建筑都叫 {@code new_building}；
 * 切到另一个分类再建一个就与前者重名，导出/加载随即出错。
 *
 * <p>约束的来源：导出时三类建筑的 {@code .sk}/{@code .nbt}/{@code .json}
 * 都写进同一个包，NSUK 也按 id 索引它们，所以 id 是<b>包级</b>标识而不是分类内的局部名。
 */
class BuildingIdUniquenessTest {

    private static CreationProject projectWith(String... idsAndCategories) {
        CreationProject project = new CreationProject("my_pack", "我的包");
        List<CreationBuilding> buildings = new ArrayList<>();
        for (int index = 0; index < idsAndCategories.length; index += 2) {
            buildings.add(new CreationBuilding(idsAndCategories[index], idsAndCategories[index + 1]));
        }
        project.buildings().addAll(buildings);
        return project;
    }

    @Test
    @DisplayName("nextBuildingId 跨分类查重：不同分类里也不能都叫 new_building")
    void nextBuildingIdIsUniqueAcrossCategories() {
        CreationProject project = projectWith("new_building", "industry");

        // 关键断言：商业分类下再要一个 new_building，**不能再给它 new_building**
        assertEquals("new_building_2", project.nextBuildingId("new_building"));

        // 落进去之后再要一个，继续递增
        project.buildings().add(new CreationBuilding("new_building_2", "commercial"));
        assertEquals("new_building_3", project.nextBuildingId("new_building"));
    }

    @Test
    @DisplayName("findBuildingById 无视分类：同名不同类也算重名")
    void findBuildingByIdIgnoresCategory() {
        CreationProject project = projectWith("dup", "industry", "dup", "commercial");
        assertNotNull(project.findBuildingById("dup"));
        // 按分类找仍然只看那一类
        assertNotNull(project.findBuilding("industry", "dup"));
        assertNotNull(project.findBuilding("commercial", "dup"));
        assertNull(project.findBuilding("residential", "dup"));
        assertNull(project.findBuildingById("nope"));
    }

    @Test
    @DisplayName("仓库里已有的 id 不被复用，即使它在另一个分类")
    void existingIdInAnotherCategoryStillCounts() {
        CreationProject project = projectWith("bakery", "commercial");
        assertEquals("bakery_2", project.nextBuildingId("bakery"));
    }
}
