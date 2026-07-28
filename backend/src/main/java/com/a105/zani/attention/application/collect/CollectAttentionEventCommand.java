package com.a105.zani.attention.application.collect;

import java.time.Instant;

import com.a105.zani.attention.domain.model.AttentionState;

/**
 * 학생 브라우저가 보낸 10초 판정 1건. userId는 인증 주체에서, sessionId는 경로에서 온다.
 *
 * @param clientEventId 클라이언트가 만든 이벤트 식별자. 재시도 멱등의 기준이다.
 */
public record CollectAttentionEventCommand(
        Long sessionId,
        Long userId,
        AttentionState state,
        Instant startedAt,
        Instant endedAt,
        int durationSec,
        double signalQuality,
        String clientEventId) {}
