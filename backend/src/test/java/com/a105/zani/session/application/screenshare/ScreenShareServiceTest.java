package com.a105.zani.session.application.screenshare;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.ScreenShareInUseException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.port.ActiveScreenSharePort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenShareServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long MY_USER_ID = 7L;
    private static final long MY_PARTICIPANT_ID = 456L;
    private static final long OTHER_PARTICIPANT_ID = 999L;
    private static final Instant STARTED_AT = Instant.parse("2026-07-30T09:00:00Z");

    private Session session;
    private SessionParticipant participant;

    private final SessionRepository sessionRepository = new SessionRepository() {
        @Override
        public Session save(Session s) {
            return s;
        }

        @Override
        public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
            return List.of();
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.empty();
        }
    };

    private final SessionParticipantRepository participantRepository = new SessionParticipantRepository() {
        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.ofNullable(participant);
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            return Optional.ofNullable(participant);
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            return participant == null ? List.of() : List.of(participant);
        }

        @Override
        public SessionParticipant save(SessionParticipant p) {
            return p;
        }
    };

    /** 실제 Redis Lua와 같은 의미의 인메모리 슬롯: 비어 있거나 소유자가 같으면 획득/갱신, 아니면 거부. */
    private final Map<Long, Long> activeSlots = new HashMap<>();

    private final ActiveScreenSharePort activeScreenSharePort = new ActiveScreenSharePort() {
        @Override
        public boolean claim(long sessionId, long participantId, Duration ttl) {
            Long current = activeSlots.get(sessionId);
            if (current == null || current == participantId) {
                activeSlots.put(sessionId, participantId);
                return true;
            }
            return false;
        }

        @Override
        public void release(long sessionId, long participantId) {
            activeSlots.remove(sessionId, participantId);
        }

        @Override
        public Optional<Long> currentSharer(long sessionId) {
            return Optional.ofNullable(activeSlots.get(sessionId));
        }
    };

    private final ScreenShareService service =
            new ScreenShareService(sessionRepository, participantRepository, activeScreenSharePort);

    private void liveMember() {
        session = Session.reconstitute(
                SESSION_ID,
                1L,
                "제목",
                "INVITE01",
                false,
                STARTED_AT,
                SessionStatus.LIVE,
                SessionAnalysisStatus.NOT_STARTED);
        participant = SessionParticipant.join(
                MY_PARTICIPANT_ID, SESSION_ID, MY_USER_ID, SessionParticipantRole.STUDENT, STARTED_AT);
    }

    @Test
    void grantsWhenNoOneIsSharing() {
        liveMember();

        StartScreenShareResult result = service.start(new StartScreenShareCommand(SESSION_ID, MY_USER_ID));

        assertEquals(MY_PARTICIPANT_ID, result.participantId());
        assertEquals(Optional.of(MY_PARTICIPANT_ID), activeScreenSharePort.currentSharer(SESSION_ID));
    }

    @Test
    void refreshesWhenTheSameParticipantIsAlreadySharing() {
        liveMember();
        service.start(new StartScreenShareCommand(SESSION_ID, MY_USER_ID));

        // 공유 중 TTL 갱신을 위한 반복 호출은 같은 참가자라 성공해야 한다(자기 자신을 거부하지 않는다).
        StartScreenShareResult refreshed = service.start(new StartScreenShareCommand(SESSION_ID, MY_USER_ID));

        assertEquals(MY_PARTICIPANT_ID, refreshed.participantId());
    }

    @Test
    void rejectsWhenAnotherParticipantIsAlreadySharing() {
        liveMember();
        activeSlots.put(SESSION_ID, OTHER_PARTICIPANT_ID);

        assertThrows(
                ScreenShareInUseException.class,
                () -> service.start(new StartScreenShareCommand(SESSION_ID, MY_USER_ID)));
        // 거부는 기존 공유자를 유지한다(선점 아님).
        assertEquals(Optional.of(OTHER_PARTICIPANT_ID), activeScreenSharePort.currentSharer(SESSION_ID));
    }

    @Test
    void rejectsWhenTheUserIsNotASessionMember() {
        liveMember();
        participant = null;

        assertThrows(
                NotSessionMemberException.class,
                () -> service.start(new StartScreenShareCommand(SESSION_ID, MY_USER_ID)));
    }

    @Test
    void rejectsWhenTheSessionHasEnded() {
        liveMember();
        session = Session.reconstitute(
                SESSION_ID,
                1L,
                "제목",
                "INVITE01",
                false,
                STARTED_AT,
                SessionStatus.ENDED,
                SessionAnalysisStatus.NOT_STARTED);

        assertThrows(
                SessionAlreadyEndedException.class,
                () -> service.start(new StartScreenShareCommand(SESSION_ID, MY_USER_ID)));
    }

    @Test
    void stopReleasesTheOwnSlotSoTheNextParticipantCanShare() {
        liveMember();
        service.start(new StartScreenShareCommand(SESSION_ID, MY_USER_ID));

        service.stop(new StopScreenShareCommand(SESSION_ID, MY_USER_ID));

        assertTrue(activeScreenSharePort.currentSharer(SESSION_ID).isEmpty());
    }

    @Test
    void stopDoesNotReleaseAnotherParticipantsSlot() {
        liveMember();
        activeSlots.put(SESSION_ID, OTHER_PARTICIPANT_ID);

        // 내 공유가 이미 정리되고 다른 사람이 공유 중일 때, 뒤늦은 내 stop이 남의 슬롯을 지우면 안 된다.
        service.stop(new StopScreenShareCommand(SESSION_ID, MY_USER_ID));

        assertEquals(Optional.of(OTHER_PARTICIPANT_ID), activeScreenSharePort.currentSharer(SESSION_ID));
    }
}
