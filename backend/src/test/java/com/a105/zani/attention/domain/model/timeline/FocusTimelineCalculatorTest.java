package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.PromptAnswer;

import static org.assertj.core.api.Assertions.assertThat;

class FocusTimelineCalculatorTest {

    private static final long PARTICIPANT = 1L;
    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    /** fromSeconds 부터 count 건을 10초 간격으로 만든다. */
    private static List<ObservationRecord> events(long fromSeconds, int count, DetectorOutcome outcome) {
        List<ObservationRecord> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(new ObservationRecord(PARTICIPANT, (fromSeconds + i * 10L) * 1000L, outcome));
        }
        return records;
    }

    /** 0초부터 first 를 firstCount 건, 이어서 second 를 secondCount 건 내보낸다. */
    private static List<ObservationRecord> mixed(
            DetectorOutcome first, int firstCount, DetectorOutcome second, int secondCount) {
        List<ObservationRecord> records = new ArrayList<>(events(0, firstCount, first));
        records.addAll(events(firstCount * 10L, secondCount, second));
        return records;
    }

    private static ParticipantReplay replayOf(List<ObservationRecord> events) {
        return replayOf(events, List.of());
    }

    private static ParticipantReplay replayOf(List<ObservationRecord> events, List<PromptRecord> prompts) {
        return ParticipantReplay.of(PARTICIPANT, events, prompts, POLICY);
    }

    private static FocusTimelinePoint pointAt(List<FocusTimelinePoint> points, long offsetSeconds) {
        return points.stream()
                .filter(point -> point.offsetSeconds() == offsetSeconds)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no point at " + offsetSeconds + "s"));
    }

    @Test
    @DisplayName("30초 창이 모두 GOOD 이면 100 이다")
    void all_good_is_one_hundred() {
        List<FocusTimelinePoint> points =
                FocusTimelineCalculator.calculate(replayOf(events(0, 60, DetectorOutcome.ENGAGED)), 300_000L, POLICY);

        assertThat(pointAt(points, 100L).focusPercent()).isEqualTo(100);
        assertThat(pointAt(points, 100L).state()).isEqualTo(StudentTimelineState.GOOD);
    }

    @Test
    @DisplayName("측정 가능 시간이 창의 70% 미만이면 값을 계산하지 않는다")
    void below_seventy_percent_coverage_is_null() {
        // 30초 창 = 슬롯 3개. 카메라 OFF 가 2개면 측정 가능 10초 = 33% 라 null 이다.
        List<FocusTimelinePoint> points = FocusTimelineCalculator.calculate(
                replayOf(mixed(DetectorOutcome.CAMERA_OFF, 2, DetectorOutcome.ENGAGED, 1)), 300_000L, POLICY);

        assertThat(pointAt(points, 30L).focusPercent()).isNull();
    }

    @Test
    @DisplayName("CAMERA_OFF 와 UNMEASURABLE 은 0 점이 아니라 빈 값이다")
    void camera_off_is_not_zero() {
        List<FocusTimelinePoint> points = FocusTimelineCalculator.calculate(
                replayOf(events(0, 60, DetectorOutcome.CAMERA_OFF)), 300_000L, POLICY);

        FocusTimelinePoint point = pointAt(points, 200L);
        assertThat(point.focusPercent()).isNull();
        assertThat(point.state()).isEqualTo(StudentTimelineState.CAMERA_OFF);
    }

    @Test
    @DisplayName("확정 전 UNMEASURABLE 은 측정 가능 시간에 남아 점수를 낮춘다")
    void unconfirmed_unmeasurable_lowers_the_score() {
        // 슬롯 3개 중 2개는 ENGAGED, 1개는 확정 전 UNMEASURABLE.
        List<FocusTimelinePoint> points = FocusTimelineCalculator.calculate(
                replayOf(mixed(DetectorOutcome.ENGAGED, 2, DetectorOutcome.UNMEASURABLE, 1)), 300_000L, POLICY);

        // 측정 가능 30초 중 GOOD 20초 → 67
        assertThat(pointAt(points, 30L).focusPercent()).isEqualTo(67);
    }

    @Test
    @DisplayName("상태는 CAMERA_OFF · UNMEASURABLE · CHECK_NEEDED · GOOD 순으로 고른다")
    void state_priority() {
        List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
        records.addAll(events(120, 3, DetectorOutcome.UNMEASURABLE)); // 140초에 확정, 440초까지 유효
        records.addAll(events(150, 12, DetectorOutcome.ENGAGED));
        records.addAll(events(270, 6, DetectorOutcome.CAMERA_OFF)); // 270~330초
        records.addAll(events(330, 30, DetectorOutcome.ENGAGED));

        // CONFUSED 는 200초부터 500초까지 유효해 아래 세 시각에 모두 걸쳐 있다.
        List<FocusTimelinePoint> points = FocusTimelineCalculator.calculate(
                replayOf(records, List.of(new PromptRecord(PARTICIPANT, 200_000L, PromptAnswer.CONFUSED))),
                700_000L,
                POLICY);

        // 카메라가 꺼져 있다. UNMEASURABLE 확정도 CONFUSED 도 함께 유효하지만 카메라가 이긴다.
        assertThat(pointAt(points, 300L).state()).isEqualTo(StudentTimelineState.CAMERA_OFF);
        // 카메라는 켜져 있고 UNMEASURABLE 확정과 CONFUSED 가 함께 유효하다. UNMEASURABLE 이 이긴다.
        assertThat(pointAt(points, 250L).state()).isEqualTo(StudentTimelineState.UNMEASURABLE);
        // UNMEASURABLE 은 440초에 끝났고 CONFUSED 만 남았다. 검출기는 GOOD 이지만 CHECK_NEEDED 다.
        assertThat(pointAt(points, 450L).state()).isEqualTo(StudentTimelineState.CHECK_NEEDED);
        // 남은 유의 상태가 없다. 검출기 GOOD 만 있다.
        assertThat(pointAt(points, 550L).state()).isEqualTo(StudentTimelineState.GOOD);
    }

    @Test
    @DisplayName("CONFUSED 응답은 CHECK_NEEDED 로 뭉뚱그린다 — 학생 화면은 셋을 구분하지 않는다")
    void significant_answers_collapse_into_check_needed() {
        for (PromptAnswer answer : List.of(PromptAnswer.CONFUSED, PromptAnswer.MISSED, PromptAnswer.NON_RESPONSE)) {
            List<FocusTimelinePoint> points = FocusTimelineCalculator.calculate(
                    replayOf(
                            events(0, 60, DetectorOutcome.ENGAGED),
                            List.of(new PromptRecord(PARTICIPANT, 100_000L, answer))),
                    300_000L,
                    POLICY);

            assertThat(pointAt(points, 200L).state()).as("%s 응답", answer).isEqualTo(StudentTimelineState.CHECK_NEEDED);
        }
    }

    @Test
    @DisplayName("관측이 없는 시각은 상태도 빈 값이다")
    void a_gap_in_observations_has_no_state() {
        // 0~120초만 관측이 있고 그 뒤로는 아무것도 없다.
        List<FocusTimelinePoint> points =
                FocusTimelineCalculator.calculate(replayOf(events(0, 12, DetectorOutcome.ENGAGED)), 300_000L, POLICY);

        FocusTimelinePoint point = pointAt(points, 200L);
        assertThat(point.state()).isNull();
        assertThat(point.focusPercent()).isNull();
    }

    @Test
    @DisplayName("창을 채우지 못하는 세션 시작 직후는 빈 값이다")
    void the_first_window_is_null() {
        List<FocusTimelinePoint> points =
                FocusTimelineCalculator.calculate(replayOf(events(0, 60, DetectorOutcome.ENGAGED)), 300_000L, POLICY);

        assertThat(pointAt(points, 0L).focusPercent()).isNull();
    }
}
