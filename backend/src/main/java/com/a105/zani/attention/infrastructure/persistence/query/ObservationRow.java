package com.a105.zani.attention.infrastructure.persistence.query;

/**
 * attention_events 한 행의 조회 결과.
 *
 * <p>{@code detectorOutcome} 이 문자열인 것은 컬럼이 문자열이기 때문이다. enum 으로 바로 받으면 서버가 모르는 값 하나에 쿼리 전체가 실패한다 — 검출기 계약이 넓어져 서버보다 앞선
 * 값이 먼저 저장될 수 있으므로 변환은 어댑터에서 하고 모르는 값은 건너뛴다.
 */
public record ObservationRow(Long participantId, Long offsetMs, String detectorOutcome) {}
