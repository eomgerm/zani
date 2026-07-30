package com.a105.zani.attention.application.port;

import java.time.Instant;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;

/**
 * 팁 생성 파이프라인에 넘기는 트리거 스냅샷.
 *
 * <p>비율을 상태별 필드 <b>4개</b>로 펼쳐 담는다. {@code Map} 으로 넘기면 키가 빠져도 조용히 0으로 읽혀, 집계가 깨진 것과 아무도 그 상태가 아닌 것을 구분할 수 없다.
 *
 * <p>분모 학생 수를 함께 넘긴다. 비율만으로는 5명 중 3명과 100명 중 30명을 구분할 수 없고, 표본 크기는 리포트(티켓 205)에 남아야 한다.
 *
 * <p>학생 식별자·개별 응답·개별 판정·얼굴/시선/자세 값은 담지 않는다. 이 값은 LLM 입력으로 흘러간다.
 *
 * @param triggeredAt 트리거 시각. 내부는 절대 시각으로 다루고 LLM 프롬프트에 넣을 때 수업 시작 기준 상대 시각으로 바꾼다
 * @param studentsCounted 분모 학생 수(§7.1)
 * @param significantRatio 유의 학생 비율. 중복 제거된 고유 학생 기준이라 상태별 비율의 합과 다를 수 있다
 * @param previousTip 직전 팁. 없으면 {@code null}
 */
public record CoachingTipRequest(
        long sessionId,
        String triggerId,
        Instant triggeredAt,
        int studentsCounted,
        double significantRatio,
        double confusedRatio,
        double missedRatio,
        double nonResponseRatio,
        double unmeasurableRatio,
        PreviousCoachingTip previousTip) {

    public static CoachingTipRequest of(
            long sessionId,
            String triggerId,
            Instant triggeredAt,
            CoachingSignalSummary summary,
            PreviousCoachingTip previousTip) {
        return new CoachingTipRequest(
                sessionId,
                triggerId,
                triggeredAt,
                summary.denominator(),
                summary.ratio().orElse(0),
                summary.ratioOf(AttentionState.CONFUSED).orElse(0),
                summary.ratioOf(AttentionState.MISSED).orElse(0),
                summary.ratioOf(AttentionState.NON_RESPONSE).orElse(0),
                summary.ratioOf(AttentionState.UNMEASURABLE).orElse(0),
                previousTip);
    }
}
