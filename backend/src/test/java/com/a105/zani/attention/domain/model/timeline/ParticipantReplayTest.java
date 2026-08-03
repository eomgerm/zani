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

    /** fromSeconds 부터 count 건을 10초 간격으로 만든다. 값은 슬롯 시작 시각 기준이다. */
    private static List<ObservationRecord> events(long fromSeconds, int count, DetectorOutcome outcome) {
        List<ObservationRecord> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(ObservationRecords.at(PARTICIPANT, (fromSeconds + i * 10L) * 1000L, outcome));
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

    @Nested
    @DisplayName("슬롯 배정")
    class Slots {

        @Test
        @DisplayName("시작 시각이 구간 안에 있는 슬롯만 돌려준다 — 걸친 슬롯은 자기 시작 칸에 남는다")
        void returns_slots_by_their_start_time() {
            ParticipantReplay replay = replay(events(0, 6, DetectorOutcome.ENGAGED), List.of());

            // 0·10·20초 슬롯이 첫 칸, 30·40·50초 슬롯이 둘째 칸.
            assertThat(replay.slotsStartingIn(0L, 30_000L))
                    .extracting(ObservationSlot::startMs)
                    .containsExactly(0L, 10_000L, 20_000L);
            assertThat(replay.slotsStartingIn(30_000L, 60_000L))
                    .extracting(ObservationSlot::startMs)
                    .containsExactly(30_000L, 40_000L, 50_000L);
        }

        @Test
        @DisplayName("격자에 안 맞는 시각의 슬롯도 시작 시각 기준으로 한 칸에만 들어간다")
        void an_off_grid_slot_belongs_to_exactly_one_bucket() {
            // 25초에 시작하는 슬롯은 [25,35) 이라 두 칸에 걸치지만 첫 칸에만 배정된다.
            ParticipantReplay replay = replay(
                    List.of(new ObservationRecord(PARTICIPANT, 35_000L, 25_000L, DetectorOutcome.ENGAGED)), List.of());

            assertThat(replay.slotsStartingIn(0L, 30_000L)).hasSize(1);
            assertThat(replay.slotsStartingIn(30_000L, 60_000L)).isEmpty();
        }

        @Test
        @DisplayName("관측이 없으면 빈 목록이다")
        void no_slots_yields_an_empty_list() {
            assertThat(replay(List.of(), List.of()).slotsStartingIn(0L, 30_000L))
                    .isEmpty();
        }
    }

    /**
     * 슬롯 시작을 무엇으로 정하는지 못 박는다.
     *
     * <p>이 판정 하나가 어긋나면 연속 접속·측정 불가 지속·{@code UNMEASURABLE} 3연속·내용 구간 배정이 전부 함께 어긋난다. 시간축은 그 위의 모든 계산이 딛는 바닥이라 계약을 따로
     * 고정한다(설계 문서 §2.14).
     */
    @Nested
    @DisplayName("시간축")
    class TimeAxis {

        @Test
        @DisplayName("창 시작이 있으면 그 시각에 슬롯을 놓는다 — 관측 시각은 창의 끝이다")
        void a_recorded_window_start_becomes_the_slot_start() {
            // [0,10) 을 본 판정. 값은 10초에 정해졌다.
            ParticipantReplay replay = replay(
                    List.of(new ObservationRecord(PARTICIPANT, 10_000L, 0L, DetectorOutcome.ENGAGED)), List.of());

            assertThat(replay.slots()).extracting(ObservationSlot::startMs).containsExactly(0L);
            assertThat(replay.slots()).extracting(ObservationSlot::endMs).containsExactly(10_000L);
        }

        @Test
        @DisplayName("창 시작이 없으면 관측 시각에서 10초를 뺀다 — 계약이 창 길이를 10초로 고정한다")
        void a_missing_window_start_is_corrected_by_ten_seconds() {
            ParticipantReplay replay = replay(
                    List.of(new ObservationRecord(PARTICIPANT, 30_000L, null, DetectorOutcome.BARELY_ENGAGED)),
                    List.of());

            assertThat(replay.slots()).extracting(ObservationSlot::startMs).containsExactly(20_000L);
        }

        @Test
        @DisplayName("보정 결과가 음수면 0 으로 자른다")
        void a_correction_below_zero_is_clamped() {
            // 세션 시작 4초 뒤에 정해진 값. 창은 세션 이전까지 걸치지만 격자에 음수는 없다.
            ParticipantReplay replay = replay(
                    List.of(new ObservationRecord(PARTICIPANT, 4_000L, null, DetectorOutcome.ENGAGED)), List.of());

            assertThat(replay.slots()).extracting(ObservationSlot::startMs).containsExactly(0L);
        }

        @Test
        @DisplayName("CAMERA_OFF 는 창 없이 확정되므로 관측 시각이 곧 슬롯 시작이다")
        void an_outcome_without_a_window_keeps_its_occurred_time() {
            ParticipantReplay replay = replay(
                    List.of(
                            new ObservationRecord(PARTICIPANT, 30_000L, null, DetectorOutcome.CAMERA_OFF),
                            new ObservationRecord(PARTICIPANT, 40_000L, null, DetectorOutcome.DETECTOR_UNAVAILABLE)),
                    List.of());

            assertThat(replay.slots()).extracting(ObservationSlot::startMs).containsExactly(30_000L, 40_000L);
        }

        @Test
        @DisplayName("30초 경계에 걸친 판정은 창 시작이 든 칸에 들어간다 — 관측 시각을 쓰면 다음 칸으로 넘어간다")
        void a_judgement_on_the_bucket_boundary_lands_in_its_own_bucket() {
            // [20,30) 을 본 판정의 관측 시각은 30초다. 그 값을 슬롯 시작으로 쓰면 둘째 칸으로 밀린다.
            ParticipantReplay replay = replay(
                    List.of(new ObservationRecord(PARTICIPANT, 30_000L, 20_000L, DetectorOutcome.ENGAGED)), List.of());

            assertThat(replay.slotsStartingIn(0L, 30_000L)).hasSize(1);
            assertThat(replay.slotsStartingIn(30_000L, 60_000L)).isEmpty();
        }

        @Test
        @DisplayName("보정 뒤 순서가 뒤집혀도 슬롯 시작 기준으로 다시 정렬한다")
        void slots_are_sorted_by_their_corrected_start() {
            // 쿼리가 주는 순서는 관측 시각 오름차순이라 CAMERA_OFF(15초) 가 먼저다. 그런데 슬롯 시작은
            // 10초 · 15초 순이라 보정하면 순서가 뒤집힌다. 다시 정렬하지 않으면 슬롯 끝을 다음 슬롯 시작으로
            // 자르는 계산이 음수 길이를 만든다.
            ParticipantReplay replay = replay(
                    List.of(
                            new ObservationRecord(PARTICIPANT, 15_000L, null, DetectorOutcome.CAMERA_OFF),
                            new ObservationRecord(PARTICIPANT, 20_000L, null, DetectorOutcome.ENGAGED)),
                    List.of());

            assertThat(replay.slots()).extracting(ObservationSlot::startMs).containsExactly(10_000L, 15_000L);
            assertThat(replay.slots())
                    .allSatisfy(slot -> assertThat(slot.endMs()).isGreaterThan(slot.startMs()));
        }

        @Test
        @DisplayName("판정 하나가 밀리면 연속 접속 1분도 함께 밀린다")
        void the_connection_timer_reads_the_same_axis() {
            // [0,10) 부터 이어진 접속이므로 60초에 1분을 채운다. 창 시작을 무시하면 70초까지 밀린다.
            ParticipantReplay replay = replay(events(0, 12, DetectorOutcome.ENGAGED), List.of());

            assertThat(replay.counted(60_000L)).isTrue();
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
