package com.a105.zani.report.application.getinstructorclip;

import java.util.List;

public interface InstructorClipQueryPort {

    /**
     * 공통 리포트({@code session_reports})가 게시됐는지. 수업 클립의 공개 여부는 이 게시가 정한다 — 수업 요약({@code GetSessionSummaryService})과 같은
     * 게이트다.
     */
    boolean sessionReportPublished(long sessionId);

    /** 시작 시각 오름차순의 실명 화자 전사. 전사가 아직 없으면 빈 목록이며 오류가 아니다. */
    List<GetInstructorClipResult.TranscriptSegment> transcript(long sessionId);
}
