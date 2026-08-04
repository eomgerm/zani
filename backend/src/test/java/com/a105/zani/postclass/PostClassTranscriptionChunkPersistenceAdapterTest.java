package com.a105.zani.postclass;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.a105.zani.postclass.application.exception.TranscriptionChunkBoundaryMismatchException;
import com.a105.zani.postclass.application.port.AudioChunk;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionChunkPort;
import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 체크포인트 계약을 실제 MySQL 로 검증한다.
 *
 * <p>인메모리로는 확인할 수 없는 것들이 이 계약의 핵심이다 — {@code (recording_file_id, chunk_index)} UNIQUE 가 실제로 재분할을 흡수하는지, 조건부 UPDATE 한
 * 문장이 두 실행의 동시 선점을 막는지, 실행권 토큰이 회수된 청크의 늦은 쓰기를 걸러내는지, JSON 컬럼이 세그먼트를 왕복하는지. 로컬 MySQL 이 떠 있어야 통과한다.
 */
@SpringBootTest
class PostClassTranscriptionChunkPersistenceAdapterTest {

    private static final long SESSION_ID = 9_100_001L;
    private static final long FILE_ID = 9_100_101L;
    private static final long OTHER_FILE_ID = 9_100_102L;
    private static final Instant NOW = Instant.parse("2026-08-04T01:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private TranscriptionChunkPort port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM postclass_transcription_chunks WHERE session_id = ?", SESSION_ID);
    }

    private static AudioChunk chunk(int index, long startMs, long endMs) {
        return new AudioChunk(index, Path.of("chunk-" + index + ".ogg"), startMs, endMs, 1_024L);
    }

    private List<AudioChunk> threeChunks() {
        // EC2 실측 CSV 와 같은 구간이다.
        return List.of(chunk(0, 0, 120_000), chunk(1, 120_000, 240_000), chunk(2, 240_000, 336_580));
    }

    /** 청크와 그 실행권 토큰. 기록 호출이 토큰을 요구하므로 함께 들고 다녀야 한다. */
    private record Claimed(TranscriptionChunk chunk, int token) {}

    private Claimed claim(TranscriptionChunk target, Instant now) {
        OptionalInt token = port.tryClaim(target.id(), now.plus(LEASE), now);
        assertTrue(token.isPresent(), "선점에 성공해야 한다");
        return new Claimed(target, token.getAsInt());
    }

    private Claimed claimFirst() {
        return claim(port.findClaimable(FILE_ID, NOW, 10).get(0), NOW);
    }

    @Test
    void CSV_구간을_그대로_등록하고_순번대로_돌려준다() {
        List<TranscriptionChunk> registered = port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);

        // 반환값이 DB 상태여야 한다. 호출자가 넘긴 목록이 아니라 저장된 값이 시간축의 정본이다.
        assertEquals(3, registered.size());
        assertEquals(0, registered.get(0).chunkIndex());
        assertEquals(336_580, registered.get(2).sourceEndMs());

        List<TranscriptionChunk> claimable = port.findClaimable(FILE_ID, NOW, 10);

        assertEquals(3, claimable.size());
        assertEquals(0, claimable.get(0).chunkIndex());
        assertEquals(0, claimable.get(0).sourceStartMs());
        assertEquals(120_000, claimable.get(0).sourceEndMs());
        assertEquals(336_580, claimable.get(2).sourceEndMs());
        assertEquals(TranscriptionChunkStatus.PENDING, claimable.get(0).status());
        assertEquals(0, claimable.get(0).attemptCount());
    }

    @Test
    void 같은_재분할은_멱등이라_행이_늘지_않는다() {
        // 재시도로 같은 원본을 다시 자른 상황. UNIQUE (recording_file_id, chunk_index) 가 흡수해야 한다.
        List<TranscriptionChunk> first = port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);

        List<TranscriptionChunk> second = port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW.plusSeconds(60));

        assertEquals(3, second.size());
        assertEquals(3, port.findAllBySessionId(SESSION_ID).size());
        // 행 자체가 그대로여야 한다. 지우고 다시 넣으면 성공 결과가 사라진다.
        assertEquals(first.get(0).id(), second.get(0).id());
    }

    @Test
    void 경계가_달라진_재분할은_거절한다() {
        // 청크 길이 설정이 바뀌었거나 ffmpeg 동작이 달라진 상황. 그대로 진행하면
        // "DB 의 옛 오프셋 + 새 청크의 상대 시각" 으로 절대 시각이 만들어져 시간축이 조용히 밀린다.
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        List<AudioChunk> shifted = List.of(chunk(0, 0, 90_000), chunk(1, 90_000, 240_000), chunk(2, 240_000, 336_580));

        assertThrows(
                TranscriptionChunkBoundaryMismatchException.class,
                () -> port.registerAll(SESSION_ID, FILE_ID, shifted, NOW.plusSeconds(60)));

        // 기록된 경계는 그대로여야 한다.
        assertEquals(120_000, port.findAllBySessionId(SESSION_ID).get(0).sourceEndMs());
    }

    @Test
    void 청크_개수가_달라진_재분할은_거절하고_추가_행을_남기지_않는다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        List<AudioChunk> extra = List.of(
                chunk(0, 0, 120_000),
                chunk(1, 120_000, 240_000),
                chunk(2, 240_000, 336_580),
                chunk(3, 336_580, 400_000));

        assertThrows(
                TranscriptionChunkBoundaryMismatchException.class,
                () -> port.registerAll(SESSION_ID, FILE_ID, extra, NOW.plusSeconds(60)));

        // 롤백돼야 한다. 4번 청크가 남으면 다음 재시도부터는 개수가 맞아 잘못된 경계로 굳는다.
        assertEquals(3, port.findAllBySessionId(SESSION_ID).size());
    }

    @Test
    void 재분할이_이미_성공한_청크의_결과를_덮어쓰지_않는다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed claimed = claimFirst();
        assertTrue(port.markSucceeded(
                claimed.chunk().id(),
                claimed.token(),
                List.of(new TranscriptSegment(0, 5_000, "안녕하세요", -0.21, 0.05)),
                NOW));

        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW.plusSeconds(60));

        TranscriptionChunk after = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.SUCCEEDED, after.status());
        assertEquals(1, after.segments().size());
        assertEquals("안녕하세요", after.segments().get(0).text());
    }

    @Test
    void 선점은_한_번만_성공하고_시도_횟수를_실행권_토큰으로_돌려준다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        TranscriptionChunk target = port.findClaimable(FILE_ID, NOW, 10).get(0);

        OptionalInt token = port.tryClaim(target.id(), NOW.plus(LEASE), NOW);
        // 두 번째 실행은 조건절에 걸려 0 행을 갱신한다. 이것이 동시 선점을 막는 근거다.
        OptionalInt denied = port.tryClaim(target.id(), NOW.plus(LEASE), NOW);

        assertEquals(OptionalInt.of(1), token, "선점 후의 attemptCount 가 실행권 토큰이다");
        assertTrue(denied.isEmpty());

        TranscriptionChunk after = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.PROCESSING, after.status());
        assertEquals(1, after.attemptCount(), "선점에서 한 번만 올라야 한다");
    }

    @Test
    void 선점한_청크는_lease_가_남아_있으면_다시_잡히지_않는다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed claimed = claimFirst();

        List<TranscriptionChunk> claimable = port.findClaimable(FILE_ID, NOW.plusSeconds(60), 10);

        assertFalse(claimable.stream()
                .anyMatch(chunk -> chunk.id().equals(claimed.chunk().id())));
    }

    @Test
    void 서버가_죽어_lease_가_만료되면_회수하고_토큰이_올라간다() {
        // PROCESSING 으로 남은 행이 영원히 묶이면 그 세션의 전사가 끝나지 않는다.
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed first = claimFirst();

        Instant afterLease = NOW.plus(LEASE).plusSeconds(1);
        List<TranscriptionChunk> claimable = port.findClaimable(FILE_ID, afterLease, 10);

        assertTrue(claimable.stream()
                .anyMatch(chunk -> chunk.id().equals(first.chunk().id())));
        assertEquals(OptionalInt.of(2), port.tryClaim(first.chunk().id(), afterLease.plus(LEASE), afterLease));
        assertEquals(2, port.findAllBySessionId(SESSION_ID).get(0).attemptCount());
    }

    @Test
    void 회수된_청크에는_이전_실행의_늦은_결과가_기록되지_않는다() {
        // A 선점 → A 의 lease 만료 → B 가 회수 → 늦게 끝난 A 가 기록 시도.
        // 이것을 막지 않으면 A 의 결과가 B 의 결과를 덮거나 그 반대가 되고, 어느 쪽인지는 순서에 달린다.
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed lost = claimFirst();
        Instant afterLease = NOW.plus(LEASE).plusSeconds(1);
        Claimed holder = claim(lost.chunk(), afterLease);

        boolean lateWrite = port.markSucceeded(
                lost.chunk().id(),
                lost.token(),
                List.of(new TranscriptSegment(0, 5_000, "늦은 결과", -0.21, 0.05)),
                afterLease);

        assertFalse(lateWrite, "실행권을 잃은 쪽은 false 를 받고 결과를 버려야 한다");
        TranscriptionChunk stillProcessing = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.PROCESSING, stillProcessing.status());
        assertTrue(stillProcessing.segments().isEmpty());

        // 실행권을 가진 쪽은 정상적으로 기록한다.
        assertTrue(port.markSucceeded(
                holder.chunk().id(),
                holder.token(),
                List.of(new TranscriptSegment(0, 5_000, "제대로 된 결과", -0.21, 0.05)),
                afterLease));
        assertEquals(
                "제대로 된 결과",
                port.findAllBySessionId(SESSION_ID).get(0).segments().get(0).text());
    }

    @Test
    void 실행권을_잃으면_재시도와_최종_실패도_기록되지_않는다() {
        // 늦은 실패 기록도 위험하다. B 가 처리 중인 청크를 A 의 실패가 PENDING 이나 FAILED 로 되돌리면
        // 같은 청크가 두 번 호출되거나, 성공할 작업이 실패로 굳는다.
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed lost = claimFirst();
        Instant afterLease = NOW.plus(LEASE).plusSeconds(1);
        claim(lost.chunk(), afterLease);

        assertFalse(port.markRetry(lost.chunk().id(), lost.token(), "timeout", afterLease.plusSeconds(30), afterLease));
        assertFalse(port.markFailed(lost.chunk().id(), lost.token(), "unauthorized", afterLease));
        assertFalse(port.markSkippedSilent(lost.chunk().id(), lost.token(), afterLease));

        TranscriptionChunk after = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.PROCESSING, after.status());
        assertEquals(2, after.attemptCount());
    }

    @Test
    void 성공한_청크는_다시_잡히지_않고_세그먼트가_왕복한다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed claimed = claimFirst();
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment(0, 20_000, "아 네 안녕하세요", -0.21, 0.0541),
                new TranscriptSegment(20_000, 26_000, "네 둘다 이해 못하면", -0.36, 0.6612));

        assertTrue(port.markSucceeded(claimed.chunk().id(), claimed.token(), segments, NOW));

        // 재기동 후에도 다시 호출되지 않는다.
        assertFalse(port.findClaimable(FILE_ID, NOW.plus(Duration.ofHours(1)), 10).stream()
                .anyMatch(chunk -> chunk.id().equals(claimed.chunk().id())));

        TranscriptionChunk after = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.SUCCEEDED, after.status());
        assertEquals(2, after.segments().size());
        // 청크 기준 상대 시각으로 저장돼야 한다. 절대 시각으로 바꿔 저장하면 조립을 다시 할 수 없다.
        assertEquals(0, after.segments().get(0).startMs());
        assertEquals(26_000, after.segments().get(1).endMs());
        assertEquals(-0.36, after.segments().get(1).avgLogprob(), 1e-9);
        assertEquals(0.6612, after.segments().get(1).noSpeechProb(), 1e-9);
        assertNull(after.leaseUntil());
    }

    @Test
    void 무음_스킵은_성공과_구분되고_결과_문서를_남기지_않는다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed claimed = claimFirst();

        assertTrue(port.markSkippedSilent(claimed.chunk().id(), claimed.token(), NOW));

        TranscriptionChunk after = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.SKIPPED_SILENT, after.status());
        assertTrue(after.segments().isEmpty());
        assertTrue(after.status().contributesToTranscript());
        assertTrue(after.status().isTerminal());
    }

    @Test
    void 호출_후_무음인_것은_성공이며_빈_세그먼트로_남는다() {
        // markSkippedSilent 와 같은 결과처럼 보이지만 단계가 다르다. 호출했는지 여부가 그 구분이다.
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed claimed = claimFirst();

        assertTrue(port.markSucceeded(claimed.chunk().id(), claimed.token(), List.of(), NOW));

        TranscriptionChunk after = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.SUCCEEDED, after.status());
        assertTrue(after.segments().isEmpty());
    }

    @Test
    void 재시도_기록은_시도_횟수를_보존하고_기한_전에는_잡히지_않는다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed claimed = claimFirst();
        Instant nextAttemptAt = NOW.plusSeconds(30);

        assertTrue(port.markRetry(claimed.chunk().id(), claimed.token(), "timeout", nextAttemptAt, NOW));

        TranscriptionChunk after = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.PENDING, after.status());
        // 선점에서 이미 올렸으므로 여기서 또 올리면 한 번의 시도가 두 번으로 세어진다.
        assertEquals(1, after.attemptCount());
        assertNull(after.leaseUntil());
        assertEquals(nextAttemptAt, after.nextAttemptAt());

        assertFalse(port.findClaimable(FILE_ID, NOW.plusSeconds(10), 10).stream()
                .anyMatch(chunk -> chunk.id().equals(claimed.chunk().id())));
        assertTrue(port.findClaimable(FILE_ID, nextAttemptAt, 10).stream()
                .anyMatch(chunk -> chunk.id().equals(claimed.chunk().id())));
    }

    @Test
    void 최종_실패는_다시_잡히지_않는다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed claimed = claimFirst();

        assertTrue(port.markFailed(claimed.chunk().id(), claimed.token(), "unauthorized", NOW));

        TranscriptionChunk after = port.findAllBySessionId(SESSION_ID).get(0);
        assertEquals(TranscriptionChunkStatus.FAILED, after.status());
        assertTrue(after.status().isTerminal());
        assertFalse(after.status().contributesToTranscript());
        assertNull(after.nextAttemptAt());
        assertFalse(port.findClaimable(FILE_ID, NOW.plus(Duration.ofHours(1)), 10).stream()
                .anyMatch(chunk -> chunk.id().equals(claimed.chunk().id())));
    }

    @Test
    void 긴_오류_메시지는_컬럼_길이에_맞춰_잘린다() {
        // 자르지 않으면 UPDATE 가 통째로 실패해, 실패를 기록하려다 실패하고 청크가 PROCESSING 으로 남는다.
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        Claimed claimed = claimFirst();

        assertTrue(port.markFailed(claimed.chunk().id(), claimed.token(), "x".repeat(900), NOW));

        String stored = jdbcTemplate.queryForObject(
                "SELECT last_error FROM postclass_transcription_chunks WHERE id = ?",
                String.class,
                claimed.chunk().id());
        assertEquals(500, stored.length());
    }

    @Test
    void 다른_원본_파일의_청크는_섞이지_않는다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);
        port.registerAll(SESSION_ID, OTHER_FILE_ID, List.of(chunk(0, 0, 60_000)), NOW);

        assertEquals(3, port.findClaimable(FILE_ID, NOW, 10).size());
        assertEquals(1, port.findClaimable(OTHER_FILE_ID, NOW, 10).size());
        assertEquals(4, port.findAllBySessionId(SESSION_ID).size());
    }

    @Test
    void 세션_청크를_모두_지운다() {
        port.registerAll(SESSION_ID, FILE_ID, threeChunks(), NOW);

        port.deleteBySessionId(SESSION_ID);

        assertTrue(port.findAllBySessionId(SESSION_ID).isEmpty());
    }
}
