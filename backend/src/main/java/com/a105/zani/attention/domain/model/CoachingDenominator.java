package com.a105.zani.attention.domain.model;

import java.time.Duration;
import java.util.Set;

/**
 * 강사 집단 트리거에서 <b>세는 학생</b>의 집합(확정 문서 §7).
 *
 * <p>연속 접속 1분을 넘긴 학생 중 측정 불가가 1분 이상 이어진 학생을 뺀 결과다. 비율의 분모이므로 비어 있으면 트리거를 판단할 수 없다 — 0으로 나누는 대신 "아직 판단하지 않는다"로 다룬다.
 *
 * @param participantIds 세는 학생의 세션 참가자 ID
 */
public record CoachingDenominator(Set<Long> participantIds) {

    /** 집계 분모에 들어오기까지 필요한 연속 접속 시간(§7). */
    public static final Duration REQUIRED_CONNECTION = Duration.ofMinutes(1);

    public CoachingDenominator {
        participantIds = Set.copyOf(participantIds);
    }

    public static CoachingDenominator empty() {
        return new CoachingDenominator(Set.of());
    }

    public int size() {
        return participantIds.size();
    }

    /** 분모가 0이면 비율을 계산할 수 없다. 트리거는 판단을 미룬다(§7). */
    public boolean isEmpty() {
        return participantIds.isEmpty();
    }

    public boolean counts(long participantId) {
        return participantIds.contains(participantId);
    }
}
