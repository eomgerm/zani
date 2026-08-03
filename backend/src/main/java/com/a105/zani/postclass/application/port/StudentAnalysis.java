package com.a105.zani.postclass.application.port;

import java.util.List;

/**
 * 모델이 낸 학생 한 명분 결과. 검증 전 값이라 저장 타입과 분리한다.
 *
 * <p>구간 시각이 없다. 모델이 준 숫자는 쓰지 않고 서버가 구간 번호로 되돌리기 때문이다(FRD §17.6).
 *
 * <p>감정·성격·역량을 담는 필드는 없다(FRD §17.4).
 */
public record StudentAnalysis(String participationSummary, List<RecommendationDraft> recommendations, QuizDraft quiz) {

    /**
     * 서버가 인정하는 추천 유형. {@code report} 도메인의 enum 과 값이 같지만 문자열로 둔다 — 도메인 경계를 원시 타입으로 유지하고, LLM 스키마의 enum 목록과 한 자리에서 관리한다.
     * 목록이 바뀌면 프롬프트도 함께 바뀌므로 두 곳이 갈라질 여지가 작다.
     */
    public static final List<String> RECOMMENDATION_TYPES = List.of("CONFUSED", "MISSED", "QUESTION", "REPEAT");

    public record RecommendationDraft(int sectionIndex, String type, String title, String description) {}

    public record QuizDraft(String title, String description, List<QuestionDraft> questions) {}

    public record QuestionDraft(String questionText, String explanation, List<OptionDraft> options) {}

    public record OptionDraft(String optionText, boolean correct) {}
}
