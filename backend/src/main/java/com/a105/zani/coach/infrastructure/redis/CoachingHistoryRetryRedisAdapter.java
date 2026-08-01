package com.a105.zani.coach.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.a105.zani.coach.application.port.CoachingHistoryRetryQueuePort;
import com.a105.zani.coach.application.retryhistory.PendingCoachingHistoryRetry;
import com.a105.zani.coach.application.storehistory.CoachingHistory;

/** Redis sorted-set retry queue. Updating a due score acts as a recoverable processing lease. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachingHistoryRetryRedisAdapter implements CoachingHistoryRetryQueuePort {

    private static final String RETRY_KEY = "coach:history:retries";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void enqueue(CoachingHistory history, Instant dueAt) {
        redisTemplate.opsForZSet().add(RETRY_KEY, write(new RetryEnvelope(history, 0)), dueAt.toEpochMilli());
    }

    @Override
    public List<PendingCoachingHistoryRetry> claimDue(int limit, Instant now, Duration lease) {
        Set<String> entries = redisTemplate.opsForZSet().rangeByScore(RETRY_KEY, 0, now.toEpochMilli(), 0, limit);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<PendingCoachingHistoryRetry> claimed = new ArrayList<>(entries.size());
        double leaseUntil = now.plus(lease).toEpochMilli();
        for (String entry : entries) {
            try {
                RetryEnvelope envelope = objectMapper.readValue(entry, RetryEnvelope.class);
                redisTemplate.opsForZSet().add(RETRY_KEY, entry, leaseUntil);
                claimed.add(new PendingCoachingHistoryRetry(entry, envelope.history(), envelope.attempt()));
            } catch (JacksonException | IllegalArgumentException exception) {
                redisTemplate.opsForZSet().remove(RETRY_KEY, entry);
                log.error("Discarded an unreadable coaching-history retry entry", exception);
            }
        }
        return claimed;
    }

    @Override
    public void acknowledge(PendingCoachingHistoryRetry pending) {
        redisTemplate.opsForZSet().remove(RETRY_KEY, pending.queueEntry());
    }

    @Override
    public void reschedule(PendingCoachingHistoryRetry pending, Instant dueAt) {
        String nextEntry = write(new RetryEnvelope(pending.history(), pending.attempt() + 1));
        redisTemplate.opsForZSet().add(RETRY_KEY, nextEntry, dueAt.toEpochMilli());
        redisTemplate.opsForZSet().remove(RETRY_KEY, pending.queueEntry());
    }

    private String write(RetryEnvelope envelope) {
        return objectMapper.writeValueAsString(envelope);
    }

    private record RetryEnvelope(CoachingHistory history, int attempt) {}
}
