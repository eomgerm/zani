package com.a105.zani.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.notification.application.port.NewNotification;
import com.a105.zani.notification.application.port.PendingNotification;
import com.a105.zani.notification.infrastructure.persistence.entity.NotificationOutboxJpaEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * notification_outbox 어댑터의 DB 수준 계약 검증: dedup_key 충돌을 호출자 트랜잭션 오염 없이 흡수하는 것(INSERT IGNORE), claim의 원자 선점, lease 만료 재큐잉,
 * 발송 성공·재시도·실패 기록. consumer의 멱등·실패 기록이 실제 SQL로 성립하는지를 보는 테스트라 fake로 대체하지 않는다. 로컬 MySQL이 떠 있어야 통과하며, 테스트 트랜잭션은 종료 시 롤백되어
 * 데이터를 남기지 않는다.
 */
@SpringBootTest
class NotificationOutboxPersistenceAdapterTest {

    private static final long SESSION_ID = 9_000_200L;
    /** claim 직후 시각보다 이만큼 뒤면 lease가 만료된 것으로 본다. */
    private static final int AFTER_LEASE_SECONDS = 1;
    /** claim 직후 시각보다 이만큼 앞이면 아직 lease 안이다. */
    private static final int WITHIN_LEASE_SECONDS = 60;

    @Autowired
    private NotificationOutboxPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // mark* 는 JPA 더티체킹으로 반영되므로, 롤백 트랜잭션 안에서 raw SQL 로 검증하기 전에 명시적으로 flush 한다.
    @PersistenceContext
    private EntityManager entityManager;

    private static NewNotification notification(String dedupKey, long memberId) {
        return new Notification(dedupKey, memberId).toRecord();
    }

    /** 테스트 가독성을 위한 소형 빌더. */
    private record Notification(String dedupKey, long memberId) {
        NewNotification toRecord() {
            return new NewNotification(
                    SESSION_ID, memberId, "s" + memberId + "@zani.app", "학생" + memberId, "REPORT_READY", dedupKey);
        }
    }

    private Long idOf(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM notification_outbox WHERE dedup_key = ?", Long.class, dedupKey);
    }

    private String statusOf(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_outbox WHERE dedup_key = ?", String.class, dedupKey);
    }

    private int attemptCountOf(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM notification_outbox WHERE dedup_key = ?", Integer.class, dedupKey);
    }

    private int rowCountOf(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE dedup_key = ?", Integer.class, dedupKey);
    }

    @Test
    @Transactional
    void ignoresADuplicateDedupKeyWithoutBreakingTheCallerTransaction() {
        String key = "test:dedup:" + System.nanoTime();
        Instant now = Instant.now();

        assertEquals(1, adapter.enqueueAll(List.of(notification(key, 1L)), now));
        // 중복 등록: JPA flush 예외 없이 0건만 반환해야 한다(rollback-only 오염 금지).
        assertEquals(0, adapter.enqueueAll(List.of(notification(key, 1L)), now));
        assertEquals(1, rowCountOf(key));
        // 같은 트랜잭션이 계속 사용 가능해야 한다 → 다른 키 등록이 성공한다.
        String other = "test:dedup:other:" + System.nanoTime();
        assertEquals(1, adapter.enqueueAll(List.of(notification(other, 2L)), now));
    }

    @Test
    @Transactional
    void enqueuesEveryRecipientOfABatchInOneCall() {
        String keyA = "test:batch:a:" + System.nanoTime();
        String keyB = "test:batch:b:" + System.nanoTime();

        int inserted = adapter.enqueueAll(List.of(notification(keyA, 1L), notification(keyB, 2L)), Instant.now());

        assertEquals(2, inserted);
        assertEquals(1, rowCountOf(keyA));
        assertEquals(1, rowCountOf(keyB));
    }

    @Test
    @Transactional
    void claimsARowOnlyOnce() {
        String key = "test:claim:" + System.nanoTime();
        Instant now = Instant.now();
        adapter.enqueueAll(List.of(notification(key, 1L)), now);
        Long id = idOf(key);

        List<PendingNotification> due = adapter.fetchDue(50, now).stream()
                .filter(pending -> pending.id().equals(id))
                .toList();
        assertEquals(1, due.size());

        assertTrue(adapter.claim(id, now));
        // 이미 IN_PROGRESS인 행은 다시 선점할 수 없다(중복 발송 방지).
        assertFalse(adapter.claim(id, now));
        assertEquals(NotificationOutboxJpaEntity.STATUS_IN_PROGRESS, statusOf(key));
        assertEquals(1, attemptCountOf(key));
    }

    @Test
    @Transactional
    void claimedRowIsNoLongerReturnedByFetchDue() {
        String key = "test:fetch:" + System.nanoTime();
        Instant now = Instant.now();
        adapter.enqueueAll(List.of(notification(key, 1L)), now);
        Long id = idOf(key);
        adapter.claim(id, now);

        boolean stillDue = adapter.fetchDue(50, now).stream()
                .anyMatch(pending -> pending.id().equals(id));

        assertFalse(stillDue);
    }

    @Test
    @Transactional
    void requeuesAClaimWhoseLeaseExpired() {
        String key = "test:requeue:" + System.nanoTime();
        Instant claimedAt = Instant.now();
        adapter.enqueueAll(List.of(notification(key, 1L)), claimedAt);
        Long id = idOf(key);
        assertTrue(adapter.claim(id, claimedAt));
        Instant afterLease = claimedAt.plusSeconds(AFTER_LEASE_SECONDS);

        adapter.requeueExpiredClaims(afterLease, afterLease);

        // 처리 중 크래시로 방치된 행은 다시 PENDING이 되어 consumer가 집어갈 수 있어야 한다.
        assertEquals(NotificationOutboxJpaEntity.STATUS_PENDING, statusOf(key));
        assertTrue(adapter.claim(id, afterLease));
    }

    @Test
    @Transactional
    void keepsAClaimThatIsStillWithinItsLease() {
        String key = "test:lease:" + System.nanoTime();
        Instant claimedAt = Instant.now();
        adapter.enqueueAll(List.of(notification(key, 1L)), claimedAt);
        Long id = idOf(key);
        assertTrue(adapter.claim(id, claimedAt));

        // 아직 lease가 살아 있는 행은 되돌리지 않는다. 되돌리면 처리 중인 발송이 중복된다.
        adapter.requeueExpiredClaims(claimedAt.minusSeconds(WITHIN_LEASE_SECONDS), claimedAt);

        assertEquals(NotificationOutboxJpaEntity.STATUS_IN_PROGRESS, statusOf(key));
        assertFalse(adapter.claim(id, claimedAt));
    }

    @Test
    @Transactional
    void doesNotCountRequeueingAsAnotherAttempt() {
        String key = "test:requeue-attempt:" + System.nanoTime();
        Instant claimedAt = Instant.now();
        adapter.enqueueAll(List.of(notification(key, 1L)), claimedAt);
        Long id = idOf(key);
        adapter.claim(id, claimedAt);
        assertEquals(1, attemptCountOf(key));
        Instant afterLease = claimedAt.plusSeconds(AFTER_LEASE_SECONDS);

        adapter.requeueExpiredClaims(afterLease, afterLease);

        // 시도 횟수는 선점 시점에만 올린다. 재큐잉이 횟수를 올리면 백오프·최대 시도 상한이 앞당겨진다.
        assertEquals(1, attemptCountOf(key));
        assertTrue(adapter.claim(id, afterLease));
        assertEquals(2, attemptCountOf(key));
    }

    @Test
    @Transactional
    void marksSentWithTimestamp() {
        String key = "test:sent:" + System.nanoTime();
        Instant now = Instant.now();
        adapter.enqueueAll(List.of(notification(key, 1L)), now);
        Long id = idOf(key);

        adapter.markSent(id, now);
        entityManager.flush();

        assertEquals(NotificationOutboxJpaEntity.STATUS_SENT, statusOf(key));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM notification_outbox WHERE id = ? AND sent_at IS NOT NULL",
                        Integer.class,
                        id));
    }

    @Test
    @Transactional
    void marksRetryBackToPendingWithReasonAndSchedule() {
        String key = "test:retry:" + System.nanoTime();
        Instant now = Instant.now();
        adapter.enqueueAll(List.of(notification(key, 1L)), now);
        Long id = idOf(key);
        adapter.claim(id, now);
        Instant nextAttempt = now.plusSeconds(60);

        adapter.markRetry(id, "smtp down", nextAttempt);
        entityManager.flush();

        // 실패는 사유로 기록되고 재시도를 위해 PENDING으로 돌아간다.
        assertEquals(NotificationOutboxJpaEntity.STATUS_PENDING, statusOf(key));
        assertEquals(
                "smtp down",
                jdbcTemplate.queryForObject(
                        "SELECT last_error FROM notification_outbox WHERE id = ?", String.class, id));
        // 백오프로 다음 시도 시각이 미뤄져, 그 전에는 fetchDue가 집지 않는다.
        assertFalse(adapter.fetchDue(50, now).stream()
                .anyMatch(pending -> pending.id().equals(id)));
    }

    @Test
    @Transactional
    void marksFailedWithReason() {
        String key = "test:failed:" + System.nanoTime();
        Instant now = Instant.now();
        adapter.enqueueAll(List.of(notification(key, 1L)), now);
        Long id = idOf(key);

        adapter.markFailed(id, "gave up");
        entityManager.flush();

        assertEquals(NotificationOutboxJpaEntity.STATUS_FAILED, statusOf(key));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM notification_outbox WHERE id = ? AND sent_at IS NULL",
                        Integer.class,
                        id));
    }
}
