package com.a105.zani.attention.application.getcoachingsignals;

/**
 * 최근 5분 유의 학생 비율과 익명 분포를 계산하는 읽기 유스케이스(확정 문서 §7·§7.3).
 *
 * <p>트리거를 판단하고 팁 유형을 고르는 티켓 85 가 이 결과를 쓴다.
 */
public interface GetCoachingSignalsUseCase {

    GetCoachingSignalsResult get(GetCoachingSignalsQuery query);
}
