package com.a105.zani.coach.infrastructure.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.session.application.create.SessionCreatedEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SessionCreatedEvent → 비동기 리스너 → CheckCoachingAvailability → Redis 저장까지 전 구간을 검증한다. local 프로필(mock=true)이므로 실제 GMS 호출
 * 없이 가용=true 가 저장돼야 한다. 로컬 MySQL·Redis 가 떠 있어야 통과한다.
 */
@SpringBootTest
class CoachingAvailabilityEventIntegrationTest {

    private static final long SESSION_ID = -900002L;
    private static final String KEY = "coach:available:" + SESSION_ID;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
    }

    @Test
    void sessionCreatedEventStoresCoachingAvailability() throws InterruptedException {
        redisTemplate.delete(KEY);

        eventPublisher.publishEvent(new SessionCreatedEvent(SESSION_ID, -1L));

        String value = null;
        for (int attempt = 0; attempt < 30 && value == null; attempt++) {
            Thread.sleep(100);
            value = redisTemplate.opsForValue().get(KEY);
        }
        assertEquals("true", value);
    }
}
