package com.a105.zani.coach.application.port;

import java.util.Optional;

/**
 * 전사 텍스트에서 팁 문구에 넣을 핵심 개념을 뽑는 포트. (S15P11A105-204)
 *
 * <p>구현은 실패를 흡수해 빈 값으로 돌려준다 — timeout·자격증명 오류·크레딧 소진·rate limit·서버 오류·스키마 위반이 모두 "팁을 만들지 않는다"로 같게 끝나고, 재시도가 없어 호출자가
 * 구분해서 할 수 있는 일이 없다. 팁이 없어도 수업은 계속된다(COACH-003).
 *
 * <p>203의 전사 포트가 예외를 던지는 것과 반대인데, 그쪽은 포트 소유자가 audioclip 이고 계약이 예외 전파였다. 이 포트는 coach 가 소유한다.
 */
public interface TipConceptPort {

    Optional<TipConcept> extract(TipConceptRequest request);
}
