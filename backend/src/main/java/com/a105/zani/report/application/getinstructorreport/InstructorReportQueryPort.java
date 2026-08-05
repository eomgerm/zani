package com.a105.zani.report.application.getinstructorreport;

import java.util.Optional;

/** 저장된 강사 리포트를 읽는다. 벤더(JPA) 타입은 인프라 어댑터 안에만 존재한다. */
public interface InstructorReportQueryPort {

    /** 세션의 강사 리포트. AI 가 아직 만들지 않았으면 비어 있다. */
    Optional<InstructorReportRecord> findBySessionId(long sessionId);
}
