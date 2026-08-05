package com.a105.zani.postclass.infrastructure.persistence.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.analyzecontent.SessionReportStatusQueryPort;

/**
 * 공통 리포트 존재 여부를 읽는다. {@code session_reports} 는 {@code report} 소유라 네이티브 SQL 로만 읽고 쓰지 않는다 —
 * {@code InstructorAnalysisContextQueryAdapter} 와 같은 방식이다.
 *
 * <p>세션의 소프트 삭제를 보지 않는다. 이 값이 답하는 것은 "적재됐는가" 뿐이고, 강사 분석 쪽 존재 확인도 같은 기준이다. 세션이 살아 있는지는 컨텍스트 조회가 따로 본다.
 *
 * <p>컴파일 시점 검사가 없다. 컬럼명 오타와 스키마 변경은 {@code SessionReportStatusQueryAdapterTest} 에서만 드러난다.
 */
@Component
@RequiredArgsConstructor
public class SessionReportStatusQueryAdapter implements SessionReportStatusQueryPort {

    private static final String COUNT_REPORT = """
            SELECT COUNT(*)
              FROM session_reports
             WHERE session_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean hasSessionReport(Long sessionId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_REPORT, Integer.class, sessionId);
        return count != null && count > 0;
    }
}
