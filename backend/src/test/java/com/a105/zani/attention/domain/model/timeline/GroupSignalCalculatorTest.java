package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.PromptAnswer;

import static org.assertj.core.api.Assertions.assertThat;

class GroupSignalCalculatorTest {

    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    /** 0초부터 10초 간격으로 slots 건을 내보내는 학생. */
    private static ParticipantReplay student(long id, DetectorOutcome outcome, int slots) {
        return student(id, outcome, slots, List.of());
    }

    private static ParticipantReplay student(long id, DetectorOutcome outcome, int slots, List<PromptRecord> prompts) {
        List<ObservationRecord> events = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            events.add(ObservationRecords.at(id, i * 10_000L, outcome));
        }
        return ParticipantReplay.of(id, events, prompts, POLICY);
    }

    private static GroupSignalPoint pointAt(List<GroupSignalPoint> points, long offsetSeconds) {
        return points.stream()
                .filter(point -> point.offsetSeconds() == offsetSeconds)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no point at " + offsetSeconds + "s"));
    }

    /** 전원 ENGAGED 인 학생 5명. 유의 상태가 하나도 없어 확인 필요 비율이 0 이다. */
    private static List<ParticipantReplay> fiveEngagedStudents() {
        List<ParticipantReplay> students = new ArrayList<>();
        for (long id = 1L; id <= 5L; id++) {
            students.add(student(id, DetectorOutcome.ENGAGED, 60));
        }
        return students;
    }

    /**
     * 학생 10명 중 6명이 카메라를 끈 채로 계속 있고, 켠 4명 중 2명이 CONFUSED 로 답했다.
     *
     * <p>카메라 OFF 6명은 1분이 지나 분모에서 빠지므로 connected 10 · eligible 4 가 된다.
     */
    private static List<ParticipantReplay> sixCameraOffAndFourEngagedWithTwoConfused() {
        List<ParticipantReplay> students = new ArrayList<>();
        for (long id = 1L; id <= 6L; id++) {
            students.add(student(id, DetectorOutcome.CAMERA_OFF, 60));
        }
        for (long id = 7L; id <= 10L; id++) {
            List<PromptRecord> prompts =
                    id <= 8L ? List.of(new PromptRecord(id, 300_000L, PromptAnswer.CONFUSED)) : List.of();
            students.add(student(id, DetectorOutcome.ENGAGED, 60, prompts));
        }
        return students;
    }

    /** 학생 5명 중 1명만 UNMEASURABLE 확정과 CONFUSED 응답을 함께 가진다. */
    private static List<ParticipantReplay> fiveStudentsOneWithTwoSignificantStates() {
        List<ParticipantReplay> students = new ArrayList<>();
        for (long id = 1L; id <= 4L; id++) {
            students.add(student(id, DetectorOutcome.ENGAGED, 60));
        }

        List<ObservationRecord> events = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            // 300·310·320초 세 건이 UNMEASURABLE 이라 320초에 참여 상태가 확정되고 620초까지 유효하다.
            long slotStartMs = i * 10_000L;
            boolean unmeasurable = slotStartMs >= 300_000L && slotStartMs <= 320_000L;
            events.add(ObservationRecords.at(
                    5L, slotStartMs, unmeasurable ? DetectorOutcome.UNMEASURABLE : DetectorOutcome.ENGAGED));
        }
        students.add(ParticipantReplay.of(
                5L, events, List.of(new PromptRecord(5L, 330_000L, PromptAnswer.CONFUSED)), POLICY));
        return students;
    }

    @Test
    @DisplayName("5초 간격으로 세션 길이만큼 점을 만든다")
    void emits_a_point_every_five_seconds() {
        List<GroupSignalPoint> points =
                GroupSignalCalculator.calculate(List.of(student(1L, DetectorOutcome.ENGAGED, 60)), 300_000L, POLICY);

        assertThat(points).hasSize(61); // 0, 5, ... 300
        assertThat(points.get(0).offsetSeconds()).isZero();
        assertThat(points.get(1).offsetSeconds()).isEqualTo(5L);
        assertThat(points.get(60).offsetSeconds()).isEqualTo(300L);
    }

    @Test
    @DisplayName("집계 대상이 5명 미만이면 비율과 응답 분포를 숨긴다")
    void hides_ratios_below_five_eligible_students() {
        List<ParticipantReplay> four = List.of(
                student(1L, DetectorOutcome.ENGAGED, 60),
                student(2L, DetectorOutcome.ENGAGED, 60),
                student(3L, DetectorOutcome.ENGAGED, 60),
                student(4L, DetectorOutcome.ENGAGED, 60));

        GroupSignalPoint point = pointAt(GroupSignalCalculator.calculate(four, 300_000L, POLICY), 200L);

        assertThat(point.eligibleCount()).isEqualTo(4);
        assertThat(point.checkNeededRatio()).isNull();
        assertThat(point.cameraOffRatio()).isNull();
        assertThat(point.confusedRatio()).isNull();
        assertThat(point.missedRatio()).isNull();
        assertThat(point.nonResponseRatio()).isNull();
        assertThat(point.unmeasurableRatio()).isNull();
    }

    @Test
    @DisplayName("5명이면 비율이 보인다 — 경계는 미만이다")
    void shows_ratios_at_exactly_five() {
        GroupSignalPoint point =
                pointAt(GroupSignalCalculator.calculate(fiveEngagedStudents(), 300_000L, POLICY), 200L);

        assertThat(point.eligibleCount()).isEqualTo(5);
        assertThat(point.checkNeededRatio()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("확인 필요 비율의 분모는 제외 후, 카메라 OFF 비율의 분모는 제외 전이다")
    void the_two_series_use_different_denominators() {
        // 학생 10명 중 6명이 카메라를 끈 지 1분이 넘었고, 켠 4명 중 2명이 CONFUSED 다.
        List<ParticipantReplay> students = sixCameraOffAndFourEngagedWithTwoConfused();

        GroupSignalPoint point = pointAt(GroupSignalCalculator.calculate(students, 600_000L, POLICY), 400L);

        assertThat(point.connectedCount()).isEqualTo(10);
        assertThat(point.eligibleCount()).isEqualTo(4);
        assertThat(point.cameraOffRatio()).isEqualTo(0.6d); // 6 / 10
        // eligible 이 4명이라 5명 미만 숨김이 걸린다. 분모가 다르다는 사실만 확인한다.
        assertThat(point.checkNeededRatio()).isNull();
    }

    @Test
    @DisplayName("두 상태를 함께 겪은 학생을 두 번 세지 않는다")
    void the_numerator_is_a_union() {
        // 학생 5명 중 1명이 UNMEASURABLE 확정과 CONFUSED 응답을 모두 가진다.
        GroupSignalPoint point = pointAt(
                GroupSignalCalculator.calculate(fiveStudentsOneWithTwoSignificantStates(), 600_000L, POLICY), 400L);

        assertThat(point.checkNeededRatio()).isEqualTo(0.2d); // 2/5 가 아니라 1/5
        // 분포는 상태별로 센다. 한 학생이 두 상태를 가지면 두 분포 모두에 들어가므로 합이 확인 필요 비율보다
        // 클 수 있고, 그것이 정상이다.
        assertThat(point.unmeasurableRatio()).isEqualTo(0.2d);
        assertThat(point.confusedRatio()).isEqualTo(0.2d);
    }

    @Test
    @DisplayName("접속자가 없는 구간은 분모가 0 이라 비율이 null 이다")
    void no_connected_students_yields_null() {
        GroupSignalPoint point = pointAt(
                GroupSignalCalculator.calculate(List.of(student(1L, DetectorOutcome.ENGAGED, 6)), 600_000L, POLICY),
                500L);

        assertThat(point.connectedCount()).isZero();
        assertThat(point.checkNeededRatio()).isNull();
        assertThat(point.cameraOffRatio()).isNull();
    }
}
