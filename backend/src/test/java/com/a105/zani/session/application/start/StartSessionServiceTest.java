package com.a105.zani.session.application.start;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionStatusHistoryPort;
import com.a105.zani.session.domain.exception.SessionNotLiveException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionEndReason;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartSessionServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_ID = 7L;
    private static final long OTHER_USER_ID = 8L;
    private static final String INVITE_CODE = "ABCDEFGH";
    private static final Instant NOW = Instant.parse("2026-07-30T09:00:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final RecordingStatusHistoryPort statusHistory = new RecordingStatusHistoryPort();
    private final StartSessionService service =
            new StartSessionService(sessionRepository, statusHistory, Clock.fixed(NOW, ZoneOffset.UTC));

    /** 만료는 실제 시작 기준이어야 한다. 준비에 20분을 쓴 강사의 수업 시간이 그만큼 줄어들면 안 된다. */
    @Test
    void startsAPreparingSessionAndOpensTheThreeHourWindow() {
        sessionRepository.session = Session.prepare(SESSION_ID, INSTRUCTOR_ID, "테스트 수업", INVITE_CODE);

        StartSessionResult result = service.start(new StartSessionCommand(SESSION_ID, INSTRUCTOR_ID));

        assertTrue(result.started());
        assertEquals(SessionStatus.LIVE, result.status());
        assertEquals(NOW.plus(Session.ACTIVE_DURATION), result.expiresAt());
        // 이 시점부터 초대 코드가 유효해지므로 호출자에게 함께 돌려준다.
        assertEquals(INVITE_CODE, result.inviteCode());
        assertEquals(1, sessionRepository.saveCount);
    }

    @Test
    void recordsTheTransitionInTheStatusHistory() {
        sessionRepository.session = Session.prepare(SESSION_ID, INSTRUCTOR_ID, "테스트 수업", INVITE_CODE);

        service.start(new StartSessionCommand(SESSION_ID, INSTRUCTOR_ID));

        assertEquals(List.of("PREPARING→LIVE"), statusHistory.transitions);
    }

    /** 시작 버튼 연타·재시도에 안전해야 한다. 두 번째 호출이 시작 시각을 밀면 수업이 예정보다 늦게 끝난다. */
    @Test
    void isIdempotentAndDoesNotPushTheStartTime() {
        sessionRepository.session = Session.prepare(SESSION_ID, INSTRUCTOR_ID, "테스트 수업", INVITE_CODE);
        Instant firstExpiry = service.start(new StartSessionCommand(SESSION_ID, INSTRUCTOR_ID))
                .expiresAt();

        StartSessionResult second = service.start(new StartSessionCommand(SESSION_ID, INSTRUCTOR_ID));

        assertFalse(second.started());
        assertEquals(firstExpiry, second.expiresAt());
        // 두 번째 호출은 저장도, 이력도 남기지 않는다.
        assertEquals(1, sessionRepository.saveCount);
        assertEquals(1, statusHistory.transitions.size());
    }

    @Test
    void onlyTheInstructorWhoOpenedTheSessionCanStartIt() {
        sessionRepository.session = Session.prepare(SESSION_ID, INSTRUCTOR_ID, "테스트 수업", INVITE_CODE);

        assertThrows(
                NotSessionInstructorException.class,
                () -> service.start(new StartSessionCommand(SESSION_ID, OTHER_USER_ID)));
        assertEquals(0, sessionRepository.saveCount);
    }

    @Test
    void cannotStartASessionThatHasAlreadyEnded() {
        Session session = Session.prepare(SESSION_ID, INSTRUCTOR_ID, "테스트 수업", INVITE_CODE);
        session.start(NOW.minusSeconds(600));
        session.beginEnding(NOW.minusSeconds(60), SessionEndReason.INSTRUCTOR_REQUEST);
        sessionRepository.session = session;

        assertThrows(
                SessionNotLiveException.class, () -> service.start(new StartSessionCommand(SESSION_ID, INSTRUCTOR_ID)));
    }

    @Test
    void throwsWhenTheSessionDoesNotExist() {
        sessionRepository.session = null;

        assertThrows(
                SessionNotFoundException.class,
                () -> service.start(new StartSessionCommand(SESSION_ID, INSTRUCTOR_ID)));
    }

    private static final class RecordingStatusHistoryPort implements SessionStatusHistoryPort {

        private final List<String> transitions = new ArrayList<>();

        @Override
        public void record(long sessionId, SessionStatus from, SessionStatus to, Instant changedAt) {
            transitions.add(from + "→" + to);
        }
    }

    private static final class FakeSessionRepository implements SessionRepository {

        private Session session;
        private int saveCount = 0;

        @Override
        public Session save(Session session) {
            this.session = session;
            saveCount++;
            return session;
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            return List.of();
        }

        @Override
        public List<Session> findPreparingCreatedBefore(Instant createdBefore, int limit) {
            return List.of();
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public Optional<Session> findByIdForUpdate(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.empty();
        }

        @Override
        public Optional<Session> findByInviteCodeForUpdate(String inviteCode) {
            return Optional.empty();
        }
    }
}
