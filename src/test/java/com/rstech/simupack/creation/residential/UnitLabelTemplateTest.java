package com.rstech.simupack.creation.residential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阵列命名模板：{@code {n}} 序号通配符与 {@code {source}} 源名。
 *
 * <p>这组用例同时是「三种命名方式都能用一个模板表达」的证明 ——
 * 原先的「名称方式」下拉与两个楼层字段因此被删掉了。
 */
class UnitLabelTemplateTest {

    @Test
    @DisplayName("后缀递增：{source}_{n}")
    void suffixStyleTemplate() {
        assertEquals("101_1", UnitLabelTemplate.label("{source}_{n}", "101", 1));
        assertEquals("101_2", UnitLabelTemplate.label("{source}_{n}", "101", 2));
        assertEquals("101_10", UnitLabelTemplate.label("{source}_{n}", "101", 10));
    }

    @Test
    @DisplayName("楼层号：{n}01 —— 楼层就是序号，不需要单独的楼层字段")
    void floorStyleTemplate() {
        assertEquals("101", UnitLabelTemplate.label("{n}01", "101", 1));
        assertEquals("201", UnitLabelTemplate.label("{n}01", "101", 2));
        assertEquals("301", UnitLabelTemplate.label("{n}01", "101", 3));
        assertEquals("1001", UnitLabelTemplate.label("{n}01", "x", 10));
    }

    @Test
    @DisplayName("固定文本：不含占位符时原样输出（同名由阵列校验拦）")
    void plainTemplate() {
        assertEquals("101", UnitLabelTemplate.label("101", "101", 1));
        assertEquals("101", UnitLabelTemplate.label("101", "101", 7));
    }

    @Test
    @DisplayName("序号从 1 开始，且只在 {n} 处替换")
    void indexStartsAtOne() {
        assertEquals("A1", UnitLabelTemplate.label("A{n}", "x", 1));
        assertEquals("A4", UnitLabelTemplate.label("A{n}", "x", 4));
    }

    @Test
    @DisplayName("{source} 可用多次；顺序替换保证源名里的 {n} 不会再被替换")
    void sourcePlaceholderIsSafe() {
        assertEquals("101-101-2", UnitLabelTemplate.label("{source}-{source}-{n}", "101", 2));
        // 源名里带 "{n}" 字样时，它是在 {n} 替换之后才插入的，所以保持字面量。
        assertEquals("{n}_1", UnitLabelTemplate.label("{source}_{n}", "{n}", 1));
    }

    @Test
    @DisplayName("模板为空/null 时退回默认；源名 null 当空串；未知占位符保留")
    void blanksAndUnknownPlaceholders() {
        assertEquals("101_1", UnitLabelTemplate.label(null, "101", 1));
        assertEquals("101_1", UnitLabelTemplate.label("", "101", 1));
        assertEquals("101_1", UnitLabelTemplate.label("   ", "101", 1));
        assertEquals("_1", UnitLabelTemplate.label(null, null, 1));
        assertEquals("{other}-1", UnitLabelTemplate.label("{other}-{n}", "x", 1));
    }

    @Test
    @DisplayName("序号可以带算术：{n+1} {n-1} {n*100}")
    void arithmeticOnIndex() {
        assertEquals("102", UnitLabelTemplate.label("10{n+1}", "101", 1));
        assertEquals("103", UnitLabelTemplate.label("10{n+1}", "101", 2));
        assertEquals("0", UnitLabelTemplate.label("{n-1}", "101", 1));
        assertEquals("1", UnitLabelTemplate.label("{n-1}", "101", 2));
        assertEquals("100", UnitLabelTemplate.label("10{n-1}", "101", 1), "文本与表达式可以拼在一起");
        assertEquals("100", UnitLabelTemplate.label("{n*100}", "x", 1));
        assertEquals("200", UnitLabelTemplate.label("{n*100}", "x", 2));
        assertEquals("101", UnitLabelTemplate.label("{n*100+1}", "x", 1));
        assertEquals("201", UnitLabelTemplate.label("{n*100+1}", "x", 2));
        assertEquals("5", UnitLabelTemplate.label("{n+2*2}", "x", 1));
    }

    @Test
    @DisplayName("乘号优先级高于加减号（避免 n+1*100 被左到右算成 200）")
    void multiplicationBindsTighter() {
        assertEquals(101, UnitLabelTemplate.evaluate("n+1*100", 1).intValue());
        assertEquals(101, UnitLabelTemplate.evaluate("n*100+1", 1).intValue());
        assertEquals(3, UnitLabelTemplate.evaluate("n+1+1", 1).intValue());
        assertEquals(0, UnitLabelTemplate.evaluate("n-1", 1).intValue());
        assertEquals(-1, UnitLabelTemplate.evaluate("n-2", 1).intValue(), "可以为负，模板自己决定怎么用");
    }

    @Test
    @DisplayName("表达式里可以有空格；解析不了的写法退化为原样保留")
    void tolerantParsing() {
        assertEquals(3, UnitLabelTemplate.evaluate(" n + 2 ", 1).intValue());
        assertEquals(7, UnitLabelTemplate.evaluate("n*2 +1", 3).intValue());
        // 解析不了就原样显示，用户能在名字里直接看见自己写错了。
        assertEquals("{n+}", UnitLabelTemplate.label("{n+}", "x", 1));
        assertEquals("{n+a}", UnitLabelTemplate.label("{n+a}", "x", 1));
        assertEquals("{n/2}", UnitLabelTemplate.label("{n/2}", "x", 1), "暂不支持除号");
        assertEquals("{n*99999999999}", UnitLabelTemplate.label("{n*99999999999}", "x", 1), "溢出按失败处理");
        assertEquals("{n", UnitLabelTemplate.label("{n", "x", 1), "没有闭合的括号原样保留");
        assertEquals("x", UnitLabelTemplate.label("x", "101", 1), "没有占位符时原样输出");
    }

    @Test
    @DisplayName("默认模板不会与源户型同名")
    void defaultTemplateDoesNotCollideWithSource() {
        for (int n = 1; n <= 5; n++) {
            String generated = UnitLabelTemplate.label(UnitLabelTemplate.DEFAULT_TEMPLATE, "101", n);
            assertEquals("101_" + n, generated);
            assertEquals(false, generated.equals("101"), "默认模板的第一个名字也不该等于源名");
        }
    }
}
