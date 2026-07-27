package com.a105.zani.recording.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.port.NewRecordingOutboxMessage;
import com.a105.zani.recording.application.port.PendingRecordingOutboxMessage;
import com.a105.zani.recording.application.port.RecordingOutboxType;
import com.a105.zani.recording.application.port.TrackEgressPayload;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingOutboxJpaEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * recording_outbox 어댑터의 DB 수준 계약 검증: dedup 충돌이 호출자 트랜잭션을 오염시키지 않는 것(INSERT IGNORE), claim의 원자 선점, lease 만료 재큐잉. 릴레이의 중복
 * 방지가 실제 SQL로 성립하는지를 보는 테스트라 fake로 대체하지 않는다. 로컬 MySQL이 떠 있어야 통과하며, 테스트 트랜잭션은 종료 시 롤백되어 데이터를 남기지 않는다.
 */
@SpringBootTest
class RecordingOutboxPersistenceAdapterTest {

    /** claim 직후 시각보다 이만큼 뒤면 lease가 만료된 것으로 본다. */
    private static final int AFTER_LEASE_SECONDS = 1;
    /** claim 직후 시각보다 이만큼 앞이면 아직 lease 안이다. */
    private static final int WITHIN_LEASE_SECONDS = 60;

    @Autowired
    private RecordingOutboxPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static NewRecordingOutboxMessage trackMessage(String dedupKey) {
        return new NewRecordingOutboxMessage(
                dedupKey,
                RecordingOutboxType.START_TRACK_EGRESS,
                9_000_100L,
                new TrackEgressPayload("TR_test", "student-001", TrackSource.MICROPHONE));
    }

    // 상태 전이는 fetchDue(next_attempt_at·건수 제한에 걸린다)가 아니라 해당 행을 직접 읽어 확인한다.
    private Long idOf(String dedupKey) {
        return jdbcTemplate.queryForObject("SELECT id FROM recording_outbox WHERE dedup_key = ?", Long.class, dedupKey);
    }

    private String statusOf(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM recording_outbox WHERE dedup_key = ?", String.class, dedupKey);
    }

    private int attemptCountOf(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM recording_outbox WHERE dedup_key = ?", Integer.class, dedupKey);
    }

    @Test
    @Transactional
    void silentlyRejectsADuplicateKeyWithoutBreakingTheCallerTransaction() {
        String key = "test:dedup:" + System.nanoTime();

        assertTrue(adapter.enqueue(trackMessage(key)));
        // 중복 삽입: JPA flush 예외 없이 false만 반환해야 한다(rollback-only 오염 금지).
        assertFalse(adapter.enqueue(trackMessage(key)));
        // 같은 트랜잭션이 계속 사용 가능해야 한다 → 추가 삽입이 성공한다.
        String secondKey = "test:dedup:second:" + System.nanoTime();
        assertTrue(adapter.enqueue(trackMessage(secondKey)));
    }

    @Test
    @Transactional
    void claimsARowOnlyOnceAndRoundTripsThePayload() {
        String key = "test:claim:" + System.nanoTime();
        adapter.enqueue(trackMessage(key));

        Instant now = Instant.now();
        List<PendingRecordingOutboxMessage> due = adapter.fetchDue(50, now).stream()
                .filter(message -> key.equals(message.dedupKey()))
                .toList();
        assertEquals(1, due.size());
        PendingRecordingOutboxMessage message = due.get(0);
        // JSON 왕복 검증
        assertEquals("TR_test", message.payload().trackSid());
        assertEquals("student-001", message.payload().recordingAlias());
        assertEquals(TrackSource.MICROPHONE, message.payload().source());

        assertTrue(adapter.claim(message.id(), now));
        // 이미 IN_PROGRESS인 행은 다시 선점할 수 없다(중복 수행 방지).
        assertFalse(adapter.claim(message.id(), now));
    }

    @Test
    @Transactional
    void requeuesAClaimWhoseLeaseExpired() {
        String key = "test:requeue:" + System.nanoTime();
        adapter.enqueue(trackMessage(key));
        Long id = idOf(key);
        Instant claimedAt = Instant.now();
        assertTrue(adapter.claim(id, claimedAt));
        assertEquals(RecordingOutboxJpaEntity.STATUS_IN_PROGRESS, statusOf(key));
        Instant afterLease = claimedAt.plusSeconds(AFTER_LEASE_SECONDS);

        adapter.requeueExpiredClaims(afterLease, afterLease);

        // 처리 중 크래시로 방치된 행은 다시 PENDING이 되어 릴레이가 집어갈 수 있어야 한다.
        assertEquals(RecordingOutboxJpaEntity.STATUS_PENDING, statusOf(key));
        assertTrue(adapter.claim(id, afterLease));
    }

    @Test
    @Transactional
    void keepsAClaimThatIsStillWithinItsLease() {
        String key = "test:lease:" + System.nanoTime();
        adapter.enqueue(trackMessage(key));
        Long id = idOf(key);
        Instant claimedAt = Instant.now();
        assertTrue(adapter.claim(id, claimedAt));

        // 아직 lease가 살아 있는 행은 되돌리지 않는다. 되돌리면 처리 중인 작업이 중복 수행된다.
        adapter.requeueExpiredClaims(claimedAt.minusSeconds(WITHIN_LEASE_SECONDS), claimedAt);

        assertEquals(RecordingOutboxJpaEntity.STATUS_IN_PROGRESS, statusOf(key));
        assertFalse(adapter.claim(id, claimedAt));
    }

    @Test
    @Transactional
    void doesNotCountRequeueingAsAnotherAttempt() {
        String key = "test:requeue-attempt:" + System.nanoTime();
        adapter.enqueue(trackMessage(key));
        Long id = idOf(key);
        Instant claimedAt = Instant.now();
        adapter.claim(id, claimedAt);
        assertEquals(1, attemptCountOf(key));
        Instant afterLease = claimedAt.plusSeconds(AFTER_LEASE_SECONDS);

        adapter.requeueExpiredClaims(afterLease, afterLease);

        // 시도 횟수는 선점 시점에만 올린다. 재큐잉이 횟수를 올리면 백오프·최대 시도 상한이 앞당겨진다.
        assertEquals(1, attemptCountOf(key));
        assertTrue(adapter.claim(id, afterLease));
        assertEquals(2, attemptCountOf(key));
    }
}
