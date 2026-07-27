package com.a105.zani.session.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세션 영속 어댑터의 DB 수준 계약 검증. 만료 세션 조회는 자동 종료 스케줄러가 유일하게 의존하는 쿼리라, fake가 아니라 실제 쿼리로 확인한다(파라미터 타입·상태 비교·정렬이 맞아야 한다). 로컬
 * MySQL이 떠 있어야 통과하며, 테스트 트랜잭션은 종료 시 롤백되어 데이터를 남기지 않는다.
 */
@SpringBootTest
class SessionPersistenceAdapterTest {

    private static final long MEMBER_ID = 9_100_001L;
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private SessionPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Hibernate가 UTC로 저장(jdbc.time_zone=UTC)하므로, 직접 INSERT 할 때도 UTC 기준 시각을 넣어야 조회 조건과 맞는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void insertInstructor() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                MEMBER_ID,
                "google-" + MEMBER_ID,
                MEMBER_ID + "@example.com",
                "만료 조회 테스트 강사",
                utc(NOW),
                utc(NOW));
    }

    private long insertSession(String inviteCode, SessionStatus status, Instant startedAt) {
        insertInstructor();
        long id = Math.abs(inviteCode.hashCode()) + 9_100_000_000L;
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'NOT_STARTED', ?, ?, ?)",
                id,
                MEMBER_ID,
                "만료 조회 테스트",
                inviteCode,
                status.name(),
                utc(startedAt),
                utc(startedAt),
                utc(startedAt));
        return id;
    }

    private static boolean containsSession(List<Session> sessions, long id) {
        return sessions.stream().anyMatch(session -> session.id() == id);
    }

    @Test
    @Transactional
    void findsALiveSessionThatStartedBeforeTheCutoff() {
        long id = insertSession("EXPIRE01", SessionStatus.LIVE, NOW.minus(4, ChronoUnit.HOURS));

        List<Session> due = adapter.findLiveStartedBefore(NOW.minus(3, ChronoUnit.HOURS), 50);

        assertTrue(containsSession(due, id));
    }

    @Test
    @Transactional
    void skipsALiveSessionThatIsStillWithinItsMaximumDuration() {
        long id = insertSession("EXPIRE02", SessionStatus.LIVE, NOW.minus(1, ChronoUnit.HOURS));

        List<Session> due = adapter.findLiveStartedBefore(NOW.minus(3, ChronoUnit.HOURS), 50);

        assertFalse(containsSession(due, id));
    }

    @Test
    @Transactional
    void skipsASessionThatHasAlreadyEnded() {
        long id = insertSession("EXPIRE03", SessionStatus.ENDED, NOW.minus(4, ChronoUnit.HOURS));

        List<Session> due = adapter.findLiveStartedBefore(NOW.minus(3, ChronoUnit.HOURS), 50);

        assertFalse(containsSession(due, id));
    }

    @Test
    @Transactional
    void honoursTheRequestedLimit() {
        insertSession("EXPIRE04", SessionStatus.LIVE, NOW.minus(5, ChronoUnit.HOURS));
        insertSession("EXPIRE05", SessionStatus.LIVE, NOW.minus(4, ChronoUnit.HOURS));

        assertEquals(
                1,
                adapter.findLiveStartedBefore(NOW.minus(3, ChronoUnit.HOURS), 1).size());
    }
}
