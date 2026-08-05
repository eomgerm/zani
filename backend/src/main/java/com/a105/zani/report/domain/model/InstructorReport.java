package com.a105.zani.report.domain.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.a105.zani.report.domain.exception.InstructorReportErrorCode;
import com.a105.zani.report.domain.exception.InvalidInstructorReportException;

/**
 * 강사 리포트 하나(UK_INSTRUCTOR_REPORTS_SESSION — 세션당 한 행).
 *
 * <p>인사이트가 0개인 리포트도 유효하다. 근거를 두 종류 이상 결합하지 못한 수업은 인사이트가 없고, 그때도 종합 피드백과 분야별 평가는 나온다(AI-008).
 *
 * <p>수업 전체를 요약하는 평균·총점 필드를 두지 않는다(FRD §17.7, REPORT-I-008 — 단일 품질 점수 금지). 네 분야 점수만 담는다.
 *
 * <p>공개 시각은 이 애그리거트가 다루지 않는다 — 저장과 공개는 분리돼 있고, 공개는 파이프라인의 {@code VALIDATING -> PUBLISHED} 단계가 일괄로 한다.
 */
public final class InstructorReport {

    /** 인사이트 상한. 화면이 카드 4장을 그린다. 넘는 만큼 자르는 것은 호출부의 근거 검증이 하고, 여기서는 거절한다. */
    public static final int MAX_INSIGHTS = 4;

    private static final int FEEDBACK_MAX_LENGTH = 2_000;
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;

    private final Long sessionId;
    private final String overallFeedback;
    private final int questionCount;
    private final Map<EvaluationType, Integer> scores;
    private final List<ClassInsight> insights;

    private InstructorReport(
            Long sessionId,
            String overallFeedback,
            int questionCount,
            Map<EvaluationType, Integer> scores,
            List<ClassInsight> insights) {
        this.sessionId = sessionId;
        this.overallFeedback = overallFeedback;
        this.questionCount = questionCount;
        this.scores = scores;
        this.insights = insights;
    }

    /**
     * @param questionCount 수업 전체에서 학생들이 남긴 질문 수. 채팅 행 수가 아니라 질문인 발화만 센 값이고, 그 판단은 호출부가 한다
     * @param scores {@link EvaluationType} 4종이 전부 있어야 한다. 부분 집합은 거절한다 — 화면이 도넛 4개를 그리고
     *     {@code UK_INSTRUCTOR_REPORT_SCORES_REPORT_TYPE} 가 유형별 한 행을 강제한다
     */
    public static InstructorReport create(
            Long sessionId,
            String overallFeedback,
            int questionCount,
            Map<EvaluationType, Integer> scores,
            List<ClassInsight> insights) {
        String feedback = overallFeedback == null ? null : overallFeedback.strip();
        Map<EvaluationType, Integer> givenScores =
                scores == null ? new EnumMap<>(EvaluationType.class) : new EnumMap<>(scores);
        List<ClassInsight> givenInsights = insights == null ? List.of() : insights;
        if (sessionId == null
                || feedback == null
                || feedback.isEmpty()
                || feedback.length() > FEEDBACK_MAX_LENGTH
                || questionCount < 0
                || givenInsights.size() > MAX_INSIGHTS
                || givenScores.size() != EvaluationType.values().length
                || givenScores.values().stream().anyMatch(InstructorReport::outOfRange)) {
            throw new InvalidInstructorReportException(InstructorReportErrorCode.INVALID_INSTRUCTOR_REPORT);
        }
        return new InstructorReport(
                sessionId, feedback, questionCount, Map.copyOf(givenScores), List.copyOf(givenInsights));
    }

    private static boolean outOfRange(Integer score) {
        return score == null || score < MIN_SCORE || score > MAX_SCORE;
    }

    public Long sessionId() {
        return sessionId;
    }

    public String overallFeedback() {
        return overallFeedback;
    }

    public int questionCount() {
        return questionCount;
    }

    public Map<EvaluationType, Integer> scores() {
        return scores;
    }

    public List<ClassInsight> insights() {
        return insights;
    }
}
