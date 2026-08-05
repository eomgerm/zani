package com.a105.zani.report.application.getinstructorreport;

import java.util.Optional;

/** 저장된 강사 리포트를 읽는다. 벤더(JPA) 타입은 인프라 어댑터 안에만 존재한다. */
public interface InstructorReportQueryPort {

    /** 세션의 강사 리포트. AI 가 아직 만들지 않았으면 비어 있다. */
    Optional<InstructorReportView> findBySessionId(long sessionId);

    /**
     * "한눈에 보기" 중 조회 시점에 세는 값.
     *
     * <p>리포트 본문과 나눠 부르는 이유: 본문은 AI 가 만들어 저장한 값이고 이쪽은 조회 시점에 세는 값이라 성격이 다르다. 한 조회로 묶으면 리포트가 아직 없을 때 집계까지 못 내려간다.
     */
    InstructorReportCounts counts(long sessionId);
}
