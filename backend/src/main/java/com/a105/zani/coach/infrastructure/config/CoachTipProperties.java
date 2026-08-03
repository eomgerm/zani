package com.a105.zani.coach.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강사 팁 생성 설정. (S15P11A105-204)
 *
 * @param minConfidence 이 값에 못 미치는 개념은 팁을 만들지 않는다. 강사에게 틀린 개념을 짚어주는 것이 팁을 건너뛰는 것보다 나쁘다
 * @param maxCompletionTokens 팁 응답의 출력 상한. gpt-5.4-mini 는 {@code max_tokens} 를 거부하고 {@code max_completion_tokens} 를
 *     요구한다(실측). 근거 구절까지 받으면 실측 소비가 50 토큰이라(없으면 25) 관측치의 두 배로 둔다. 잘리면 JSON 이 깨져 응답 전체를 버린다
 * @param transcriptTailChars 프롬프트에 넣을 전사 마지막 글자 수 — window 한 번 분량의 상한이다. 실측에서 5분 강의 전사가 1,665자(5.5자/초)였고, 말이 빠른
 *     강사(8~9자/초)면 300초에 2,400~2,700자가 나온다. 더 낮추면 빠른 강사만 window 가 조용히 줄어든다. 상한이 필요한 이유는 지연이 아니라(입력 1,500자와 3,331자 모두
 *     1.25초로 같았다) 최근성이다 — 입력을 늘리면 모델이 트리거 직전이 아닌 앞부분 개념을 골랐다
 */
@ConfigurationProperties(prefix = "coach.tip")
public record CoachTipProperties(Double minConfidence, Integer maxCompletionTokens, Integer transcriptTailChars) {

    public CoachTipProperties {
        if (minConfidence == null) {
            minConfidence = 0.5;
        }
        if (minConfidence < 0 || minConfidence > 1) {
            throw new IllegalArgumentException("coach.tip.min-confidence must be between 0 and 1: " + minConfidence);
        }
        if (maxCompletionTokens == null || maxCompletionTokens <= 0) {
            maxCompletionTokens = 100;
        }
        if (transcriptTailChars == null || transcriptTailChars <= 0) {
            transcriptTailChars = 3000;
        }
    }
}
