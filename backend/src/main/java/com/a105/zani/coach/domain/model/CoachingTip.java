package com.a105.zani.coach.domain.model;

/**
 * 강사에게 보여줄 완성된 팁. (S15P11A105-204)
 *
 * @param tipType 유형 5종. FE 가 카드 표현을 유형별로 다르게 할 수 있다
 * @param title §8 이 확정한 제목. 유형마다 고정이다
 * @param message §8 템플릿에 실제 퍼센트와 LLM 이 채운 개념을 넣은 문구
 * @param targetConcept LLM 이 채운 핵심 개념·내용. 자리표시자가 없는 유형(무응답·자리비움)은 비어 있다
 */
public record CoachingTip(CoachingTipType tipType, String title, String message, String targetConcept) {

    public CoachingTip {
        if (tipType == null) {
            throw new IllegalArgumentException("팁 유형이 없습니다.");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("팁 문구가 비었습니다.");
        }
    }
}
