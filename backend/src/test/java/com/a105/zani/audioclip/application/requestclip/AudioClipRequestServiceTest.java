package com.a105.zani.audioclip.application.requestclip;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.audioclip.application.exception.AudioClipSessionEndedException;
import com.a105.zani.audioclip.application.exception.AudioClipSessionNotFoundException;
import com.a105.zani.audioclip.application.port.AudioClipDispatchPort;
import com.a105.zani.audioclip.application.port.AudioClipRequestStorePort;
import com.a105.zani.audioclip.domain.model.AudioClipRequest;
import com.a105.zani.audioclip.domain.model.AudioClipRequestStatus;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioClipRequestServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long INSTRUCTOR_USER = 7L;
    private static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");

    private final FakeSessionRepository sessionRepository = new FakeSessionRepository();
    private final FakeAudioClipRequestStore requestStore = new FakeAudioClipRequestStore();
    private final FakeDispatchPort dispatchPort = new FakeDispatchPort();

    private AudioClipRequestService service;

    @BeforeEach
    void setUp() {
        service = new AudioClipRequestService(
                sessionRepository, requestStore, dispatchPort, Clock.fixed(T0, ZoneOffset.UTC));
        sessionRepository.session = Session.reconstitute(
                SESSION_ID,
                INSTRUCTOR_USER,
                "제목",
                "INVITE01",
                false,
                T0,
                SessionStatus.LIVE,
                SessionAnalysisStatus.NOT_STARTED);
    }

    @Test
    void 세션이_없으면_404() {
        sessionRepository.session = null;

        assertThrows(
                AudioClipSessionNotFoundException.class,
                () -> service.request(new RequestAudioClipCommand(SESSION_ID)));
    }

    @Test
    void 종료된_세션이면_409() {
        sessionRepository.session.end();

        assertThrows(
                AudioClipSessionEndedException.class, () -> service.request(new RequestAudioClipCommand(SESSION_ID)));
    }

    @Test
    void 정상이면_PENDING으로_저장하고_강사에게_디스패치한다() {
        RequestAudioClipResult result = service.request(new RequestAudioClipCommand(SESSION_ID));

        assertEquals(SESSION_ID, result.sessionId());
        assertEquals(AudioClipRequest.CLIP_WINDOW.toSeconds(), result.windowSeconds());
        assertEquals(T0.plus(AudioClipRequest.REQUEST_TTL), result.expiresAt());

        AudioClipRequest stored = requestStore.byId.get(result.clipId());
        assertEquals(AudioClipRequestStatus.PENDING, stored.status());
        assertEquals(1, dispatchPort.dispatched.size());
        assertEquals(result.clipId(), dispatchPort.dispatched.get(0).clipId());
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
        final Map<Long, AudioClipRequest> byId = new HashMap<>();

        @Override
        public void save(AudioClipRequest request) {
            byId.put(request.clipId(), request);
        }

        @Override
        public Optional<AudioClipRequest> find(long clipId) {
            return Optional.ofNullable(byId.get(clipId));
        }

        @Override
        public List<AudioClipRequest> findPending(long sessionId) {
            return byId.values().stream()
                    .filter(request ->
                            request.sessionId() == sessionId && request.status() == AudioClipRequestStatus.PENDING)
                    .toList();
        }
    }

    private static class FakeDispatchPort implements AudioClipDispatchPort {
        final List<AudioClipRequest> dispatched = new ArrayList<>();

        @Override
        public void dispatch(AudioClipRequest request) {
            dispatched.add(request);
        }
    }
}
