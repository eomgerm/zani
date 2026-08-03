package com.a105.zani.report.domain.model;

import java.util.List;
import java.util.stream.IntStream;

import com.a105.zani.report.domain.exception.InvalidStudentReportException;
import com.a105.zani.report.domain.exception.StudentReportErrorCode;

/**
 * 학생 한 명의 개인 리포트(UK_STUDENT_REPORTS_SESSION_PARTICIPANT — 세션·참여자당 한 행).
 *
 * <p>추천이 0개인 리포트도 유효하다. 관측이 없거나 근거 검증에서 전부 걸러진 학생도 참여도 요약과 퀴즈는 받는다(FRD §19).
 *
 * <p>공개 시각은 이 애그리거트가 다루지 않는다 — 저장과 공개는 분리돼 있고, 공개는 파이프라인의 {@code VALIDATING -> PUBLISHED} 단계가 일괄로 한다.
 */
public final class StudentReport {

    /** 추천 상한(FRD §19). 넘는 만큼 자르는 것은 호출부의 근거 검증이 하고, 여기서는 거절한다. */
    public static final int MAX_RECOMMENDATIONS = 5;

    private static final int SUMMARY_MAX_LENGTH = 2_000;

    private final Long sessionId;
    private final Long sessionParticipantId;
    private final String participationSummary;
    private final List<ReviewRecommendation> recommendations;

    private StudentReport(
            Long sessionId,
            Long sessionParticipantId,
            String participationSummary,
            List<ReviewRecommendation> recommendations) {
        this.sessionId = sessionId;
        this.sessionParticipantId = sessionParticipantId;
        this.participationSummary = participationSummary;
        this.recommendations = recommendations;
    }

    public static StudentReport create(
            Long sessionId,
            Long sessionParticipantId,
            String participationSummary,
            List<ReviewRecommendation> recommendations) {
        String summary = participationSummary == null ? null : participationSummary.strip();
        List<ReviewRecommendation> given = recommendations == null ? List.of() : recommendations;
        if (sessionId == null
                || sessionParticipantId == null
                || summary == null
                || summary.isEmpty()
                || summary.length() > SUMMARY_MAX_LENGTH
                || given.size() > MAX_RECOMMENDATIONS) {
            throw new InvalidStudentReportException(StudentReportErrorCode.INVALID_STUDENT_REPORT);
        }
        List<ReviewRecommendation> prioritized = IntStream.range(0, given.size())
                .mapToObj(index -> given.get(index).withPriority(index + 1))
                .toList();
        return new StudentReport(sessionId, sessionParticipantId, summary, prioritized);
    }

    public Long sessionId() {
        return sessionId;
    }

    public Long sessionParticipantId() {
        return sessionParticipantId;
    }

    public String participationSummary() {
        return participationSummary;
    }

    public List<ReviewRecommendation> recommendations() {
        return recommendations;
    }
}
