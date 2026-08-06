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
 * @param postclassTranscribeTimeout 사후 배치 전사 timeout. 실시간용보다 훨씬 길다 — 배치는 느린 응답을 끊는 것이 이득이 아니다
 * @param transcribeLanguage 전사 언어. 한국어 강의를 전제로 기본값은 ko 다. 비우면 GMS 가 자동 감지한다
 * @param tipModel 팁 문구를 채우는 모델. 기본 gpt-5.4-mini
 * @param tipTimeout 팁 호출 timeout. 재시도가 없으므로 초과하면 그 트리거의 팁은 만들지 않는다
 * @param analysisModel 사후 분석 모델. 기본 gpt-5.4-mini
 * @param analysisTimeout 분석 호출 timeout. 재시도가 없으므로 초과하면 그 단계는 실패로 끝난다
 * @param assistantTimeout 리포트 질의응답 timeout. 분석과 나누는 이유는 기다리는 주체가 다르기 때문이다 — 그쪽은 배치라 8시간 SLA 안에서 느긋해도 되지만 이쪽은 사람이 채팅창 앞에
 *     있다
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
        Duration postclassTranscribeTimeout,
        String transcribeLanguage,
        String tipModel,
        Duration tipTimeout,
        String analysisModel,
        Duration analysisTimeout,
        Duration assistantTimeout) {

    /**
     * record 기본 구현은 apiKey 를 그대로 출력한다. 설정 덤프·예외 메시지로 키가 새지 않도록 마스킹한다.
     *
     * <p><b>서식 문자열을 괄호로 묶어야 한다.</b> {@code .formatted} 는 메서드 호출이라 {@code +} 보다 먼저 묶이므로, 괄호가 없으면 <b>마지막 리터럴 하나에만</b>
     * 적용된다. 그러면 앞부분의 {@code %s} 는 치환되지 않은 채 남고 인자가 뒤쪽 슬롯으로 밀려 들어가, 라벨과 값이 어긋난 덤프가 나온다(예: {@code tipModel} 자리에 baseUrl 이
     * 찍힌다). 키가 새지는 않는다 — 마스킹은 인자를 만들 때 이미 끝나 있다. 다만 값을 신뢰할 수 없는 덤프는 없는 것보다 나쁘다.
     *
     * <p><b>필드를 더할 때 이 목록도 함께 고쳐야 한다.</b> 손으로 쓴 구현이라 컴파일러가 빠뜨린 것을 잡아 주지 않는다 — 실제로 {@code postclassTranscribeTimeout} 이
     * 한 번 빠져 있었다. 값이 안 보이면 "timeout 을 올렸는데 왜 그대로인가" 를 확인할 방법이 없다. apiKey 하나만 가리면 되는데 record 의 자동 생성 {@code toString} 은
     * 필드를 골라 가릴 수 없어 이 방식이 남았다. 두 실수 모두 {@code GmsPropertiesToStringTest} 가 막는다.
     */
    @Override
    public String toString() {
        return ("GmsProperties[baseUrl=%s, apiKey=%s, mockEnabled=%s, readTimeout=%s, connectTimeout=%s,"
                        + " sttModel=%s, transcribeTimeout=%s, postclassTranscribeTimeout=%s,"
                        + " transcribeLanguage=%s, tipModel=%s, tipTimeout=%s, analysisModel=%s, analysisTimeout=%s,"
                        + " assistantTimeout=%s]")
                .formatted(
                        baseUrl,
                        apiKey == null || apiKey.isBlank() ? "(unset)" : "****",
                        mockEnabled,
                        readTimeout,
                        connectTimeout,
                        sttModel,
                        transcribeTimeout,
                        postclassTranscribeTimeout,
                        transcribeLanguage,
                        tipModel,
                        tipTimeout,
                        analysisModel,
                        analysisTimeout,
                        assistantTimeout);
    }
}
