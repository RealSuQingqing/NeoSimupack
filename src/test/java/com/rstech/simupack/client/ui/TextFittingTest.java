package com.rstech.simupack.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具栏文本截断的回归测试。
 *
 * <p>这段逻辑是"窗口被 GUI 缩放压小时不把按钮盖住"的唯一保障：横向 flex 行里
 * 放不下的标签会被压到接近 0 宽，而渲染层照样把整串字画出来，表现为文字互相叠成一团。
 * 所以"截断后必须真的放得下"这条不变量值得钉住——它不是美观问题，是可读性问题。
 */
class TextFittingTest {

    @Test
    void shortTextIsReturnedUnchanged() {
        assertEquals("3 个拓展包", TextFitting.fit("3 个拓展包", 500F));
        assertEquals("", TextFitting.fit("", 100F));
        assertEquals("", TextFitting.fit(null, 100F));
    }

    @Test
    void longTextIsTruncatedWithinTheBudget() {
        String longText = "3 个拓展包  ·  114 个建筑  ·  未启用 2  ·  旧版待转换 1  ·  ⚠ 1 个拓展包格式异常";
        String fitted = TextFitting.fit(longText, 120F);

        assertTrue(fitted.endsWith("…"), "超长必须用省略号收尾：" + fitted);
        assertTrue(TextFitting.textWidth(fitted) <= 120F,
                "截断后不能超过预算宽度，否则仍会压到按钮上：" + TextFitting.textWidth(fitted));
        assertTrue(longText.startsWith(fitted.substring(0, fitted.length() - 1)),
                "截断只能砍尾巴，不能改动前面的字：" + fitted);
    }

    @Test
    void budgetSmallerThanTheEllipsisStillReturnsTheEllipsis() {
        assertEquals("…", TextFitting.fit("很长的一段文字", 2F));
        assertEquals("…", TextFitting.fit("abc", 0F));
    }

    @Test
    void exactlyFittingTextIsNotTruncated() {
        String text = "abcdef";
        assertEquals(text, TextFitting.fit(text, TextFitting.textWidth(text)));
        assertFalse(TextFitting.fit(text, 1000F).endsWith("…"));
    }

    /** 全角比半角宽：<b>同样字符个数</b>下，中文比 ASCII 更占地方。 */
    @Test
    void fullWidthCharactersCostMoreThanHalfWidth() {
        // 3 个汉字 = 3*9 = 27px；3 个 ASCII = 3*6 = 18px。
        assertTrue(TextFitting.textWidth("中文字") > TextFitting.textWidth("abc"));
        assertEquals(27F, TextFitting.textWidth("中文字"));
        assertEquals(18F, TextFitting.textWidth("abc"));
    }

    /** 空文本给 1px 而不是 0：宽度为 0 的元素在 flex 里会被当成"可以完全压扁"。 */
    @Test
    void emptyTextReportsOnePixelNotZero() {
        assertEquals(1F, TextFitting.textWidth(""));
        assertEquals(1F, TextFitting.textWidth(null));
    }
}
