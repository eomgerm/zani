package com.a105.zani.report.domain.model;

import com.a105.zani.report.domain.exception.InvalidStudentReportException;
import com.a105.zani.report.domain.exception.StudentReportErrorCode;

/**
 * 학생 한 명에게 붙는 복습 구간 추천 하나.
 *
 * <p>시각은 모델이 답한 숫자가 아니라 개념 설명 구간의 시작·종료다(FRD §17.6). 이 객체는 그 값이 재생 위치로 쓸 수 있는 형태인지만 본다 — 길이가 없거나 뒤집힌 구간은 링크를 눌러도 갈 곳이
 * 없다.
 *
 * <p>우선순위는 목록 안의 순서라 이 객체가 정하지 않는다. {@link StudentReport} 가 매긴다.
 */
public record ReviewRecommendation(
        RecommendationType type,
        String title,
        String description,
        long startedOffsetMs,
        long endedOffsetMs,
        int priority) {

    /** 우선순위가 아직 매겨지지 않은 상태. {@link StudentReport#create} 만 1부터 채운다. */
    static final int UNSET_PRIORITY = 0;

    private static final int TITLE_MAX_LENGTH = 200;

    public ReviewRecommendation {
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
        if (type == null
                || title == null
                || title.isEmpty()
                || title.length() > TITLE_MAX_LENGTH
                || description == null
                || description.isEmpty()
                || startedOffsetMs < 0
                || endedOffsetMs <= startedOffsetMs
                || priority < UNSET_PRIORITY) {
            throw new InvalidStudentReportException(StudentReportErrorCode.INVALID_REVIEW_RECOMMENDATION);
        }
    }

    public static ReviewRecommendation of(
            RecommendationType type, String title, String description, long startedOffsetMs, long endedOffsetMs) {
        return new ReviewRecommendation(type, title, description, startedOffsetMs, endedOffsetMs, UNSET_PRIORITY);
    }

    ReviewRecommendation withPriority(int priority) {
        return new ReviewRecommendation(type, title, description, startedOffsetMs, endedOffsetMs, priority);
    }
}
