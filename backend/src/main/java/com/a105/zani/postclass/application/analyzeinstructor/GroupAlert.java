package com.a105.zani.postclass.application.analyzeinstructor;

/**
 * 익명 집단 알림 하나와 그 시점의 응답 분포.
 *
 * <p>학생 식별자가 없다. 분모·분자와 응답 분포가 모두 인원 수다(REPORT-I-002).
 *
 * @param numeratorCount 확인이 필요한 상태였던 고유 학생 수
 * @param denominatorCount 그 시점의 집계 대상 학생 수. 0 이면 비율을 계산할 수 없다
 */
public record GroupAlert(
        long occurredOffsetMs,
        String alertType,
        int numeratorCount,
        int denominatorCount,
        int okCount,
        int confusedCount,
        int missedCount,
        int noResponseCount) {}
