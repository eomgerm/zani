package com.a105.zani.postclass.infrastructure.gms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.exception.PostClassTranscriptionFailedException;
import com.a105.zani.postclass.application.port.TranscriptionResult;
import com.a105.zani.postclass.infrastructure.config.PostClassTranscriptionProperties;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * 요청 형태와 verbose_json 매핑을 고정한다.
 *
 * <p>응답 본문은 실제 GMS 가 돌려준 것을 줄인 것이다. 필드명이 snake_case 라는 사실이 매핑의 핵심인데, 놓치면 {@code avg_logprob}·{@code no_speech_prob} 가
 * 조용히 null 로 들어와 신뢰도가 전부 {@code exp(0)=1.0} 이 된다 — 아무도 이상함을 알아채지 못하는 종류라 테스트로 못박는다.
 */
class GmsPostClassTranscriptionAdapterTest {

    private static final String BASE_URL = "https://gms.test";
    private static final String TRANSCRIBE_URL = BASE_URL + "/v1/audio/transcriptions";
    private static final String OGG = "audio/ogg";

    /** 테스트에서 파일을 실제로 만들어야 하므로 운영 기본값(24 MiB)보다 작게 둔다. */
    private static final long MAX_UPLOAD_BYTES = 4096;

    /** 실측 응답을 줄인 것. 필드명·구조는 그대로다. */
    private static final String VERBOSE_JSON = """
            {"task":"transcribe","language":"korean","duration":326.19000244140625,
             "text":"...",
             "segments":[
               {"id":0,"seek":0,"start":0.0,"end":20.0,"text":" 아 네 안녕하세요 들리시나요?",
                "tokens":[1,2],"temperature":0.0,"avg_logprob":-0.21,"compression_ratio":1.4,
                "no_speech_prob":0.0541},
               {"id":1,"seek":0,"start":20.0,"end":26.0,"text":" 네 둘다 이해 못하면 해야돼",
                "tokens":[3,4],"temperature":0.0,"avg_logprob":-0.36,"compression_ratio":1.2,
                "no_speech_prob":0.6612}
             ],
             "usage":{"type":"duration","seconds":327}}
            """;

    @TempDir
    Path tempDir;

    private record Fixture(GmsPostClassTranscriptionAdapter adapter, MockRestServiceServer server) {}

    private GmsProperties properties() {
        return new GmsProperties(
                BASE_URL,
                "test-key",
                false,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                "whisper-1",
                Duration.ofSeconds(20),
                Duration.ofSeconds(180),
                "ko",
                "gpt-5.4-mini",
                Duration.ofSeconds(6),
                "gpt-5.4-mini",
                Duration.ofSeconds(60));
    }

    private PostClassTranscriptionProperties transcriptionProperties() {
        return new PostClassTranscriptionProperties(
                tempDir.toString(),
                tempDir.toString(),
                "ffmpeg",
                "ffprobe",
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                5,
                true,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                MAX_UPLOAD_BYTES,
                2,
                false,
                true,
                0.8);
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(
                new GmsPostClassTranscriptionAdapter(builder.build(), properties(), transcriptionProperties()), server);
    }

    private Path chunk() throws IOException {
        Path file = tempDir.resolve("chunk-000.ogg");
        Files.write(file, "fake-ogg-bytes".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void snake_case_필드를_세그먼트로_옮긴다() throws IOException {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess(VERBOSE_JSON, MediaType.APPLICATION_JSON));

        TranscriptionResult result = fixture.adapter().transcribe(chunk(), OGG);

        assertEquals(326_190, result.durationMs());
        assertEquals("korean", result.language());
        assertEquals(2, result.segments().size());
        assertEquals(0, result.segments().get(0).startMs());
        assertEquals(20_000, result.segments().get(0).endMs());
        assertEquals("아 네 안녕하세요 들리시나요?", result.segments().get(0).text());
        // 이 두 값이 null 로 들어오면 신뢰도가 전부 1.0 이 되어 결함이 드러나지 않는다.
        assertEquals(-0.21, result.segments().get(0).avgLogprob(), 1e-9);
        assertEquals(0.0541, result.segments().get(0).noSpeechProb(), 1e-9);
        assertEquals(0.6612, result.segments().get(1).noSpeechProb(), 1e-9);
        fixture.server().verify();
    }

    @Test
    void avgLogprob_은_원값을_보존하고_신뢰도는_파생한다() throws IOException {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess(VERBOSE_JSON, MediaType.APPLICATION_JSON));

        TranscriptionResult result = fixture.adapter().transcribe(chunk(), OGG);

        // exp(-0.21) ≈ 0.8106. 원값을 남겨야 나중에 계산식을 바꿀 때 옛 값과 구분할 수 있다.
        assertEquals(-0.21, result.segments().get(0).avgLogprob(), 1e-9);
        assertEquals(Math.exp(-0.21), result.segments().get(0).confidenceScore(), 1e-9);
    }

    @Test
    void 요청은_verbose_json_만_보내고_배열_필드를_넣지_않는다() throws IOException {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andExpect(content().string(containsString("name=\"response_format\"")))
                .andExpect(content().string(containsString("verbose_json")))
                .andExpect(content().string(containsString("whisper-1")))
                .andExpect(content().string(containsString("name=\"language\"")))
                // whisper 는 확장자로 컨테이너를 판별한다. 파일명이 없으면 400 이다.
                .andExpect(content().string(containsString("filename=\"audio.ogg\"")))
                // 게이트웨이가 배열 필드를 거부한다(500 Arrays are not supported). 넣으면 전사가 통째로 실패한다.
                .andExpect(content().string(not(containsString("timestamp_granularities"))))
                .andRespond(MockRestResponseCreators.withSuccess(VERBOSE_JSON, MediaType.APPLICATION_JSON));

        fixture.adapter().transcribe(chunk(), OGG);

        fixture.server().verify();
    }

    @Test
    void 세그먼트가_없는_무음_응답도_정상으로_받는다() throws IOException {
        // 완전 무음 청크에서 실제로 나오는 형태다. 실패가 아니라 "발화 없음" 이다.
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"duration\":600.0,\"language\":\"korean\",\"segments\":[]}", MediaType.APPLICATION_JSON));

        TranscriptionResult result = fixture.adapter().transcribe(chunk(), OGG);

        assertEquals(600_000, result.durationMs());
        assertTrue(result.segments().isEmpty());
    }

    @Test
    void duration_이_없으면_재시도하지_않는다() throws IOException {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"language\":\"korean\",\"segments\":[]}", MediaType.APPLICATION_JSON));

        PostClassTranscriptionFailedException failure = assertThrows(
                PostClassTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(chunk(), OGG));
        assertFalse(failure.retryable(), "같은 요청에 같은 응답이 오므로 재시도는 예산만 태운다");
    }

    @Test
    void 세그먼트_필수_필드가_빠지면_재시도하지_않는다() throws IOException {
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"text\":\"x\"}]}",
                        MediaType.APPLICATION_JSON));

        PostClassTranscriptionFailedException failure = assertThrows(
                PostClassTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(chunk(), OGG));
        assertFalse(failure.retryable());
    }

    @Test
    void 서버_오류와_한도_초과는_재시도_가능으로_분류한다() throws IOException {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS}) {
            Fixture fixture = fixture();
            fixture.server().expect(requestTo(TRANSCRIBE_URL)).andRespond(MockRestResponseCreators.withStatus(status));

            PostClassTranscriptionFailedException failure = assertThrows(
                    PostClassTranscriptionFailedException.class,
                    () -> fixture.adapter().transcribe(chunk(), OGG));
            assertTrue(failure.retryable(), status + " 는 다음 시도에 성공할 수 있다");
        }
    }

    @Test
    void 자격증명_오류와_크기_초과는_재시도하지_않는다() throws IOException {
        // 401 은 다시 보내도 같고, 413 은 크기 가드가 있는데도 넘겼다는 뜻이라 분할 쪽 결함이다.
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.PAYLOAD_TOO_LARGE}) {
            Fixture fixture = fixture();
            fixture.server().expect(requestTo(TRANSCRIBE_URL)).andRespond(MockRestResponseCreators.withStatus(status));

            PostClassTranscriptionFailedException failure = assertThrows(
                    PostClassTranscriptionFailedException.class,
                    () -> fixture.adapter().transcribe(chunk(), OGG));
            assertFalse(failure.retryable(), status + " 는 재시도해도 같은 결과다");
        }
    }

    @Test
    void 모르는_content_type_은_외부_호출_없이_거부한다() {
        Fixture fixture = fixture();

        PostClassTranscriptionFailedException failure = assertThrows(
                PostClassTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(tempDir.resolve("x"), "audio/aac"));

        assertFalse(failure.retryable(), "매핑을 늘리지 않은 것은 코드 결함이다");
        // 서버에 아무 요청도 기대하지 않았으므로 verify 가 호출 없음을 확인한다.
        fixture.server().verify();
    }

    @Test
    void 청크_파일이_없으면_재시도_가능으로_분류한다() {
        Fixture fixture = fixture();

        PostClassTranscriptionFailedException failure = assertThrows(
                PostClassTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(tempDir.resolve("없는청크.ogg"), OGG));

        assertTrue(failure.retryable(), "원본에서 다시 자르면 된다");
        fixture.server().verify();
    }

    @Test
    void 응답_필드명이_camelCase_면_계약_위반으로_거절한다() throws IOException {
        // snake_case 매핑이 우연이 아님을 반대 방향에서 확인한다. camelCase 로 오면 avg_logprob 가 없는 것과
        // 같으므로 거절해야 한다 — 0 으로 대체하면 신뢰도가 exp(0)=1.0 이 되어 "확신에 찬 전사" 로 보인다.
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"x\","
                                + "\"avgLogprob\":-0.5,\"noSpeechProb\":0.9}]}",
                        MediaType.APPLICATION_JSON));

        PostClassTranscriptionFailedException failure = assertThrows(
                PostClassTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(chunk(), OGG));
        assertFalse(failure.retryable());
    }

    @Test
    void 확률과_로그확률이_없으면_0_으로_대체하지_않고_거절한다() throws IOException {
        // 이것이 이 어댑터에서 가장 위험한 경로다. 0 으로 채우면 신뢰도가 1.0 이 되고, 값이 없어서 그렇게
        // 됐다는 사실이 어디에도 남지 않는다. 잘못된 데이터를 조용히 저장하는 쪽이 실패보다 나쁘다.
        for (String body : new String[] {
            "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"x\",\"no_speech_prob\":0.1}]}",
            "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"x\",\"avg_logprob\":-0.2}]}"
        }) {
            Fixture fixture = fixture();
            fixture.server()
                    .expect(requestTo(TRANSCRIBE_URL))
                    .andRespond(MockRestResponseCreators.withSuccess(body, MediaType.APPLICATION_JSON));

            PostClassTranscriptionFailedException failure = assertThrows(
                    PostClassTranscriptionFailedException.class,
                    () -> fixture.adapter().transcribe(chunk(), OGG));
            assertFalse(failure.retryable());
        }
    }

    @Test
    void segments_필드_누락은_빈_배열과_다르게_거절한다() throws IOException {
        // 빈 배열은 완전 무음의 정상 응답이고, 필드가 아예 없는 것은 응답 형식이 바뀐 것이다.
        Fixture fixture = fixture();
        fixture.server()
                .expect(requestTo(TRANSCRIBE_URL))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"duration\":10.0,\"language\":\"korean\"}", MediaType.APPLICATION_JSON));

        PostClassTranscriptionFailedException failure = assertThrows(
                PostClassTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(chunk(), OGG));
        assertFalse(failure.retryable());
    }

    @Test
    void NaN_과_무한대는_0ms_로_통과하지_않고_거절한다() throws IOException {
        // Math.round(NaN) 은 예외 없이 0 을 준다. 검증이 없으면 시각이 0ms 로 저장되고 신호가 남지 않는다.
        for (String body : new String[] {
            "{\"duration\":\"NaN\",\"segments\":[]}",
            "{\"duration\":10.0,\"segments\":[{\"start\":\"NaN\",\"end\":1.0,\"text\":\"x\","
                    + "\"avg_logprob\":-0.2,\"no_speech_prob\":0.1}]}",
            "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"end\":\"Infinity\",\"text\":\"x\","
                    + "\"avg_logprob\":-0.2,\"no_speech_prob\":0.1}]}",
            "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"x\","
                    + "\"avg_logprob\":\"NaN\",\"no_speech_prob\":0.1}]}"
        }) {
            Fixture fixture = fixture();
            fixture.server()
                    .expect(requestTo(TRANSCRIBE_URL))
                    .andRespond(MockRestResponseCreators.withSuccess(body, MediaType.APPLICATION_JSON));

            PostClassTranscriptionFailedException failure = assertThrows(
                    PostClassTranscriptionFailedException.class,
                    () -> fixture.adapter().transcribe(chunk(), OGG));
            assertFalse(failure.retryable());
        }
    }

    @Test
    void 확률과_로그확률의_범위를_벗어나면_거절한다() throws IOException {
        for (String body : new String[] {
            // no_speech_prob 는 0~1 이다.
            "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"x\","
                    + "\"avg_logprob\":-0.2,\"no_speech_prob\":1.5}]}",
            "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"x\","
                    + "\"avg_logprob\":-0.2,\"no_speech_prob\":-0.1}]}",
            // 로그 확률이 양수면 확률이 1 을 넘는다는 뜻이다.
            "{\"duration\":10.0,\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"x\","
                    + "\"avg_logprob\":0.5,\"no_speech_prob\":0.1}]}",
            // 음수 시각·역전 구간.
            "{\"duration\":-1.0,\"segments\":[]}",
            "{\"duration\":10.0,\"segments\":[{\"start\":5.0,\"end\":1.0,\"text\":\"x\","
                    + "\"avg_logprob\":-0.2,\"no_speech_prob\":0.1}]}"
        }) {
            Fixture fixture = fixture();
            fixture.server()
                    .expect(requestTo(TRANSCRIBE_URL))
                    .andRespond(MockRestResponseCreators.withSuccess(body, MediaType.APPLICATION_JSON));

            PostClassTranscriptionFailedException failure = assertThrows(
                    PostClassTranscriptionFailedException.class,
                    () -> fixture.adapter().transcribe(chunk(), OGG));
            assertFalse(failure.retryable());
        }
    }

    @Test
    void 업로드_상한을_넘는_청크는_외부_호출_없이_거절한다() throws IOException {
        // 분할 쪽이 이미 확인하지만 그쪽 결함이 이 경로를 통과하게 두지 않는다. 413 왕복을 아끼고,
        // 요청 버퍼링이 본문 전체를 힙에 올리므로 호출 전에 끊어야 힙을 지킬 수 있다.
        Fixture fixture = fixture();
        Path oversized = tempDir.resolve("oversized.ogg");
        Files.write(oversized, new byte[(int) (MAX_UPLOAD_BYTES + 1)]);

        PostClassTranscriptionFailedException failure = assertThrows(
                PostClassTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(oversized, OGG));

        assertFalse(failure.retryable(), "분할 쪽 결함이라 재시도해도 같다");
        fixture.server().verify();
    }

    @Test
    void 빈_청크는_외부_호출_없이_거절한다() throws IOException {
        Fixture fixture = fixture();
        Path empty = tempDir.resolve("empty.ogg");
        Files.write(empty, new byte[0]);

        PostClassTranscriptionFailedException failure = assertThrows(
                PostClassTranscriptionFailedException.class,
                () -> fixture.adapter().transcribe(empty, OGG));

        assertFalse(failure.retryable());
        fixture.server().verify();
    }
}
