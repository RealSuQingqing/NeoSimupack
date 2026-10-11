package com.rstech.simupack.attention;

/**
 * 版本串比较，<b>逐条复刻 NSUK 的语义</b>，不自己发明。
 *
 * <p>为什么必须对齐：如果我们的比较和 NSUK 不一致，就会出现"筑艺坊亮红点、点更新却说已经是最新"
 * 这种自相矛盾的界面。对齐 NSUK 比"更正确"更重要。
 *
 * <p>NSUK {@code BuildingPackVersionChecker.parseVersion()} 的实际语义（反编译核对）：
 * <ol>
 *   <li>按 {@code .} 切分；</li>
 *   <li>每一段用 {@code replaceAll("[^0-9]", "")} 剥掉非数字后 {@code parseInt}；</li>
 *   <li>{@code NumberFormatException}（例如剥完是空串）→ 该段记 {@code 0}；</li>
 *   <li>逐段比较到较短者结束；全部相等时<b>段数少的一方算旧</b>（{@code 1.2 < 1.2.0}）。</li>
 * </ol>
 *
 * <p><b>已知副作用，不要当成 bug 去"修"</b>：{@code 1.2.0-beta} 会被解析成 {@code [1,2,0]}，
 * 与 {@code 1.2.0} 相等。这是 NSUK 的行为，我们照抄。
 */
public final class VersionCompare {

    private VersionCompare() {
    }

    /**
     * {@code left} 是否比 {@code right} 旧。
     *
     * <p>任一串无法解析（null / 空白）时返回 {@code false}——即"判不出来就不报更新"。
     * 这是 fail-closed：宁可漏报，不可误报（误报会让用户去点一个没有任何效果的更新按钮）。
     */
    public static boolean isOlderThan(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        if (a == null || b == null) {
            return false;
        }
        return compare(a, b) < 0;
    }

    /**
     * 两个版本串是否相等（按 NSUK 语义）。
     *
     * <p>注意 {@code 1.2.0-beta} 与 {@code 1.2.0} 在这里是相等的。
     * 任一侧无法解析时返回 {@code false}。
     */
    public static boolean equalTo(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        if (a == null || b == null) {
            return false;
        }
        return compare(a, b) == 0;
    }

    /**
     * 逐段数值比较，与 NSUK 完全一致。
     *
     * @return 负数表示 {@code a} 旧，正数表示 {@code b} 旧
     */
    private static int compare(int[] a, int[] b) {
        int shared = Math.min(a.length, b.length);
        for (int index = 0; index < shared; index++) {
            int difference = Integer.compare(a[index], b[index]);
            if (difference != 0) {
                return difference;
            }
        }
        // 共同段全等：段数少的一方算旧（1.2 < 1.2.0）。
        // 两侧段数也相同 -> 相等，返回 0。
        return Integer.compare(a.length, b.length);
    }

    /**
     * 解析成数值段数组；无法解析时返回 {@code null}。
     *
     * <p>与 NSUK 的一处细微差别是刻意保留的：NSUK 对 {@code null} 会直接 NPE。
     * 我们把"解析不出来"统一收敛成 {@code null}，由调用方按 fail-closed 处理——
     * 多一层判断，少一次崩溃，判定结果本身不变。
     */
    static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("\\.", -1);
        int[] numbers = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            numbers[index] = parseSegment(parts[index]);
        }
        return numbers;
    }

    /** 单段：剥掉所有非数字后 parseInt；剥完是空串或解析失败都记 0（与 NSUK 一致）。 */
    private static int parseSegment(String segment) {
        String digits = segment.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException exception) {
            // 溢出（一大串数字）在 NSUK 里同样落到 catch，记 0。
            return 0;
        }
    }
}
