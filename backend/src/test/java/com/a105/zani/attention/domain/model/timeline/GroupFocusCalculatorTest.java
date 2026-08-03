package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.DetectorOutcome;

import static org.assertj.core.api.Assertions.assertThat;

class GroupFocusCalculatorTest {

    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    /**
     * 세션 길이 180초. 30초 칸 6개가 나온다.
     *
     * <p>30초로 두면 어떤 학생도 연속 접속 1분을 못 넘겨 집계 대상이 되지 못한다. 기여자를 집계 대상으로 좁힌 뒤에는 그 길이로 평균을 확인할 수 없다.
     */
    private static final long DURATION_MS = 180_000L;

    /** 값을 확인하는 칸. 이 앞에 1분이 있어 전 구간 접속 학생이 집계 대상이 되어 있다. */
    private static final long BUCKET = 120L;

    /** 0초부터 세션 끝까지 같은 관측을 보내는 학생. */
    private static ParticipantReplay fullSessionStudent(long id, DetectorOutcome outcome) {
        return new Observations(id, 0L)
                .add(outcome, (int) (DURATION_MS / ObservationRecord.WINDOW_MS))
                .replay();
    }

    /** 모든 5초 스냅샷의 eligibleCount 가 같은 신호 목록. 비율은 이 테스트에서 보지 않는다. */
    private static List<GroupSignalPoint> signalsWithEligible(int eligible) {
        List<GroupSignalPoint> points = new ArrayList<>();
        for (long atMs = 0L;
                atMs <= DURATION_MS;
                atMs += POLICY.samplingInterval().toMillis()) {
            points.add(point(atMs / 1000L, eligible));
        }
        return points;
    }

    /** 한 스냅샷만 인원이 떨어지는 신호 목록. */
    private static List<GroupSignalPoint> signalsDippingAt(int eligible, long dipOffsetSeconds, int dipped) {
        return signalsWithEligible(eligible).stream()
                .map(candidate ->
                        candidate.offsetSeconds() == dipOffsetSeconds ? point(dipOffsetSeconds, dipped) : candidate)
                .toList();
    }

    private static GroupSignalPoint point(long offsetSeconds, int eligible) {
        return new GroupSignalPoint(offsetSeconds, eligible, eligible, null, null, null, null, null, null);
    }

    private static GroupFocusBucket bucketAt(List<GroupFocusBucket> buckets, long offsetSeconds) {
        return buckets.stream()
                .filter(b -> b.offsetSeconds() == offsetSeconds)
                .findFirst()
                .orElseThrow();
    }

    private static Double levelAt(List<ParticipantReplay> students, long offsetSeconds) {
        return bucketAt(
                        GroupFocusCalculator.calculate(students, signalsWithEligible(5), DURATION_MS, POLICY),
                        offsetSeconds)
                .focusLevel();
    }

    @Test
    @DisplayName("학생 3명의 값이 2·3·4 면 3.0 이다")
    void averages_across_students() {
        List<ParticipantReplay> students = List.of(
                fullSessionStudent(1L, DetectorOutcome.BARELY_ENGAGED), // 2.0
                fullSessionStudent(2L, DetectorOutcome.ENGAGED), // 3.0
                fullSessionStudent(3L, DetectorOutcome.HIGHLY_ENGAGED)); // 4.0

        assertThat(levelAt(students, BUCKET)).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("값이 없는 학생은 평균에서 빼고 1단계로 채우지 않는다")
    void students_without_a_value_are_dropped_not_zeroed() {
        // UNMEASURABLE 은 단계가 없어 값이 null 이지만 분모에서 빠지지는 않는다. 값이 없다는 사유 하나로만
        // 빠지는지 보려면 이 관측이어야 한다 — CAMERA_OFF 는 1분 지속으로 분모에서도 빠져 사유가 섞인다.
        List<ParticipantReplay> students = List.of(
                fullSessionStudent(1L, DetectorOutcome.HIGHLY_ENGAGED), // 4.0
                fullSessionStudent(2L, DetectorOutcome.HIGHLY_ENGAGED), // 4.0
                fullSessionStudent(3L, DetectorOutcome.UNMEASURABLE)); // null

        // 1단계로 채웠다면 3.0 이 됐을 것이다.
        assertThat(levelAt(students, BUCKET)).isEqualTo(4.0d);
    }

    @Test
    @DisplayName("값이 있는 학생이 하나도 없으면 빈 값이다")
    void no_student_values_yields_null() {
        assertThat(levelAt(List.of(fullSessionStudent(1L, DetectorOutcome.CAMERA_OFF)), BUCKET))
                .isNull();
    }

    @Test
    @DisplayName("접속 1분을 못 넘긴 학생의 값은 평균에 들어가지 않는다")
    void students_below_the_one_minute_connection_do_not_contribute() {
        // 3번은 115초에 들어와 이 칸 내내 접속 1분을 못 넘긴다. 판정은 3건이라 값 자체는 4.0 으로 나온다.
        List<ParticipantReplay> students = List.of(
                fullSessionStudent(1L, DetectorOutcome.ENGAGED), // 3.0
                fullSessionStudent(2L, DetectorOutcome.ENGAGED), // 3.0
                new Observations(3L, 115_000L)
                        .add(DetectorOutcome.HIGHLY_ENGAGED, 7)
                        .replay());

        // 3번을 넣었다면 3.33 이 됐을 것이다.
        assertThat(levelAt(students, BUCKET)).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("측정 불가 1분 지속으로 분모에서 빠진 학생의 값도 들어가지 않는다")
    void students_suspended_from_the_denominator_do_not_contribute() {
        // 3번은 55초부터 카메라 OFF 를 70초 이어 보내 115~125초 구간에서 분모에서 빠진다. 그 뒤 125초부터
        // 4단계 판정 3건을 채우므로 이 칸의 값은 4.0 으로 나오고, 접속 1분은 이미 넘겼다.
        List<ParticipantReplay> students = List.of(
                fullSessionStudent(1L, DetectorOutcome.ENGAGED), // 3.0
                fullSessionStudent(2L, DetectorOutcome.ENGAGED), // 3.0
                new Observations(3L, 55_000L)
                        .add(DetectorOutcome.CAMERA_OFF, 7)
                        .add(DetectorOutcome.HIGHLY_ENGAGED, 3)
                        .replay());

        // 칸의 첫 스냅샷 120초에서 분모에 없었으므로 빠진다. 넣었다면 3.33 이다.
        assertThat(levelAt(students, BUCKET)).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("칸 중간에 집계 대상이 된 학생은 그 칸 전체에서 빠지고 다음 칸부터 들어간다")
    void a_student_eligible_only_partway_through_is_excluded_from_the_whole_bucket() {
        // 3번은 65초에 들어와 125초에 집계 대상이 된다. 120~150 칸의 스냅샷 6개 중 5개는 대상이지만
        // 120초 하나가 아니므로 칸 전체에서 뺀다 — eligibleCount 를 최솟값으로 잡은 것과 같은 보수성이다.
        List<ParticipantReplay> students = List.of(
                fullSessionStudent(1L, DetectorOutcome.ENGAGED), // 3.0
                fullSessionStudent(2L, DetectorOutcome.ENGAGED), // 3.0
                new Observations(3L, 65_000L)
                        .add(DetectorOutcome.HIGHLY_ENGAGED, 12)
                        .replay());

        assertThat(levelAt(students, BUCKET)).isEqualTo(3.0d);
        // 다음 칸은 스냅샷 전부가 집계 대상이라 3번의 4.0 이 평균에 들어간다.
        assertThat(levelAt(students, BUCKET + 30L)).isEqualTo(3.33d);
    }

    @Test
    @DisplayName("집계 대상이 5명 미만이면 값을 감춘다")
    void hides_the_value_below_five_eligible_students() {
        List<ParticipantReplay> students = List.of(
                fullSessionStudent(1L, DetectorOutcome.HIGHLY_ENGAGED),
                fullSessionStudent(2L, DetectorOutcome.HIGHLY_ENGAGED));

        List<GroupFocusBucket> buckets =
                GroupFocusCalculator.calculate(students, signalsWithEligible(4), DURATION_MS, POLICY);

        assertThat(bucketAt(buckets, BUCKET).eligibleCount()).isEqualTo(4);
        assertThat(bucketAt(buckets, BUCKET).focusLevel()).isNull();
    }

    @Test
    @DisplayName("칸 안에서 인원이 흔들리면 최솟값을 쓴다 — 4명이던 순간이 있으면 감춘다")
    void the_bucket_uses_the_minimum_eligible_count() {
        List<ParticipantReplay> students = List.of(
                fullSessionStudent(1L, DetectorOutcome.HIGHLY_ENGAGED),
                fullSessionStudent(2L, DetectorOutcome.HIGHLY_ENGAGED));

        // 120~150 칸의 스냅샷 6개 중 130초 하나만 4명이다 → 최솟값 4.
        List<GroupFocusBucket> buckets =
                GroupFocusCalculator.calculate(students, signalsDippingAt(6, 130L, 4), DURATION_MS, POLICY);

        assertThat(bucketAt(buckets, BUCKET).eligibleCount()).isEqualTo(4);
        assertThat(bucketAt(buckets, BUCKET).focusLevel()).isNull();
    }

    @Test
    @DisplayName("학생이 없으면 빈 목록이다")
    void no_students_yields_an_empty_list() {
        assertThat(GroupFocusCalculator.calculate(List.of(), List.of(), DURATION_MS, POLICY))
                .isEmpty();
    }

    /** 10초 관측을 이어 붙여 한 학생의 재생기를 만든다. */
    private static final class Observations {

        private final long participantId;
        private final List<ObservationRecord> records = new ArrayList<>();
        private long cursorMs;

        private Observations(long participantId, long fromMs) {
            this.participantId = participantId;
            this.cursorMs = fromMs;
        }

        private Observations add(DetectorOutcome outcome, int count) {
            for (int i = 0; i < count; i++) {
                records.add(ObservationRecords.at(participantId, cursorMs, outcome));
                cursorMs += ObservationRecord.WINDOW_MS;
            }
            return this;
        }

        private ParticipantReplay replay() {
            return ParticipantReplay.of(participantId, records, List.of(), POLICY);
        }
    }
}
