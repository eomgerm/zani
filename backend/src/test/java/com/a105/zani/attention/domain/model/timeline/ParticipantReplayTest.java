package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.PromptAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ParticipantReplayTest {

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

    private static ParticipantReplay replay(List<ObservationRecord> events, List<PromptRecord> prompts) {
        return ParticipantReplay.of(PARTICIPANT, events, prompts, POLICY);
    }

    @Nested
    @DisplayName("연속 접속")
    class Connection {

        @Test
        @DisplayName("1분을 넘긴 시점부터 집계 대상이다 — 59초는 빠지고 60초는 들어온다")
        void counted_only_after_one_minute() {
            ParticipantReplay replay = replay(events(0, 12, DetectorOutcome.ENGAGED), List.of());

            assertThat(replay.counted(59_000L)).isFalse();
            assertThat(replay.counted(60_000L)).isTrue();
        }

        @Test
        @DisplayName("이벤트가 30초를 넘겨 끊기면 이탈로 보고 연속 접속을 다시 센다")
        void gap_over_thirty_seconds_restarts_the_timer() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
            // 110초에서 끊고 200초부터 다시 보낸다. 공백 90초.
            records.addAll(events(200, 12, DetectorOutcome.ENGAGED));

            ParticipantReplay replay = replay(records, List.of());

            assertThat(replay.counted(150_000L)).isFalse();
            // 200초에 다시 시작했으므로 259초는 아직이고 260초부터 다시 들어온다.
            assertThat(replay.counted(259_000L)).isFalse();
            assertThat(replay.counted(260_000L)).isTrue();
        }

        @Test
        @DisplayName("공백이 30초 이하면 접속이 이어진 것으로 본다")
        void gap_within_thirty_seconds_keeps_the_connection() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 6, DetectorOutcome.ENGAGED));
            // 50초 다음 이벤트가 80초 — 공백 30초.
            records.addAll(events(80, 6, DetectorOutcome.ENGAGED));

            assertThat(replay(records, List.of()).counted(90_000L)).isTrue();
        }
    }

    @Nested
    @DisplayName("측정 불가로 인한 분모 제외")
    class Exclusion {

        @Test
        @DisplayName("CAMERA_OFF 가 59초면 남고 60초부터 빠진다")
        void camera_off_excludes_after_one_minute() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
            records.addAll(events(120, 12, DetectorOutcome.CAMERA_OFF));

            ParticipantReplay replay = replay(records, List.of());

            assertThat(replay.measurementSuspended(120_000L + 59_000L)).isFalse();
            assertThat(replay.measurementSuspended(120_000L + 60_000L)).isTrue();
            assertThat(replay.cameraOff(120_000L + 60_000L)).isTrue();
        }

        @Test
        @DisplayName("DETECTOR_UNAVAILABLE 도 분모에서 빼지만 카메라 OFF 비율에는 넣지 않는다")
        void detector_unavailable_excludes_but_is_not_camera_off() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
            records.addAll(events(120, 12, DetectorOutcome.DETECTOR_UNAVAILABLE));

            ParticipantReplay replay = replay(records, List.of());

            assertThat(replay.measurementSuspended(200_000L)).isTrue();
            assertThat(replay.cameraOff(200_000L)).isFalse();
        }

        @Test
        @DisplayName("검출기가 죽은 시간은 1분을 채우기 전이라도 측정 가능 시간이 아니다")
        void detector_unavailable_is_never_measurable_time() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
            // 두 건뿐이라 분모 제외(1분 지속)에는 못 미친다. 그래도 측정한 것이 없으므로 개인 점수의 분모에도
            // 들어가면 안 된다 — 남겨 두면 검출기가 죽은 학생의 점수가 0 으로 떨어진다.
            records.addAll(events(120, 2, DetectorOutcome.DETECTOR_UNAVAILABLE));

            ParticipantReplay replay = replay(records, List.of());

            assertThat(replay.measurementSuspended(125_000L)).isFalse();
            assertThat(replay.measurable(125_000L)).isFalse();
        }

        @Test
        @DisplayName("측정이 가능해지면 1분을 기다리지 않고 즉시 돌아온다")
        void recovery_is_immediate() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
            records.addAll(events(120, 12, DetectorOutcome.CAMERA_OFF));
            records.addAll(events(240, 6, DetectorOutcome.ENGAGED));

            assertThat(replay(records, List.of()).measurementSuspended(240_000L))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("참여 상태")
    class States {

        @Test
        @DisplayName("UNMEASURABLE 은 3연속일 때만 참여 상태가 되고 5분 동안 남는다")
        void unmeasurable_needs_three_in_a_row() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
            // 120·130초 두 건은 확정에 못 미친다. 140초에 세 번째가 들어와 확정된다.
            records.addAll(events(120, 3, DetectorOutcome.UNMEASURABLE));
            records.addAll(events(150, 12, DetectorOutcome.ENGAGED));

            ParticipantReplay replay = replay(records, List.of());

            assertThat(replay.significantStatesAt(130_000L)).isEmpty();
            assertThat(replay.significantStatesAt(140_000L)).containsExactly(AttentionState.UNMEASURABLE);
            // 확정 시각부터 5분 유효.
            assertThat(replay.significantStatesAt(140_000L + 299_000L)).containsExactly(AttentionState.UNMEASURABLE);
            assertThat(replay.significantStatesAt(140_000L + 300_000L)).isEmpty();
        }

        @Test
        @DisplayName("프롬프트 응답은 응답 시각부터 5분 동안 유의 상태로 남는다")
        void prompt_answer_lasts_five_minutes() {
            ParticipantReplay replay = replay(
                    events(0, 60, DetectorOutcome.BARELY_ENGAGED),
                    List.of(new PromptRecord(PARTICIPANT, 100_000L, PromptAnswer.CONFUSED)));

            assertThat(replay.significantStatesAt(99_000L)).isEmpty();
            assertThat(replay.significantStatesAt(100_000L)).containsExactly(AttentionState.CONFUSED);
            assertThat(replay.significantStatesAt(399_000L)).containsExactly(AttentionState.CONFUSED);
            assertThat(replay.significantStatesAt(400_000L)).isEmpty();
        }

        @Test
        @DisplayName("같은 시각에 두 유의 상태를 함께 가질 수 있다")
        void two_significant_states_can_overlap() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
            records.addAll(events(120, 3, DetectorOutcome.UNMEASURABLE)); // 140초에 확정
            records.addAll(events(150, 30, DetectorOutcome.BARELY_ENGAGED));

            ParticipantReplay replay =
                    replay(records, List.of(new PromptRecord(PARTICIPANT, 200_000L, PromptAnswer.CONFUSED)));

            // UNMEASURABLE 은 140초부터 440초까지, CONFUSED 는 200초부터 500초까지 유효하다.
            assertThat(replay.significantStatesAt(250_000L))
                    .containsExactlyInAnyOrder(AttentionState.UNMEASURABLE, AttentionState.CONFUSED);
        }

        @Test
        @DisplayName("OK 응답은 GOOD 이라 유의 상태가 아니다")
        void ok_answer_is_not_significant() {
            ParticipantReplay replay = replay(
                    events(0, 60, DetectorOutcome.BARELY_ENGAGED),
                    List.of(new PromptRecord(PARTICIPANT, 100_000L, PromptAnswer.OK)));

            assertThat(replay.significantStatesAt(150_000L)).isEmpty();
            assertThat(replay.good(150_000L)).isTrue();
        }

        @Test
        @DisplayName("검출기 3·4단계는 프롬프트 없이 바로 GOOD 이고 1·2단계는 아니다")
        void engaged_levels_are_good() {
            assertThat(replay(events(0, 12, DetectorOutcome.HIGHLY_ENGAGED), List.of())
                            .good(50_000L))
                    .isTrue();
            assertThat(replay(events(0, 12, DetectorOutcome.NOT_ENGAGED), List.of())
                            .good(50_000L))
                    .isFalse();
        }

        @Test
        @DisplayName("확정 전 UNMEASURABLE 은 측정 가능 시간에 남는다")
        void unconfirmed_unmeasurable_still_counts_as_measurable() {
            List<ObservationRecord> records = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
            records.addAll(events(120, 2, DetectorOutcome.UNMEASURABLE));

            ParticipantReplay replay = replay(records, List.of());

            // FRD §19.2 는 "참여 상태"가 UNMEASURABLE 인 시간만 뺀다. 3연속 전은 참여 상태가 아니다.
            assertThat(replay.measurable(125_000L)).isTrue();
            assertThat(replay.good(125_000L)).isFalse();
        }
    }

    @Test
    @DisplayName("이벤트가 순서 없이 들어와도 시간순으로 재생한다")
    void unordered_input_is_sorted() {
        List<ObservationRecord> shuffled = new ArrayList<>(events(0, 12, DetectorOutcome.ENGAGED));
        Collections.reverse(shuffled);

        assertThatCode(() -> replay(shuffled, List.of()).counted(60_000L)).doesNotThrowAnyException();
        assertThat(replay(shuffled, List.of()).counted(60_000L)).isTrue();
    }
}
