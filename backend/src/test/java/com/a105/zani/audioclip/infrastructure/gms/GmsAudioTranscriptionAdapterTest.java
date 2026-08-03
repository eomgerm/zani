package com.a105.zani.audioclip.infrastructure.gms;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import com.a105.zani.audioclip.application.exception.AudioClipTranscriptionFailedException;
import com.a105.zani.common.infrastructure.gms.GmsProperties;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * 전사 어댑터의 요청 형식과 응답별 처리를 검증한다.
 *
 * <p>포트 계약대로 성공은 텍스트를 반환하고, timeout·401·402·429·5xx 는 {@link AudioClipTranscriptionFailedException} 으로 던져야 한다. 팁을 건너뛸지
 * 결정하는 것은 호출자 몫이다.
 */
@ExtendWith(OutputCaptureExtension.class)
class GmsAudioTranscriptionAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String TRANSCRIBE_URL = BASE_URL + "/v1/audio/transcriptions";
    private static final String MP3 = "audio/mpeg";

    private GmsProperties properties(String language) {
        return new GmsProperties(
                BASE_URL,
                "test-key",
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                "whisper-1",
                Duration.ofSeconds(20),
                language,
                "gpt-5.4-mini",
                Duration.ofSeconds(6),
                "gpt-5.4-mini",
                Duration.ofSeconds(60));
    }

    private InputStream audio() {
        return new ByteArrayInputStream("fake-mp3-bytes".getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(GmsAudioTranscriptionAdapter adapter, MockRestServiceServer server) {}

    private Fixture fixture() {
        return fixture("ko");
    }

    private Fixture fixture(String language) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GmsAudioTranscriptionAdapter(builder.build(), properties(language)), server);
    }

    @Test
    void returnsTranscriptAndSendsMultipartWithModelAndLanguage() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(containsString("name=\"file\"")))
                .andExpect(content().string(containsString("audio.mp3")))
                .andExpect(content().string(containsString("name=\"model\"")))
                .andExpect(content().string(containsString("whisper-1")))
                .andExpect(content().string(containsString("name=\"language\"")))
                .andExpect(content().string(containsString("ko")))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"text\":\"오늘은 제네릭을 설명했습니다.\"}", MediaType.APPLICATION_JSON));

        assertEquals("오늘은 제네릭을 설명했습니다.", fixture.adapter().transcribe(audio(), MP3));
        fixture.server().verify();
    }

    @Test
    void omitsLanguageWhenBlank() {
        Fixture fixture = fixture("  ");
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"language\""))))
                .andRespond(MockRestResponseCreators.withSuccess("{\"text\":\"ok\"}", MediaType.APPLICATION_JSON));

        fixture.adapter().transcribe(audio(), MP3);
        fixture.server().verify();
    }

    @Test
    void derivesFilenameExtensionFromContentType() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andExpect(content().string(containsString("audio.wav")))
                .andRespond(MockRestResponseCreators.withSuccess("{\"text\":\"ok\"}", MediaType.APPLICATION_JSON));

        fixture.adapter().transcribe(audio(), "audio/wav");
        fixture.server().verify();
    }

    @Test
    void returnsEmptyTextWhenResponseHasNoText() {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess("{\"text\":\"\"}", MediaType.APPLICATION_JSON));

        assertEquals("", fixture.adapter().transcribe(audio(), MP3));
    }

    @Test
    void failsOnUnauthorized() {
        assertFails(MockRestResponseCreators.withUnauthorizedRequest()
                .body("{\"message\":\"[GMS 에러] Invalid or expired GMS key\"}"));
    }

    @Test
    void failsOnCreditExhausted() {
        assertFails(MockRestResponseCreators.withStatus(HttpStatus.PAYMENT_REQUIRED)
                .body("{\"message\":\"credit exhausted\"}"));
    }

    @Test
    void failsOnRateLimit() {
        assertFails(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("{\"message\":\"slow down\"}"));
    }

    @Test
    void failsOnServerError() {
        assertFails(MockRestResponseCreators.withServerError().body("{\"message\":\"unavailable\"}"));
    }

    @Test
    void failureLogContainsStatusButOmitsVendorResponseBody(CapturedOutput output) {
        assertFails(MockRestResponseCreators.withBadRequest().body("sensitive-vendor-response"));

        assertTrue(output.getAll().contains("failureType=HTTP_ERROR"));
        assertTrue(output.getAll().contains("statusCode=400"));
        assertFalse(output.getAll().contains("sensitive-vendor-response"));
    }

    @Test
    void failsOnTimeout(CapturedOutput output) {
        assertFails(request -> {
            throw new SocketTimeoutException("read timed out");
        });

        assertTrue(output.getAll().contains("failureType=TIMEOUT"));
        assertFalse(output.getAll().contains("read timed out"));
    }

    @Test
    void failsWithoutCallingGmsWhenContentTypeIsUnsupported() {
        Fixture fixture = fixture();
        // 서버 기대를 등록하지 않았으므로 호출이 일어나면 verify 에서 잡힌다.

        assertThrows(
                AudioClipTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(audio(), "audio/aiff"));
        fixture.server().verify();
    }

    @Test
    void failsWhenStreamCannotBeRead() {
        Fixture fixture = fixture();
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stream closed");
            }
        };

        assertThrows(
                AudioClipTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(broken, MP3));
        fixture.server().verify();
    }

    private void assertFails(ResponseCreator response) {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(TRANSCRIBE_URL)).andRespond(response);

        assertThrows(
                AudioClipTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(audio(), MP3));
    }
}
