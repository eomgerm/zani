package com.a105.zani.attention.domain.model;

import java.util.Set;

/**
 * 서버가 기록하는 프롬프트 종류(확정 문서 §5).
 *
 * <p>문서의 프롬프트는 3종이지만 서버에 도달하는 것은 이해 확인 하나뿐이다. 브라우저가 자세 안내·카메라 안내의 응답을 보내지 않기 때문이다(티켓 81). 자세 안내는 확인 버튼 하나뿐이라 남길 상태가 없고,
 * 카메라 안내는 응답이 집계를 바꾸지 않는다(§5.2).
 *
 * <p>노출 30초와 종류별 5분 쿨타임, 카메라 안내 재권유 여부도 모두 브라우저가 들고 있고 서버에 남기지 않는다. 프롬프트를 띄울지도 브라우저가 판정한다(75).
 */
public enum PromptKind {

    /** 이해 확인 — 저참여 3연속일 때. 이해했어요 / 헷갈려요 / 놓쳤어요, 30초 무응답. */
    UNDERSTANDING_CHECK(Set.of(PromptAnswer.OK, PromptAnswer.CONFUSED, PromptAnswer.MISSED, PromptAnswer.NON_RESPONSE));

    private final Set<PromptAnswer> allowedAnswers;

    PromptKind(Set<PromptAnswer> allowedAnswers) {
        this.allowedAnswers = allowedAnswers;
    }

    /** 이 종류의 프롬프트에 낼 수 있는 답인지. 다른 종류의 답이 섞이면 집계가 뒤틀린다. */
    public boolean allows(PromptAnswer answer) {
        return allowedAnswers.contains(answer);
    }
}
