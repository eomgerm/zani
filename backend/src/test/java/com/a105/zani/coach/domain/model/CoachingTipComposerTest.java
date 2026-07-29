package com.a105.zani.coach.domain.model;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §8 고정 문구 조립. 퍼센트 자리와 조사 처리를 검증한다. */
class CoachingTipComposerTest {

    private CoachingSignalSummary summary(int denominator, int numerator, Map<AttentionState, Integer> counts) {
        return new CoachingSignalSummary(denominator, numerator, counts);
    }

    @Test
    @DisplayName("헷갈림 팁은 해당 상태 비율과 핵심 개념을 넣는다")
    void composesConfusedTip() {
        CoachingSignalSummary summary = summary(10, 3, Map.of(AttentionState.CONFUSED, 3));

        CoachingTip tip = CoachingTipComposer.compose(CoachingTipType.CONFUSED_HIGH, summary, "제네릭 와일드카드");

        assertThat(tip.title()).isEqualTo("추가 설명이 필요해요");
        assertThat(tip.message()).contains("전체 학생의 30%가 현재 내용을 헷갈려 하고 있어요.");
        assertThat(tip.message()).contains("제네릭 와일드카드를 다른 예시로 다시 설명해 주세요.");
        assertThat(tip.targetConcept()).isEqualTo("제네릭 와일드카드");
    }

    @Test
    @DisplayName("복합 팁의 첫 줄은 합집합 비율이고 뒤 두 값은 상태별 비율이다 — 합이 첫 줄보다 클 수 있다")
    void composesBothHighTipWithUnionRatioFirst() {
        // 10명 중 헷갈림 3·놓침 3 인데 두 상태를 모두 겪은 학생이 1명이라 고유 학생은 5명이다.
        CoachingSignalSummary summary = summary(10, 5, Map.of(AttentionState.CONFUSED, 3, AttentionState.MISSED, 3));

        CoachingTip tip = CoachingTipComposer.compose(CoachingTipType.CONFUSED_AND_MISSED_HIGH, summary, "상한 경계");

        assertThat(tip.message()).contains("전체 학생의 50%가 현재 수업을 따라가는 데 어려움을 겪고 있어요.");
        assertThat(tip.message()).contains("헷갈려요 30% · 놓쳤어요 30%");
        assertThat(tip.message()).contains("설명 속도를 낮추고 상한 경계를 다시 정리해 주세요.");
    }

    @Test
    @DisplayName("무응답·자리비움 팁은 개념 없이 조립되고 targetConcept 이 비어 있다")
    void composesTipsWithoutConcept() {
        CoachingSignalSummary nonResponse = summary(10, 4, Map.of(AttentionState.NON_RESPONSE, 4));
        CoachingTip tip = CoachingTipComposer.compose(CoachingTipType.NON_RESPONSE_HIGH, nonResponse, null);

        assertThat(tip.message()).contains("전체 학생의 40%가 질문에 응답하지 않았어요.");
        assertThat(tip.targetConcept()).isNull();

        CoachingSignalSummary away = summary(10, 4, Map.of(AttentionState.UNMEASURABLE, 4));
        assertThat(CoachingTipComposer.compose(CoachingTipType.UNMEASURABLE_HIGH, away, null)
                        .message())
                .contains("전체 학생의 40%가 현재 화면에서 감지되지 않고 있어요.");
    }

    @Test
    @DisplayName("목적격 조사를 종성에 맞춰 붙인다 — §8 원문의 고정 '을' 을 그대로 두면 '와일드카드을' 이 된다")
    void appliesObjectParticleByFinalConsonant() {
        CoachingSignalSummary summary = summary(10, 3, Map.of(AttentionState.MISSED, 3));

        assertThat(CoachingTipComposer.compose(CoachingTipType.MISSED_HIGH, summary, "상속")
                        .message())
                .contains("상속을 짧게");
        assertThat(CoachingTipComposer.compose(CoachingTipType.MISSED_HIGH, summary, "제네릭 와일드카드")
                        .message())
                .contains("제네릭 와일드카드를 짧게");
        assertThat(CoachingTipComposer.compose(CoachingTipType.MISSED_HIGH, summary, "REST API")
                        .message())
                .contains("REST API를 짧게");
    }

    @Test
    @DisplayName("줄바꿈은 플랫폼과 무관하게 \\n 이다 — 개발 기기와 배포 서버에서 같은 문구가 나와야 한다")
    void usesPlatformIndependentNewLine() {
        CoachingSignalSummary summary = summary(10, 3, Map.of(AttentionState.CONFUSED, 3));

        String message = CoachingTipComposer.compose(CoachingTipType.CONFUSED_HIGH, summary, "제네릭")
                .message();

        assertThat(message).doesNotContain("\r").contains("있어요.\n제네릭을");
    }

    @Test
    @DisplayName("비율은 반올림해 정수 퍼센트로 넣는다")
    void roundsRatioToPercent() {
        CoachingSignalSummary summary = summary(3, 1, Map.of(AttentionState.CONFUSED, 1));

        assertThat(CoachingTipComposer.compose(CoachingTipType.CONFUSED_HIGH, summary, "제네릭")
                        .message())
                .contains("전체 학생의 33%");
    }

    @Test
    @DisplayName("개념이 필요한 유형인데 비어 있으면 거절한다")
    void rejectsMissingConcept() {
        CoachingSignalSummary summary = summary(10, 3, Map.of(AttentionState.CONFUSED, 3));

        assertThatThrownBy(() -> CoachingTipComposer.compose(CoachingTipType.CONFUSED_HIGH, summary, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("분모가 0이면 문구를 만들지 않는다")
    void rejectsZeroDenominator() {
        assertThatThrownBy(() -> CoachingTipComposer.compose(
                        CoachingTipType.NON_RESPONSE_HIGH, CoachingSignalSummary.empty(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
