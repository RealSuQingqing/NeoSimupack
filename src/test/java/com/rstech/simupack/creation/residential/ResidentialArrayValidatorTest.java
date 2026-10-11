package com.rstech.simupack.creation.residential;

import com.rstech.simupack.creation.residential.ResidentialArrayValidator.Issue;
import com.rstech.simupack.creation.residential.ResidentialArrayValidator.Placed;
import com.rstech.simupack.creation.residential.ResidentialArrayValidator.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阵列校验：越界、重叠、重名与数量上限。 */
class ResidentialArrayValidatorTest {

    private static Placed placed(String label, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new Placed(UnitBox.of(minX, minY, minZ, maxX, maxY, maxZ), label);
    }

    private static boolean hasCode(List<Issue> issues, String code) {
        return issues.stream().anyMatch(issue -> issue.code().equals(code));
    }

    private static long countCode(List<Issue> issues, String code) {
        return issues.stream().filter(issue -> issue.code().equals(code)).count();
    }

    @Test
    @DisplayName("空批次直接报错，不继续做别的判定")
    void emptyBatchIsAnError() {
        List<Issue> issues = ResidentialArrayValidator.validate(List.of(), List.of(), 10, 10, 10);
        assertEquals(1, issues.size());
        assertTrue(hasCode(issues, "empty"));
        assertTrue(ResidentialArrayValidator.hasError(issues));
    }

    @Test
    @DisplayName("干净的分层阵列没有任何问题")
    void cleanFloorArrayPasses() {
        List<Placed> existing = List.of(placed("101", 0, 0, 0, 5, 3, 8));
        List<Placed> generated = new ArrayList<>();
        for (int floor = 2; floor <= 4; floor++) {
            generated.add(placed(floor + "01", 0, 4 * (floor - 1), 0, 5, 4 * (floor - 1) + 3, 8));
        }
        List<Issue> issues = ResidentialArrayValidator.validate(generated, existing, 12, 20, 12);
        assertTrue(issues.isEmpty(), () -> "期望无问题，实际：" + issues);
        assertFalse(ResidentialArrayValidator.hasError(issues));
    }

    @Test
    @DisplayName("陷阱：{floor}01 从楼层 1 起会与名为 101 的源户型同名，但只警告不阻止")
    void floorTemplateStartingAtOneOnlyWarns() {
        List<Placed> existing = List.of(placed("101", 0, 0, 0, 5, 3, 8));
        List<Placed> generated = List.of(placed("101", 0, 4, 0, 5, 7, 8));
        List<Issue> issues = ResidentialArrayValidator.validate(generated, existing, 12, 20, 12);
        assertTrue(hasCode(issues, "duplicate_with_existing"));
        assertFalse(ResidentialArrayValidator.hasError(issues));
    }

    @Test
    @DisplayName("超出结构尺寸报 out_of_bounds；尺寸未知时跳过")
    void outOfBoundsIsErrorButUnknownSizeSkips() {
        List<Placed> generated = List.of(placed("201", 0, 10, 0, 5, 13, 8));
        assertTrue(hasCode(ResidentialArrayValidator.validate(generated, List.of(), 10, 12, 10), "out_of_bounds"));
        assertTrue(ResidentialArrayValidator.validate(generated, List.of(), 0, 0, 0).isEmpty());
    }

    @Test
    @DisplayName("与已有户型重叠报 overlap_existing，并带上双方名字")
    void overlapWithExistingIsReported() {
        List<Placed> existing = List.of(placed("101", 0, 0, 0, 5, 3, 8));
        List<Placed> generated = List.of(placed("201", 0, 2, 0, 5, 5, 8));
        List<Issue> issues = ResidentialArrayValidator.validate(generated, existing, 20, 20, 20);
        assertTrue(hasCode(issues, "overlap_existing"));
        Issue issue = issues.stream().filter(i -> i.code().equals("overlap_existing")).findFirst().orElseThrow();
        assertEquals("201 / 101", issue.detail());
    }

    @Test
    @DisplayName("生成结果之间互相重叠报 overlap_self")
    void selfOverlapIsReported() {
        // 偏移小于户型厚度时，相邻两层会重叠
        List<Placed> generated = List.of(
                placed("201", 0, 4, 0, 5, 7, 8),
                placed("301", 0, 6, 0, 5, 9, 8));
        assertTrue(hasCode(ResidentialArrayValidator.validate(generated, List.of(), 40, 40, 40),
                "overlap_self"));
    }

    @Test
    @DisplayName("批次内部重名是 ERROR；与已有户型重名只是 WARNING")
    void duplicateLabelsAreSplitBySeverity() {
        List<Placed> existing = List.of(placed("101", 0, 0, 0, 5, 3, 8));
        List<Placed> batchSameName = List.of(
                placed("202", 0, 4, 0, 5, 7, 8),
                placed("202", 0, 8, 0, 5, 11, 8));
        List<Issue> batchIssues = ResidentialArrayValidator.validate(batchSameName, existing, 40, 40, 40);
        assertTrue(hasCode(batchIssues, "duplicate_in_batch"));
        assertTrue(ResidentialArrayValidator.hasError(batchIssues));

        List<Placed> collidesWithExisting = List.of(placed("101", 0, 4, 0, 5, 7, 8));
        List<Issue> existingIssues =
                ResidentialArrayValidator.validate(collidesWithExisting, existing, 40, 40, 40);
        assertTrue(hasCode(existingIssues, "duplicate_with_existing"));
        Issue warning = existingIssues.stream()
                .filter(i -> i.code().equals("duplicate_with_existing")).findFirst().orElseThrow();
        assertEquals(Severity.WARNING, warning.severity());
        assertFalse(ResidentialArrayValidator.hasError(existingIssues), "同名不该阻止生成");
    }

    @Test
    @DisplayName("超过上限直接报 too_many 并停止后续判定")
    void tooManyStopsEarly() {
        List<Placed> generated = new ArrayList<>();
        for (int i = 0; i < ResidentialArrayValidator.MAX_TOTAL + 1; i++) {
            generated.add(placed("u" + i, 0, i, 0, 1, i, 1));
        }
        List<Issue> issues = ResidentialArrayValidator.validate(generated, List.of(), 100, 100, 100);
        assertEquals(1, issues.size());
        assertTrue(hasCode(issues, "too_many"));
    }

    @Test
    @DisplayName("问题条数有上限，避免几十条同类刷屏")
    void reportedIssuesAreCapped() {
        List<Placed> generated = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            generated.add(placed("u" + i, 100, 100, 100, 101, 101, 101));
        }
        List<Issue> issues = ResidentialArrayValidator.validate(generated, List.of(), 4, 4, 4);
        assertEquals(ResidentialArrayValidator.MAX_REPORTED, issues.size());
        assertTrue(countCode(issues, "out_of_bounds") > 0);
    }

    @Test
    @DisplayName("issue 的语言键按住宅阵列命名空间拼")
    void translationKeyUsesResidentialNamespace() {
        assertEquals("gui.simupack.residential.array.issue.out_of_bounds",
                Issue.error("out_of_bounds", "x").translationKey());
    }
}
