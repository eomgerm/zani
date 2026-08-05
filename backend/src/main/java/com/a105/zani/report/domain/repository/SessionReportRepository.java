package com.a105.zani.report.domain.repository;

import java.time.Instant;

import com.a105.zani.report.domain.model.SessionReport;

public interface SessionReportRepository {

    /** 요약과 구간을 함께 저장한다. 둘은 같은 LLM 응답에서 나오므로 따로 남으면 뜻이 통하지 않는다. */
    void save(SessionReport report);

    /** 이 세션의 공통 리포트가 이미 있는지. 세션당 1회 분석의 멱등 판단에 쓴다. */
    boolean existsBySessionId(Long sessionId);

    /**
     * 아직 공개되지 않은 리포트에 공개 시각을 찍는다.
     *
     * <p>이미 값이 있으면 덮지 않는다. 재시도가 시각을 다시 쓰면 알림 발견 순서가 흔들리고, 무엇보다 공개는 되돌릴 수 없는 일이라 두 번째 호출이 조용히 성공한 것처럼 보이면 안 된다.
     *
     * @return 이번 호출이 공개를 확정했으면 {@code true}. 이미 공개됐거나 리포트가 없으면 {@code false}
     */
    boolean markPublished(Long sessionId, Instant publishedAt);
}
