package com.a105.zani.report.domain.model;

import java.util.Arrays;

import com.a105.zani.report.domain.exception.InstructorReportErrorCode;
import com.a105.zani.report.domain.exception.InvalidInstructorReportException;

/**
 * 수업 품질 유형별 평가 분야. V1 의 {@code instructor_report_scores.evaluation_type} 컬럼 주석이 정본이다.
 *
 * <p>강사 개인 역량 평가가 아니라 수업 품질 평가다(FRD §17.4). 네 값을 합쳐 하나의 점수·등급으로 축약하지 않는다(FRD §17.7, REPORT-I-008) — 그래서 이 enum 에도, 이 값을
 * 담는 애그리거트에도 평균이나 총점을 계산하는 메서드가 없다.
 *
 * <p>화면의 전달력 · 구성·흐름 · 상호작용 · 난이도 조절에 순서대로 대응한다.
 */
public enum EvaluationType {
    DELIVERY,
    STRUCTURE_FLOW,
    INTERACTION,
    DIFFICULTY_CONTROL;

    /** 다른 도메인이 이 enum 을 import 하지 않고 문자열로 넘길 수 있게 한다. 미정의 값은 이 도메인이 거절한다. */
    public static EvaluationType from(String value) {
        return Arrays.stream(values())
                .filter(type -> type.name().equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new InvalidInstructorReportException(InstructorReportErrorCode.INVALID_EVALUATION_TYPE));
    }
}
