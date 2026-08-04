package com.a105.zani.postclass.application.port;

import java.util.List;

/**
 * 모델이 낸 강사 분석 결과. 검증 전 값이라 저장 타입과 분리한다.
 *
 * <p>구간 시각이 없다. 모델이 준 숫자는 쓰지 않고 서버가 구간 번호로 되돌린다(FRD §17.6).
 *
 * <p>수업 전체를 요약하는 단일 점수·등급 필드가 없다(FRD §17.7, REPORT-I-008).
 *
 * @param questionCount 수업 전체에서 학생들이 남긴 질문 수. 서버가 채팅 행을 세지 않고 모델이 판단한다 — 공개 채팅에는 질문만 있지 않고("네", "감사합니다"), 물음표 없는 질문과 완곡한
 *     요청("다시 설명해주실 수 있나요")도 있어 문장을 읽어야 가려낼 수 있다
 */
public record InstructorAnalysis(
        int questionCount, String overallFeedback, Scores scores, List<InsightDraft> insights) {

    /**
     * 서버가 인정하는 근거 종류. {@code report} 도메인에 저장하지 않는다 — 화면이 근거 종류를 목록으로 보여주지 않고, 이 값의 역할은 AI-008(근거 두 종류 이상 결합)을 응답 스키마로
     * 강제할 자리를 만드는 것이다.
     */
    public static final List<String> EVIDENCE_KINDS = List.of(
            "ATTENTION_FLOW",
            "CHECK_RESPONSE",
            "CHAT",
            "SECTION_SUMMARY",
            "INTERACTION",
            "INSTRUCTOR_NOTE",
            "COACHING_TIP");

    /** AI-008 — 수업 품질 피드백은 두 종류 이상의 근거를 결합해야 한다. 응답 스키마의 {@code minItems} 가 이 값이다. */
    public static final int MIN_EVIDENCE_KINDS = 2;

    /** 수업 품질 유형별 평가. 네 값을 합쳐 하나로 축약하는 필드나 메서드를 두지 않는다. */
    public record Scores(int delivery, int structureFlow, int interaction, int difficultyControl) {}

    /**
     * @param sectionIndex 이 인사이트가 짚는 개념 구간 번호(1부터). 0 이면 전체 수업 대상이다. 서버가 구간의 시작·종료 시각으로 되돌린다
     * @param evidence 관찰한 근거. {@code instructor_report_insights.content} 로 저장된다
     */
    public record InsightDraft(
            int sectionIndex, String title, String evidence, String suggestion, List<String> evidenceKinds) {}
}
