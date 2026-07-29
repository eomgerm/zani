package com.a105.zani.attention.application.port;

/**
 * 강사에게 보여줄 완성된 팁 한 건.
 *
 * <p>문구는 §8 고정 템플릿에 퍼센트와 LLM 이 채운 두 자리를 넣어 <b>서버에서 완성한 상태</b>다. 프론트는 그대로 표시하고 유형별로 화면을 나누지 않는다(티켓 86).
 *
 * <p>학생 이름·개별 응답·개별 판정은 담지 않는다. 이 값은 강사 화면까지 그대로 흘러간다.
 *
 * @param tipType 고른 유형. 프론트는 검증과 로깅에만 쓴다
 * @param title 카드 제목
 * @param message 완성된 조언 문구
 * @param targetConcept LLM 이 채운 핵심 개념. 어느 대목에 대한 조언인지 강사가 알 수 있게 한다
 */
public record CoachingTip(CoachingTipType tipType, String title, String message, String targetConcept) {}
