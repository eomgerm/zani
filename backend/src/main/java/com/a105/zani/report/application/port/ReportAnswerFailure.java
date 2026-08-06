package com.a105.zani.report.application.port;

/**
 * 질의응답 호출이 실패한 사유.
 *
 * <p>사유를 나누는 이유는 화면이 할 말이 다르기 때문이다. 사후 분석 파이프라인과 달리 재시도 정책이 없고 사람이 화면 앞에서 기다리므로, 갈래는 "다시 눌러 보세요" 와 "질문을 줄여 주세요" 둘로
 * 충분하다.
 */
public enum ReportAnswerFailure {
    /** 본문이 게이트웨이 상한을 넘었다. 그대로 보내면 게이트웨이가 잘라 "Model not found" 로 답해 원인을 알 수 없는 실패가 된다(GMS 가이드 §4.1). */
    REQUEST_TOO_LARGE,
    /** timeout·401·402·429·5xx. 다시 눌러 볼 가치가 있다. */
    UNAVAILABLE,
    /** 응답이 왔지만 계약을 벗어났다. 같은 질문을 다시 보내도 같은 응답이 올 가능성이 높다(temperature 0). */
    UNUSABLE_RESPONSE
}
