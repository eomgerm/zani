package com.a105.zani.report.application.getstudentreport;

import java.util.List;

/**
 * 학생 본인 리포트. 활동 집계·참여 요약·복습 추천에 복습 클립이 쓰는 재생 정보를 함께 담는다(S15P11A105-302).
 *
 * <p>시간 단위는 전부 <b>초</b> 다. 저장소가 ms({@code started_offset_ms})여도 이 경계에서 초로 낮춘다 — 참여도 타임라인과 척도를 맞춰야 화면이 두 값을 같은 축에 놓을 수
 * 있다.
 *
 * @param recordingUrl 권한을 검증한 단기 접근 주소. 최종 MP4 가 아직 없으면 {@code null} 이며 오류가 아니다
 * @param durationSeconds 수업 길이. 종료 시각과 시작 시각의 차이다
 * @param seekTimestamp 초기 재생 위치. 추천 각각의 이동 목표는 자기 {@code startSeconds} 가 맡으므로 이 값은 딥링크 진입 위치 전용이다
 */
public record GetStudentReportResult(
        Activity activity,
        String participationSummary,
        List<Recommendation> recommendations,
        String recordingUrl,
        long durationSeconds,
        List<TranscriptSegment> transcript,
        long seekTimestamp) {

    /**
     * 본인 활동 집계.
     *
     * @param questionCount 모델이 판단한 질문 수. 값이 없으면 {@code null} 이다 — 0 으로 낮추지 않는다
     */
    public record Activity(long publicChatCount, long confusedCount, long missedCount, Integer questionCount) {}

    public record Recommendation(
            long id,
            String recommendationType,
            String title,
            String description,
            long startSeconds,
            long endSeconds,
            int priority) {}

    public record TranscriptSegment(long startSeconds, long endSeconds, String speakerName, String text) {}
}
