package com.a105.zani.recording.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.port.NewRecordingOutboxMessage;
import com.a105.zani.recording.application.port.PendingRecordingOutboxMessage;
import com.a105.zani.recording.application.port.RecordingOutboxType;
import com.a105.zani.recording.application.port.TrackEgressPayload;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * recording_outbox 어댑터의 DB 수준 계약 검증: dedup 충돌이 호출자 트랜잭션을 오염시키지 않는 것(INSERT IGNORE)과 claim의 원자 선점. 로컬 MySQL이 떠 있어야 통과한다.
 * 테스트 트랜잭션은 종료 시 롤백되어 데이터를 남기지 않는다.
 */
@SpringBootTest
class RecordingOutboxPersistenceAdapterTest {

    @Autowired
    private RecordingOutboxPersistenceAdapter adapter;

    private static NewRecordingOutboxMessage trackMessage(String dedupKey) {
        return new NewRecordingOutboxMessage(
                dedupKey,
                RecordingOutboxType.START_TRACK_EGRESS,
                9_000_100L,
                new TrackEgressPayload("TR_test", "student-001", TrackSource.MICROPHONE));
    }

    @Test
    @Transactional
    void 같은_dedup_key는_같은_트랜잭션_안에서도_조용히_거부되고_트랜잭션은_살아남는다() {
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
    void claim은_한_번만_성공하고_payload가_왕복된다() {
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
}
