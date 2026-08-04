package com.a105.zani.postclass;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.exception.TranscriptDocumentInvalidException;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.domain.model.ConfidenceMethod;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.domain.model.TranscriptDocumentSegment;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 최종 전사 저장을 실제 MySQL 로 검증한다.
 *
 * <p>인메모리로 확인할 수 없는 것이 이 어댑터의 핵심이다 — {@code UK_TRANSCRIPTS_SESSION} 아래에서 upsert 가 정말 한 행을 유지하는지, JSON 컬럼이 문서를 왕복하는지,
 * {@code created_at} 이 재조립에 보존되는지. 로컬 MySQL 이 떠 있어야 통과한다.
 */
@SpringBootTest
class TranscriptPersistenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-04T02:00:00Z");

    private final List<Long> createdSessionIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();

    @Autowired
    private TranscriptPort port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static TranscriptDocument document(String text, long startOffsetMs) {
        return TranscriptDocument.complete(
                "ko",
                List.of(new TranscriptDocumentSegment(
                        9_300_101L,
                        TrackSource.MICROPHONE,
                        "TR_roundtrip01",
                        startOffsetMs,
                        startOffsetMs + 4_000,
                        text,
                        -0.21,
                        Math.exp(-0.21),
                        ConfidenceMethod.EXP_AVG_LOGPROB,
                        0.0541,
                        9_300_201L,
                        3)));
    }

    /**
     * 이 테스트가 만든 행을 지운다.
     *
     * <p>남기지 않는 이유는 계약 위반 테스트 둘이 일부러 깨진 문서를 넣기 때문이다. 그것이 DB 에 남으면 나중에 전사를 읽는 테스트가 이유를 알 수 없는 실패를 하게 된다.
     */
    @AfterEach
    void clean() {
        for (Long sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM transcripts WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        }
        for (Long memberId : createdMemberIds) {
            jdbcTemplate.update("DELETE FROM members WHERE id = ?", memberId);
        }
        createdSessionIds.clear();
        createdMemberIds.clear();
    }

    /** {@code transcripts} 는 {@code sessions} 로 FK 가 있어 실제 세션 행이 필요하다. */
    private long createSession() {
        long memberId = TsidGenerator.generate();
        long sessionId = TsidGenerator.generate();
        createdMemberIds.add(memberId);
        createdSessionIds.add(sessionId);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                memberId,
                "transcript-" + suffix,
                "transcript-" + suffix + "@example.invalid",
                "transcript adapter test",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?)",
                sessionId,
                memberId,
                "transcript assembly",
                suffix,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return sessionId;
    }

    private int rowCount(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transcripts WHERE session_id = ?", Integer.class, sessionId);
    }

    @Test
    void 문서가_JSON_컬럼을_왕복한다() {
        long sessionId = createSession();

        port.save(sessionId, document("우선순위 큐를 사용합니다", 301_000), NOW);

        TranscriptDocument stored = port.findBySessionId(sessionId).orElseThrow();
        assertEquals(TranscriptDocument.SCHEMA_VERSION, stored.schemaVersion());
        assertEquals("ko", stored.language());
        assertTrue(!stored.partial());
        TranscriptDocumentSegment segment = stored.segments().get(0);
        assertEquals("우선순위 큐를 사용합니다", segment.text());
        assertEquals(301_000, segment.startOffsetMs());
        assertEquals(305_000, segment.endOffsetMs());
        assertEquals(TrackSource.MICROPHONE, segment.source());
        assertEquals(9_300_101L, segment.sessionParticipantId());
        assertEquals(3, segment.chunkIndex());
        assertEquals(-0.21, segment.avgLogprob(), 1e-9);
        assertEquals(0.0541, segment.noSpeechProb(), 1e-9);
        // 추적 좌표 둘과 신뢰도 계산 방법도 왕복해야 한다. enum 이 이름으로 직렬화되는지도 여기서 걸린다.
        assertEquals("TR_roundtrip01", segment.trackSid());
        assertEquals(9_300_201L, segment.recordingFileId());
        assertEquals(ConfidenceMethod.EXP_AVG_LOGPROB, segment.confidenceMethod());
    }

    @Test
    void 저장된_JSON_에_새_필드가_이름_그대로_들어간다() {
        // 소비자가 읽는 것은 컬럼의 JSON 문자열이다. record 왕복만 보면 필드명이 바뀐 것을 놓친다.
        long sessionId = createSession();

        port.save(sessionId, document("계약 확인", 1_000), NOW);

        String json = jdbcTemplate.queryForObject(
                "SELECT transcript_document FROM transcripts WHERE session_id = ?", String.class, sessionId);
        assertTrue(json.contains("\"trackSid\""), "trackSid 필드명이 그대로여야 한다: " + json);
        assertTrue(json.contains("\"EXP_AVG_LOGPROB\""), "confidenceMethod 는 enum 이름으로 저장된다: " + json);
        assertTrue(json.contains("\"startOffsetMs\""), "시간 필드명은 startOffsetMs 를 유지한다: " + json);
        assertTrue(json.contains("\"endOffsetMs\""), "시간 필드명은 endOffsetMs 를 유지한다: " + json);
        assertTrue(json.contains("\"recordingFileId\""), "recordingFileId 도 함께 남는다: " + json);
    }

    @Test
    void trackSid_가_없는_세그먼트도_왕복한다() {
        // 한 Egress 가 파일을 여러 개 남기면 첫 행만 Track SID 를 갖는다. 그 경우가 저장·조회를 막지 않아야 한다.
        long sessionId = createSession();
        TranscriptDocument document = TranscriptDocument.complete(
                "ko",
                List.of(new TranscriptDocumentSegment(
                        9_300_101L,
                        TrackSource.MICROPHONE,
                        null,
                        0,
                        1_000,
                        "SID 없는 파일",
                        -0.21,
                        Math.exp(-0.21),
                        ConfidenceMethod.EXP_AVG_LOGPROB,
                        0.02,
                        9_300_201L,
                        0)));

        port.save(sessionId, document, NOW);

        assertNull(
                port.findBySessionId(sessionId).orElseThrow().segments().get(0).trackSid());
    }

    @Test
    void 재조립은_행을_늘리지_않고_문서를_교체한다() {
        // UK_TRANSCRIPTS_SESSION 이 세션당 한 행을 강제한다. 두 번째 저장이 예외로 끝나면
        // 오케스트레이션의 재실행 경로가 통째로 막힌다.
        long sessionId = createSession();
        port.save(sessionId, document("첫 조립", 1_000), NOW);

        port.save(sessionId, document("다시 조립", 2_000), NOW.plusSeconds(600));

        assertEquals(1, rowCount(sessionId));
        assertEquals(
                "다시 조립",
                port.findBySessionId(sessionId).orElseThrow().segments().get(0).text());
    }

    @Test
    void 재조립이_생성_시각을_덮지_않는다() {
        // 처음 전사가 만들어진 시각이다. 재조립이 덮으면 "이 세션은 언제부터 전사가 있었나" 를 잃는다.
        long sessionId = createSession();
        port.save(sessionId, document("첫 조립", 1_000), NOW);
        Instant createdAt = jdbcTemplate
                .queryForObject(
                        "SELECT created_at FROM transcripts WHERE session_id = ?", java.sql.Timestamp.class, sessionId)
                .toInstant();

        port.save(sessionId, document("다시 조립", 1_000), NOW.plusSeconds(3_600));

        Instant afterCreatedAt = jdbcTemplate
                .queryForObject(
                        "SELECT created_at FROM transcripts WHERE session_id = ?", java.sql.Timestamp.class, sessionId)
                .toInstant();
        Instant updatedAt = jdbcTemplate
                .queryForObject(
                        "SELECT updated_at FROM transcripts WHERE session_id = ?", java.sql.Timestamp.class, sessionId)
                .toInstant();
        assertEquals(createdAt, afterCreatedAt);
        // 두 컬럼을 서로 비교한다. Instant 리터럴과 대조하지 않는 이유는 값이 세션 타임존(Asia/Seoul)을
        // 거쳐 읽히기 때문이다 — 그 차이를 재면 이 테스트가 확인하려는 것과 무관한 실패가 난다.
        assertEquals(3_600, updatedAt.getEpochSecond() - createdAt.getEpochSecond());
    }

    @Test
    void 전사가_없는_세션은_빈_값이다() {
        assertTrue(port.findBySessionId(createSession()).isEmpty());
    }

    @Test
    void 발화가_없는_세션도_빈_세그먼트로_저장된다() {
        long sessionId = createSession();

        port.save(sessionId, TranscriptDocument.complete("ko", List.of()), NOW);

        assertTrue(port.findBySessionId(sessionId).orElseThrow().segments().isEmpty());
    }

    @Test
    void 불변식을_어긴_저장_문서도_비재시도_계약_위반으로_올린다() {
        // 시각이 뒤집힌 구간이 이미 저장돼 있는 경우. record 생성자가 거절하는데, 그 예외가
        // 어댑터를 그대로 뚫고 나가면 호출자는 분류할 수 없는 오류를 받는다. 감싸지는지 확인해
        // 둔다 — 가정하고 넘어갈 대상이 아니다.
        long sessionId = createSession();
        jdbcTemplate.update(
                "INSERT INTO transcripts (id, session_id, transcript_document, created_at, updated_at)"
                        + " VALUES (?, ?, CAST(? AS JSON), ?, ?)",
                TsidGenerator.generate(),
                sessionId,
                "{\"schemaVersion\":1,\"language\":\"ko\",\"partial\":false,\"segments\":["
                        + "{\"sessionParticipantId\":1,\"source\":\"MICROPHONE\",\"startOffsetMs\":600500,"
                        + "\"endOffsetMs\":600000,\"text\":\"뒤집힌 구간\",\"avgLogprob\":-0.21,"
                        + "\"confidence\":0.81,\"noSpeechProb\":0.02,\"recordingFileId\":2,\"chunkIndex\":0}]}",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));

        assertThrows(TranscriptDocumentInvalidException.class, () -> port.findBySessionId(sessionId));
    }

    @Test
    void 형태가_다른_저장_문서는_비재시도_계약_위반으로_올린다() {
        // 저장소 장애가 아니다. 같은 행을 다시 읽어도 같은 결과이므로 재시도 가능으로 분류하면
        // 예산만 태운다. 그렇다고 빈 값으로 돌려주면 "전사가 없다" 로 오인돼 재조립이 옛 문서를 덮는다.
        long sessionId = createSession();
        jdbcTemplate.update(
                "INSERT INTO transcripts (id, session_id, transcript_document, created_at, updated_at)"
                        + " VALUES (?, ?, CAST('{\"segments\": \"not-an-array\"}' AS JSON), ?, ?)",
                TsidGenerator.generate(),
                sessionId,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));

        assertThrows(TranscriptDocumentInvalidException.class, () -> port.findBySessionId(sessionId));
    }
}
