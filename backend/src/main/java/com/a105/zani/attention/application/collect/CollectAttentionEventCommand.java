package com.a105.zani.attention.application.collect;

import java.time.Instant;

import com.a105.zani.attention.domain.model.DetectionSignal;

/**
 * 브라우저 검출기가 낸 관측 한 건(확정 문서 §6). userId는 인증 주체에서, sessionId는 경로에서 온다.
 *
 * @param signal 검출기 출력과 저참여 여부
 * @param windowStartedAt 10초 창 시작 시각. 보는 순간 확정되는 출력은 창이 없어 null 이다(§4.2)
 * @param observedAt 이 값이 정해진 시각. 창이 있으면 창의 끝이다
 * @param signalQuality 유효 프레임 비율(0.0~1.0). 창이 없는 출력은 null
 * @param featureSchemaVersion 브라우저가 쓴 특징 추출 계약 버전
 * @param engineVersion 브라우저가 쓴 추론 엔진·모델 버전
 * @param clientEventId 클라이언트가 만든 이벤트 식별자. 재시도 멱등의 기준
 */
public record CollectAttentionEventCommand(
        Long sessionId,
        Long userId,
        DetectionSignal signal,
        Instant windowStartedAt,
        Instant observedAt,
        Double signalQuality,
        String featureSchemaVersion,
        String engineVersion,
        String clientEventId) {}
