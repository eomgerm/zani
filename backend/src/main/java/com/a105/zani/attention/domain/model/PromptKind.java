package com.a105.zani.attention.domain.model;

import java.util.Optional;
import java.util.Set;

/**
 * 학생에게 띄우는 프롬프트 3종(확정 문서 §3). 종류마다 고를 수 있는 답이 다르다.
 *
 * <p>프롬프트를 띄울지는 브라우저가 판정하고(75), 서버는 그 결과만 받는다.
 */
public enum PromptKind {

    /** 이해 확인 — AI 1·2단계 3연속일 때. 이해했어요 / 헷갈려요 / 놓쳤어요. */
    UNDERSTANDING_CHECK(
            Set.of(PromptAnswer.UNDERSTOOD, PromptAnswer.CONFUSED, PromptAnswer.MISSED, PromptAnswer.NO_RESPONSE)),

    /** 자세 안내 — 얼굴 판단 불가 3연속일 때. 확인 버튼 하나뿐이다. */
    POSTURE_GUIDE(Set.of(PromptAnswer.ACKNOWLEDGED, PromptAnswer.NO_RESPONSE)),

    /** 카메라 확인 — 카메라 OFF 감지. 예 / 아니오. */
    CAMERA_CHECK(Set.of(PromptAnswer.CAMERA_UNAVAILABLE, PromptAnswer.CAMERA_AVAILABLE, PromptAnswer.NO_RESPONSE));

    private final Set<PromptAnswer> allowedAnswers;

    PromptKind(Set<PromptAnswer> allowedAnswers) {
        this.allowedAnswers = allowedAnswers;
    }

    /** 이 종류의 프롬프트에 낼 수 있는 답인지. 다른 종류의 답이 섞이면 집계가 뒤틀린다. */
    public boolean allows(PromptAnswer answer) {
        return allowedAnswers.contains(answer);
    }

    /**
     * 이 답이 참여 상태를 확정하는지. 없으면 현재 상태를 그대로 둔다.
     *
     * <p><b>이해 확인만 상태를 남긴다</b>(확정 문서 §3). 자세 안내와 카메라 확인은 무응답이 흔하고 정상이다. 얼굴이 안 보여서 뜬 자세 안내에 학생이 답하지 않는 것은 당연한데, 이걸
     * NON_RESPONSE 로 남기면 그 학생이 코칭 분자에 계속 끼어 "학생 반응을 확인해 주세요" 팁이 잘못 뜬다.
     */
    public Optional<AttentionState> confirmedState(PromptAnswer answer) {
        return this == UNDERSTANDING_CHECK ? answer.confirmedState() : Optional.empty();
    }
}
