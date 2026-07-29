package com.a105.zani.attention.domain.model;

/**
 * 학생이 이해 확인 프롬프트에 낸 답(확정 문서 §5). 30초가 지나면 브라우저가 패널을 닫고 {@link #NON_RESPONSE}를 보낸다.
 *
 * <p>자세 안내와 카메라 안내의 답은 여기 없다. 브라우저가 그 둘의 응답을 서버로 보내지 않기 때문이다(티켓 81). 자세 안내는 확인 버튼 하나뿐이라 남길 상태가 없고, 카메라 안내는 응답이 집계를 바꾸지
 * 않는다(§5.2) — 분모 제외는 서버가 검출기 이벤트로 직접 판단한다(§7.1).
 */
public enum PromptAnswer {

    /** 이해했어요. */
    OK(AttentionState.GOOD),

    /** 헷갈려요. */
    CONFUSED(AttentionState.CONFUSED),

    /** 놓쳤어요. */
    MISSED(AttentionState.MISSED),

    /** 30초 무응답. 브라우저가 패널을 닫으며 보낸다. */
    NON_RESPONSE(AttentionState.NON_RESPONSE);

    private final AttentionState state;

    PromptAnswer(AttentionState state) {
        this.state = state;
    }

    /** 이 답이 확정하는 참여 상태. 네 답 모두 상태를 남긴다. */
    public AttentionState confirmedState() {
        return state;
    }
}
