package com.a105.zani.postclass.application.port;

import java.util.List;

/**
 * 모델이 낸 학생 한 명분 결과. 검증 전 값이라 저장 타입과 분리한다.
 *
 * <p>구간 시각이 없다. 모델이 준 숫자는 쓰지 않고 서버가 구간 번호로 되돌리기 때문이다(FRD §17.6).
 *
 * <p>감정·성격·역량을 담는 필드는 없다(FRD §17.4).
 *
 * @param questionCount 학생이 남긴 질문 수. 서버가 채팅 행을 세지 않고 모델이 판단한다 — 공개 채팅에는 질문만 있지 않고("네", "감사합니다"), 물음표 없는 질문과 완곡한 요청("다시
 *     설명해주실 수 있나요")도 있어 문장을 읽어야 가려낼 수 있다
 */
public record StudentAnalysis(
        String participationSummary, int questionCount, List<RecommendationDraft> recommendations, QuizDraft quiz) {

    /**
     * 서버가 인정하는 추천 근거 유형. {@code report} 도메인의 enum 과 값이 같지만 문자열로 둔다 — 도메인 경계를 원시 타입으로 유지하고, LLM 스키마의 enum 목록과 한 자리에서
     * 관리한다. 목록이 바뀌면 프롬프트도 함께 바뀌므로 두 곳이 갈라질 여지가 작다.
     *
     * <p>다섯 가지는 관측 하나에 하나씩 대응한다 — 프롬프트 응답 셋(헷갈림·놓침·미응답), 참여도 판정, 질문. 유형별 개수 배분은 두지 않는다. 근거가 있는 구간을 고르고 그 구간의 가장 강한 근거를
     * 유형으로 붙이면, 다섯 개가 한 유형에 몰릴 수도 있고 한 유형도 안 나올 수도 있다.
     */
    public static final List<String> RECOMMENDATION_TYPES =
            List.of("CONFUSED", "MISSED", "NO_RESPONSE", "LOW_ENGAGEMENT", "QUESTION");

    public record RecommendationDraft(int sectionIndex, String type, String title, String description) {}

    public record QuizDraft(String title, String description, List<QuestionDraft> questions) {}

    /**
     * @param sectionIndex 이 문항의 근거가 되는 개념 구간 번호(1부터). 서버가 구간의 시작 시각으로 되돌려 "관련 강의 구간 다시 보기" 링크를 만든다. 범위 밖이면 그 문항의 구간만
     *     비운다 — 문항을 버리면 3~5개 불변식이 깨진다
     */
    public record QuestionDraft(int sectionIndex, String questionText, String explanation, List<OptionDraft> options) {}

    public record OptionDraft(String optionText, boolean correct) {}
}
