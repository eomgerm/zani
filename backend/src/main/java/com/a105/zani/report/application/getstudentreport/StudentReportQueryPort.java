package com.a105.zani.report.application.getstudentreport;

import java.util.Optional;

public interface StudentReportQueryPort {

    /**
     * 이 세션의 공통 리포트가 게시됐는지. 개인 리포트를 화면에 내보낼지 정하는 <b>유일한</b> 공개 게이트다.
     *
     * <p>개인 리포트 자신의 {@code published_at} 을 보지 않는 이유: 사후 파이프라인의 공개 단계가 {@code session_reports} 에만 시각을 찍고 개별 리포트 테이블에는 찍지
     * 않는다(S15P11A105-304). 그 컬럼을 게이트로 쓰면 분석이 완주해도 학생 리포트가 영구히 404 다 — 강사 리포트가 같은 이유로 막혀 있었다(S15P11A105-310).
     *
     * <p>같은 값을 강사 리포트·수업 클립·수업 요약이 본다. 공개를 판정하는 값은 하나여야 한다.
     */
    boolean sessionReportPublished(long sessionId);

    Optional<StudentReportView> findBySessionIdAndParticipantId(long sessionId, long participantId);
}
