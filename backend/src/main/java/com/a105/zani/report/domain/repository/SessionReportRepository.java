package com.a105.zani.report.domain.repository;

import com.a105.zani.report.domain.model.SessionReport;

public interface SessionReportRepository {

    /** 요약과 구간을 함께 저장한다. 둘은 같은 LLM 응답에서 나오므로 따로 남으면 뜻이 통하지 않는다. */
    void save(SessionReport report);

    /** 이 세션의 공통 리포트가 이미 있는지. 세션당 1회 분석의 멱등 판단에 쓴다. */
    boolean existsBySessionId(Long sessionId);
}
