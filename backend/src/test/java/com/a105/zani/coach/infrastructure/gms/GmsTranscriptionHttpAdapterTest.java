package com.a105.zani.coach.infrastructure.gms;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import com.a105.zani.coach.application.port.AudioClip;
import com.a105.zani.coach.application.port.TranscriptResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/** 전사 어댑터의 요청 형식과 응답별 처리를 검증한다. 성공은 텍스트를 반환하고, timeout·401·402·429·5xx 는 예외 없이 빈 결과로 흡수해야 한다(COACH-003). */
class GmsTranscriptionHttpAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String TRANSCRIBE_URL = BASE_URL + "/v1/audio/transcriptions";

    private GmsProperties properties() {
        return new GmsProperties(
                BASE_URL,
                "test-key",
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                "whisper-1",
                Duration.ofSeconds(10),
                "ko");
    }

    private AudioClip clip() {
        Instant to = Instant.parse("2026-07-28T02:00:00Z");
        return new AudioClip(
                "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8), to.minusSeconds(120), to, Duration.ofSeconds(120));
    }

    private record Fixture(GmsTranscriptionHttpAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GmsTranscriptionHttpAdapter(builder.build(), properties()), server);
    }

    @Test
    void returnsTranscriptAndSendsMultipartWithModelAndLanguage() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", containsString(MediaType.MULTIPART_FORM_DATA_VALUE)))
                .andExpect(content().string(containsString("name=\"file\"")))
                .andExpect(content().string(containsString("coach-audio.mp3")))
                .andExpect(content().string(containsString("whisper-1")))
                .andExpect(content().string(containsString("ko")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"text\":\"오늘은 제네릭을 설명했습니다.\"}", MediaType.APPLICATION_JSON));

        Optional<TranscriptResult> result = fixture.adapter().transcribe(clip());

        assertTrue(result.isPresent());
        assertEquals("오늘은 제네릭을 설명했습니다.", result.get().text());
        fixture.server().verify();
    }

    @Test
    void recordsElapsedTime() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess("{\"text\":\"ok\"}", MediaType.APPLICATION_JSON));

        TranscriptResult result = fixture.adapter().transcribe(clip()).orElseThrow();

        assertTrue(result.elapsedMs() >= 0, "소요 시간이 기록돼야 한다");
    }

    @Test
    void treatsBlankResponseTextAsBlankResult() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess("{\"text\":\"\"}", MediaType.APPLICATION_JSON));

        TranscriptResult result = fixture.adapter().transcribe(clip()).orElseThrow();

        assertTrue(result.isBlank(), "무음·빈 전사는 blank 로 판정돼야 한다");
    }

    @Test
    void absorbsUnauthorized() {
        assertAbsorbs(MockRestResponseCreators.withUnauthorizedRequest()
                .body("{\"message\":\"[GMS 에러] Invalid or expired GMS key\"}"));
    }

    @Test
    void absorbsCreditExhausted() {
        assertAbsorbs(MockRestResponseCreators.withStatus(HttpStatus.PAYMENT_REQUIRED)
                .body("{\"message\":\"credit exhausted\"}"));
    }

    @Test
    void absorbsRateLimit() {
        assertAbsorbs(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("{\"message\":\"slow down\"}"));
    }

    @Test
    void absorbsServerError() {
        assertAbsorbs(MockRestResponseCreators.withServerError().body("{\"message\":\"unavailable\"}"));
    }

    @Test
    void absorbsTimeout() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(TRANSCRIBE_URL)).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });

        assertTrue(fixture.adapter().transcribe(clip()).isEmpty());
    }

    @Test
    void skipsCallWhenAudioIsEmpty() {
        Fixture fixture = fixture();
        // 서버 기대를 등록하지 않았으므로, 호출이 일어나면 검증에서 실패한다.
        Instant now = Instant.parse("2026-07-28T02:00:00Z");

        assertTrue(fixture.adapter()
                .transcribe(new AudioClip(new byte[0], now, now, Duration.ZERO))
                .isEmpty());
        fixture.server().verify();
    }

    private void assertAbsorbs(org.springframework.test.web.client.ResponseCreator response) {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(TRANSCRIBE_URL)).andRespond(response);

        assertTrue(fixture.adapter().transcribe(clip()).isEmpty());
    }
}
