package com.a105.zani.attention.application.getdenominator;

/**
 * 강사 집단 트리거의 분모를 계산하는 읽기 유스케이스(확정 문서 §7).
 *
 * <p>분자(§7.3)를 세는 티켓 79 와 트리거를 판단하는 티켓 85 가 이 결과를 쓴다.
 */
public interface GetDenominatorUseCase {

    GetDenominatorResult get(GetDenominatorQuery query);
}
