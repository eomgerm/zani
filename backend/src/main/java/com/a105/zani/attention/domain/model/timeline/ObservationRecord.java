package com.a105.zani.attention.domain.model.timeline;

import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 저장된 검출기 관측 한 건. 재생기가 읽는 최소 단위다.
 *
 * @param participantId 관측을 보낸 세션 참가자
 * @param offsetMs 세션 시작 기준 경과 시각(ms)
 * @param outcome 브라우저가 판정한 관측 값
 */
public record ObservationRecord(long participantId, long offsetMs, DetectorOutcome outcome) {}
