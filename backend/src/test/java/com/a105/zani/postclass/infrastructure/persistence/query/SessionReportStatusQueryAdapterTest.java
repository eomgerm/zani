package com.a105.zani.postclass.infrastructure.persistence.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.analyzecontent.SessionReportStatusQueryPort;

import static org.assertj.core.api.Assertions.assertThat;

/** 네이티브 SQL 이라 컴파일 시점 검사가 없다. 컬럼명 오타와 스키마 변경은 이 테스트에서만 드러난다. 로컬 MySQL 이 떠 있어야 통과한다. */
@SpringBootTest
@Transactional
class SessionReportStatusQueryAdapterTest {

    @Autowired
    private SessionReportStatusQueryPort queryPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sessionId;

    @BeforeEach
    void setUp() {
        sessionId = insertSession(insertMember());
    }

    @Test
    @DisplayName("공통 리포트가 없으면 거짓이다")
    void returns_false_without_a_session_report() {
        assertThat(queryPort.hasSessionReport(sessionId)).isFalse();
    }

    @Test
    @DisplayName("공통 리포트가 있으면 참이다")
    void returns_true_with_a_session_report() {
        insertSessionReport();

        assertThat(queryPort.hasSessionReport(sessionId)).isTrue();
    }

    /** 적재 여부만 답한다. 세션이 살아 있는지는 컨텍스트 조회가 따로 보므로 여기서 걸러 내면 이미 만든 리포트를 다시 만들게 된다. */
    @Test
    @DisplayName("세션이 소프트 삭제돼도 리포트가 있으면 참이다")
    void stays_true_when_the_session_is_soft_deleted() {
        insertSessionReport();
        jdbcTemplate.update("UPDATE sessions SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", sessionId);

        assertThat(queryPort.hasSessionReport(sessionId)).isTrue();
    }

    private long insertMember() {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, '공통 리포트 조회 테스트', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                "google-" + id,
                id + "@example.com");
        return id;
    }

    private long insertSession(long hostMemberId) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, '상태 관리 수업', ?, 'ENDED', 'PROCESSING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                hostMemberId,
                inviteCode(id));
        return id;
    }

    private void insertSessionReport() {
        jdbcTemplate.update(
                "INSERT INTO session_reports (id, session_id, summary, created_at, updated_at)"
                        + " VALUES (?, ?, '수업 공통 요약', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId);
    }

    private static String inviteCode(long id) {
        String encoded = Long.toString(Math.abs(id), 36).toUpperCase();
        return encoded.length() <= 8 ? encoded : encoded.substring(encoded.length() - 8);
    }
}
