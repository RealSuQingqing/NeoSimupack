package com.rstech.simupack.creation.residential;

/**
 * 阵列命名的模板（纯逻辑，不依赖 Minecraft）。
 *
 * <p>模板里认两种占位符：
 * <ul>
 *   <li>{@code {...}} —— <b>序号表达式</b>，里面有 {@code n}（从 1 开始的新增序号），
 *       可以带算术：{@code {n}}、{@code {n+1}}、{@code {n-1}}、{@code {n*100}}、
 *       {@code {n*100+1}}。支持 {@code +}、{@code -}、{@code *}，<b>乘号优先级更高</b>
 *       （所以 {@code n*100+1} 与 {@code n+1*100} 都是 {@code n*100+1} 的意思）；</li>
 *   <li>{@code {source}} —— 源户型的 label。</li>
 * </ul>
 * 解析不了的花括号原样保留（例如 {@code {n+}}、{@code {别的}}），
 * 这样用户写错时能在名字里直接看见，而不是被静默吞掉。
 *
 * <p><b>为什么一个模板就够、不需要「名称方式」下拉</b>：常见需求都能写出来 ——
 * <pre>
 *   后缀递增     {source}_{n}    →  101_1, 101_2, 101_3
 *   接在源号后面 10{n+1}         →  102, 103, 104        （源是 101 时接着往下编）
 *   楼层号       {n}01           →  101, 201, 301        （楼层就是序号）
 *   每层 100 号  {n*100}         →  100, 200, 300
 *   固定同名     直接写文本       →  全同名（阵列校验会报「批次内重名」并阻止）
 * </pre>
 *
 * <p>界面上的做法是：字段下面实时显示按当前模板生成的<b>名字示例</b>，
 * 用户不用记语法也能看见效果。
 */
public final class UnitLabelTemplate {
    /** 默认模板：源名 + 下划线 + 序号。不会与源户型重名，是最保守的选择。 */
    public static final String DEFAULT_TEMPLATE = "{source}_{n}";
    /** 源名占位符。 */
    public static final String PLACEHOLDER_SOURCE = "{source}";

    private UnitLabelTemplate() {
    }

    /**
     * 生成第 {@code index} 个新增户型的 label。
     *
     * @param template    模板；null 或空白时退回 {@link #DEFAULT_TEMPLATE}
     * @param sourceLabel 源户型 label；null 当空串
     * @param index       新增序号，从 1 开始
     */
    public static String label(String template, String sourceLabel, int index) {
        String base = sourceLabel == null ? "" : sourceLabel;
        String effective = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
        // 先算序号表达式，最后才替换 {source}：这样源名里若真含有 "{n}" 之类的字样，
        // 也不会在插入之后被当成表达式再算一遍。
        return evaluateAll(effective, index).replace(PLACEHOLDER_SOURCE, base);
    }

    /** 把模板里所有能解析的 {@code {...}} 换成算出来的数字，解析不了的整段原样保留。 */
    private static String evaluateAll(String template, int index) {
        StringBuilder out = new StringBuilder(template.length() + 8);
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            if (open < 0) {
                out.append(template, cursor, template.length());
                break;
            }
            int close = template.indexOf('}', open + 1);
            if (close < 0) {
                // 没有闭合的 '{'：后面全部原样保留。
                out.append(template, cursor, template.length());
                break;
            }
            out.append(template, cursor, open);
            Integer value = evaluate(template.substring(open + 1, close), index);
            if (value == null) {
                out.append(template, open, close + 1);
            } else {
                out.append(value);
            }
            cursor = close + 1;
        }
        return out.toString();
    }

    /** 解析一段序号表达式；失败返回 null。 */
    static Integer evaluate(String expression, int index) {
        if (expression == null) {
            return null;
        }
        IndexExpression parser = new IndexExpression(expression, index);
        Integer value = parser.parse();
        return value != null && parser.atEnd() ? value : null;
    }

    /**
     * 序号表达式的小解析器。
     *
     * <p>文法（空白随意）：
     * <pre>
     *   表达式 := 项 (('+' | '-') 项)*
     *   项     := 因子 ('*' 因子)*
     *   因子   := 'n' | 数字
     * </pre>
     * 溢出一律当解析失败处理 —— 名字里显示原样的 {@code {n*99999999999}} 比显示一个
     * 绕回来的负数有用得多。
     */
    private static final class IndexExpression {
        private final String text;
        private final int index;
        private int pos;

        IndexExpression(String text, int index) {
            this.text = text;
            this.index = index;
        }

        boolean atEnd() {
            skipSpaces();
            return pos >= text.length();
        }

        Integer parse() {
            return sum();
        }

        private Integer sum() {
            Integer left = product();
            if (left == null) {
                return null;
            }
            while (true) {
                skipSpaces();
                if (pos >= text.length()) {
                    return left;
                }
                char operator = text.charAt(pos);
                if (operator != '+' && operator != '-') {
                    return left;
                }
                pos++;
                Integer right = product();
                if (right == null) {
                    return null;
                }
                left = addOrNull(left, operator == '+' ? right : -right);
                if (left == null) {
                    return null;
                }
            }
        }

        private Integer product() {
            Integer left = atom();
            if (left == null) {
                return null;
            }
            while (true) {
                skipSpaces();
                if (pos >= text.length() || text.charAt(pos) != '*') {
                    return left;
                }
                pos++;
                Integer right = atom();
                if (right == null) {
                    return null;
                }
                left = multiplyOrNull(left, right);
                if (left == null) {
                    return null;
                }
            }
        }

        private Integer atom() {
            skipSpaces();
            if (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == 'n' || c == 'N') {
                    pos++;
                    return index;
                }
            }
            int start = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                return null;
            }
            try {
                return Integer.parseInt(text.substring(start, pos));
            } catch (NumberFormatException overflow) {
                return null;
            }
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private static Integer addOrNull(int left, int right) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException overflow) {
                return null;
            }
        }

        private static Integer multiplyOrNull(int left, int right) {
            try {
                return Math.multiplyExact(left, right);
            } catch (ArithmeticException overflow) {
                return null;
            }
        }
    }
}
