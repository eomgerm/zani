package com.a105.zani.common.infrastructure.gms;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSAFY GMS 접속 설정. API key 는 환경 변수에서만 읽고 응답·로그에 남기지 않는다.
 *
 * <p>여러 도메인이 공유하는 외부 시스템 설정이라 {@code common} 에 둔다({@link GmsClientConfig} 참고).
 *
 * @param baseUrl GMS 프록시 base URL. 예: https://gms.ssafy.io/gmsapi/api.openai.com
 * @param apiKey GMS API key (Bearer)
 * @param mockEnabled true 면 실제 GMS 를 호출하지 않는다. 운영 프로파일에서 금지
 * @param readTimeout 응답 대기 기본값. 호출별 요구가 다른 어댑터는 자기 client 를 구성한다
 * @param connectTimeout 연결 timeout
 * @param sttModel 전사 모델. 기본 whisper-1
 * @param transcribeTimeout 전사 호출 timeout. 재시도가 없으므로 초과하면 그 트리거의 전사는 실패로 끝난다
 * @param transcribeLanguage 전사 언어. 한국어 강의를 전제로 기본값은 ko 다. 비우면 GMS 가 자동 감지한다
 * @param tipModel 팁 문구를 채우는 모델. 기본 gpt-5.4-mini
 * @param tipTimeout 팁 호출 timeout. 재시도가 없으므로 초과하면 그 트리거의 팁은 만들지 않는다
 * @param analysisModel 사후 분석 모델. 기본 gpt-5.4-mini
 * @param analysisTimeout 분석 호출 timeout. 재시도가 없으므로 초과하면 그 단계는 실패로 끝난다
 */
@ConfigurationProperties(prefix = "gms")
public record GmsProperties(
        String baseUrl,
        String apiKey,
        boolean mockEnabled,
        Duration readTimeout,
        Duration connectTimeout,
        String sttModel,
        Duration transcribeTimeout,
        String transcribeLanguage,
        String tipModel,
        Duration tipTimeout,
        String analysisModel,
        Duration analysisTimeout) {

    /** record 기본 구현은 apiKey 를 그대로 출력한다. 설정 덤프·예외 메시지로 키가 새지 않도록 마스킹한다. */
    @Override
    public String toString() {
        // 괄호가 필요하다. 메서드 호출이 + 보다 먼저 묶여 formatted 가 마지막 리터럴에만 걸리면, 앞 두 조각은
        // %s 가 그대로 남고 마지막 조각에 엉뚱한 값이 들어간다.
        return ("GmsProperties[baseUrl=%s, apiKey=%s, mockEnabled=%s, readTimeout=%s, connectTimeout=%s,"
                        + " sttModel=%s, transcribeTimeout=%s, transcribeLanguage=%s, tipModel=%s, tipTimeout=%s,"
                        + " analysisModel=%s, analysisTimeout=%s]")
                .formatted(
                        baseUrl,
                        apiKey == null || apiKey.isBlank() ? "(unset)" : "****",
                        mockEnabled,
                        readTimeout,
                        connectTimeout,
                        sttModel,
                        transcribeTimeout,
                        transcribeLanguage,
                        tipModel,
                        tipTimeout,
                        analysisModel,
                        analysisTimeout);
    }
}
