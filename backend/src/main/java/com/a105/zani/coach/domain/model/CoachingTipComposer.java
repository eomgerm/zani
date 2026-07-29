package com.a105.zani.coach.domain.model;

import java.util.OptionalDouble;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;

/**
 * §8 고정 템플릿에 실제 퍼센트와 LLM 이 채운 개념을 넣어 팁 문구를 완성한다. (S15P11A105-204)
 *
 * <p>외부 의존이 없는 계산이라 도메인에 둔다. 유형과 문구가 갈라지면 §8 을 고칠 때 한쪽만 바뀌므로 {@link CoachingTipType} 과 같은 패키지에 있다.
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
     * @param concept LLM 이 채운 핵심 개념·내용. {@link CoachingTipType#requiresConcept()} 가 false 면 무시한다
     * @throws IllegalArgumentException 개념이 필요한 유형인데 비어 있거나, 분모가 0이라 비율을 계산할 수 없으면
     */
    public static CoachingTip compose(CoachingTipType type, CoachingSignalSummary summary, String concept) {
        if (type.requiresConcept() && (concept == null || concept.isBlank())) {
            throw new IllegalArgumentException("이 유형은 핵심 개념이 필요합니다: " + type);
        }
        String message =
                switch (type) {
                    case CONFUSED_HIGH ->
                        "전체 학생의 %d%%가 현재 내용을 헷갈려 하고 있어요.%s%s 다른 예시로 다시 설명해 주세요."
                                .formatted(percentOf(summary, AttentionState.CONFUSED), NEW_LINE, objectOf(concept));
                    case MISSED_HIGH ->
                        "전체 학생의 %d%%가 방금 설명을 놓쳤어요.%s%s 짧게 요약한 뒤 수업을 이어가 주세요."
                                .formatted(percentOf(summary, AttentionState.MISSED), NEW_LINE, objectOf(concept));
                    case CONFUSED_AND_MISSED_HIGH ->
                        """
                            전체 학생의 %d%%가 현재 수업을 따라가는 데 어려움을 겪고 있어요.
                            헷갈려요 %d%% · 놓쳤어요 %d%%
                            설명 속도를 낮추고 %s 다시 정리해 주세요.""".formatted(
                                        percent(summary.ratio()),
                                        percentOf(summary, AttentionState.CONFUSED),
                                        percentOf(summary, AttentionState.MISSED),
                                        objectOf(concept));
                    case NON_RESPONSE_HIGH ->
                        "전체 학생의 %d%%가 질문에 응답하지 않았어요.%s간단한 질문을 통해 학생들의 참여 상태를 확인해 주세요."
                                .formatted(percentOf(summary, AttentionState.NON_RESPONSE), NEW_LINE);
                    case UNMEASURABLE_HIGH ->
                        "전체 학생의 %d%%가 현재 화면에서 감지되지 않고 있어요.%s간단한 질문을 통해 학생들의 참여 상태를 확인해 주세요."
                                .formatted(percentOf(summary, AttentionState.UNMEASURABLE), NEW_LINE);
                };
        return new CoachingTip(type, type.title(), message, type.requiresConcept() ? concept : null);
    }

    /**
     * 상태별 비율. 단일 유형 팁의 "전체 학생의 N%"가 이 값이다({@code CoachingSignalSummary#ratioOf} 주석, §7.6).
     *
     * <p>복합 팁의 첫 줄만 {@code ratio()}(합집합)를 쓴다. 한 학생이 헷갈림과 놓침을 모두 겪으면 중복 제거되므로, 복합 문구의 뒤 두 값을 더한 값이 첫 줄보다 클 수 있다. §8
     * 예시(50% = 30% + 20%)는 우연히 맞은 숫자다.
     */
    private static int percentOf(CoachingSignalSummary summary, AttentionState state) {
        return percent(summary.ratioOf(state));
    }

    private static int percent(OptionalDouble ratio) {
        double value = ratio.orElseThrow(() -> new IllegalArgumentException("분모가 0이라 팁 문구를 만들 수 없습니다."));
        return (int) Math.round(value * 100);
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
