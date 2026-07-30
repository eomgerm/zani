package com.a105.zani.attention.application.port;

import java.util.OptionalLong;

import com.a105.zani.attention.domain.model.UnmeasurableRun;

/**
 * 관측 하나를 집계 상태에 반영한 결과.
 *
 * <p>연속 횟수와 측정 불가 구간을 함께 돌려주는 이유는 저장소가 둘을 한 번에 갱신하기 때문이다. 따로 물으면 그 사이에 다른 관측이 끼어들어 서로 다른 시점의 값을 보게 된다.
 *
 * @param unmeasurableRun 적용된 뒤의 {@code UNMEASURABLE} 연속 횟수(§7.3)
 * @param measurementOutageMs 이어지고 있는 측정 불가 구간의 길이(ms). 측정이 가능한 관측이면 비어 있다(§7.1)
 */
public record ObservationApplied(UnmeasurableRun unmeasurableRun, OptionalLong measurementOutageMs) {}
