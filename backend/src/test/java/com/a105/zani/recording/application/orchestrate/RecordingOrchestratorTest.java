package com.a105.zani.recording.application.orchestrate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.port.IssuedTrackEgress;
import com.a105.zani.recording.application.port.NewRecordingOutboxMessage;
import com.a105.zani.recording.application.port.PendingRecordingOutboxMessage;
import com.a105.zani.recording.application.port.RecordingOutboxPort;
import com.a105.zani.recording.application.port.TrackEgressPort;
import com.a105.zani.recording.application.port.TrackEgressRequest;
import com.a105.zani.recording.domain.exception.ForbiddenStudentCameraTrackException;
import com.a105.zani.recording.domain.exception.InvalidRecordingAliasException;
import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackRecordingDecision;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.domain.repository.RecordingRepository;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingOrchestratorTest {

    private static final long SESSION_ID = 100L;
    private static final long PARTICIPANT_ID = 300L;
    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    private final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    private final FakeTrackEgressPort egressPort = new FakeTrackEgressPort();
    private final InMemoryRecordingRepository recordings = new InMemoryRecordingRepository();
    private final InMemoryAudioStreamEgressRegistry audioStreamRegistry = new InMemoryAudioStreamEgressRegistry();
    private final RecordingOrchestrator orchestrator = new RecordingOrchestrator(
            outbox, egressPort, recordings, audioStreamRegistry, Clock.fixed(NOW, ZoneOffset.UTC));

    private RequestTrackEgressCommand command(SessionParticipantRole role, TrackSource source, String trackSid) {
        String alias = role == SessionParticipantRole.INSTRUCTOR ? "instructor" : "student-001";
        return new RequestTrackEgressCommand(SESSION_ID, trackSid, alias, role, source, PARTICIPANT_ID);
    }

    @Test
    void 저장_대상_트랙은_outbox에_등록된다() {
        RequestTrackEgressResult result =
                orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_a"));

        assertEquals(TrackRecordingDecision.RECORD, result.decision());
        assertTrue(result.enqueued());
        assertEquals(1, outbox.rows.size());
    }

    @Test
    void 같은_트랙의_중복_요청은_한_번만_등록된다() {
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_a"));
        RequestTrackEgressResult second =
                orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_a"));

        assertFalse(second.enqueued());
        assertEquals(1, outbox.rows.size());
    }

    @Test
    void 학생_카메라는_거부되고_outbox에_남지_않는다() {
        assertThrows(
                ForbiddenStudentCameraTrackException.class,
                () -> orchestrator.request(command(SessionParticipantRole.STUDENT, TrackSource.CAMERA, "TR_c")));
        assertEquals(0, outbox.rows.size());
    }

    @Test
    void 학생_화면공유는_영상과_오디오가_모두_등록된다() {
        RequestTrackEgressResult video =
                orchestrator.request(command(SessionParticipantRole.STUDENT, TrackSource.SCREEN_SHARE, "TR_s"));
        RequestTrackEgressResult audio =
                orchestrator.request(command(SessionParticipantRole.STUDENT, TrackSource.SCREEN_SHARE_AUDIO, "TR_sa"));

        assertEquals(TrackRecordingDecision.RECORD, video.decision());
        assertEquals(TrackRecordingDecision.RECORD, audio.decision());
        assertTrue(video.enqueued());
        assertTrue(audio.enqueued());
        assertEquals(2, outbox.rows.size());
    }

    @Test
    void 별칭_형식이_아니거나_역할과_어긋나면_등록을_거부한다() {
        // 실명 등 비별칭 identity → 경로 유입 차단
        assertThrows(
                InvalidRecordingAliasException.class,
                () -> orchestrator.request(new RequestTrackEgressCommand(
                        SESSION_ID,
                        "TR_a",
                        "김태정",
                        SessionParticipantRole.STUDENT,
                        TrackSource.MICROPHONE,
                        PARTICIPANT_ID)));
        // 별칭·역할 불일치
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> orchestrator.request(new RequestTrackEgressCommand(
                        SESSION_ID,
                        "TR_a",
                        "instructor",
                        SessionParticipantRole.STUDENT,
                        TrackSource.MICROPHONE,
                        PARTICIPANT_ID)));
        // trackSid 형식 위반
        assertThrows(
                InvalidRecordingTrackException.class,
                () -> orchestrator.request(new RequestTrackEgressCommand(
                        SESSION_ID,
                        "../etc",
                        "student-001",
                        SessionParticipantRole.STUDENT,
                        TrackSource.MICROPHONE,
                        PARTICIPANT_ID)));
        assertEquals(0, outbox.rows.size());
    }

    @Test
    void 릴레이는_claim에_성공한_행만_처리하고_recordings_행을_남긴다() {
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.MICROPHONE, "TR_m"));

        int processed = orchestrator.relayPendingOutbox();

        // 강사 마이크는 파일 Egress 와 코칭 스트림 Egress 두 작업을 만든다.
        assertEquals(2, processed);
        assertEquals(1, egressPort.requests.size());
        assertEquals("TR_m", egressPort.requests.get(0).trackSid());
        Recording saved = recordings.saved.get(0);
        assertEquals("EG_1", saved.livekitEgressId());
        assertEquals(RecordingStatus.STARTING, saved.status());
        assertEquals(1, saved.attemptNumber());
        assertEquals(NOW, saved.startedAt());
        assertEquals("COMPLETED", outbox.statusOf("track:100:TR_m"));
    }

    @Test
    void 강사_마이크는_코칭용_오디오_스트림_Egress도_시작한다() {
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.MICROPHONE, "TR_m"));

        orchestrator.relayPendingOutbox();

        assertEquals(1, egressPort.audioStreamRequests.size());
        assertEquals("TR_m", egressPort.audioStreamRequests.get(0).trackSid());
        assertEquals("COMPLETED", outbox.statusOf("audio-stream:100:TR_m"));
        // 녹화 행은 파일 Egress 한 건만 남는다. 스트림은 메모리 버퍼로 흘러가 남길 산출물이 없다.
        assertEquals(1, recordings.saved.size());
    }

    @Test
    void 강사_카메라와_학생_마이크는_오디오_스트림_Egress를_만들지_않는다() {
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_c"));
        orchestrator.request(command(SessionParticipantRole.STUDENT, TrackSource.MICROPHONE, "TR_s"));

        orchestrator.relayPendingOutbox();

        // 코칭은 강사 발화만 대상으로 한다.
        assertEquals(0, egressPort.audioStreamRequests.size());
    }

    @Test
    void 다른_릴레이가_이미_claim한_행은_건너뛴다() {
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.MICROPHONE, "TR_m"));
        outbox.preClaimAll(); // 다른 인스턴스가 선점한 상황

        int processed = orchestrator.relayPendingOutbox();

        assertEquals(0, processed);
        assertEquals(0, egressPort.requests.size());
    }

    @Test
    void Egress_시작_후_저장이_실패하면_재시도하지_않고_egressId를_남긴다() {
        // 재시도하면 같은 트랙에 두 번째 Egress가 붙으므로, 이 작업은 즉시 FAILED로 종결돼야 한다.
        recordings.failSave = true;
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_orphan"));

        orchestrator.relayPendingOutbox();
        outbox.makeAllDueNow();
        orchestrator.relayPendingOutbox();

        assertEquals(1, egressPort.requests.size());
        assertEquals("FAILED", outbox.statusOf("track:100:TR_orphan"));
        assertTrue(outbox.errorOf("track:100:TR_orphan").contains("EG_1"));
    }

    @Test
    void 완료_표시가_실패해도_재실행_시_Egress를_다시_시작하지_않는다() {
        // markCompleted가 실패하면 행이 IN_PROGRESS로 남고 lease 만료 후 다시 소비된다.
        // 그때 handle이 기존 Egress를 채택해야 하므로 외부 시작은 한 번만 일어나야 한다.
        outbox.failMarkCompleted = true;
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_mc"));
        orchestrator.relayPendingOutbox();

        outbox.failMarkCompleted = false;
        outbox.requeueAll();
        orchestrator.relayPendingOutbox();

        assertEquals(1, egressPort.requests.size());
        assertEquals(1, recordings.saved.size());
        assertEquals("COMPLETED", outbox.statusOf("track:100:TR_mc"));
    }

    @Test
    void 완료_표시가_실패해도_재실행_시_스트림_Egress를_다시_시작하지_않는다() {
        // 같은 트랙에 스트림 Egress 가 두 개 붙으면 두 PCM 이 한 링버퍼에 뒤섞이고, 누적 바이트가 경과 시간을
        // 앞질러 무음 패딩이 영구히 멈춘다. 예외도 로그도 없이 조용히 틀리는 종류라 재실행 경로를 못 박는다.
        outbox.failMarkCompleted = true;
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.MICROPHONE, "TR_ws"));
        orchestrator.relayPendingOutbox();
        assertEquals(1, egressPort.audioStreamRequests.size());

        outbox.failMarkCompleted = false;
        outbox.requeueAll();
        orchestrator.relayPendingOutbox();

        assertEquals(1, egressPort.audioStreamRequests.size(), "살아 있는 스트림 Egress 를 채택해야 한다");
        assertEquals("COMPLETED", outbox.statusOf("audio-stream:100:TR_ws"));
    }

    @Test
    void 채택한_스트림_Egress도_웹훅_필터에_다시_표시한다() {
        // 표시가 TTL 로 사라졌거나 첫 저장이 실패했을 수 있다. 표시가 없으면 웹훅이 "녹화 미준비" 503 을 돌려
        // LiveKit 이 무한 재전송한다.
        outbox.failMarkCompleted = true;
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.MICROPHONE, "TR_re"));
        orchestrator.relayPendingOutbox();
        audioStreamRegistry.egressIds.clear();

        outbox.failMarkCompleted = false;
        outbox.requeueAll();
        orchestrator.relayPendingOutbox();

        assertTrue(audioStreamRegistry.isAudioStream("EG_WS_1"), "채택한 Egress 도 표시돼야 한다");
    }

    @Test
    void 스트림_Egress가_종료됐으면_재실행이_새로_시작한다() {
        // 종료된 실행을 채택하면 흐름이 끊긴 상태로 굳어 그 세션은 코칭 오디오를 영구히 받지 못한다.
        // 중복 유입이 해로운 구간은 실행이 살아 있을 때뿐이므로, 죽었으면 새로 시작해야 한다.
        outbox.failMarkCompleted = true;
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.MICROPHONE, "TR_dead"));
        orchestrator.relayPendingOutbox();
        egressPort.audioStreamEnded("TR_dead");

        outbox.failMarkCompleted = false;
        outbox.requeueAll();
        orchestrator.relayPendingOutbox();

        assertEquals(2, egressPort.audioStreamRequests.size(), "죽은 스트림은 채택하지 않는다");
    }

    @Test
    void Egress_실패는_백오프와_함께_재시도로_남긴다() {
        egressPort.failWith = new IllegalStateException("livekit down");
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_f"));

        orchestrator.relayPendingOutbox();

        assertEquals("PENDING", outbox.statusOf("track:100:TR_f"));
        assertEquals(1, outbox.attemptOf("track:100:TR_f"));
        // 1회 실패 → 30초 뒤 재시도
        assertEquals(NOW.plus(Duration.ofSeconds(30)), outbox.nextAttemptOf("track:100:TR_f"));
        assertEquals(0, recordings.saved.size());
    }

    @Test
    void 백오프가_지나기_전에는_재시도하지_않는다() {
        egressPort.failWith = new IllegalStateException("livekit down");
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_b"));
        orchestrator.relayPendingOutbox(); // 실패 → next_attempt_at = NOW+30s

        int processed = orchestrator.relayPendingOutbox(); // 같은 시각(NOW) 재실행

        assertEquals(0, processed);
        assertEquals(1, outbox.attemptOf("track:100:TR_b"));
    }

    @Test
    void 재시도_상한을_넘으면_FAILED로_남긴다() {
        egressPort.failWith = new IllegalStateException("livekit down");
        orchestrator.request(command(SessionParticipantRole.INSTRUCTOR, TrackSource.CAMERA, "TR_x"));

        for (int i = 0; i < 5; i++) {
            outbox.makeAllDueNow(); // 테스트에서는 백오프 시간을 건너뛴다
            orchestrator.relayPendingOutbox();
        }

        assertEquals("FAILED", outbox.statusOf("track:100:TR_x"));
        assertEquals(5, outbox.attemptOf("track:100:TR_x"));
    }

    private static final class Row {
        final PendingRecordingOutboxMessage message;
        String status = "PENDING";
        int attemptCount = 0;
        String lastError;
        Instant nextAttemptAt;

        Row(PendingRecordingOutboxMessage message, Instant nextAttemptAt) {
            this.message = message;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    /** 실제 어댑터의 INSERT IGNORE·claim UPDATE 시맨틱을 반영한 인메모리 fake. */
    private static final class InMemoryOutboxStore implements RecordingOutboxPort {

        private final Map<String, Row> rows = new LinkedHashMap<>();
        private final Map<Long, Row> byId = new HashMap<>();
        private long nextId = 1;

        @Override
        public boolean enqueue(NewRecordingOutboxMessage message) {
            if (rows.containsKey(message.dedupKey())) {
                return false;
            }
            long id = nextId++;
            Row row = new Row(
                    new PendingRecordingOutboxMessage(
                            id, message.dedupKey(), message.type(), message.sessionId(), message.payload(), 0),
                    Instant.EPOCH);
            rows.put(message.dedupKey(), row);
            byId.put(id, row);
            return true;
        }

        @Override
        public List<PendingRecordingOutboxMessage> fetchDue(int limit, Instant now) {
            List<PendingRecordingOutboxMessage> due = new ArrayList<>();
            for (Row row : rows.values()) {
                if ("PENDING".equals(row.status) && !row.nextAttemptAt.isAfter(now) && due.size() < limit) {
                    due.add(new PendingRecordingOutboxMessage(
                            row.message.id(),
                            row.message.dedupKey(),
                            row.message.type(),
                            row.message.sessionId(),
                            row.message.payload(),
                            row.attemptCount));
                }
            }
            return due;
        }

        @Override
        public boolean claim(Long id, Instant now) {
            Row row = byId.get(id);
            if (!"PENDING".equals(row.status)) {
                return false;
            }
            row.status = "IN_PROGRESS";
            row.attemptCount++;
            return true;
        }

        @Override
        public void requeueExpiredClaims(Instant cutoff, Instant now) {
            // 인메모리 fake에서는 lease 만료 시나리오를 다루지 않는다(영속 어댑터 계약).
        }

        private boolean failMarkCompleted;

        @Override
        public void markCompleted(Long id) {
            if (failMarkCompleted) {
                throw new IllegalStateException("db down while marking completed");
            }
            byId.get(id).status = "COMPLETED";
        }

        /** lease 만료로 다시 소비되는 상황을 재현한다. */
        void requeueAll() {
            rows.values().forEach(row -> {
                if ("IN_PROGRESS".equals(row.status)) {
                    row.status = "PENDING";
                    row.nextAttemptAt = Instant.EPOCH;
                }
            });
        }

        @Override
        public void markRetry(Long id, String error, Instant nextAttemptAt) {
            Row row = byId.get(id);
            row.status = "PENDING";
            row.nextAttemptAt = nextAttemptAt;
        }

        @Override
        public void markFailed(Long id, String error) {
            Row row = byId.get(id);
            row.status = "FAILED";
            row.lastError = error;
        }

        void preClaimAll() {
            rows.values().forEach(row -> row.status = "IN_PROGRESS");
        }

        void makeAllDueNow() {
            rows.values().forEach(row -> row.nextAttemptAt = Instant.EPOCH);
        }

        String statusOf(String dedupKey) {
            return rows.get(dedupKey).status;
        }

        int attemptOf(String dedupKey) {
            return rows.get(dedupKey).attemptCount;
        }

        Instant nextAttemptOf(String dedupKey) {
            return rows.get(dedupKey).nextAttemptAt;
        }

        String errorOf(String dedupKey) {
            return rows.get(dedupKey).lastError;
        }
    }

    private static final class InMemoryAudioStreamEgressRegistry
            implements com.a105.zani.recording.application.port.AudioStreamEgressRegistryPort {

        private final java.util.Set<String> egressIds = new java.util.HashSet<>();

        @Override
        public void remember(String egressId, long sessionId) {
            egressIds.add(egressId);
        }

        @Override
        public boolean isAudioStream(String egressId) {
            return egressIds.contains(egressId);
        }
    }

    private static final class FakeTrackEgressPort implements TrackEgressPort {

        private final List<TrackEgressRequest> requests = new ArrayList<>();
        private final List<com.a105.zani.recording.application.port.AudioStreamEgressRequest> audioStreamRequests =
                new ArrayList<>();
        private final Map<String, String> egressByTrackSid = new HashMap<>();
        /** 아직 살아 있는 스트림 Egress. 종료된 실행은 담지 않는다(실제 어댑터가 상태로 걸러낸다). */
        private final Map<String, String> liveAudioStreamByTrackSid = new HashMap<>();

        private RuntimeException failWith;

        @Override
        public IssuedTrackEgress startAudioStream(
                com.a105.zani.recording.application.port.AudioStreamEgressRequest request) {
            if (failWith != null) {
                throw failWith;
            }
            audioStreamRequests.add(request);
            String egressId = "EG_WS_" + audioStreamRequests.size();
            liveAudioStreamByTrackSid.put(request.trackSid(), egressId);
            return new IssuedTrackEgress(egressId);
        }

        @Override
        public IssuedTrackEgress start(TrackEgressRequest request) {
            if (failWith != null) {
                throw failWith;
            }
            requests.add(request);
            String egressId = "EG_" + requests.size();
            egressByTrackSid.put(request.trackSid(), egressId);
            return new IssuedTrackEgress(egressId);
        }

        @Override
        public java.util.Optional<String> findExistingEgressId(TrackEgressRequest request) {
            // 실제 어댑터처럼 종료된 실행도 포함해 되돌린다(한 번 시작하면 계속 조회된다).
            return java.util.Optional.ofNullable(egressByTrackSid.get(request.trackSid()));
        }

        @Override
        public java.util.Optional<String> findLiveAudioStreamEgressId(
                com.a105.zani.recording.application.port.AudioStreamEgressRequest request) {
            return java.util.Optional.ofNullable(liveAudioStreamByTrackSid.get(request.trackSid()));
        }

        /** 스트림 Egress 가 종료된 상황. 재실행이 채택하지 않고 새로 시작해야 한다. */
        private void audioStreamEnded(String trackSid) {
            liveAudioStreamByTrackSid.remove(trackSid);
        }
    }

    private static final class InMemoryRecordingRepository implements RecordingRepository {

        private final List<Recording> saved = new ArrayList<>();
        private boolean failSave;

        @Override
        public Recording save(Recording recording) {
            if (failSave) {
                throw new IllegalStateException("db down");
            }
            saved.add(recording);
            return recording;
        }

        @Override
        public java.util.Optional<Recording> findByLivekitEgressId(String livekitEgressId) {
            return saved.stream()
                    .filter(recording -> recording.livekitEgressId().equals(livekitEgressId))
                    .findFirst();
        }
    }
}
