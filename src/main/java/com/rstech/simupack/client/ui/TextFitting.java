package com.rstech.simupack.client.ui;

/**
 * 文本宽度估算与截断。
 *
 * <p>刻意不依赖任何客户端类：界面上"放不下就把文字截掉"这条规则必须能脱机测试。
 * （{@link SimuPackLayout} 标了 {@code @OnlyIn(Dist.CLIENT)}，在单元测试里连类都初始化不了。）
 *
 * <p>为什么需要它：横向 flex 行里可用宽度不够时，标签会被压到接近 0 宽，
 * 而渲染层照样把整串字画出来——表现为文字互相叠成一团，还会盖住旁边的按钮。
 * LDLib2 的 {@code TextWrap} 只提供 WRAP，没有省略号模式，所以只能自己截。
 */
public final class TextFitting {

    private TextFitting() {
    }

    /**
     * 粗略估算文本像素宽度。
     *
     * <p>全角（中日韩）按 9px、其余按 6px。宁可略宽也不要算窄——算窄会让标签被压成 0 宽，
     * 文字就会溢出去和相邻控件叠在一起。
     */
    public static float textWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 1F;
        }
        float width = 0F;
        for (int i = 0; i < text.length(); i++) {
            width += charWidth(text.charAt(i));
        }
        return width;
    }

    /**
     * 把文本截断到 {@code maxWidth} 像素以内，超出部分用省略号代替。
     *
     * <p>宽度估算用 {@link #textWidth}，所以这里是"宁可早一点截断"的保守行为。
     */
    public static String fit(String text, float maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (textWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        float ellipsisWidth = textWidth(ellipsis);
        if (maxWidth <= ellipsisWidth) {
            return ellipsis;
        }
        float budget = maxWidth - ellipsisWidth;
        StringBuilder kept = new StringBuilder();
        float used = 0F;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            float characterWidth = charWidth(character);
            if (used + characterWidth > budget) {
                break;
            }
            kept.append(character);
            used += characterWidth;
        }
        return kept + ellipsis;
    }

    private static float charWidth(char character) {
        return character > 0x2E80 ? 9F : 6F;
    }
}
