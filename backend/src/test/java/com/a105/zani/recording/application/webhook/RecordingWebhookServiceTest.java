package com.a105.zani.recording.application.webhook;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.exception.InvalidWebhookSignatureException;
import com.a105.zani.recording.application.exception.RecordingNotReadyException;
import com.a105.zani.recording.application.orchestrate.RequestTrackEgressCommand;
import com.a105.zani.recording.application.orchestrate.RequestTrackEgressResult;
import com.a105.zani.recording.application.orchestrate.RequestTrackEgressUseCase;
import com.a105.zani.recording.application.port.RecordingWebhookEventPort;
import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.model.RecordingFile;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackRecordingDecision;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.domain.repository.RecordingFileRepository;
import com.a105.zani.recording.domain.repository.RecordingRepository;
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

class RecordingWebhookServiceTest {

    private static final long SESSION_ID = 100L;
    private static final Instant SESSION_START = Instant.parse("2026-07-25T05:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-25T06:00:00Z");

    private RecordingWebhookEvent nextEvent;
    private final List<RequestTrackEgressCommand> egressRequests = new ArrayList<>();
    private final Map<String, Recording> recordingsByEgressId = new HashMap<>();
    private final List<RecordingFile> savedFiles = new ArrayList<>();
    private final Map<Long, SessionParticipant> participantsById = new HashMap<>();
    private final Set<String> processedEvents = new HashSet<>();
    private final Set<String> seenEvents = new HashSet<>();

    private RecordingWebhookService service;

    private static RecordingWebhookEvent trackPublished(
            String eventId, String identity, String trackSid, TrackSource source) {
        return new RecordingWebhookEvent(
                eventId,
                RecordingWebhookEventType.TRACK_PUBLISHED,
                SESSION_ID,
                identity,
                trackSid,
                source,
                null,
                null,
                null,
                List.of());
    }

    private static RecordingWebhookEvent egressEvent(
            String eventId,
            RecordingWebhookEventType type,
            String egressId,
            Boolean complete,
            List<EgressFileResult> files) {
        return new RecordingWebhookEvent(
                eventId, type, SESSION_ID, null, null, null, egressId, complete, "TR_src", files);
    }

    @BeforeEach
    void setUp() {
        RequestTrackEgressUseCase egressUseCase = command -> {
            egressRequests.add(command);
            return new RequestTrackEgressResult(TrackRecordingDecision.RECORD, true);
        };
        RecordingRepository recordingRepository = new RecordingRepository() {
            @Override
            public Recording save(Recording recording) {
                recordingsByEgressId.put(recording.livekitEgressId(), recording);
                return recording;
            }

            @Override
            public Optional<Recording> findByLivekitEgressId(String egressId) {
                return Optional.ofNullable(recordingsByEgressId.get(egressId));
            }
        };
        RecordingFileRepository fileRepository = new RecordingFileRepository() {
            @Override
            public RecordingFile save(RecordingFile file) {
                savedFiles.add(file);
                return file;
            }

            @Override
            public boolean existsByStorageKey(String storageKey) {
                return savedFiles.stream().anyMatch(saved -> saved.storageKey().equals(storageKey));
            }
        };
        SessionRepository sessionRepository = new SessionRepository() {
            @Override
            public Session save(Session session) {
                return session;
            }

            @Override
            public Optional<Session> findById(Long id) {
                return Optional.of(Session.reconstitute(
                        SESSION_ID,
                        1L,
                        "제목",
                        "INVITE01",
                        false,
                        SESSION_START,
                        SessionStatus.LIVE,
                        SessionAnalysisStatus.NOT_STARTED));
            }

            @Override
            public Optional<Session> findByInviteCode(String inviteCode) {
                return Optional.empty();
            }

            @Override
            public List<Session> findLiveStartedBefore(Instant startedBefore, int limit) {
                // 이 테스트는 만료 세션 조회를 쓰지 않는다(자동 종료 스케줄러 전용 경로).
                return List.of();
            }
        };
        SessionParticipantRepository participantRepository = new SessionParticipantRepository() {
            @Override
            public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
                return Optional.empty();
            }

            @Override
            public Optional<SessionParticipant> findById(Long id) {
                return Optional.ofNullable(participantsById.get(id));
            }

            @Override
            public List<SessionParticipant> findBySessionId(Long sessionId) {
                return participantsById.values().stream()
                        .filter(p -> p.sessionId().equals(sessionId))
                        .sorted((a, b) -> Long.compare(a.id(), b.id()))
                        .toList();
            }

            @Override
            public SessionParticipant save(SessionParticipant participant) {
                return participant;
            }
        };
        RecordingWebhookEventPort eventStore = new RecordingWebhookEventPort() {
            @Override
            public boolean begin(String eventId, String eventType, String payload) {
                if (processedEvents.contains(eventId)) {
                    return false;
                }
                seenEvents.add(eventId);
                return true;
            }

            @Override
            public void markProcessed(String eventId) {
                processedEvents.add(eventId);
            }
        };
        service = new RecordingWebhookService(
                (body, auth) -> {
                    if ("bad".equals(auth)) {
                        throw new InvalidWebhookSignatureException(new IllegalStateException("bad signature"));
                    }
                    return nextEvent;
                },
                eventStore,
                egressUseCase,
                recordingRepository,
                fileRepository,
                sessionRepository,
                participantRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void addParticipant(long id, SessionParticipantRole role) {
        participantsById.put(
                id, SessionParticipant.reconstitute(id, SESSION_ID, id + 500, role, SESSION_START, SESSION_START));
    }

    @Test
    void 서명이_틀리면_401_예외() {
        assertThrows(InvalidWebhookSignatureException.class, () -> service.process("{}", "bad"));
    }

    @Test
    void 이미_처리된_이벤트는_다시_처리하지_않는다() {
        addParticipant(1L, SessionParticipantRole.INSTRUCTOR);
        nextEvent = trackPublished("EV_1", "p-1", "TR_a", TrackSource.CAMERA);
        service.process("{}", "ok");
        service.process("{}", "ok");

        assertEquals(1, egressRequests.size());
    }

    @Test
    void 강사_track_published는_instructor_별칭으로_Egress를_요청한다() {
        addParticipant(1L, SessionParticipantRole.INSTRUCTOR);
        nextEvent = trackPublished("EV_2", "p-1", "TR_a", TrackSource.CAMERA);

        service.process("{}", "ok");

        assertEquals(1, egressRequests.size());
        assertEquals("instructor", egressRequests.get(0).recordingAlias());
        assertEquals(SESSION_ID, egressRequests.get(0).sessionId());
        assertTrue(processedEvents.contains("EV_2"));
    }

    @Test
    void 학생_별칭은_참가자_id_순서로_안정적으로_배정된다() {
        addParticipant(1L, SessionParticipantRole.INSTRUCTOR);
        addParticipant(2L, SessionParticipantRole.STUDENT);
        addParticipant(3L, SessionParticipantRole.STUDENT);
        nextEvent = trackPublished("EV_3", "p-3", "TR_m", TrackSource.MICROPHONE);

        service.process("{}", "ok");

        assertEquals("student-002", egressRequests.get(0).recordingAlias());
    }

    @Test
    void egress_ended_COMPLETE는_상태와_상대경로_offset_trackSid_파일을_저장한다() {
        recordingsByEgressId.put("EG_1", Recording.startTrack(10L, SESSION_ID, "EG_1", 1, SESSION_START));
        nextEvent = egressEvent(
                "EV_4",
                RecordingWebhookEventType.EGRESS_ENDED,
                "EG_1",
                Boolean.TRUE,
                List.of(new EgressFileResult(
                        "/srv/zani/recordings/100/raw/instructor/instructor-camera-TR_a.mp4",
                        SESSION_START.plusSeconds(10).toEpochMilli(),
                        SESSION_START.plusSeconds(70).toEpochMilli(),
                        1_000L)));

        service.process("{}", "ok");

        assertEquals(RecordingStatus.COMPLETE, recordingsByEgressId.get("EG_1").status());
        assertEquals(1, savedFiles.size());
        assertEquals(
                "raw/instructor/instructor-camera-TR_a.mp4", savedFiles.get(0).storageKey());
        assertEquals("TR_src", savedFiles.get(0).livekitTrackSid());
        assertEquals(10_000L, savedFiles.get(0).startedOffsetMs());
        assertEquals(70_000L, savedFiles.get(0).endedOffsetMs());
    }

    @Test
    void egress_ended가_두_번_처리돼도_파일은_한_번만_저장된다() {
        recordingsByEgressId.put("EG_R", Recording.startTrack(15L, SESSION_ID, "EG_R", 1, SESSION_START));
        List<EgressFileResult> files = List.of(new EgressFileResult(
                "/srv/zani/recordings/100/raw/instructor/f.mp4",
                SESSION_START.toEpochMilli(),
                SESSION_START.plusSeconds(5).toEpochMilli(),
                10L));
        // markProcessed 유실 등으로 같은 egress 종결이 다른 이벤트 id로 다시 도착해도 파일이 중복되면 안 된다.
        nextEvent = egressEvent("EV_R1", RecordingWebhookEventType.EGRESS_ENDED, "EG_R", Boolean.TRUE, files);
        service.process("{}", "ok");
        nextEvent = egressEvent("EV_R2", RecordingWebhookEventType.EGRESS_ENDED, "EG_R", Boolean.TRUE, files);
        service.process("{}", "ok");

        // 종결 전이는 한 번만 일어나므로(complete()가 false 반환) 파일도 한 번만 저장된다.
        assertEquals(1, savedFiles.size());
    }

    @Test
    void 세션_루트를_벗어난_파일_경로는_저장하지_않는다() {
        recordingsByEgressId.put("EG_P", Recording.startTrack(16L, SESSION_ID, "EG_P", 1, SESSION_START));
        nextEvent = egressEvent(
                "EV_P",
                RecordingWebhookEventType.EGRESS_ENDED,
                "EG_P",
                Boolean.TRUE,
                List.of(new EgressFileResult("/tmp/other-root/file.mp4", 0, 0, 10L)));

        service.process("{}", "ok");

        assertEquals(RecordingStatus.COMPLETE, recordingsByEgressId.get("EG_P").status());
        assertEquals(0, savedFiles.size());
    }

    @Test
    void 종결_상태가_아닌_egress_ended는_상태를_바꾸지_않는다() {
        recordingsByEgressId.put("EG_N", Recording.startTrack(17L, SESSION_ID, "EG_N", 1, SESSION_START));
        nextEvent = egressEvent("EV_N", RecordingWebhookEventType.EGRESS_ENDED, "EG_N", null, List.of());

        service.process("{}", "ok");

        assertEquals(RecordingStatus.STARTING, recordingsByEgressId.get("EG_N").status());
        assertEquals(0, savedFiles.size());
    }

    @Test
    void egress_ended_실패는_FAILED_상태를_저장한다() {
        recordingsByEgressId.put("EG_2", Recording.startTrack(11L, SESSION_ID, "EG_2", 1, SESSION_START));
        nextEvent = egressEvent("EV_5", RecordingWebhookEventType.EGRESS_ENDED, "EG_2", Boolean.FALSE, List.of());

        service.process("{}", "ok");

        assertEquals(RecordingStatus.FAILED, recordingsByEgressId.get("EG_2").status());
        assertEquals(0, savedFiles.size());
    }

    @Test
    void recordings_행이_아직_없으면_재전송을_위해_예외를_던진다() {
        nextEvent = egressEvent("EV_6", RecordingWebhookEventType.EGRESS_STARTED, "EG_missing", null, List.of());

        assertThrows(RecordingNotReadyException.class, () -> service.process("{}", "ok"));
        assertTrue(seenEvents.contains("EV_6"));
        // 처리 실패 → PROCESSED로 남지 않아 재전송에서 재처리된다.
        assertEquals(0, processedEvents.size());
    }

    @Test
    void egress_started는_상태를_RECORDING으로_올린다() {
        recordingsByEgressId.put("EG_3", Recording.startTrack(12L, SESSION_ID, "EG_3", 1, SESSION_START));
        nextEvent = egressEvent("EV_7", RecordingWebhookEventType.EGRESS_STARTED, "EG_3", null, List.of());

        service.process("{}", "ok");

        assertEquals(RecordingStatus.RECORDING, recordingsByEgressId.get("EG_3").status());
    }
}
