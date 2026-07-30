package com.a105.zani.attention.application.port;

/**
 * 강사에게 보여줄 팁의 유형 5종(확정 문서 §7.6·§8).
 *
 * <p>어느 유형을 고를지는 §7.6 규칙이 정하고 문구는 §8 고정 템플릿이 정한다(티켓 204). 이 enum 은 그 <b>이름</b>만 담는다 — 폴링 응답에 실려 프론트 계약이 되므로 유형 선택 정책보다
 * 먼저 확정돼야 한다.
 *
 * <p>네 유의 상태와 이름을 맞춘 이유는 대응이 1:1 이기 때문이다. 복합 유형 하나만 더 있다.
 */
public enum CoachingTipType {
    /** 헷갈림이 가장 높다. */
    CONFUSED,

    /** 놓침이 가장 높다. */
    MISSED,

    /** 헷갈림과 놓침이 각각 20%를 넘고 둘의 합이 나머지보다 크다(§7.6-2). */
    CONFUSED_AND_MISSED,

    /** 무응답이 가장 높다. */
    NON_RESPONSE,

    /** 자리비움(측정 불가)이 가장 높다. */
    UNMEASURABLE
}
