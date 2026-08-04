package com.a105.zani.report.domain.model;

import com.a105.zani.report.domain.exception.InvalidStudentReportException;
import com.a105.zani.report.domain.exception.StudentReportErrorCode;

/**
 * 복습 추천이 붙은 근거(FRD §17.5·§19.3). 값은 {@code review_recommendations.recommendation_type} 에 그대로 들어가고 학생 화면의 배지가 된다.
 *
 * <p>다섯 가지가 관측 하나에 하나씩 대응한다. 해석이 아니라 관측을 이름으로 쓴다 — 학생이 "왜 이 구간을 추천받았는지" 를 자기 기록으로 되짚을 수 있어야 한다(REPORT-S-003).
 */
public enum RecommendationType {
    /** 확인 프롬프트에 "헷갈려요" 로 응답한 구간. */
    CONFUSED,
    /** 확인 프롬프트에 "놓쳤어요" 로 응답한 구간. */
    MISSED,
    /** 확인 프롬프트에 응답하지 않은 구간. 학생 상태 6종의 {@code NON_RESPONSE} 에 대응한다. */
    NO_RESPONSE,
    /** 참여도 판정이 낮게 이어진 구간. */
    LOW_ENGAGEMENT,
    /** 학생이 질문을 남긴 구간. */
    QUESTION;

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
