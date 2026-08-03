package com.a105.zani.report.domain.model;

import com.a105.zani.report.domain.exception.InvalidStudentReportException;
import com.a105.zani.report.domain.exception.StudentReportErrorCode;

/** 복습 추천이 붙은 이유(FRD §17.5). 값은 {@code review_recommendations.recommendation_type} 에 그대로 들어간다. */
public enum RecommendationType {
    CONFUSED,
    MISSED,
    QUESTION,
    REPEAT;

    /** 저장 요청의 문자열을 유형으로 되돌린다. 미정의 값은 저장 대상이 아니다 — 호출부가 이미 걸러야 한다. */
    public static RecommendationType from(String value) {
        for (RecommendationType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new InvalidStudentReportException(StudentReportErrorCode.INVALID_RECOMMENDATION_TYPE);
    }
}
