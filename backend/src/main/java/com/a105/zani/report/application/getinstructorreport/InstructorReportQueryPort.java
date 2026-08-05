package com.a105.zani.report.application.getinstructorreport;

import java.util.Optional;

/** 저장된 강사 리포트를 읽는다. 벤더(JPA) 타입은 인프라 어댑터 안에만 존재한다. */
public interface InstructorReportQueryPort {

    /**
     * 이 세션의 공통 리포트가 게시됐는지. 강사 리포트를 화면에 내보낼지 정하는 <b>유일한</b> 공개 게이트다.
     *
     * <p>강사 리포트 자신의 {@code published_at} 을 보지 않는 이유: 사후 파이프라인의 공개 단계가 {@code session_reports} 에만 시각을 찍고 개별 리포트 테이블에는 찍지
     * 않는다(S15P11A105-304). 그 컬럼을 게이트로 쓰면 파이프라인이 완주해도 강사 리포트가 영구히 404 다.
     *
     * <p>같은 값을 수업 클립({@code InstructorClipQueryPort#sessionReportPublished})과 수업 요약이 본다. 한 화면의 탭들이 서로 다른 게이트를 보면, 클립은
     * 열리는데 리포트는 404 인 상태가 정상 동작으로 존재하게 된다.
     */
    boolean sessionReportPublished(long sessionId);

    /** 세션의 강사 리포트. AI 가 아직 만들지 않았으면 비어 있다. */
    Optional<InstructorReportView> findBySessionId(long sessionId);

    /**
     * "한눈에 보기" 중 조회 시점에 세는 값.
     *
     * <p>리포트 본문과 나눠 부르는 이유: 본문은 AI 가 만들어 저장한 값이고 이쪽은 조회 시점에 세는 값이라 성격이 다르다. 한 조회로 묶으면 리포트가 아직 없을 때 집계까지 못 내려간다.
     */
    InstructorReportCounts counts(long sessionId);
}
