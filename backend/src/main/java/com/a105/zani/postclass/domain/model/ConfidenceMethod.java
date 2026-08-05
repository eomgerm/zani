package com.a105.zani.postclass.domain.model;

/**
 * 세그먼트 {@code confidence} 를 만든 방법(S15P11A105-247).
 *
 * <p>값을 문자열로 두지 않고 enum 으로 두는 이유는 <b>식을 바꿀 때 드러나게 하려는 것</b>이다. 문자열이면 새 식을 넣으면서 값을 바꾸는 것을 잊을 수 있고, 그러면 옛 값과 새 값이 같은 이름으로
 * 섞인다. 상수를 더해야만 새 식을 쓸 수 있게 해 두면 그 실수가 컴파일 단계에서 막힌다.
 *
 * <p>소비자는 이 값을 보고 {@code confidence} 를 어떻게 해석할지 정한다. 없으면 "0.8 이 무엇의 0.8 인가" 를 알 방법이 없다.
 */
public enum ConfidenceMethod {
    /**
     * {@code exp(avgLogprob)}.
     *
     * <p>보정된 정답 확률이 아니라 휴리스틱이다. GMS 가 주는 것은 토큰당 평균 로그확률뿐이고, 그것을 0~1 로 옮기는 가장 단순한 변환이다. {@code avgLogprob} 원값을 함께 저장하므로
     * 나중에 다른 식으로 다시 계산할 수 있다.
     */
    EXP_AVG_LOGPROB
}
