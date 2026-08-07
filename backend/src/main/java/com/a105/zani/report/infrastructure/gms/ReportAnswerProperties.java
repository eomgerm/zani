package com.a105.zani.report.infrastructure.gms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리포트 질의응답 호출 설정(S15P11A105-259).
 *
 * <p>요청 바이트 임계는 설정으로 열지 않는다 — 게이트웨이 실측 상한(102,400B)에서 나온 값이라 환경별로 달라질 이유가 없고, 잘못 올리면 게이트웨이가 본문을 잘라 조용히 실패한다(GMS 가이드
 * §4.1).
 *
 * @param maxCompletionTokens 답변과 인용의 출력 상한. 답변 600자 + 인용 3개를 덮어야 한다 — 잘리면 {@code finish_reason=length} 로 JSON 이 깨져 사람이
 *     기다린 끝에 아무것도 못 받는다
 */
@ConfigurationProperties(prefix = "report.assistant")
public record ReportAnswerProperties(Integer maxCompletionTokens) {

    /** 값이 없거나 0 이하면 기본값을 쓴다. 0 으로 들어오면 모델이 아무것도 내지 못해 모든 질문이 조용히 실패한다. */
    public ReportAnswerProperties {
        if (maxCompletionTokens == null || maxCompletionTokens <= 0) {
            maxCompletionTokens = 1_500;
        }
    }
}
