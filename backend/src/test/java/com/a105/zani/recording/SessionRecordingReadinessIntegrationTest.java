package com.a105.zani.recording;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.recording.application.gettrackfiles.GetSessionRecordingSnapshotUseCase;
import com.a105.zani.recording.application.gettrackfiles.RecordingReadiness;
import com.a105.zani.recording.application.gettrackfiles.SessionRecordingSnapshot;
import com.a105.zani.recording.application.port.RecordingOutboxType;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사후 전사가 녹화 준비 상태를 어떻게 읽는지 실제 MySQL 로 검증한다.
 *
 * <p>여기서 확인하는 것은 <b>무엇을 세고 무엇을 세지 않는지</b>다. 기준이 넓으면 상관없는 Egress 실패가 전사를 영구히 막고, 좁으면 잃은 발화를 못 본 채 빈 전사를 완전한 것으로 확정한다. 어느
 * 쪽도 인메모리로는 확인할 수 없다 — {@code payload} 가 {@code VARCHAR} 라 트랙 종류를 실제 문자열에서 꺼내야 하고, 상태 문자열도 DB 에 저장된 값과 맞아야 한다.
 */
@SpringBootTest
class SessionRecordingReadinessIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-04T04:00:00Z");

    private final List<Long> createdSessionIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();

    @Autowired
    private GetSessionRecordingSnapshotUseCase useCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sessionId;
    private long participantId;

    @BeforeEach
    void setUp() {
        sessionId = createEndedSession();
        participantId = createParticipant(sessionId);
    }

    @AfterEach
    void clean() {
        for (Long id : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM recording_files WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM recordings WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM recording_outbox WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", id);
        }
        for (Long id : createdMemberIds) {
            jdbcTemplate.update("DELETE FROM members WHERE id = ?", id);
        }
        createdSessionIds.clear();
        createdMemberIds.clear();
    }

    private long createEndedSession() {
        long memberId = TsidGenerator.generate();
        long id = TsidGenerator.generate();
        createdMemberIds.add(memberId);
        createdSessionIds.add(id);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                memberId,
                "readiness-" + suffix,
                "readiness-" + suffix + "@example.invalid",
                "readiness test",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?, ?)",
                id,
                memberId,
                "readiness",
                suffix,
                java.sql.Timestamp.from(NOW.minusSeconds(3_600)),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return id;
    }

    private long createParticipant(long session) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at) VALUES (?, ?, ?, 'INSTRUCTOR', ?, ?, ?, ?)",
                id,
                session,
                createdMemberIds.get(createdMemberIds.size() - 1),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return id;
    }

    private long insertRecording(RecordingStatus status, TrackSource source) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO recordings (id, session_id, livekit_egress_id, session_participant_id, track_source,"
                        + " recording_type, attempt_number, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'TRACK', 1, ?, ?, ?)",
                id,
                sessionId,
                "EG_" + UUID.randomUUID(),
                participantId,
                source == null ? null : source.name(),
                status.name(),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return id;
    }

    private void insertOutbox(RecordingOutboxType type, String status, String payload) {
        jdbcTemplate.update(
                "INSERT INTO recording_outbox (id, dedup_key, outbox_type, session_id, payload, status,"
                        + " attempt_count, next_attempt_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 5, ?, ?, ?)",
                TsidGenerator.generate(),
                "dedup:" + UUID.randomUUID(),
                type.name(),
                sessionId,
                payload,
                status,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }

    private String payload(TrackSource source) {
        String sourceField = source == null ? "null" : "\"" + source.name() + "\"";
        return "{\"trackSid\":\"TR_1\",\"recordingAlias\":\"instructor\",\"source\":" + sourceField
                + ",\"sessionParticipantId\":" + participantId + "}";
    }

    /** {@code recording_files} 는 {@code (session_id, recording_id)} 로 복합 FK 가 걸려 있어 실제 녹화 행을 가리켜야 한다. */
    private void insertMicrophoneFile(long recordingId) {
        jdbcTemplate.update(
                "INSERT INTO recording_files (id, session_id, recording_id, session_participant_id, file_type,"
                        + " track_source, storage_key, livekit_track_sid, started_offset_ms, ended_offset_ms,"
                        + " created_at) VALUES (?, ?, ?, ?, 'TRACK', 'MICROPHONE', ?, ?, 0, 600000, ?)",
                TsidGenerator.generate(),
                sessionId,
                recordingId,
                participantId,
                "raw/instructor/mic-" + UUID.randomUUID(),
                "TR_" + UUID.randomUUID(),
                java.sql.Timestamp.from(NOW));
    }

    private RecordingReadiness readiness() {
        SessionRecordingSnapshot snapshot = useCase.findBySessionId(sessionId);
        return snapshot.readiness();
    }

    @Test
    void 마이크_파일_Egress_가_최종_실패하면_BROKEN() {
        insertRecording(RecordingStatus.FAILED, TrackSource.MICROPHONE);

        assertEquals(RecordingReadiness.BROKEN, readiness());
    }

    @Test
    void 마이크_파일_Egress_가_진행_중이면_IN_PROGRESS() {
        insertRecording(RecordingStatus.RECORDING, TrackSource.MICROPHONE);

        assertEquals(RecordingReadiness.IN_PROGRESS, readiness());
    }

    @Test
    void 진행_중과_실패가_함께_있으면_기다린다() {
        // 실패 판정은 다 끝난 뒤에 해야 한다. 진행 중인 것이 성공하면 그 실패가 무의미해질 수도 있다.
        insertRecording(RecordingStatus.RECORDING, TrackSource.MICROPHONE);
        insertRecording(RecordingStatus.FAILED, TrackSource.MICROPHONE);

        assertEquals(RecordingReadiness.IN_PROGRESS, readiness());
    }

    @Test
    void 화면_공유와_카메라_Egress_실패는_전사를_막지_않는다() {
        // 그것이 실패해도 발화는 마이크 트랙에 그대로 있다. 함께 세면 멀쩡한 세션의 전사가 막힌다.
        insertRecording(RecordingStatus.FAILED, TrackSource.SCREEN_SHARE);
        insertRecording(RecordingStatus.FAILED, TrackSource.SCREEN_SHARE_AUDIO);
        insertRecording(RecordingStatus.FAILED, TrackSource.CAMERA);
        insertMicrophoneFile(insertRecording(RecordingStatus.COMPLETE, TrackSource.MICROPHONE));

        assertEquals(RecordingReadiness.SETTLED, readiness());
        assertEquals(1, useCase.findBySessionId(sessionId).files().size());
    }

    @Test
    void 링버퍼용_AUDIO_STREAM_Egress_실패는_전사를_막지_않는다() {
        // 강사 마이크 하나에 outbox 가 둘 생긴다. 하나는 사후 전사용 파일 Egress, 하나는 실시간 코칭
        // 링버퍼용 WebSocket Egress. 후자가 실패해도 OGG 파일은 정상이다.
        insertMicrophoneFile(insertRecording(RecordingStatus.COMPLETE, TrackSource.MICROPHONE));
        insertOutbox(RecordingOutboxType.START_TRACK_EGRESS, "COMPLETED", payload(TrackSource.MICROPHONE));
        insertOutbox(RecordingOutboxType.START_AUDIO_STREAM_EGRESS, "FAILED", "{}");

        assertEquals(RecordingReadiness.SETTLED, readiness());
    }

    @Test
    void 마이크_파일_Egress_outbox_전달_실패는_BROKEN() {
        // 그만큼의 트랙은 녹화 자체가 시작되지 않았다. 기다려도 오지 않는다.
        insertOutbox(RecordingOutboxType.START_TRACK_EGRESS, "FAILED", payload(TrackSource.MICROPHONE));

        assertEquals(RecordingReadiness.BROKEN, readiness());
    }

    @Test
    void 화면_공유_outbox_전달_실패는_전사를_막지_않는다() {
        insertOutbox(RecordingOutboxType.START_TRACK_EGRESS, "FAILED", payload(TrackSource.SCREEN_SHARE_AUDIO));

        assertEquals(RecordingReadiness.SETTLED, readiness());
    }

    @Test
    void 마이크_outbox_가_아직_전달되지_않았으면_IN_PROGRESS() {
        insertOutbox(RecordingOutboxType.START_TRACK_EGRESS, "PENDING", payload(TrackSource.MICROPHONE));

        assertEquals(RecordingReadiness.IN_PROGRESS, readiness());
    }

    @Test
    void 종류를_모르는_payload_는_발화_트랙으로_본다() {
        // V12 이전 payload 에는 source 가 없다. 아니라고 단정하면 잃은 발화를 못 본다.
        insertOutbox(RecordingOutboxType.START_TRACK_EGRESS, "FAILED", payload(null));

        assertEquals(RecordingReadiness.BROKEN, readiness());
    }

    @Test
    void 종류를_모르는_녹화_행도_발화_트랙으로_본다() {
        insertRecording(RecordingStatus.FAILED, null);

        assertEquals(RecordingReadiness.BROKEN, readiness());
    }

    @Test
    void 녹화가_모두_종결되고_파일이_없으면_SETTLED() {
        // 아무도 마이크를 열지 않은 수업. 이때의 빈 목록만 정상적인 빈 전사다.
        insertRecording(RecordingStatus.COMPLETE, TrackSource.MICROPHONE);
        insertOutbox(RecordingOutboxType.START_TRACK_EGRESS, "COMPLETED", payload(TrackSource.MICROPHONE));

        assertEquals(RecordingReadiness.SETTLED, readiness());
        assertTrue(useCase.findBySessionId(sessionId).files().isEmpty());
    }

    @Test
    void 세션이_아직_끝나지_않았으면_IN_PROGRESS() {
        // 정상 흐름에서는 나올 수 없다(메모 확정이 세션 종료 뒤다). 뒤집히면 수업 도중의 절반짜리
        // 전사가 최종본으로 확정되므로 확인한다.
        jdbcTemplate.update("UPDATE sessions SET status = 'LIVE', ended_at = NULL WHERE id = ?", sessionId);
        insertRecording(RecordingStatus.COMPLETE, TrackSource.MICROPHONE);

        assertEquals(RecordingReadiness.IN_PROGRESS, readiness());
    }
}
