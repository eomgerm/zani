package com.a105.zani.coach.domain.model;

import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;

/**
 * §8 고정 템플릿에 실제 퍼센트와 LLM 이 채운 개념을 넣어 팁 문구를 완성한다. (S15P11A105-204)
 *
 * <p>LLM 이 문구 전체를 쓰지 않는 이유: 같은 상황에서 조언이 매번 달라지면 §8 이 문구를 확정한 목적(강사가 읽고 바로 행동할 수 있는 형태)이 무너진다. LLM 은 {@code {핵심
 * 개념}}·{@code {핵심 내용}} 한 자리만 채운다.
 *
 * <p>외부 의존이 없는 계산이라 도메인에 둔다. 유형 선택 규칙과 같은 패키지에 있어야 §7.6·§8 을 함께 고칠 수 있다.
 */
public final class CoachingTipComposer {

    /**
     * 줄바꿈을 {@code \n} 으로 고정한다.
     *
     * <p>{@code String.format} 의 {@code %n} 은 플랫폼 줄 구분자라 개발 기기(Windows)와 배포 서버(Linux)에서 다른 문구가 나온다. 강사 화면에 그대로 실리는 값이라
     * 환경에 따라 달라지면 안 된다.
     */
    private static final String NEW_LINE = "\n";

    private CoachingTipComposer() {}

    /**
     * 이 유형의 문구에 {@code {핵심 개념}}·{@code {핵심 내용}} 자리가 있는가.
     *
     * <p>false 면 LLM 을 호출할 필요가 없다 — 무응답·자리비움 문구는 §8 에 자리표시자가 없다. 어느 템플릿에 자리가 있는지는 문구를 가진 쪽만 아는 사실이라 여기 둔다.
     */
    public static boolean requiresConcept(CoachingTipType type) {
        return switch (type) {
            case CONFUSED, MISSED, CONFUSED_AND_MISSED -> true;
            case NON_RESPONSE, UNMEASURABLE -> false;
        };
    }

    /** §8 이 확정한 카드 제목. 유형마다 고정이다. */
    public static String titleOf(CoachingTipType type) {
        return switch (type) {
            case CONFUSED -> "추가 설명이 필요해요";
            case MISSED -> "내용을 다시 짚어주세요";
            case CONFUSED_AND_MISSED -> "수업 흐름을 점검해 주세요";
            case NON_RESPONSE -> "학생 반응을 확인해 주세요";
            case UNMEASURABLE -> "학생들이 자리를 비운 것 같아요";
        };
    }

    /**
     * @param concept LLM 이 채운 핵심 개념·내용. {@link #requiresConcept(CoachingTipType)} 가 false 면 무시한다
     * @throws IllegalArgumentException 개념이 필요한 유형인데 비어 있거나, 분모가 0이라 비율을 계산할 수 없으면
     */
    public static CoachingTip compose(CoachingTipType type, CoachingTipRatios ratios, String concept) {
        if (requiresConcept(type) && (concept == null || concept.isBlank())) {
            throw new IllegalArgumentException("이 유형은 핵심 개념이 필요합니다: " + type);
        }
        if (ratios.studentsCounted() == 0) {
            throw new IllegalArgumentException("분모가 0이라 팁 문구를 만들 수 없습니다.");
        }
        String message =
                switch (type) {
                    case CONFUSED ->
                        "전체 학생의 %d%%가 현재 내용을 헷갈려 하고 있어요.%s%s 다른 예시로 다시 설명해 주세요."
                                .formatted(percent(ratios.confusedRatio()), NEW_LINE, objectOf(concept));
                    case MISSED ->
                        "전체 학생의 %d%%가 방금 설명을 놓쳤어요.%s%s 짧게 요약한 뒤 수업을 이어가 주세요."
                                .formatted(percent(ratios.missedRatio()), NEW_LINE, objectOf(concept));
                    case CONFUSED_AND_MISSED ->
                        """
                            전체 학생의 %d%%가 현재 수업을 따라가는 데 어려움을 겪고 있어요.
                            헷갈려요 %d%% · 놓쳤어요 %d%%
                            설명 속도를 낮추고 %s 다시 정리해 주세요.""".formatted(
                                        percent(ratios.significantRatio()),
                                        percent(ratios.confusedRatio()),
                                        percent(ratios.missedRatio()),
                                        objectOf(concept));
                    case NON_RESPONSE ->
                        "전체 학생의 %d%%가 질문에 응답하지 않았어요.%s간단한 질문을 통해 학생들의 참여 상태를 확인해 주세요."
                                .formatted(percent(ratios.nonResponseRatio()), NEW_LINE);
                    case UNMEASURABLE ->
                        "전체 학생의 %d%%가 현재 화면에서 감지되지 않고 있어요.%s간단한 질문을 통해 학생들의 참여 상태를 확인해 주세요."
                                .formatted(percent(ratios.unmeasurableRatio()), NEW_LINE);
                };
        return new CoachingTip(type, titleOf(type), message, requiresConcept(type) ? concept : null);
    }

    /**
     * 비율을 정수 퍼센트로 바꾼다.
     *
     * <p>단일 유형 팁의 "전체 학생의 N%"는 그 상태의 비율이고, 복합 팁 첫 줄만 유의 학생 비율(합집합)을 쓴다. 한 학생이 헷갈림과 놓침을 모두 겪으면 중복 제거되므로 복합 문구의 뒤 두 값을 더한
     * 값이 첫 줄보다 클 수 있다. §8 예시(50% = 30% + 20%)는 우연히 맞은 숫자다.
     */
    private static int percent(double ratio) {
        return (int) Math.round(ratio * 100);
    }

    /**
     * 목적격 조사를 붙인다. §8 원문은 {@code {핵심 개념}을} 처럼 조사가 고정돼 있는데, 자리표시자를 실제 값으로 바꾸면 "와일드카드을" 처럼 어긋난다. 팁은 강사가 그대로 읽는 문구라 그대로 두면
     * 매번 눈에 걸린다.
     *
     * <p>한글 음절은 종성 유무로 판단하고, 그 밖(영문·숫자·기호)은 "를"로 둔다. 영문 약어의 발음까지 다루면 규칙이 커지는데 팁 문구가 얻는 것이 크지 않다.
     */
    private static String objectOf(String concept) {
        String trimmed = concept.strip();
        char last = trimmed.charAt(trimmed.length() - 1);
        boolean hangulSyllable = last >= '가' && last <= '힣';
        boolean hasFinalConsonant = hangulSyllable && (last - '가') % 28 != 0;
        return trimmed + (hasFinalConsonant ? "을" : "를");
    }
}
