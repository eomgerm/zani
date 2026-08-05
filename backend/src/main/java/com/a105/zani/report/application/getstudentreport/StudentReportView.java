package com.a105.zani.report.application.getstudentreport;

import java.util.List;

/**
 * 조회 어댑터가 조립해 주는 읽기 모델.
 *
 * @param questionCount 모델이 판단한 질문 수. 분석이 값을 내지 못했으면 {@code null} 이며 0 이 아니다. 세 집계와 달리 서버가 세는 값이 아니라 저장된 판정이다
 * @param transcript 실명 화자 전사. 세션 단위 값이라 학생마다 다르지 않다. 전사가 아직 없으면 빈 목록이며 오류가 아니다
 */
public record StudentReportView(
        long publicChatCount,
        long confusedCount,
        long missedCount,
        Integer questionCount,
        String participationSummary,
        List<Recommendation> recommendations,
        List<TranscriptSegment> transcript) {

    /** @param id TSID 라 JS 안전 정수 범위를 넘는다. 바깥 경계에서 문자열로 내보내는 것을 전제로 여기서는 원값을 들고 있는다 */
    public record Recommendation(
            long id,
            String recommendationType,
            String title,
            String description,
            long startSeconds,
            long endSeconds,
            int priority) {}

    /**
     * 전사 한 줄.
     *
     * @param speakerName 표시 이름. 저장소의 화자 키({@code sessionParticipantId})를 푸는 일은 조회 어댑터가 끝낸다 — 익명 별칭을 내보내지
     *     않는다(REPORT-S-001)
     */
    public record TranscriptSegment(long startSeconds, long endSeconds, String speakerName, String text) {}
}
