package com.a105.zani.attention.infrastructure.persistence.query;

/**
 * attention_events 한 행의 조회 결과.
 *
 * <p>{@code detectorOutcome} 이 문자열인 것은 컬럼이 문자열이기 때문이다. enum 으로 바로 받으면 서버가 모르는 값 하나에 쿼리 전체가 실패한다 — 검출기 계약이 넓어져 서버보다 앞선
 * 값이 먼저 저장될 수 있으므로 변환은 어댑터에서 하고 모르는 값은 건너뛴다.
 *
 * <p>{@code windowStartedOffsetMs} 는 nullable 이다. 수집 계약이 선택 값으로 두고 "수업 후 리포트의 근거로만 쓴다" 고 적어 둔 컬럼이라, 그 근거를 쓰는 이 쿼리가 함께
 * 읽지 않으면 존재할 이유가 없다.
 */
public record ObservationRow(
        Long participantId, Long occurredOffsetMs, Long windowStartedOffsetMs, String detectorOutcome) {}
