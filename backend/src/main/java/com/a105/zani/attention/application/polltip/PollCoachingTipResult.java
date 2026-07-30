package com.a105.zani.attention.application.polltip;

import java.util.Optional;

import com.a105.zani.attention.application.port.CoachingOutcome;

/**
 * 폴링 결과.
 *
 * <p>대기 중인 트리거가 없으면 비어 있다. 이때도 오류가 아니라 정상 응답이다 — 학생들이 잘 따라오고 있다는 뜻일 수도, 쿨타임이 끝나고 아직 다음 트리거가 없다는 뜻일 수도 있다.
 */
public record PollCoachingTipResult(Optional<CoachingOutcome> outcome) {

    public static PollCoachingTipResult of(CoachingOutcome outcome) {
        return new PollCoachingTipResult(Optional.of(outcome));
    }

    public static PollCoachingTipResult none() {
        return new PollCoachingTipResult(Optional.empty());
    }
}
