package com.a105.zani.report.application.getinstructorreport;

import java.util.List;

/**
 * 저장된 강사 리포트 한 건. 조회 포트가 돌려주는 값이며 엔티티는 이 경계를 넘지 않는다.
 *
 * <p><b>개선 팁은 더 이상 따로 오지 않는다.</b> 250 이 {@code instructor_report_tips} 를 지우고 제안을 인사이트 안으로 접었다(V20). 화면이 "수업 개선 TIP" 카드와
 * "인사이트" 카드를 "수업 인사이트" 하나로 합쳤고, 구간 시각은 인사이트에만 있어 그쪽으로 접는 편이 붙일 컬럼이 적었다.
 *
 * <p><b>공개 여부를 담지 않는다.</b> 공개 게이트는 공통 리포트의 게시이며 {@code InstructorReportQueryPort#sessionReportPublished} 가 따로 답한다 — 이
 * 값에 {@code instructor_reports.published_at} 을 실어 두면 아무도 채우지 않는 컬럼으로 화면을 막게 된다.
 *
 * @param questionCount 모델이 판단한 질문 수. 분석이 값을 내지 못했으면 {@code null} 이며 0 이 아니다
 */
public record InstructorReportView(
        String overallFeedback, Integer questionCount, List<ScoreRecord> scores, List<InsightRecord> insights) {

    /**
     * 분야별 평가.
     *
     * @param evaluationType {@code DELIVERY} · {@code STRUCTURE_FLOW} · {@code INTERACTION} ·
     *     {@code DIFFICULTY_CONTROL}
     * @param score 0~100
     */
    public record ScoreRecord(String evaluationType, int score) {}

    /**
     * 수업 인사이트 한 장 — 제목·근거·제안·구간.
     *
     * <p><b>유형이 없다.</b> 250 이 {@code insight_type} 을 지웠다(V20). AI 가 제목을 직접 짓고 화면 아이콘도 하나라, 유형으로 갈라야 할 표시가 없다.
     *
     * @param content 그렇게 판단한 근거. 화면의 "관찰" 자리다
     * @param suggestion AI 가 제시한 개선 제안. 화면의 "TIP" 자리이며 없을 수 있다
     * @param startedOffsetMs 대상 구간 시작(ms). 수업 전체를 가리키면 {@code null}
     * @param endedOffsetMs 대상 구간 종료(ms). 수업 전체를 가리키면 {@code null}
     */
    public record InsightRecord(
            String title, String content, String suggestion, Long startedOffsetMs, Long endedOffsetMs) {}
}
