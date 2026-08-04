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
import com.a105.zani.recording.application.port.LiveKitWebhookEventPort;
import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.model.RecordingFile;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackRecordingDecision;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.domain.repository.RecordingFileRepository;
import com.a105.zani.recording.domain.repository.RecordingRepository;
import com.a105.zani.session.application.confirmconnection.ConfirmParticipantConnectionCommand;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionAnalysisStatus;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveKitWebhookServiceTest {

    private static final long SESSION_ID = 100L;
    // Egress 시작 시 recordings 에 보관되는 화자. egress_ended 가 이 값을 recording_files 로 옮긴다(S15P11A105-97).
    private static final long STUDENT_PARTICIPANT_ID = 300L;
    private static final Instant SESSION_START = Instant.parse("2026-07-25T05:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-25T06:00:00Z");

    private LiveKitWebhookEvent nextEvent;
    private final List<RequestTrackEgressCommand> egressRequests = new ArrayList<>();
    private final Map<String, Recording> recordingsByEgressId = new HashMap<>();
    private final List<RecordingFile> savedFiles = new ArrayList<>();
    private final Map<Long, SessionParticipant> participantsById = new HashMap<>();
    private final List<ConfirmParticipantConnectionCommand> confirmedConnections = new ArrayList<>();
    private final Set<String> processedEvents = new HashSet<>();
    private final Set<String> seenEvents = new HashSet<>();

    private LiveKitWebhookService service;

    private static LiveKitWebhookEvent trackPublished(
            String eventId, String identity, String trackSid, TrackSource source) {
        return new LiveKitWebhookEvent(
                eventId,
                LiveKitWebhookEventType.TRACK_PUBLISHED,
                SESSION_ID,
                identity,
                trackSid,
                source,
                null,
                null,
                null,
                null,
                List.of());
    }

    private static LiveKitWebhookEvent egressEvent(
            String eventId,
            LiveKitWebhookEventType type,
            String egressId,
            Boolean complete,
            List<EgressFileResult> files) {
        return new LiveKitWebhookEvent(
                eventId, type, SESSION_ID, null, null, null, egressId, complete, "TR_src", null, files);
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
                        null,
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
        LiveKitWebhookEventPort eventStore = new LiveKitWebhookEventPort() {
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
        service = new LiveKitWebhookService(
                (body, auth) -> {
                    if ("bad".equals(auth)) {
                        throw new InvalidWebhookSignatureException(new IllegalStateException("bad signature"));
                    }
                    return nextEvent;
                },
                eventStore,
                egressUseCase,
                confirmedConnections::add,
                recordingRepository,
                fileRepository,
                sessionRepository,
                participantRepository,
                audioStreamRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 코칭용 스트림 Egress 표시. 테스트가 직접 등록해 webhook 분기를 검증한다. */
    private final java.util.Set<String> audioStreamEgressIds = new java.util.HashSet<>();

    private final com.a105.zani.recording.application.port.AudioStreamEgressRegistryPort audioStreamRegistry =
            new com.a105.zani.recording.application.port.AudioStreamEgressRegistryPort() {
                @Override
                public void remember(String egressId, long sessionId) {
                    audioStreamEgressIds.add(egressId);
                }

                @Override
                public boolean isAudioStream(String egressId) {
                    return audioStreamEgressIds.contains(egressId);
                }
            };

    private void addParticipant(long id, SessionParticipantRole role) {
        participantsById.put(
                id, SessionParticipant.reconstitute(id, SESSION_ID, id + 500, role, SESSION_START, SESSION_START));
    }

    private static LiveKitWebhookEvent participantJoined(String eventId, String identity) {
        return new LiveKitWebhookEvent(
                eventId,
                LiveKitWebhookEventType.PARTICIPANT_JOINED,
                SESSION_ID,
                identity,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());
    }

    @Test
    void 서명이_틀리면_401_예외() {
        assertThrows(InvalidWebhookSignatureException.class, () -> service.process("{}", "bad"));
    }

    @Test
    void participant_joined는_연결_확정을_세션_도메인에_위임한다() {
        nextEvent = participantJoined("EV_join", "p-7");

        service.process("{}", "ok");

        assertEquals(1, confirmedConnections.size());
        assertEquals(SESSION_ID, confirmedConnections.get(0).sessionId());
        assertEquals(7L, confirmedConnections.get(0).participantId());
        assertEquals(NOW, confirmedConnections.get(0).connectedAt());
        assertTrue(processedEvents.contains("EV_join"));
    }

    @Test
    void 시스템_참가자의_participant_joined는_자격을_만들지_않는다() {
        // Egress·시스템 참가자는 p-{id} 규칙을 따르지 않는다. 이들에게 자격이 생기면 출석 인원이 부풀려진다.
        nextEvent = participantJoined("EV_egress", "EG_recorder");

        service.process("{}", "ok");

        assertTrue(confirmedConnections.isEmpty());
        // 처리할 것이 없다는 판단도 처리 완료다 — 그러지 않으면 LiveKit이 같은 통지를 계속 재전송한다.
        assertTrue(processedEvents.contains("EV_egress"));
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
        recordingsByEgressId.put(
                "EG_1",
                Recording.startTrack(
                        10L,
                        SESSION_ID,
                        "EG_1",
                        STUDENT_PARTICIPANT_ID,
                        TrackSource.MICROPHONE,
                        "TR_1",
                        1,
                        SESSION_START));
        nextEvent = egressEvent(
                "EV_4",
                LiveKitWebhookEventType.EGRESS_ENDED,
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
        // 화자·트랙 종류는 recordings 에 보관된 값을 그대로 옮긴다. 경로에는 익명 별칭만 있어 역추적할 수 없다(S15P11A105-97).
        assertEquals(STUDENT_PARTICIPANT_ID, savedFiles.get(0).sessionParticipantId());
        assertEquals(TrackSource.MICROPHONE, savedFiles.get(0).trackSource());
    }

    @Test
    void track_published는_검증된_참가자_id를_Egress_요청에_실어_보낸다() {
        addParticipant(7L, SessionParticipantRole.STUDENT);
        nextEvent = trackPublished("EV_P1", "p-7", "TR_p1", TrackSource.MICROPHONE);

        service.process("{}", "ok");

        assertEquals(1, egressRequests.size());
        assertEquals(7L, egressRequests.get(0).sessionParticipantId());
        assertEquals(TrackSource.MICROPHONE, egressRequests.get(0).source());
    }

    @Test
    void V12_이전_녹화의_종료_webhook은_화자가_없어도_파일을_저장하고_상태를_넘긴다() {
        // 배포 순간에 진행 중이던 Egress. recordings 의 신규 컬럼이 전부 null 이다.
        // 예외가 나가면 상태 저장 전에 끊겨 RECORDING 으로 남고 LiveKit 이 무한 재전송한다.
        recordingsByEgressId.put(
                "EG_LEGACY",
                Recording.reconstitute(
                        30L,
                        SESSION_ID,
                        "EG_LEGACY",
                        null,
                        null,
                        null,
                        Recording.TYPE_TRACK,
                        1,
                        RecordingStatus.RECORDING,
                        SESSION_START,
                        null));
        nextEvent = egressEvent(
                "EV_LEGACY",
                LiveKitWebhookEventType.EGRESS_ENDED,
                "EG_LEGACY",
                Boolean.TRUE,
                List.of(new EgressFileResult(
                        "/srv/zani/recordings/100/raw/participants/student-001/student-001-microphone-TR_old.ogg",
                        SESSION_START.plusSeconds(10).toEpochMilli(),
                        SESSION_START.plusSeconds(20).toEpochMilli(),
                        1_000L)));

        service.process("{}", "ok");

        assertEquals(
                RecordingStatus.COMPLETE, recordingsByEgressId.get("EG_LEGACY").status());
        assertEquals(1, savedFiles.size());
        assertNull(savedFiles.get(0).sessionParticipantId());
        assertNull(savedFiles.get(0).trackSource());
    }

    @Test
    void 같은_참가자가_트랙을_재발행하면_파일은_둘이지만_화자는_같다() {
        // 마이크 OFF·재접속으로 Track SID 가 바뀌어도 화자는 유지되어야 한다. Egress 는 SID 마다 별개 행이다.
        recordingsByEgressId.put(
                "EG_A",
                Recording.startTrack(
                        20L,
                        SESSION_ID,
                        "EG_A",
                        STUDENT_PARTICIPANT_ID,
                        TrackSource.MICROPHONE,
                        "TR_first",
                        1,
                        SESSION_START));
        recordingsByEgressId.put(
                "EG_B",
                Recording.startTrack(
                        21L,
                        SESSION_ID,
                        "EG_B",
                        STUDENT_PARTICIPANT_ID,
                        TrackSource.MICROPHONE,
                        "TR_second",
                        1,
                        SESSION_START));

        nextEvent = egressEvent(
                "EV_A",
                LiveKitWebhookEventType.EGRESS_ENDED,
                "EG_A",
                Boolean.TRUE,
                List.of(new EgressFileResult(
                        "/srv/zani/recordings/100/raw/participants/student-001/student-001-microphone-TR_first.ogg",
                        SESSION_START.plusSeconds(10).toEpochMilli(),
                        SESSION_START.plusSeconds(20).toEpochMilli(),
                        1_000L)));
        service.process("{}", "ok");

        nextEvent = egressEvent(
                "EV_B",
                LiveKitWebhookEventType.EGRESS_ENDED,
                "EG_B",
                Boolean.TRUE,
                List.of(new EgressFileResult(
                        "/srv/zani/recordings/100/raw/participants/student-001/student-001-microphone-TR_second.ogg",
                        SESSION_START.plusSeconds(40).toEpochMilli(),
                        SESSION_START.plusSeconds(50).toEpochMilli(),
                        1_000L)));
        service.process("{}", "ok");

        assertEquals(2, savedFiles.size());
        assertEquals(STUDENT_PARTICIPANT_ID, savedFiles.get(0).sessionParticipantId());
        assertEquals(STUDENT_PARTICIPANT_ID, savedFiles.get(1).sessionParticipantId());
        assertNotEquals(savedFiles.get(0).storageKey(), savedFiles.get(1).storageKey());
    }

    @Test
    void egress_ended가_두_번_처리돼도_파일은_한_번만_저장된다() {
        recordingsByEgressId.put(
                "EG_R",
                Recording.startTrack(
                        15L,
                        SESSION_ID,
                        "EG_R",
                        STUDENT_PARTICIPANT_ID,
                        TrackSource.MICROPHONE,
                        "TR_R",
                        1,
                        SESSION_START));
        List<EgressFileResult> files = List.of(new EgressFileResult(
                "/srv/zani/recordings/100/raw/instructor/f.mp4",
                SESSION_START.toEpochMilli(),
                SESSION_START.plusSeconds(5).toEpochMilli(),
                10L));
        // markProcessed 유실 등으로 같은 egress 종결이 다른 이벤트 id로 다시 도착해도 파일이 중복되면 안 된다.
        nextEvent = egressEvent("EV_R1", LiveKitWebhookEventType.EGRESS_ENDED, "EG_R", Boolean.TRUE, files);
        service.process("{}", "ok");
        nextEvent = egressEvent("EV_R2", LiveKitWebhookEventType.EGRESS_ENDED, "EG_R", Boolean.TRUE, files);
        service.process("{}", "ok");

        // 종결 전이는 한 번만 일어나므로(complete()가 false 반환) 파일도 한 번만 저장된다.
        assertEquals(1, savedFiles.size());
    }

    @Test
    void 세션_루트를_벗어난_파일_경로는_저장하지_않는다() {
        recordingsByEgressId.put(
                "EG_P",
                Recording.startTrack(
                        16L,
                        SESSION_ID,
                        "EG_P",
                        STUDENT_PARTICIPANT_ID,
                        TrackSource.MICROPHONE,
                        "TR_P",
                        1,
                        SESSION_START));
        nextEvent = egressEvent(
                "EV_P",
                LiveKitWebhookEventType.EGRESS_ENDED,
                "EG_P",
                Boolean.TRUE,
                List.of(new EgressFileResult("/tmp/other-root/file.mp4", 0, 0, 10L)));

        service.process("{}", "ok");

        assertEquals(RecordingStatus.COMPLETE, recordingsByEgressId.get("EG_P").status());
        assertEquals(0, savedFiles.size());
    }

    @Test
    void 종결_상태가_아닌_egress_ended는_상태를_바꾸지_않는다() {
        recordingsByEgressId.put(
                "EG_N",
                Recording.startTrack(
                        17L,
                        SESSION_ID,
                        "EG_N",
                        STUDENT_PARTICIPANT_ID,
                        TrackSource.MICROPHONE,
                        "TR_N",
                        1,
                        SESSION_START));
        nextEvent = egressEvent("EV_N", LiveKitWebhookEventType.EGRESS_ENDED, "EG_N", null, List.of());

        service.process("{}", "ok");

        assertEquals(RecordingStatus.STARTING, recordingsByEgressId.get("EG_N").status());
        assertEquals(0, savedFiles.size());
    }

    @Test
    void egress_ended_실패는_FAILED_상태를_저장한다() {
        recordingsByEgressId.put(
                "EG_2",
                Recording.startTrack(
                        11L,
                        SESSION_ID,
                        "EG_2",
                        STUDENT_PARTICIPANT_ID,
                        TrackSource.MICROPHONE,
                        "TR_2",
                        1,
                        SESSION_START));
        nextEvent = egressEvent("EV_5", LiveKitWebhookEventType.EGRESS_ENDED, "EG_2", Boolean.FALSE, List.of());

        service.process("{}", "ok");

        assertEquals(RecordingStatus.FAILED, recordingsByEgressId.get("EG_2").status());
        assertEquals(0, savedFiles.size());
    }

    @Test
    void recordings_행이_아직_없으면_재전송을_위해_예외를_던진다() {
        nextEvent = egressEvent("EV_6", LiveKitWebhookEventType.EGRESS_STARTED, "EG_missing", null, List.of());

        assertThrows(RecordingNotReadyException.class, () -> service.process("{}", "ok"));
        assertTrue(seenEvents.contains("EV_6"));
        // 처리 실패 → PROCESSED로 남지 않아 재전송에서 재처리된다.
        assertEquals(0, processedEvents.size());
    }

    @Test
    void 코칭용_스트림_egress_이벤트는_녹화_처리_없이_종결한다() {
        // 스트림 Egress 는 파일을 만들지 않아 recordings 행이 없다. 위 테스트처럼 재전송을 유도하면
        // 행이 영원히 생기지 않아 LiveKit 이 무한 재전송한다.
        audioStreamEgressIds.add("EG_ws");
        nextEvent = egressEvent("EV_ws_1", LiveKitWebhookEventType.EGRESS_STARTED, "EG_ws", null, List.of());

        service.process("{}", "ok");

        assertTrue(processedEvents.contains("EV_ws_1"), "재전송되지 않도록 PROCESSED로 종결해야 한다");
    }

    @Test
    void 표시가_없어도_페이로드의_출력_종류로_스트림_egress를_가려낸다() {
        // Redis 가 죽어 remember 가 조용히 실패하면 표시가 영영 남지 않는다. 표시가 유일한 근거였을 때는
        // 그 Egress 의 모든 후속 이벤트가 5xx 로 떨어져 LiveKit 이 무한 재전송했다.
        audioStreamEgressIds.clear();
        nextEvent = audioStreamEgressEvent("EV_ws_payload", "EG_ws_payload");

        service.process("{}", "ok");

        assertTrue(processedEvents.contains("EV_ws_payload"), "표시 없이 페이로드만으로 종결해야 한다");
    }

    @Test
    void 페이로드가_파일_출력이라고_알려주면_표시가_있어도_녹화로_처리한다() {
        // 1차 근거가 2차 근거를 덮어야 한다. 반대로 동작하면 낡은 표시 하나가 정상 녹화를 통째로 건너뛴다.
        audioStreamEgressIds.add("EG_file");
        nextEvent = new LiveKitWebhookEvent(
                "EV_file_wins",
                LiveKitWebhookEventType.EGRESS_STARTED,
                SESSION_ID,
                null,
                null,
                null,
                "EG_file",
                null,
                "TR_src",
                false,
                List.of());

        // 녹화 경로로 들어갔다면 recordings 행이 없으므로 재전송을 유도하는 예외가 나야 한다.
        // 표시를 우선했다면 조용히 PROCESSED 로 끝나 버려 정상 녹화가 통째로 누락된다.
        assertThrows(RecordingNotReadyException.class, () -> service.process("{}", "ok"));
        assertFalse(processedEvents.contains("EV_file_wins"), "파일 Egress 는 녹화 경로로 가야 한다");
    }

    /** 페이로드가 WebSocket 출력이라고 알려주는 egress 이벤트. */
    private LiveKitWebhookEvent audioStreamEgressEvent(String eventId, String egressId) {
        return new LiveKitWebhookEvent(
                eventId,
                LiveKitWebhookEventType.EGRESS_STARTED,
                SESSION_ID,
                null,
                null,
                null,
                egressId,
                null,
                "TR_src",
                true,
                List.of());
    }

    @Test
    void 코칭용_스트림_egress_종료_이벤트도_파일을_만들지_않는다() {
        audioStreamEgressIds.add("EG_ws");
        nextEvent = egressEvent(
                "EV_ws_2",
                LiveKitWebhookEventType.EGRESS_ENDED,
                "EG_ws",
                Boolean.TRUE,
                List.of(new EgressFileResult(
                        "/srv/zani/recordings/100/raw/instructor/x.ogg",
                        SESSION_START.toEpochMilli(),
                        SESSION_START.plusSeconds(10).toEpochMilli(),
                        1_000L)));

        service.process("{}", "ok");

        assertTrue(processedEvents.contains("EV_ws_2"));
        assertTrue(savedFiles.isEmpty(), "스트림 Egress 는 녹화 파일을 남기지 않는다");
    }

    @Test
    void egress_started는_상태를_RECORDING으로_올린다() {
        recordingsByEgressId.put(
                "EG_3",
                Recording.startTrack(
                        12L,
                        SESSION_ID,
                        "EG_3",
                        STUDENT_PARTICIPANT_ID,
                        TrackSource.MICROPHONE,
                        "TR_3",
                        1,
                        SESSION_START));
        nextEvent = egressEvent("EV_7", LiveKitWebhookEventType.EGRESS_STARTED, "EG_3", null, List.of());

        service.process("{}", "ok");

        assertEquals(RecordingStatus.RECORDING, recordingsByEgressId.get("EG_3").status());
    }
}
