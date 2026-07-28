package com.a105.zani.attention.application.port;

import java.time.Instant;

import com.a105.zani.attention.domain.model.AttentionState;

/**
 * 참가자의 현재 판정 상태 한 건.
 *
 * @param state 판정된 참여 상태
 * @param signalQuality 유효 프레임 비율(0.0~1.0). 측정 가능 학생 비율 계산에 쓴다.
 * @param recordedAt 서버가 판정을 받은 시각(UTC). 클라이언트 시계가 아니라 서버 시계다.
 */
public record AttentionSnapshot(AttentionState state, double signalQuality, Instant recordedAt) {}
