package com.a105.zani.report.application.getinstructorreport;

import java.time.Instant;
import java.util.List;

/**
 * 저장된 강사 리포트 한 건. 조회 포트가 돌려주는 값이며 엔티티는 이 경계를 넘지 않는다.
 *
 * @param publishedAt 공개 완료 시각. {@code null} 이면 AI 가 아직 만드는 중이라 화면에 내보내지 않는다
 */
public record InstructorReportView(
        String overallFeedback,
        Instant publishedAt,
        List<ScoreRecord> scores,
        List<InsightRecord> insights,
        List<TipRecord> tips) {

    /**
     * 분야별 평가.
     *
     * @param evaluationType {@code DELIVERY} · {@code STRUCTURE_FLOW} · {@code INTERACTION} ·
     *     {@code DIFFICULTY_CONTROL}
     * @param score 0~100
     */
    public record ScoreRecord(String evaluationType, int score) {}

    /**
     * 수업 인사이트.
     *
     * @param startedOffsetMs 대상 구간 시작(ms). 수업 전체를 가리키면 {@code null}
     * @param endedOffsetMs 대상 구간 종료(ms). 수업 전체를 가리키면 {@code null}
     */
    public record InsightRecord(String insightType, String content, Long startedOffsetMs, Long endedOffsetMs) {}

    /** 개선 팁. */
    public record TipRecord(String tipType, String title, String content) {}
}
