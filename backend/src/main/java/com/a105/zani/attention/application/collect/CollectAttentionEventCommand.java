package com.a105.zani.attention.application.collect;

import java.time.Instant;

import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 브라우저 검출기가 낸 관측 한 건(확정 문서 §6). userId는 인증 주체에서, sessionId는 경로에서 온다.
 *
 * @param outcome 검출기 출력 7종 중 하나(§1)
 * @param windowStartedAt 10초 창 시작 시각. 없으면 null — 기록에만 쓴다
 * @param observedAt 이 값이 정해진 시각. 창이 있으면 창의 끝이다
 * @param signalQuality 유효 프레임 비율(0.0~1.0). 없으면 null
 * @param featureSchemaVersion 브라우저가 쓴 특징 추출 계약 버전
 * @param clientEventId 클라이언트가 만든 이벤트 식별자. 재시도 멱등의 기준
 */
public record CollectAttentionEventCommand(
        Long sessionId,
        Long userId,
        DetectorOutcome outcome,
        Instant windowStartedAt,
        Instant observedAt,
        Double signalQuality,
        String featureSchemaVersion,
        String clientEventId) {}
