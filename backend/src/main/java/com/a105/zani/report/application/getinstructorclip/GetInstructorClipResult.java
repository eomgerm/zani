package com.a105.zani.report.application.getinstructorclip;

import java.util.List;

/**
 * 강사 수업 클립 — 공통 녹화와 실명 화자 전사의 재생 정보(S15P11A105-308).
 *
 * <p>학생 리포트({@code GetStudentReportResult})가 담는 재생 정보와 같은 모양이다. 강사는 활동 집계·복습 추천 같은 학생 본인 값이 없어 재생 정보만 내려간다 — 강사용 리포트
 * 본문은 {@code GetInstructorReportResult} 가 따로 맡는다.
 *
 * <p>시간 단위는 전부 <b>초</b> 다. 저장소가 ms({@code started_offset_ms})여도 이 경계에서 초로 낮춘다 — 참여도 타임라인과 척도를 맞춰야 화면이 두 값을 같은 축에 놓을 수
 * 있다.
 *
 * @param recordingUrl 권한을 검증한 단기 접근 주소. 최종 MP4 가 아직 없으면 {@code null} 이며 오류가 아니다
 * @param durationSeconds 수업 길이. 종료 시각과 시작 시각의 차이다
 * @param seekTimestamp 초기 재생 위치. 딥링크 진입 위치 전용이다
 */
public record GetInstructorClipResult(
        String recordingUrl, long durationSeconds, List<TranscriptSegment> transcript, long seekTimestamp) {

    /**
     * 전사 한 줄.
     *
     * @param speakerName 표시 이름. 저장소의 화자 키({@code sessionParticipantId})를 푸는 일은 조회 어댑터가 끝낸다 — 익명 별칭을 내보내지
     *     않는다(REPORT-S-001)
     */
    public record TranscriptSegment(long startSeconds, long endSeconds, String speakerName, String text) {}
}
