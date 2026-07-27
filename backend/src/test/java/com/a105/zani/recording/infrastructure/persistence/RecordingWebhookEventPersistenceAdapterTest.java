package com.a105.zani.recording.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * webhook 이벤트 스토어의 DB 수준 계약 검증: event_id UNIQUE + INSERT IGNORE 중복 흡수, PROCESSED/RECEIVED에 따른 재처리 판정. 로컬 MySQL이 떠 있어야
 * 통과한다. 테스트 트랜잭션은 종료 시 롤백된다.
 */
@SpringBootTest
class RecordingWebhookEventPersistenceAdapterTest {

    @Autowired
    private RecordingWebhookEventPersistenceAdapter adapter;

    @Test
    @Transactional
    void 처음_수신한_이벤트는_처리_가능하다() {
        String eventId = "test-ev-" + System.nanoTime();

        assertTrue(adapter.begin(eventId, "egress_ended", "{}"));
    }

    @Test
    @Transactional
    void RECEIVED로_남은_이벤트는_재처리를_허용하고_PROCESSED는_거부한다() {
        String eventId = "test-ev-" + System.nanoTime();
        assertTrue(adapter.begin(eventId, "egress_ended", "{}"));

        // 처리 실패로 RECEIVED로 남은 상태의 재전송 → 재처리 허용.
        assertTrue(adapter.begin(eventId, "egress_ended", "{}"));

        adapter.markProcessed(eventId);
        // 처리 완료된 이벤트의 재전송 → 중복으로 거부.
        assertFalse(adapter.begin(eventId, "egress_ended", "{}"));
    }
}
