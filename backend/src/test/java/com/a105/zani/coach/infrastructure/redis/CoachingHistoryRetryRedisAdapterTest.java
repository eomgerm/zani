package com.a105.zani.coach.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.coach.application.port.CoachingHistoryRetryQueuePort;
import com.a105.zani.coach.application.retryhistory.PendingCoachingHistoryRetry;
import com.a105.zani.coach.application.storehistory.CoachingHistory;
import com.a105.zani.coach.application.storehistory.CoachingResponseCounts;
import com.a105.zani.coach.application.storehistory.CoachingTranscript;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "coach.history-retry-delay=PT1H")
class CoachingHistoryRetryRedisAdapterTest {

    private static final String RETRY_KEY = "coach:history:retries";
    private static final Instant NOW = Instant.parse("2026-07-31T01:00:00Z");

    @Autowired
    private CoachingHistoryRetryQueuePort retryQueuePort;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void prepareQueue() {
        redisTemplate.delete(RETRY_KEY);
    }

    @AfterEach
    void clearQueue() {
        redisTemplate.delete(RETRY_KEY);
    }

    @Test
    void retainsTheWholeSessionOwnedResultUntilItIsAcknowledged() {
        CoachingHistory history = history();
        retryQueuePort.enqueue(history, NOW);

        List<PendingCoachingHistoryRetry> claimed = retryQueuePort.claimDue(10, NOW, Duration.ofMinutes(1));

        assertThat(claimed).singleElement().satisfies(pending -> {
            assertThat(pending.history()).isEqualTo(history);
            assertThat(pending.attempt()).isZero();
            retryQueuePort.acknowledge(pending);
        });
        assertThat(redisTemplate.opsForZSet().size(RETRY_KEY)).isZero();
    }

    private CoachingHistory history() {
        return new CoachingHistory(
                7L,
                "trigger-1",
                NOW.minusSeconds(1),
                NOW,
                new CoachingResponseCounts(10, 4, 3, 1, 0, 0),
                CoachingTipType.CONFUSED,
                CoachingTranscript.transcribed(NOW.minusSeconds(30).toEpochMilli(), NOW.toEpochMilli()),
                "binary tree",
                new CoachingTip(CoachingTipType.CONFUSED, "title", "message", "binary tree"),
                null);
    }
}
