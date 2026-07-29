package com.a105.zani.session;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.session.application.create.CreateSessionCommand;
import com.a105.zani.session.application.create.CreateSessionResult;
import com.a105.zani.session.application.create.CreateSessionUseCase;
import com.a105.zani.session.application.end.EndSessionByInstructorCommand;
import com.a105.zani.session.application.end.EndSessionByInstructorUseCase;
import com.a105.zani.session.application.exception.SessionCapacityReachedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.exception.SessionNotJoinableException;
import com.a105.zani.session.application.issuemediatoken.IssueMediaTokenCommand;
import com.a105.zani.session.application.issuemediatoken.IssueMediaTokenResult;
import com.a105.zani.session.application.issuemediatoken.IssueMediaTokenUseCase;
import com.a105.zani.session.application.join.JoinSessionCommand;
import com.a105.zani.session.application.join.JoinSessionUseCase;
import com.a105.zani.session.application.port.IssuedMediaToken;
import com.a105.zani.session.application.port.LiveKitTokenPort;
import com.a105.zani.session.application.start.StartSessionCommand;
import com.a105.zani.session.application.start.StartSessionResult;
import com.a105.zani.session.application.start.StartSessionUseCase;
import com.a105.zani.session.application.trackmediaconnection.MediaConnectionCommand;
import com.a105.zani.session.application.trackmediaconnection.TrackMediaConnectionUseCase;
import com.a105.zani.session.domain.model.SessionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 세션 생성 → 시작 → 입장 → 실제 LiveKit 연결까지의 생명주기 정합 검증(S15P11A105-222).
 *
 * <p>실제 MySQL·Redis 위에서 실제 유스케이스를 그대로 구동하고, LiveKit 토큰 발급 경계만 fixture 로 대체한다(테스트 환경에는 자격증명이 없다). 로컬 MySQL/Redis 가 떠 있어야
 * 통과한다.
 *
 * <p>여기서 고정하는 계약은 네 가지다.
 *
 * <ul>
 *   <li>생성 직후 강사가 곧바로 미디어 토큰을 받는다(강사 참가 관계가 함께 만들어져야 가능하다)
 *   <li>학생 입장은 시작된 수업에서만, 강사를 포함해 30명까지
 *   <li>하이픈·소문자 코드는 정규화되고 종료된 수업은 거절된다
 *   <li>API 입장만 한 사용자는 출석으로 잡히지 않는다
 * </ul>
 */
@SpringBootTest
@Import(SessionLifecycleIntegrationTest.LiveKitTokenFixtureConfig.class)
class SessionLifecycleIntegrationTest {

    private static final long INSTRUCTOR_ID = -9_001L;
    private static final long FIRST_STUDENT_ID = -9_100L;
    /** 강사 1명 + 학생 29명 = 30명이 정원이므로, 정원을 넘기려면 학생이 30명 필요하다. */
    private static final int SEEDED_STUDENTS = 30;

    @Autowired
    private CreateSessionUseCase createSessionUseCase;

    @Autowired
    private StartSessionUseCase startSessionUseCase;

    @Autowired
    private JoinSessionUseCase joinSessionUseCase;

    @Autowired
    private IssueMediaTokenUseCase issueMediaTokenUseCase;

    @Autowired
    private EndSessionByInstructorUseCase endSessionByInstructorUseCase;

    @Autowired
    private TrackMediaConnectionUseCase trackMediaConnectionUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long sessionId;

    @BeforeEach
    void seedMembers() {
        releaseActivationLock();
        insertMember(INSTRUCTOR_ID, "생명주기 테스트 강사");
        for (int i = 0; i < SEEDED_STUDENTS; i++) {
            insertMember(FIRST_STUDENT_ID - i, "생명주기 테스트 학생 " + i);
        }
    }

    @AfterEach
    void cleanUp() {
        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM session_status_changes WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        }
        jdbcTemplate.update(
                "DELETE FROM members WHERE id <= ? AND id >= ?", INSTRUCTOR_ID, FIRST_STUDENT_ID - SEEDED_STUDENTS);
        releaseActivationLock();
    }

    private void releaseActivationLock() {
        // 생성 시 잡히는 잠금은 TTL이 3시간이라, 종료까지 가지 않는 테스트는 직접 풀어야 다음 테스트가 세션을 만들 수 있다.
        redisTemplate.delete("session:active-lock:" + INSTRUCTOR_ID);
    }

    private void insertMember(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                id,
                "lifecycle-test-subject-" + id,
                "lifecycle-test-" + id + "@zani.local",
                displayName);
    }

    private CreateSessionResult createSession() {
        CreateSessionResult created = createSessionUseCase.create(new CreateSessionCommand(INSTRUCTOR_ID, "생명주기 테스트"));
        sessionId = created.sessionId();
        return created;
    }

    private String startSession() {
        StartSessionResult started = startSessionUseCase.start(new StartSessionCommand(sessionId, INSTRUCTOR_ID));
        return started.inviteCode();
    }

    private String sessionStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM sessions WHERE id = ?", String.class, sessionId);
    }

    /**
     * Hibernate 가 UTC 로 저장하므로(jdbc.time_zone=UTC) 드라이버가 돌려주는 LocalDateTime 도 UTC 기준으로 읽어야 한다. DATETIME 은 시간대를 담지 않아
     * JDBC 가 Instant 로 직접 변환해 주지 않는다.
     */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private Instant firstJoinedAtOf(long memberId) {
        List<LocalDateTime> values = jdbcTemplate.queryForList(
                "SELECT first_joined_at FROM session_participants WHERE session_id = ? AND member_id = ?",
                LocalDateTime.class,
                sessionId,
                memberId);
        return values.isEmpty() ? null : toInstant(values.get(0));
    }

    private Instant startedAt() {
        return toInstant(jdbcTemplate.queryForObject(
                "SELECT started_at FROM sessions WHERE id = ?", LocalDateTime.class, sessionId));
    }

    private long participantIdOf(long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM session_participants WHERE session_id = ? AND member_id = ?",
                Long.class,
                sessionId,
                memberId);
    }

    /** 생성 트랜잭션이 강사 참가 관계를 함께 만들지 않으면 여기서 403 이 난다. 강사가 자기 수업에 못 들어가는 상태였다. */
    @Test
    void theInstructorCanRequestAMediaTokenImmediatelyAfterCreatingTheSession() {
        CreateSessionResult created = createSession();

        assertEquals(SessionStatus.PREPARING, created.status());
        assertNull(created.expiresAt(), "시작 전에는 자동 종료 시각이 정해지지 않는다");

        IssueMediaTokenResult token =
                issueMediaTokenUseCase.issue(new IssueMediaTokenCommand(created.sessionId(), INSTRUCTOR_ID));

        assertEquals("p-" + participantIdOf(INSTRUCTOR_ID), token.participantIdentity());
        assertNull(token.sessionExpiresAt(), "아직 시작하지 않았으므로 종료 예정 시각도 없다");
    }

    /** 초대 코드는 생성 시점에 발급되지만 시작 전에는 통하지 않아야 한다. 준비 중인 방에 학생이 들어오면 강사 점검을 방해한다. */
    @Test
    void studentsCannotJoinUntilTheInstructorStartsTheSession() {
        CreateSessionResult created = createSession();

        assertThrows(
                SessionNotJoinableException.class,
                () -> joinSessionUseCase.join(new JoinSessionCommand(created.inviteCode(), FIRST_STUDENT_ID)));

        String inviteCode = startSession();
        assertEquals(SessionStatus.LIVE.name(), sessionStatus());

        assertEquals(
                created.sessionId(),
                joinSessionUseCase
                        .join(new JoinSessionCommand(inviteCode, FIRST_STUDENT_ID))
                        .sessionId());
    }

    @Test
    void startingAnAlreadyLiveSessionIsIdempotentAndDoesNotPushTheStartTimeBack() {
        createSession();
        startSession();
        Instant firstStart = startedAt();

        StartSessionResult again = startSessionUseCase.start(new StartSessionCommand(sessionId, INSTRUCTOR_ID));

        assertFalse(again.started());
        // 시작 시각이 밀리면 최대 수업 시간이 그만큼 늘어난다.
        assertEquals(firstStart, startedAt());
    }

    /** 정원 30 은 강사를 포함한다. 강사 1 + 학생 29 로 가득 차고, 30번째 학생(=31번째 참가자)은 거절돼야 한다. */
    @Test
    void acceptsTwentyNineStudentsAndRejectsTheThirtyFirstMember() {
        createSession();
        String inviteCode = startSession();

        for (int i = 0; i < 29; i++) {
            long studentId = FIRST_STUDENT_ID - i;
            assertEquals(
                    sessionId,
                    joinSessionUseCase
                            .join(new JoinSessionCommand(inviteCode, studentId))
                            .sessionId(),
                    "학생 " + i + " 는 정원 안이라 들어갈 수 있어야 한다");
        }

        assertEquals(
                30L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM session_participants WHERE session_id = ?", Long.class, sessionId));

        long overflowStudent = FIRST_STUDENT_ID - 29;
        assertThrows(
                SessionCapacityReachedException.class,
                () -> joinSessionUseCase.join(new JoinSessionCommand(inviteCode, overflowStudent)));
    }

    /** 이미 들어와 있는 학생의 새로고침은 정원을 다시 소비하지 않는다. */
    @Test
    void anExistingStudentCanRejoinAFullSession() {
        createSession();
        String inviteCode = startSession();
        for (int i = 0; i < 29; i++) {
            joinSessionUseCase.join(new JoinSessionCommand(inviteCode, FIRST_STUDENT_ID - i));
        }

        assertEquals(
                sessionId,
                joinSessionUseCase
                        .join(new JoinSessionCommand(inviteCode, FIRST_STUDENT_ID))
                        .sessionId());
        assertEquals(
                30L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM session_participants WHERE session_id = ?", Long.class, sessionId));
    }

    @Test
    void normalisesHyphenatedAndLowercaseInviteCodes() {
        createSession();
        String inviteCode = startSession();
        String displayForm = inviteCode.substring(0, 4) + "-" + inviteCode.substring(4);

        assertEquals(
                sessionId,
                joinSessionUseCase
                        .join(new JoinSessionCommand(displayForm.toLowerCase(), FIRST_STUDENT_ID))
                        .sessionId());
        // 같은 학생이 다른 표기로 다시 들어와도 참가 관계는 하나다.
        assertEquals(
                sessionId,
                joinSessionUseCase
                        .join(new JoinSessionCommand(inviteCode, FIRST_STUDENT_ID))
                        .sessionId());
        assertEquals(
                2L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM session_participants WHERE session_id = ?", Long.class, sessionId));
    }

    @Test
    void rejectsAnInviteCodeThatMatchesNoSession() {
        assertThrows(
                SessionNotFoundException.class,
                () -> joinSessionUseCase.join(new JoinSessionCommand("ZZZZ-ZZZZ", FIRST_STUDENT_ID)));
    }

    /** 종료 즉시 초대 코드가 만료돼야 한다(가이드 §6). */
    @Test
    void rejectsTheInviteCodeOfAnEndedSession() {
        createSession();
        String inviteCode = startSession();
        endSessionByInstructorUseCase.endByInstructor(new EndSessionByInstructorCommand(sessionId, INSTRUCTOR_ID));

        assertEquals(SessionStatus.NOTE_PENDING.name(), sessionStatus());
        assertThrows(
                SessionNotJoinableException.class,
                () -> joinSessionUseCase.join(new JoinSessionCommand(inviteCode, FIRST_STUDENT_ID)));
    }

    /**
     * 완료 조건의 핵심: API 로 입장만 하고 실제 미디어에 접속하지 않은 사용자는 출석으로 잡히지 않아야 한다. {@code first_joined_at} 이 비어 있는 동안 그 학생은 접속 1분 분모에
     * 들어가지 않는다.
     */
    @Test
    void apiJoinAloneLeavesAttendanceUnconfirmedUntilLiveKitReportsTheConnection() {
        createSession();
        String inviteCode = startSession();
        joinSessionUseCase.join(new JoinSessionCommand(inviteCode, FIRST_STUDENT_ID));

        assertNull(firstJoinedAtOf(FIRST_STUDENT_ID), "API 입장만으로는 출석이 아니다");

        Instant connectedAt = Instant.parse("2026-07-26T00:00:00Z");
        trackMediaConnectionUseCase.confirmJoined(
                new MediaConnectionCommand(sessionId, "p-" + participantIdOf(FIRST_STUDENT_ID), connectedAt));

        assertEquals(connectedAt, firstJoinedAtOf(FIRST_STUDENT_ID));
    }

    /** 강사도 마찬가지다. 생성 시점에는 참가 관계만 있고 출석은 실제 연결에서 확정된다. */
    @Test
    void theInstructorIsEnrolledButNotYetPresentWhenTheSessionIsCreated() {
        createSession();

        assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM session_participants WHERE session_id = ? AND role = 'INSTRUCTOR'",
                        Long.class,
                        sessionId));
        assertNull(firstJoinedAtOf(INSTRUCTOR_ID));
    }

    /** 생명주기 추적이 가능하려면 전이가 남아야 한다. 생성·시작·종료 절차가 모두 이력에 찍힌다. */
    @Test
    void recordsEveryLifecycleTransition() {
        createSession();
        startSession();
        endSessionByInstructorUseCase.endByInstructor(new EndSessionByInstructorCommand(sessionId, INSTRUCTOR_ID));

        List<String> transitions = jdbcTemplate.queryForList(
                "SELECT to_status FROM session_status_changes WHERE session_id = ? ORDER BY changed_at, id",
                String.class,
                sessionId);

        assertEquals(List.of("PREPARING", "LIVE", "ENDING", "NOTE_PENDING"), transitions);
    }

    @Test
    void refusesToIssueAMediaTokenOnceTheSessionHasStartedEnding() {
        createSession();
        String inviteCode = startSession();
        joinSessionUseCase.join(new JoinSessionCommand(inviteCode, FIRST_STUDENT_ID));
        endSessionByInstructorUseCase.endByInstructor(new EndSessionByInstructorCommand(sessionId, INSTRUCTOR_ID));

        assertThrows(
                RuntimeException.class,
                () -> issueMediaTokenUseCase.issue(new IssueMediaTokenCommand(sessionId, FIRST_STUDENT_ID)));
    }

    /** 종료가 강사의 활성 세션 잠금을 반납하지 않으면, 수업을 끝낸 강사가 3시간 동안 새 수업을 열지 못한다. */
    @Test
    void endingASessionLetsTheInstructorOpenANewOneRightAway() {
        createSession();
        startSession();
        endSessionByInstructorUseCase.endByInstructor(new EndSessionByInstructorCommand(sessionId, INSTRUCTOR_ID));
        long endedSessionId = sessionId;

        CreateSessionResult next = createSessionUseCase.create(new CreateSessionCommand(INSTRUCTOR_ID, "다음 수업"));

        assertNotNull(next.sessionId());
        jdbcTemplate.update("DELETE FROM session_status_changes WHERE session_id = ?", next.sessionId());
        jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", next.sessionId());
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", next.sessionId());
        sessionId = endedSessionId;
    }

    /** 테스트 환경에는 LiveKit 자격증명이 없으므로 토큰 발급 경계만 대체한다. 그 앞의 멤버십·상태 검증은 실제 코드가 그대로 돈다. */
    @TestConfiguration
    static class LiveKitTokenFixtureConfig {

        @Bean
        @Primary
        LiveKitTokenPort fakeLiveKitTokenPort() {
            return request -> new IssuedMediaToken(
                    "wss://livekit.test",
                    "fake-token-for-" + request.identity(),
                    "zani-test-session-" + request.sessionId(),
                    Instant.parse("2026-07-26T00:10:00Z"));
        }
    }
}
