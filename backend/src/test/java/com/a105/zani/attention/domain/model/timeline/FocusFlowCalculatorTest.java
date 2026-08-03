package com.a105.zani.attention.domain.model.timeline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.PromptAnswer;

import static org.assertj.core.api.Assertions.assertThat;

class FocusFlowCalculatorTest {

    private static final long PARTICIPANT = 1L;
    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    /** 10초 간격 관측을 순서대로 만든다. 배열 순서가 곧 시간 순서다. */
    private static ParticipantReplay replayOf(DetectorOutcome... outcomes) {
        return replayOf(List.of(), outcomes);
    }

    private static ParticipantReplay replayOf(List<PromptRecord> prompts, DetectorOutcome... outcomes) {
        List<ObservationRecord> records = new ArrayList<>();
        for (int i = 0; i < outcomes.length; i++) {
            records.add(ObservationRecords.at(PARTICIPANT, i * 10_000L, outcomes[i]));
        }
        return ParticipantReplay.of(PARTICIPANT, records, prompts, POLICY);
    }

    private static Double levelAt(List<FocusBucket> buckets, long offsetSeconds) {
        return buckets.stream()
                .filter(b -> b.offsetSeconds() == offsetSeconds)
                .findFirst()
                .orElseThrow()
                .focusLevel();
    }

    @Test
    @DisplayName("30초 칸마다 값 하나를 만든다 — 겹치지 않는다")
    void emits_one_value_per_non_overlapping_bucket() {
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED),
                60_000L,
                POLICY);

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).offsetSeconds()).isZero();
        assertThat(buckets.get(1).offsetSeconds()).isEqualTo(30L);
    }

    @Test
    @DisplayName("3·4·4 단계는 3.67 이다")
    void averages_the_engagement_levels() {
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(
                        DetectorOutcome.ENGAGED, // 3
                        DetectorOutcome.HIGHLY_ENGAGED, // 4
                        DetectorOutcome.HIGHLY_ENGAGED), // 4
                30_000L,
                POLICY);

        assertThat(levelAt(buckets, 0L)).isEqualTo(3.67d);
    }

    @Test
    @DisplayName("1·2 단계도 평균에 들어간다 — 참여 상태 판정과 다른 계산이다")
    void low_levels_are_included() {
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(
                        DetectorOutcome.NOT_ENGAGED, // 1
                        DetectorOutcome.BARELY_ENGAGED, // 2
                        DetectorOutcome.ENGAGED), // 3
                30_000L,
                POLICY);

        assertThat(levelAt(buckets, 0L)).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("CAMERA_OFF 는 평균에 들어가지 않는다 — 1단계로 바꾸지 않는다")
    void camera_off_is_excluded_from_the_average() {
        // 4단계 하나에 CAMERA_OFF 둘. 평균 대상은 1건뿐이라 게이트에 걸려 null 이다.
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(DetectorOutcome.HIGHLY_ENGAGED, DetectorOutcome.CAMERA_OFF, DetectorOutcome.CAMERA_OFF),
                30_000L,
                POLICY);

        // 1단계(=1.0)도 아니고 4.0 도 아니다. 값이 없다.
        assertThat(levelAt(buckets, 0L)).isNull();
    }

    @Test
    @DisplayName("판정 2건은 20초라 70% 게이트에 걸려 빈 값이다")
    void two_observations_fail_the_coverage_gate() {
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(DetectorOutcome.HIGHLY_ENGAGED, DetectorOutcome.HIGHLY_ENGAGED), 30_000L, POLICY);

        assertThat(levelAt(buckets, 0L)).isNull();
    }

    @Test
    @DisplayName("판정 3건은 30초라 게이트를 넘는다 — 경계는 21초다")
    void three_observations_pass_the_coverage_gate() {
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(
                        DetectorOutcome.HIGHLY_ENGAGED, DetectorOutcome.HIGHLY_ENGAGED, DetectorOutcome.HIGHLY_ENGAGED),
                30_000L,
                POLICY);

        assertThat(levelAt(buckets, 0L)).isEqualTo(4.0d);
    }

    @Test
    @DisplayName("미확정 UNMEASURABLE 이 섞이면 남은 2건으로는 게이트를 못 넘는다")
    void unconfirmed_unmeasurable_does_not_rescue_the_gate() {
        // 설계 문서 §2.9 가 §2.6 을 대체했음을 고정하는 테스트다.
        // 옛 규칙("참여 상태가 UNMEASURABLE 이 아닌 시간")이었다면 30초 통과에 4.0 이 나왔을 것이다.
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(DetectorOutcome.HIGHLY_ENGAGED, DetectorOutcome.HIGHLY_ENGAGED, DetectorOutcome.UNMEASURABLE),
                30_000L,
                POLICY);

        assertThat(levelAt(buckets, 0L)).isNull();
    }

    @Test
    @DisplayName("프롬프트에 CONFUSED 로 답해도 집중 흐름 값은 바뀌지 않는다")
    void prompt_answers_do_not_change_the_focus_level() {
        // 설계 문서 §2.11. 직관과 어긋나는 동작이라 테스트로 못 박는다.
        List<FocusBucket> withoutPrompt = FocusFlowCalculator.calculate(
                replayOf(
                        DetectorOutcome.HIGHLY_ENGAGED, DetectorOutcome.HIGHLY_ENGAGED, DetectorOutcome.HIGHLY_ENGAGED),
                30_000L,
                POLICY);

        List<FocusBucket> withPrompt = FocusFlowCalculator.calculate(
                replayOf(
                        List.of(new PromptRecord(PARTICIPANT, 10_000L, PromptAnswer.CONFUSED)),
                        DetectorOutcome.HIGHLY_ENGAGED,
                        DetectorOutcome.HIGHLY_ENGAGED,
                        DetectorOutcome.HIGHLY_ENGAGED),
                30_000L,
                POLICY);

        assertThat(levelAt(withPrompt, 0L))
                .isEqualTo(levelAt(withoutPrompt, 0L))
                .isEqualTo(4.0d);
    }

    @Test
    @DisplayName("관측이 없는 칸은 빈 값이다")
    void a_bucket_without_observations_is_null() {
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(DetectorOutcome.ENGAGED, DetectorOutcome.ENGAGED, DetectorOutcome.ENGAGED), 90_000L, POLICY);

        assertThat(levelAt(buckets, 0L)).isEqualTo(3.0d);
        assertThat(levelAt(buckets, 30L)).isNull();
        assertThat(levelAt(buckets, 60L)).isNull();
    }

    @Test
    @DisplayName("마지막 자투리 칸도 30초 기준으로 재므로 빈 값이다")
    void a_short_trailing_bucket_fails_the_gate() {
        // 세션 길이 50초. 마지막 칸 [30,50) 은 20초뿐이고 판정 2건이라 게이트를 못 넘는다.
        List<FocusBucket> buckets = FocusFlowCalculator.calculate(
                replayOf(
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED,
                        DetectorOutcome.ENGAGED),
                50_000L,
                POLICY);

        assertThat(buckets).hasSize(2);
        assertThat(levelAt(buckets, 0L)).isEqualTo(3.0d);
        assertThat(levelAt(buckets, 30L)).isNull();
    }

    @Test
    @DisplayName("관측이 한 건도 없으면 빈 목록이다")
    void no_observations_yields_an_empty_list() {
        ParticipantReplay empty = ParticipantReplay.of(PARTICIPANT, List.of(), List.of(), POLICY);

        assertThat(FocusFlowCalculator.calculate(empty, 0L, POLICY)).isEmpty();
    }
}
