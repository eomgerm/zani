package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.PromptAnswer;

import static org.assertj.core.api.Assertions.assertThat;

class StateIntervalMergerTest {

    private static final long PARTICIPANT = 1L;
    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    private static List<DetectorOutcome> repeat(DetectorOutcome outcome, int count) {
        List<DetectorOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            outcomes.add(outcome);
        }
        return outcomes;
    }

    private static ParticipantReplay replayOf(List<PromptRecord> prompts, List<DetectorOutcome> outcomes) {
        List<ObservationRecord> records = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            records.add(ObservationRecords.at(PARTICIPANT, i * 10_000L, outcomes.get(i)));
        }
        return ParticipantReplay.of(PARTICIPANT, records, prompts, POLICY);
    }

    @Test
    @DisplayName("같은 상태가 이어지면 하나의 구간으로 합친다")
    void merges_adjacent_identical_states() {
        List<StateInterval> intervals =
                StateIntervalMerger.merge(replayOf(List.of(), repeat(DetectorOutcome.ENGAGED, 12)), 120_000L, POLICY);

        assertThat(intervals).hasSize(1);
        assertThat(intervals.get(0).state()).isEqualTo(StudentTimelineState.GOOD);
    }

    @Test
    @DisplayName("GOOD 도 구간으로 준다 — FE 가 빈 곳을 채우지 않게 한다")
    void good_is_included() {
        List<StateInterval> intervals = StateIntervalMerger.merge(
                replayOf(List.of(), repeat(DetectorOutcome.HIGHLY_ENGAGED, 6)), 60_000L, POLICY);

        assertThat(intervals).extracting(StateInterval::state).containsExactly(StudentTimelineState.GOOD);
    }

    @Test
    @DisplayName("상태가 바뀌면 구간이 나뉜다")
    void a_state_change_splits_the_interval() {
        List<DetectorOutcome> outcomes = new ArrayList<>(repeat(DetectorOutcome.ENGAGED, 6));
        outcomes.addAll(repeat(DetectorOutcome.CAMERA_OFF, 12));

        List<StateInterval> intervals = StateIntervalMerger.merge(replayOf(List.of(), outcomes), 180_000L, POLICY);

        assertThat(intervals)
                .extracting(StateInterval::state)
                .containsExactly(StudentTimelineState.GOOD, StudentTimelineState.CAMERA_OFF);
    }

    @Test
    @DisplayName("CAMERA_OFF 가 UNMEASURABLE 과 CHECK_NEEDED 보다 앞선다")
    void camera_off_wins_the_priority() {
        // CAMERA_OFF 중에 프롬프트 CONFUSED 가 유효한 상황.
        List<StateInterval> intervals = StateIntervalMerger.merge(
                replayOf(
                        List.of(new PromptRecord(PARTICIPANT, 70_000L, PromptAnswer.CONFUSED)),
                        repeat(DetectorOutcome.CAMERA_OFF, 18)),
                180_000L,
                POLICY);

        assertThat(intervals).extracting(StateInterval::state).containsOnly(StudentTimelineState.CAMERA_OFF);
    }

    @Test
    @DisplayName("CONFUSED · MISSED · NON_RESPONSE 는 모두 CHECK_NEEDED 하나로 뭉뚱그린다")
    void significant_answers_collapse_into_check_needed() {
        for (PromptAnswer answer : List.of(PromptAnswer.CONFUSED, PromptAnswer.MISSED, PromptAnswer.NON_RESPONSE)) {
            List<StateInterval> intervals = StateIntervalMerger.merge(
                    replayOf(
                            List.of(new PromptRecord(PARTICIPANT, 0L, answer)),
                            repeat(DetectorOutcome.BARELY_ENGAGED, 12)),
                    120_000L,
                    POLICY);

            assertThat(intervals).extracting(StateInterval::state).containsOnly(StudentTimelineState.CHECK_NEEDED);
        }
    }

    @Test
    @DisplayName("관측이 없는 시간은 구간을 만들지 않는다 — 구간 사이가 빌 수 있다")
    void gaps_produce_no_interval() {
        // 30초까지만 관측이 있고 나머지 90초는 비어 있다.
        List<StateInterval> intervals =
                StateIntervalMerger.merge(replayOf(List.of(), repeat(DetectorOutcome.ENGAGED, 3)), 120_000L, POLICY);

        assertThat(intervals).hasSize(1);
        assertThat(intervals.get(0).endSeconds()).isLessThan(120L);
    }

    @Test
    @DisplayName("짧은 CAMERA_OFF 도 구간이 된다 — 분모 제외처럼 1분을 기다리지 않는다")
    void a_short_camera_off_still_becomes_an_interval() {
        // 카메라가 30초만 꺼졌다. 분모 제외 기준(1분 지속)에는 못 미치지만 관측은 있었으므로
        // "기록 없음" 으로 사라지면 안 된다(설계 문서 §2.13).
        List<DetectorOutcome> outcomes = new ArrayList<>(repeat(DetectorOutcome.ENGAGED, 6));
        outcomes.addAll(repeat(DetectorOutcome.CAMERA_OFF, 3));
        outcomes.addAll(repeat(DetectorOutcome.ENGAGED, 6));

        List<StateInterval> intervals = StateIntervalMerger.merge(replayOf(List.of(), outcomes), 150_000L, POLICY);

        assertThat(intervals)
                .extracting(StateInterval::state)
                .containsExactly(StudentTimelineState.GOOD, StudentTimelineState.CAMERA_OFF, StudentTimelineState.GOOD);
        assertThat(intervals.get(1).startSeconds()).isEqualTo(60L);
        assertThat(intervals.get(1).endSeconds()).isEqualTo(90L);
    }

    @Test
    @DisplayName("검출기가 죽은 시간은 UNMEASURABLE 구간이다")
    void detector_unavailable_becomes_unmeasurable() {
        List<DetectorOutcome> outcomes = new ArrayList<>(repeat(DetectorOutcome.ENGAGED, 6));
        outcomes.addAll(repeat(DetectorOutcome.DETECTOR_UNAVAILABLE, 6));

        List<StateInterval> intervals = StateIntervalMerger.merge(replayOf(List.of(), outcomes), 120_000L, POLICY);

        assertThat(intervals)
                .extracting(StateInterval::state)
                .containsExactly(StudentTimelineState.GOOD, StudentTimelineState.UNMEASURABLE);
    }

    @Test
    @DisplayName("관측이 한 건도 없으면 빈 목록이다")
    void no_observations_yields_an_empty_list() {
        ParticipantReplay empty = ParticipantReplay.of(PARTICIPANT, List.of(), List.of(), POLICY);

        assertThat(StateIntervalMerger.merge(empty, 120_000L, POLICY)).isEmpty();
    }
}
