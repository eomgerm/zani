package com.a105.zani.coach.application.port;

import java.util.Optional;

/**
 * 전사 텍스트에서 팁 문구에 넣을 핵심 개념을 뽑는 포트. (S15P11A105-204)
 *
 * <p>두 실패를 구분해 돌려준다. 합치면 로그에서 "GMS 가 느리다"와 "강사가 설명한 내용이 없었다"를 가려낼 수 없고, 고칠 방향도 달라진다.
 *
 * <ul>
 *   <li><b>빈 값</b> — 기술적 실패다. timeout·자격증명 오류·크레딧 소진·rate limit·서버 오류·스키마 위반. 파이프라인은 {@code TIP_GENERATION_FAILED} 로
 *       다룬다
 *   <li><b>{@link TipConcept#isUsable()} 이 false 인 값</b> — 호출은 정상이었지만 쓸 근거가 없다. 강사가 인사·출석 확인만 한 구간이거나, 모델이 낸 근거 구절이 전사에
 *       없어 검증에 실패한 경우다. 파이프라인은 {@code LOW_CONFIDENCE} 로 다룬다
 * </ul>
 *
 * <p>어느 쪽이든 예외를 던지지 않는다 — 팁이 없어도 수업은 계속된다(COACH-003). 203 의 전사 포트가 예외를 던지는 것과 반대인데, 그쪽은 포트 소유자가 audioclip 이고 계약이 예외
 * 전파였다. 이 포트는 coach 가 소유한다.
 */
public interface TipConceptPort {

    Optional<TipConcept> extract(TipConceptRequest request);
}
