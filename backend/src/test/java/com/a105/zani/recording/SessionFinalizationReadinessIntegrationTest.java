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
import com.a105.zani.recording.application.checkfinalizationreadiness.FinalizationReadiness;
import com.a105.zani.recording.application.checkfinalizationreadiness.GetSessionFinalizationReadinessUseCase;
import com.a105.zani.recording.application.port.RecordingOutboxType;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 최종 MP4 합성이 모든 파일 Track Egress와 outbox의 종결을 기다리는지 실제 MySQL로 검증한다. */
@SpringBootTest
class SessionFinalizationReadinessIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-04T08:00:00Z");

    @Autowired
    private GetSessionFinalizationReadinessUseCase useCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private long sessionId;
    private long participantId;

    @BeforeEach
    void setUp() {
        sessionId = createSession("ENDED");
        participantId = createParticipant(sessionId);
    }

    @AfterEach
    void clean() {
        for (Long id : sessionIds) {
            jdbcTemplate.update("DELETE FROM recording_files WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM recordings WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM recording_outbox WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", id);
        }
        for (Long id : memberIds) {
            jdbcTemplate.update("DELETE FROM members WHERE id = ?", id);
        }
    }

    @Test
    void 카메라_마이크_화면과_화면오디오가_모두_종결되면_SETTLED() {
        for (TrackSource source : TrackSource.values()) {
            insertRecording(RecordingStatus.COMPLETE, source);
            insertTrackOutbox("COMPLETED", source);
        }

        assertEquals(FinalizationReadiness.SETTLED, useCase.check(sessionId));
    }

    @Test
    void 화면_공유_Egress가_진행_중이면_IN_PROGRESS() {
        insertRecording(RecordingStatus.RECORDING, TrackSource.SCREEN_SHARE);

        assertEquals(FinalizationReadiness.IN_PROGRESS, useCase.check(sessionId));
    }

    @Test
    void 카메라_Egress가_최종_실패하면_BROKEN() {
        insertRecording(RecordingStatus.FAILED, TrackSource.CAMERA);

        assertEquals(FinalizationReadiness.BROKEN, useCase.check(sessionId));
    }

    @Test
    void 화면오디오_outbox가_아직_전달되지_않았으면_IN_PROGRESS() {
        insertTrackOutbox("PENDING", TrackSource.SCREEN_SHARE_AUDIO);

        assertEquals(FinalizationReadiness.IN_PROGRESS, useCase.check(sessionId));
    }

    @Test
    void 화면_공유_outbox가_최종_실패하면_BROKEN() {
        insertTrackOutbox("FAILED", TrackSource.SCREEN_SHARE);

        assertEquals(FinalizationReadiness.BROKEN, useCase.check(sessionId));
    }

    @Test
    void 링버퍼용_audio_stream_outbox는_합성_준비_상태에서_제외한다() {
        insertOutbox(RecordingOutboxType.START_AUDIO_STREAM_EGRESS, "FAILED", TrackSource.MICROPHONE);

        assertEquals(FinalizationReadiness.SETTLED, useCase.check(sessionId));
    }

    @Test
    void 세션이_끝나지_않았으면_Egress가_종결돼도_IN_PROGRESS() {
        jdbcTemplate.update("UPDATE sessions SET status = 'LIVE', ended_at = NULL WHERE id = ?", sessionId);
        insertRecording(RecordingStatus.COMPLETE, TrackSource.MICROPHONE);

        assertEquals(FinalizationReadiness.IN_PROGRESS, useCase.check(sessionId));
    }

    @Test
    void 진행_중과_실패가_함께_있으면_진행_중을_우선한다() {
        insertRecording(RecordingStatus.RECORDING, TrackSource.MICROPHONE);
        insertRecording(RecordingStatus.FAILED, TrackSource.SCREEN_SHARE);

        assertEquals(FinalizationReadiness.IN_PROGRESS, useCase.check(sessionId));
    }

    private long createSession(String status) {
        long memberId = TsidGenerator.generate();
        long id = TsidGenerator.generate();
        memberIds.add(memberId);
        sessionIds.add(id);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                memberId,
                "finalize-" + suffix,
                "finalize-" + suffix + "@example.invalid",
                "finalization readiness",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " ended_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'NOT_STARTED', ?, ?, ?, ?)",
                id,
                memberId,
                "finalization readiness",
                suffix,
                status,
                java.sql.Timestamp.from(NOW.minusSeconds(3_600)),
                "ENDED".equals(status) ? java.sql.Timestamp.from(NOW) : null,
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
                memberIds.get(memberIds.size() - 1),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return id;
    }

    private void insertRecording(RecordingStatus status, TrackSource source) {
        jdbcTemplate.update(
                "INSERT INTO recordings (id, session_id, livekit_egress_id, session_participant_id, track_source,"
                        + " livekit_track_sid, recording_type, attempt_number, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'TRACK', 1, ?, ?, ?)",
                TsidGenerator.generate(),
                sessionId,
                "EG_" + UUID.randomUUID(),
                participantId,
                source.name(),
                "TR_" + UUID.randomUUID(),
                status.name(),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }

    private void insertTrackOutbox(String status, TrackSource source) {
        insertOutbox(RecordingOutboxType.START_TRACK_EGRESS, status, source);
    }

    private void insertOutbox(RecordingOutboxType type, String status, TrackSource source) {
        String payload = "{\"trackSid\":\"TR_1\",\"recordingAlias\":\"instructor\",\"source\":\"" + source.name()
                + "\",\"sessionParticipantId\":" + participantId + "}";
        jdbcTemplate.update(
                "INSERT INTO recording_outbox (id, dedup_key, outbox_type, session_id, payload, status,"
                        + " attempt_count, next_attempt_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)",
                TsidGenerator.generate(),
                "finalize:" + UUID.randomUUID(),
                type.name(),
                sessionId,
                payload,
                status,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }
}
