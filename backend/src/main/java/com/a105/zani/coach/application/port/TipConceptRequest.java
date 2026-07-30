package com.a105.zani.coach.application.port;

import com.a105.zani.attention.application.port.CoachingTipType;

/**
 * 팁 문구의 {@code {핵심 개념}}·{@code {핵심 내용}} 자리를 채우기 위해 LLM 에 넘길 입력. (S15P11A105-204)
 *
 * <p>학생 이름·이메일·ID·개별 응답·개별 판정·영상·얼굴/시선/자세 값·학생 음성은 담지 않는다. 여기 담긴 것만 GMS 로 나간다.
 *
 * @param tipType 어떤 자리를 채울지 결정한다 — 헷갈림은 핵심 개념, 놓침·복합은 핵심 내용
 * @param transcriptTail 강사 발화 전사의 마지막 구간. 전체를 보내지 않는 이유는 300초 전사가 수천 자이고 트리거 직전 발화가 근거이기 때문이다
 * @param lectureTitle 수업 제목. 같은 낱말이 과목에 따라 다른 개념을 가리킬 수 있어 맥락으로 넣는다
 * @param elapsedMinutes 수업 시작 후 경과 분. 절대 시각을 넣지 않는 이유는 LLM 이 알아야 할 것이 "수업 몇 분째인가"뿐이라서다
 */
public record TipConceptRequest(
        CoachingTipType tipType, String transcriptTail, String lectureTitle, long elapsedMinutes) {}
