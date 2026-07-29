package com.a105.zani.attention.application.getcoachingsignals;

import com.a105.zani.attention.domain.model.CoachingSignalSummary;

/**
 * 최근 5분 익명 집계 결과.
 *
 * @param summary 유의 학생 비율과 상태별 분포. 학생 식별자를 담지 않는다
 */
public record GetCoachingSignalsResult(CoachingSignalSummary summary) {}
