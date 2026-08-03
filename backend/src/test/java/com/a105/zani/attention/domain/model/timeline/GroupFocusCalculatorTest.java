package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.DetectorOutcome;

import static org.assertj.core.api.Assertions.assertThat;

class GroupFocusCalculatorTest {

    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    /** 지정한 관측을 10초 간격으로 slots 건 내보내는 학생. */
    private static ParticipantReplay studentAtLevel(long id, DetectorOutcome outcome, int slots) {
        List<ObservationRecord> records = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            records.add(new ObservationRecord(id, i * 10_000L, outcome));
        }
        return ParticipantReplay.of(id, records, List.of(), POLICY);
    }

    /** eligibleCount 만 지정한 5초 신호 점 목록. 비율은 이 테스트에서 보지 않는다. */
    private static List<GroupSignalPoint> signalsWithEligible(int... eligiblePerPoint) {
        List<GroupSignalPoint> points = new ArrayList<>();
        for (int i = 0; i < eligiblePerPoint.length; i++) {
            int eligible = eligiblePerPoint[i];
            points.add(new GroupSignalPoint(i * 5L, eligible, eligible, null, null, null, null, null, null));
        }
        return points;
    }

    private static GroupFocusBucket bucketAt(List<GroupFocusBucket> buckets, long offsetSeconds) {
        return buckets.stream()
                .filter(b -> b.offsetSeconds() == offsetSeconds)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("학생 3명의 값이 2·3·4 면 3.0 이다")
    void averages_across_students() {
        List<ParticipantReplay> students = List.of(
                studentAtLevel(1L, DetectorOutcome.BARELY_ENGAGED, 3), // 2.0
                studentAtLevel(2L, DetectorOutcome.ENGAGED, 3), // 3.0
                studentAtLevel(3L, DetectorOutcome.HIGHLY_ENGAGED, 3)); // 4.0

        List<GroupFocusBucket> buckets =
                GroupFocusCalculator.calculate(students, signalsWithEligible(5, 5, 5, 5, 5, 5, 5), 30_000L, POLICY);

        assertThat(bucketAt(buckets, 0L).focusLevel()).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("값이 없는 학생은 평균에서 빼고 1단계로 채우지 않는다")
    void students_without_a_value_are_dropped_not_zeroed() {
        List<ParticipantReplay> students = List.of(
                studentAtLevel(1L, DetectorOutcome.HIGHLY_ENGAGED, 3), // 4.0
                studentAtLevel(2L, DetectorOutcome.HIGHLY_ENGAGED, 3), // 4.0
                studentAtLevel(3L, DetectorOutcome.CAMERA_OFF, 3)); // null

        List<GroupFocusBucket> buckets =
                GroupFocusCalculator.calculate(students, signalsWithEligible(5, 5, 5, 5, 5, 5, 5), 30_000L, POLICY);

        // 1단계로 채웠다면 3.0 이 됐을 것이다.
        assertThat(bucketAt(buckets, 0L).focusLevel()).isEqualTo(4.0d);
    }

    @Test
    @DisplayName("값이 있는 학생이 하나도 없으면 빈 값이다")
    void no_student_values_yields_null() {
        List<GroupFocusBucket> buckets = GroupFocusCalculator.calculate(
                List.of(studentAtLevel(1L, DetectorOutcome.CAMERA_OFF, 3)),
                signalsWithEligible(5, 5, 5, 5, 5, 5, 5),
                30_000L,
                POLICY);

        assertThat(bucketAt(buckets, 0L).focusLevel()).isNull();
    }

    @Test
    @DisplayName("집계 대상이 5명 미만이면 값을 감춘다")
    void hides_the_value_below_five_eligible_students() {
        List<ParticipantReplay> students = List.of(
                studentAtLevel(1L, DetectorOutcome.HIGHLY_ENGAGED, 3),
                studentAtLevel(2L, DetectorOutcome.HIGHLY_ENGAGED, 3));

        List<GroupFocusBucket> buckets =
                GroupFocusCalculator.calculate(students, signalsWithEligible(4, 4, 4, 4, 4, 4, 4), 30_000L, POLICY);

        assertThat(bucketAt(buckets, 0L).eligibleCount()).isEqualTo(4);
        assertThat(bucketAt(buckets, 0L).focusLevel()).isNull();
    }

    @Test
    @DisplayName("칸 안에서 인원이 흔들리면 최솟값을 쓴다 — 4명이던 순간이 있으면 감춘다")
    void the_bucket_uses_the_minimum_eligible_count() {
        List<ParticipantReplay> students = List.of(
                studentAtLevel(1L, DetectorOutcome.HIGHLY_ENGAGED, 3),
                studentAtLevel(2L, DetectorOutcome.HIGHLY_ENGAGED, 3));

        // 0·5·10·15·20·25초 스냅샷: 6,6,4,6,6,6 → 최솟값 4
        List<GroupFocusBucket> buckets =
                GroupFocusCalculator.calculate(students, signalsWithEligible(6, 6, 4, 6, 6, 6, 6), 30_000L, POLICY);

        assertThat(bucketAt(buckets, 0L).eligibleCount()).isEqualTo(4);
        assertThat(bucketAt(buckets, 0L).focusLevel()).isNull();
    }

    @Test
    @DisplayName("학생이 없으면 빈 목록이다")
    void no_students_yields_an_empty_list() {
        assertThat(GroupFocusCalculator.calculate(List.of(), List.of(), 30_000L, POLICY))
                .isEmpty();
    }
}
