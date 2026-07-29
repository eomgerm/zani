package com.a105.zani.recording;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.recording.application.exception.TrackEgressUnavailableException;
import com.a105.zani.recording.application.orchestrate.RelayRecordingOutboxUseCase;
import com.a105.zani.recording.application.port.IssuedTrackEgress;
import com.a105.zani.recording.application.port.RecordingWebhookVerifierPort;
import com.a105.zani.recording.application.port.TrackEgressPort;
import com.a105.zani.recording.application.port.TrackEgressRequest;
import com.a105.zani.recording.application.webhook.EgressFileResult;
import com.a105.zani.recording.application.webhook.ProcessRecordingWebhookUseCase;
import com.a105.zani.recording.application.webhook.RecordingWebhookEvent;
import com.a105.zani.recording.application.webhook.RecordingWebhookEventType;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 녹화 파이프라인 통합 검증(S15P11A105-72). 실제 MySQL(outbox·recordings·recording_files·webhook 이벤트)과 실제 orchestrator·webhook 서비스를
 * 그대로 구동하고, LiveKit 경계(Egress 시작·webhook 서명 검증)만 fixture로 대체한다. S3/LocalStack은 팀 결정에 따라 쓰지 않으므로(EC2 로컬 저장) 파일은 경로 문자열로만
 * 검증한다.
 *
 * <p>검증 시나리오: 시작 중복, callback 순서 역전, 일부 실패, 전체 실패. 시간 의존 로직(재시도 백오프)은 고정 시계를 주입해 제어하고, 릴레이 스케줄러는 간격을 늘려 테스트가 명시적으로만
 * 릴레이를 돌리게 한다. 로컬 MySQL/Redis가 떠 있어야 통과한다.
 */
@SpringBootTest(properties = "recording.outbox-relay-delay=PT1H")
@Import(RecordingIntegrationTest.LiveKitFixtureConfig.class)
class RecordingIntegrationTest {

    private static final Instant SESSION_START = Instant.parse("2026-07-25T05:00:00Z");
    private static final String INSTRUCTOR_IDENTITY_PREFIX = "p-";

    @Autowired
    private ProcessRecordingWebhookUseCase webhookUseCase;

    @Autowired
    private RelayRecordingOutboxUseCase relayUseCase;

    @Autowired
    private FakeTrackEgressPort egressFixture;

    @Autowired
    private FakeWebhookVerifier webhookFixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sessionId;
    private long instructorParticipantId;
    private long memberId;

    @BeforeEach
    void setUp() {
        egressFixture.reset();
        // outbox 행의 next_attempt_at은 어댑터가 실제 시각으로 기록한다. 테스트 시계를 그보다 조금 앞세워야
        // 방금 등록한 행이 릴레이 대상(due)이 된다. 백오프 검증은 이 시계를 더 앞당겨 제어한다.
        clock.setInstant(Instant.now().plusSeconds(60));

        sessionId = TsidGenerator.generate();
        instructorParticipantId = TsidGenerator.generate();
        memberId = TsidGenerator.generate();
        insertMember(memberId);
        insertSession(sessionId, memberId);
        insertParticipant(instructorParticipantId, sessionId, memberId, SessionParticipantRole.INSTRUCTOR);
    }

    @AfterEach
    void tearDown() {
        // 실제 DB를 쓰므로 이 테스트가 만든 행만 정리한다(storage_key UNIQUE·invite_code UNIQUE 충돌 방지).
        jdbcTemplate.update("DELETE FROM recording_files WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM recordings WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM recording_outbox WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM members WHERE id = ?", memberId);
        jdbcTemplate.update("DELETE FROM recording_webhook_events WHERE event_id LIKE ?", sessionId + "-%");
    }

    private void insertMember(long id) {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                "it-google-" + id,
                "it-" + id + "@zani.local",
                "통합테스트 강사",
                java.sql.Timestamp.from(SESSION_START),
                java.sql.Timestamp.from(SESSION_START));
    }

    private void insertSession(long id, long hostMemberId) {
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 'LIVE', 'NOT_STARTED', ?, ?, ?)",
                id,
                hostMemberId,
                "통합 테스트 세션",
                String.format("IT%06d", Math.floorMod(id, 1_000_000)),
                java.sql.Timestamp.from(SESSION_START),
                java.sql.Timestamp.from(SESSION_START),
                java.sql.Timestamp.from(SESSION_START));
    }

    private void insertParticipant(long id, long sessionId, long memberId, SessionParticipantRole role) {
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                sessionId,
                memberId,
                role.name(),
                java.sql.Timestamp.from(SESSION_START),
                java.sql.Timestamp.from(SESSION_START),
                java.sql.Timestamp.from(SESSION_START));
    }

    private void publishTrack(String eventId, long participantId, String trackSid, TrackSource source) {
        webhookFixture.nextEvent = new RecordingWebhookEvent(
                eventKey(eventId),
                RecordingWebhookEventType.TRACK_PUBLISHED,
                sessionId,
                INSTRUCTOR_IDENTITY_PREFIX + participantId,
                trackSid,
                source,
                null,
                null,
                null,
                null,
                List.of(),
                SESSION_START);
        webhookUseCase.process("{}", "signature");
    }

    private void egressCallback(
            String eventId,
            RecordingWebhookEventType type,
            String egressId,
            Boolean complete,
            String trackSid,
            List<EgressFileResult> files) {
        webhookFixture.nextEvent = new RecordingWebhookEvent(
                eventKey(eventId),
                type,
                sessionId,
                null,
                null,
                null,
                egressId,
                complete,
                trackSid,
                null,
                files,
                SESSION_START);
        webhookUseCase.process("{}", "signature");
    }

    /** 이벤트 id는 실행마다 유일해야 한다(같은 id는 중복 이벤트로 걸러지므로). */
    private String eventKey(String name) {
        return sessionId + "-" + name;
    }

    private EgressFileResult file(String name, long startOffsetSeconds, long endOffsetSeconds) {
        return new EgressFileResult(
                "/srv/zani/recordings/" + sessionId + "/raw/instructor/" + name,
                SESSION_START.plusSeconds(startOffsetSeconds).toEpochMilli(),
                SESSION_START.plusSeconds(endOffsetSeconds).toEpochMilli(),
                1_024L);
    }

    private int outboxCount(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recording_outbox WHERE session_id = ? AND status = ?",
                Integer.class,
                sessionId,
                status);
    }

    private String recordingStatus(String egressId) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM recordings WHERE session_id = ? AND livekit_egress_id = ?",
                String.class,
                sessionId,
                egressId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private int recordingCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recordings WHERE session_id = ?", Integer.class, sessionId);
    }

    private List<String> fileKeys() {
        return jdbcTemplate.queryForList(
                "SELECT storage_key FROM recording_files WHERE session_id = ? ORDER BY storage_key",
                String.class,
                sessionId);
    }

    /** 백오프를 넘겨 재시도를 소진시킨다(지수 백오프 최대 4분). */
    private void exhaustRelayRetries() {
        for (int attempt = 0; attempt < 5; attempt++) {
            relayUseCase.relayPendingOutbox();
            clock.advanceSeconds(600);
        }
    }

    @Test
    void 같은_트랙의_track_published가_중복돼도_Egress는_한_번만_시작된다() {
        // 시작 중복: 같은 트랙에 대해 서로 다른 이벤트 id로 두 번 도착 + 릴레이도 두 번 실행.
        publishTrack("EV-dup-1", instructorParticipantId, "TR_dup", TrackSource.CAMERA);
        publishTrack("EV-dup-2", instructorParticipantId, "TR_dup", TrackSource.CAMERA);

        relayUseCase.relayPendingOutbox();
        relayUseCase.relayPendingOutbox();

        // dedup key UNIQUE가 중복 등록을, claim이 중복 수행을 막는다.
        assertEquals(1, egressFixture.requests.size());
        assertEquals("TR_dup", egressFixture.requests.get(0).trackSid());
        assertEquals(1, recordingCount());
        assertEquals("STARTING", recordingStatus("EG-TR_dup"));
        assertEquals(1, outboxCount("COMPLETED"));
    }

    @Test
    void callback이_역순으로_와도_상태가_역행하지_않고_파일이_중복되지_않는다() {
        publishTrack("EV-ord-1", instructorParticipantId, "TR_ord", TrackSource.CAMERA);
        relayUseCase.relayPendingOutbox();
        String egressId = "EG-TR_ord";

        // 순서 역전: 종료 콜백이 시작 콜백보다 먼저 도착한다.
        egressCallback(
                "EV-ord-ended",
                RecordingWebhookEventType.EGRESS_ENDED,
                egressId,
                Boolean.TRUE,
                "TR_ord",
                List.of(file("instructor-camera-TR_ord.mp4", 5, 65)));
        assertEquals("COMPLETE", recordingStatus(egressId));
        assertEquals(1, fileKeys().size());

        // 뒤늦은 시작 콜백과 중복 종료 콜백은 종결 상태를 되돌리거나 파일을 늘리지 못한다.
        egressCallback("EV-ord-started", RecordingWebhookEventType.EGRESS_STARTED, egressId, null, "TR_ord", List.of());
        egressCallback(
                "EV-ord-ended-again",
                RecordingWebhookEventType.EGRESS_ENDED,
                egressId,
                Boolean.TRUE,
                "TR_ord",
                List.of(file("instructor-camera-TR_ord.mp4", 5, 65)));

        assertEquals("COMPLETE", recordingStatus(egressId));
        assertEquals(1, fileKeys().size());
        assertEquals("raw/instructor/instructor-camera-TR_ord.mp4", fileKeys().get(0));
    }

    @Test
    void 일부_트랙만_실패하면_나머지는_정상_기록된다() {
        // 일부 실패: 마이크 트랙의 Egress 시작만 계속 실패하고, 카메라 트랙은 정상 진행된다.
        egressFixture.failTrackSids.add("TR_part_mic");
        publishTrack("EV-part-cam", instructorParticipantId, "TR_part_cam", TrackSource.CAMERA);
        publishTrack("EV-part-mic", instructorParticipantId, "TR_part_mic", TrackSource.MICROPHONE);

        exhaustRelayRetries();

        // 성공한 트랙은 recordings 행이 남고, 실패한 트랙은 outbox FAILED로 남아 누락 구간의 근거가 된다.
        assertEquals(1, recordingCount());
        assertEquals("STARTING", recordingStatus("EG-TR_part_cam"));
        assertNull(recordingStatus("EG-TR_part_mic"));
        assertEquals(1, outboxCount("COMPLETED"));
        // 강사 마이크는 파일 Egress 와 코칭 스트림 Egress 두 작업이라, 마이크가 죽으면 둘 다 FAILED 로 남는다.
        assertEquals(2, outboxCount("FAILED"));

        // 성공한 트랙의 종료 콜백은 정상 처리된다.
        egressCallback(
                "EV-part-ended",
                RecordingWebhookEventType.EGRESS_ENDED,
                "EG-TR_part_cam",
                Boolean.TRUE,
                "TR_part_cam",
                List.of(file("instructor-camera-TR_part_cam.mp4", 0, 60)));
        assertEquals("COMPLETE", recordingStatus("EG-TR_part_cam"));
        assertEquals(1, fileKeys().size());
    }

    @Test
    void 전체_트랙이_실패하면_모든_outbox가_FAILED로_남고_녹화_행은_생기지_않는다() {
        // 전체 실패: LiveKit Egress가 완전히 불가한 상황(자격증명 오류·서버 다운 등).
        egressFixture.failAll = true;
        publishTrack("EV-all-cam", instructorParticipantId, "TR_all_cam", TrackSource.CAMERA);
        publishTrack("EV-all-mic", instructorParticipantId, "TR_all_mic", TrackSource.MICROPHONE);

        exhaustRelayRetries();

        assertEquals(0, recordingCount());
        assertEquals(0, outboxCount("COMPLETED"));
        // 카메라 1건 + 마이크 2건(파일·코칭 스트림).
        assertEquals(3, outboxCount("FAILED"));
        assertTrue(fileKeys().isEmpty());
    }

    @Test
    void egress가_실패로_종료되면_녹화가_FAILED로_기록된다() {
        publishTrack("EV-fail-1", instructorParticipantId, "TR_fail", TrackSource.CAMERA);
        relayUseCase.relayPendingOutbox();

        egressCallback(
                "EV-fail-ended",
                RecordingWebhookEventType.EGRESS_ENDED,
                "EG-TR_fail",
                Boolean.FALSE,
                "TR_fail",
                List.of());

        assertEquals("FAILED", recordingStatus("EG-TR_fail"));
        assertTrue(fileKeys().isEmpty());
    }

    @Test
    void 한_Egress가_파일을_여러_개_남겨도_UNIQUE_제약을_위반하지_않는다() {
        publishTrack("EV-multi-1", instructorParticipantId, "TR_multi", TrackSource.CAMERA);
        relayUseCase.relayPendingOutbox();

        egressCallback(
                "EV-multi-ended",
                RecordingWebhookEventType.EGRESS_ENDED,
                "EG-TR_multi",
                Boolean.TRUE,
                "TR_multi",
                List.of(file("seg-1.mp4", 0, 30), file("seg-2.mp4", 30, 60)));

        // UK(recording_id, livekit_track_sid) 때문에 trackSid는 첫 행만 갖고, 두 행 모두 저장된다.
        List<String> keys = fileKeys();
        assertEquals(2, keys.size());
        List<String> trackSids = jdbcTemplate.queryForList(
                "SELECT livekit_track_sid FROM recording_files WHERE session_id = ? ORDER BY storage_key",
                String.class,
                sessionId);
        assertNotNull(trackSids.get(0));
        assertNull(trackSids.get(1));
    }

    /** LiveKit 경계 대체 fixture와 제어 가능한 시계. 실제 어댑터 대신 @Primary로 주입된다. */
    @TestConfiguration
    static class LiveKitFixtureConfig {

        @Bean
        @Primary
        FakeTrackEgressPort egressFixture() {
            return new FakeTrackEgressPort();
        }

        @Bean
        @Primary
        FakeWebhookVerifier webhookFixture() {
            return new FakeWebhookVerifier();
        }

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(SESSION_START.plusSeconds(60));
        }
    }

    static class FakeTrackEgressPort implements TrackEgressPort {

        private final List<TrackEgressRequest> requests = new ArrayList<>();
        private final List<com.a105.zani.recording.application.port.AudioStreamEgressRequest> audioStreamRequests =
                new ArrayList<>();
        private final Set<String> activeTrackSids = new HashSet<>();
        private final Set<String> liveAudioStreamTrackSids = new HashSet<>();
        private final Set<String> failTrackSids = new HashSet<>();
        private boolean failAll;

        /** 코칭 버퍼용 WebSocket Egress. 파일 Egress 와 별개 실행이라 따로 기록한다. */
        @Override
        public IssuedTrackEgress startAudioStream(
                com.a105.zani.recording.application.port.AudioStreamEgressRequest request) {
            if (failAll || failTrackSids.contains(request.trackSid())) {
                throw new TrackEgressUnavailableException(new IllegalStateException("egress unavailable"));
            }
            audioStreamRequests.add(request);
            liveAudioStreamTrackSids.add(request.trackSid());
            return new IssuedTrackEgress("EG_WS_" + request.trackSid());
        }

        /** 실제 어댑터처럼 아직 살아 있는 스트림만 되돌린다. 종료된 실행은 채택 대상이 아니다. */
        @Override
        public java.util.Optional<String> findLiveAudioStreamEgressId(
                com.a105.zani.recording.application.port.AudioStreamEgressRequest request) {
            return liveAudioStreamTrackSids.contains(request.trackSid())
                    ? java.util.Optional.of("EG_WS_" + request.trackSid())
                    : java.util.Optional.empty();
        }

        @Override
        public IssuedTrackEgress start(TrackEgressRequest request) {
            if (failAll || failTrackSids.contains(request.trackSid())) {
                throw new TrackEgressUnavailableException(new IllegalStateException("egress unavailable"));
            }
            requests.add(request);
            activeTrackSids.add(request.trackSid());
            // egressId를 trackSid에서 결정적으로 만들어, 테스트가 후속 콜백을 구성할 수 있게 한다.
            return new IssuedTrackEgress(egressIdOf(request.trackSid()));
        }

        /** 실제 LiveKit처럼 기존 Egress(진행 중·종료 포함)를 되돌려, 재실행이 중복 시작하지 않는지 검증할 수 있게 한다. */
        @Override
        public java.util.Optional<String> findExistingEgressId(TrackEgressRequest request) {
            return activeTrackSids.contains(request.trackSid())
                    ? java.util.Optional.of(egressIdOf(request.trackSid()))
                    : java.util.Optional.empty();
        }

        private static String egressIdOf(String trackSid) {
            return "EG-" + trackSid;
        }

        void reset() {
            requests.clear();
            activeTrackSids.clear();
            failTrackSids.clear();
            failAll = false;
        }
    }

    /** 서명 검증을 통과한 것으로 간주하고, 테스트가 지정한 이벤트를 그대로 반환한다. */
    static class FakeWebhookVerifier implements RecordingWebhookVerifierPort {

        private RecordingWebhookEvent nextEvent;

        @Override
        public RecordingWebhookEvent verify(String body, String authorizationHeader) {
            return nextEvent;
        }
    }

    static class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
