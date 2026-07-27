package com.a105.zani.audioclip.application.subscriberequests;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.application.exception.AudioClipSessionEndedException;
import com.a105.zani.audioclip.application.exception.NotSessionInstructorException;
import com.a105.zani.audioclip.application.port.AudioClipRequestStorePort;
import com.a105.zani.audioclip.domain.model.AudioClipRequest;
import com.a105.zani.audioclip.domain.model.AudioClipRequestStatus;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioClipSubscriptionServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_USER = 7L;
    private static final long STUDENT_USER = 8L;
    private static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");

    private final FakeParticipantRepository participantRepository = new FakeParticipantRepository();
    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final FakeAudioClipRequestStore requestStore = new FakeAudioClipRequestStore();

    private AudioClipSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new AudioClipSubscriptionService(
                sessionRepository, participantRepository, requestStore, Clock.fixed(T0, ZoneOffset.UTC));
        sessionRepository.session = Session.reconstitute(
                SESSION_ID,
                INSTRUCTOR_USER,
                "제목",
                "INVITE01",
                false,
                T0.minusSeconds(600),
                SessionStatus.LIVE,
                SessionAnalysisStatus.NOT_STARTED);
        participantRepository.put(SESSION_ID, INSTRUCTOR_USER, SessionParticipantRole.INSTRUCTOR);
        participantRepository.put(SESSION_ID, STUDENT_USER, SessionParticipantRole.STUDENT);
    }

    private SubscribeAudioClipRequestsResult subscribe(long userId) {
        return service.subscribe(new SubscribeAudioClipRequestsQuery(SESSION_ID, userId));
    }

    @Test
    void 세션_멤버가_아니면_403() {
        assertThrows(NotSessionInstructorException.class, () -> subscribe(999L));
    }

    @Test
    void 학생이면_403() {
        assertThrows(NotSessionInstructorException.class, () -> subscribe(STUDENT_USER));
    }

    @Test
    void 종료된_세션이면_409() {
        sessionRepository.session.end();

        assertThrows(AudioClipSessionEndedException.class, () -> subscribe(INSTRUCTOR_USER));
    }

    @Test
    void 리플레이_대상은_만료되지_않은_PENDING_요청뿐이다() {
        // 만료: T0-2분에 생성 → 만료 시각 T0-1분 (지남). 유효: T0-30초 생성 → 만료 T0+30초.
        requestStore.pending.add(AudioClipRequest.create(1L, SESSION_ID, T0.minusSeconds(120)));
        AudioClipRequest fresh = AudioClipRequest.create(2L, SESSION_ID, T0.minusSeconds(30));
        requestStore.pending.add(fresh);

        SubscribeAudioClipRequestsResult result = subscribe(INSTRUCTOR_USER);

        assertEquals(List.of(fresh), result.pendingRequests());
    }

    @Test
    void 쌓인_요청이_없으면_빈_리플레이_목록이다() {
        assertEquals(List.of(), subscribe(INSTRUCTOR_USER).pendingRequests());
    }

    private static class FakeParticipantRepository implements SessionParticipantRepository {
        private final Map<String, SessionParticipant> byKey = new HashMap<>();
        private long nextId = 1;

        void put(long sessionId, long userId, SessionParticipantRole role) {
            byKey.put(
                    sessionId + ":" + userId,
                    SessionParticipant.reconstitute(nextId++, sessionId, userId, role, T0, T0));
        }

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.ofNullable(byKey.get(sessionId + ":" + userId));
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            return sessionParticipant;
        }
    }

    private static class FakeSessionRepository implements SessionRepository {
        Session session;

        @Override
        public Session save(Session session) {
            this.session = session;
            return session;
        }

        @Override
        public Optional<Session> findById(Long id) {
            return Optional.ofNullable(session);
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.ofNullable(session);
        }
    }

    private static class FakeAudioClipRequestStore implements AudioClipRequestStorePort {
        final List<AudioClipRequest> pending = new java.util.ArrayList<>();

        @Override
        public void save(AudioClipRequest request) {
            pending.add(request);
        }

        @Override
        public Optional<AudioClipRequest> find(long clipId) {
            return pending.stream()
                    .filter(request -> request.clipId() == clipId)
                    .findFirst();
        }

        @Override
        public List<AudioClipRequest> findPending(long sessionId) {
            return pending.stream()
                    .filter(request ->
                            request.sessionId() == sessionId && request.status() == AudioClipRequestStatus.PENDING)
                    .toList();
        }
    }
}
