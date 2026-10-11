package com.rstech.simupack.attention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 <b>NSUK 语义</b>的版本比较。
 *
 * <p>这套语义是 B 类红点的判据地基，而且出错时<b>不会有异常</b>——只会静默地判错，
 * 表现为"红点亮着、点更新却说已是最新"。所以每一条都要钉死。
 *
 * <p>其中 {@code 1.2.0-beta == 1.2.0} 是 NSUK 的<b>已知副作用</b>，
 * 写成测试是为了防止有人"顺手修得更正确"——对齐 NSUK 比更正确更重要。
 */
class VersionCompareTest {

    @Test
    void numericSegmentsCompareAsNumbersNotStrings() {
        // 字符串比较会认为 "1.10.0" < "1.2.0"，那正是要避免的错误。
        assertTrue(VersionCompare.isOlderThan("1.2.0", "1.10.0"));
        assertFalse(VersionCompare.isOlderThan("1.10.0", "1.2.0"));
        assertTrue(VersionCompare.isOlderThan("2.9", "2.10"));
    }

    @Test
    void fewerSegmentsCountAsOlderWhenTheRestIsEqual() {
        // NSUK 逐段比到较短者结束，全等时段数少的一方算旧。
        assertTrue(VersionCompare.isOlderThan("1.2", "1.2.0"));
        assertFalse(VersionCompare.isOlderThan("1.2.0", "1.2"));
        // 反过来也成立：段数多的一方"更新"，所以不是"相等"。
        assertFalse(VersionCompare.equalTo("1.2", "1.2.0"));
    }

    @Test
    void prereleaseSuffixIsStrippedSoItEqualsThePlainVersion() {
        // 已知副作用，不是 bug：replaceAll("[^0-9]","") 把 "-beta" 剥光。
        // 这条测试是刻意写下的「别修它」，因为 NSUK 自己就是这么判的。
        assertTrue(VersionCompare.equalTo("1.2.0-beta", "1.2.0"));
        assertFalse(VersionCompare.isOlderThan("1.2.0-beta", "1.2.0"));
    }

    @Test
    void nonNumericSegmentCountsAsZero() {
        // 剥完是空串 -> NumberFormatException -> 0（NSUK 的行为）。
        assertTrue(VersionCompare.equalTo("1.x.0", "1.0.0"));
        assertTrue(VersionCompare.isOlderThan("1.x", "1.1"));
    }

    @Test
    void leadingAndTrailingNoiseDoesNotChangeTheNumber() {
        // "v" 前缀、零填充都应被剥成同一个数。
        assertTrue(VersionCompare.equalTo("v1.2.3", "1.2.3"));
        assertTrue(VersionCompare.equalTo("1.02.3", "1.2.3"));
    }

    @Test
    void identicalVersionsAreNeitherOlderNorNewer() {
        assertFalse(VersionCompare.isOlderThan("2.2.0", "2.2.0"));
        assertTrue(VersionCompare.equalTo("2.2.0", "2.2.0"));
    }

    @Test
    void blankOrNullVersionsNeverReportAnUpdate() {
        // fail-closed：判不出来就说"不旧"，绝不误报一个点不动的更新。
        assertFalse(VersionCompare.isOlderThan(null, "1.0"));
        assertFalse(VersionCompare.isOlderThan("1.0", null));
        assertFalse(VersionCompare.isOlderThan("", "1.0"));
        assertFalse(VersionCompare.isOlderThan("   ", "1.0"));
        assertFalse(VersionCompare.isOlderThan("1.0", ""));
        assertFalse(VersionCompare.equalTo(null, null));
        assertFalse(VersionCompare.equalTo("", ""));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertTrue(VersionCompare.equalTo("  2.2.0  ", "2.2.0"));
        assertTrue(VersionCompare.isOlderThan(" 2.1.0 ", " 2.2.0 "));
    }

    @Test
    void oversizedNumbersFallBackToZeroInsteadOfOverflowing() {
        // parseInt 溢出在 NSUK 里同样落到 catch 记 0；两边必须一致。
        assertTrue(VersionCompare.equalTo("1.99999999999999999999", "1.0"));
    }

    @Test
    void realisticOfficialPackPairIsDetectedAsOutdated() {
        // M0 实测数据：本地 2.1.0、内置 2.2.0 -> 应当判旧（B 类要亮）。
        assertTrue(VersionCompare.isOlderThan("2.1.0", "2.2.0"));
        assertFalse(VersionCompare.isOlderThan("2.2.0", "2.2.0")); // 当前 dev 环境的真实状态
    }
}
