package com.a105.zani.postclass.infrastructure.gms;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.a105.zani.common.infrastructure.gms.GmsProperties;
import com.a105.zani.postclass.application.exception.PostClassTranscriptionFailedException;
import com.a105.zani.postclass.application.port.PostClassTranscriptionPort;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionResult;
import com.a105.zani.postclass.infrastructure.config.PostClassTranscriptionProperties;

/**
 * GMS whisper-1 로 청크 하나를 전사한다(S15P11A105-247).
 *
 * <p><b>요청 형태는 실측으로 고정됐다.</b>
 *
 * <ul>
 *   <li>{@code response_format=verbose_json} 만 보낸다. 이것으로 {@code segments[]} 에
 *       {@code start}·{@code end}·{@code avg_logprob}· {@code no_speech_prob} 가 온다
 *   <li><b>{@code timestamp_granularities[]} 를 보내지 않는다.</b> 게이트웨이가 배열 필드를 거부한다 — {@code 500 [GMS 에러] Arrays are not
 *       supported.} 그래서 단어 단위 타임스탬프는 받을 수 없고 문장 단위가 상한이다
 *   <li>원본 OGG 를 그대로 올린다. 변환하지 않는다 — 실측에서 {@code .ogg} 가 그대로 200 을 받았다
 * </ul>
 *
 * <p><b>실패를 재시도 가능·불가로 나눈다.</b> 자격증명 오류나 응답 계약 위반은 다시 보내도 같은 응답이 와서 8시간 예산만 태운다. timeout·429·5xx 는 다음 시도에 성공할 수 있다.
 *
 * <p>오디오 내용과 전사 텍스트는 로그에 남기지 않는다. 크기·소요 시간·상태 코드만 남긴다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "false")
public class GmsPostClassTranscriptionAdapter implements PostClassTranscriptionPort {

    private static final Logger log = LoggerFactory.getLogger(GmsPostClassTranscriptionAdapter.class);
    private static final String TRANSCRIPTION_PATH = "/v1/audio/transcriptions";

    /** whisper 는 업로드 파일의 확장자로 컨테이너를 판별하므로 파일명을 반드시 실어야 한다. */
    private static final Map<String, String> FILENAME_BY_CONTENT_TYPE = Map.of(
            "audio/ogg", "audio.ogg",
            "audio/opus", "audio.ogg",
            "audio/webm", "audio.webm",
            "audio/mp4", "audio.m4a",
            "audio/mpeg", "audio.mp3");

    private final RestClient restClient;
    private final String sttModel;
    private final String language;
    private final long maxUploadBytes;

    public GmsPostClassTranscriptionAdapter(
            @Qualifier("gmsPostclassTranscriptionRestClient") RestClient restClient,
            GmsProperties properties,
            PostClassTranscriptionProperties transcriptionProperties) {
        this.restClient = restClient;
        this.sttModel = properties.sttModel();
        this.language = properties.transcribeLanguage();
        this.maxUploadBytes = transcriptionProperties.maxUploadBytes();
    }

    @Override
    public TranscriptionResult transcribe(Path audio, String contentType) {
        String filename = filenameFor(contentType);
        byte[] bytes = readAll(audio);

        long startedAt = System.nanoTime();
        try {
            VerboseTranscription response = restClient
                    .post()
                    .uri(TRANSCRIPTION_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody(bytes, contentType, filename))
                    .retrieve()
                    .body(VerboseTranscription.class);

            TranscriptionResult result = toResult(response);
            log.info(
                    "Post-class chunk transcribed: bytes={} elapsedMs={} durationMs={} segments={}",
                    bytes.length,
                    elapsedMs(startedAt),
                    result.durationMs(),
                    result.segments().size());
            return result;
        } catch (RestClientResponseException responseException) {
            HttpStatusCode status = responseException.getStatusCode();
            boolean retryable = isRetryable(status);
            log.warn(
                    "Post-class chunk transcription failed: bytes={} elapsedMs={} statusCode={} retryable={}",
                    bytes.length,
                    elapsedMs(startedAt),
                    status.value(),
                    retryable);
            throw new PostClassTranscriptionFailedException(retryable, responseException);
        } catch (PostClassTranscriptionFailedException contractViolation) {
            throw contractViolation;
        } catch (RuntimeException exception) {
            // 네트워크 계열은 다음 시도에 성공할 수 있다.
            boolean retryable = exception instanceof ResourceAccessException || hasCause(exception);
            log.warn(
                    "Post-class chunk transcription failed: bytes={} elapsedMs={} failureType={} retryable={}",
                    bytes.length,
                    elapsedMs(startedAt),
                    exception.getClass().getSimpleName(),
                    retryable);
            throw new PostClassTranscriptionFailedException(retryable, exception);
        }
    }

    /**
     * 응답을 포트 계약으로 옮기며 검증한다. 계약을 벗어나면 재시도 불가로 끊는다.
     *
     * <p><b>빠진 값을 기본값으로 채우지 않는다.</b> 특히 {@code avg_logprob}·{@code no_speech_prob} 를 0 으로 대체하면 신뢰도가 {@code exp(0)=1.0}
     * 이 되어 "확신에 찬 전사" 로 보인다. 값이 없어서 그렇게 됐다는 사실은 어디에도 남지 않는다 — 잘못된 데이터를 조용히 저장하는 쪽이 실패보다 나쁘다. 실측에서 87개 세그먼트가 모두 두 값을 갖고
     * 있었으므로, 없다는 것은 응답 계약이 바뀐 것이고 사람이 봐야 한다.
     *
     * <p>{@code segments} 는 <b>빈 배열과 필드 누락을 구분한다.</b> 빈 배열은 완전 무음 청크에서 실제로 나오는 정상 응답이고, 필드가 아예 없는 것은 계약 위반이다. 둘을 같이 다루면
     * 응답 형식이 바뀌어도 "발화 없음" 으로 조용히 넘어간다.
     *
     * <p>{@code NaN}·무한대를 거절한다. {@code Math.round(NaN)} 은 0 이므로 검증 없이 통과시키면 0ms 로 저장된다.
     */
    private TranscriptionResult toResult(VerboseTranscription response) {
        if (response == null) {
            throw contractViolation("response body is empty");
        }
        long durationMs = toMillis(requireFiniteNonNegative(response.duration(), "duration"));
        if (response.segments() == null) {
            // 빈 배열(정상적인 무음)과 다르다. 필드가 사라졌다면 응답 형식이 바뀐 것이다.
            throw contractViolation("segments field is absent");
        }
        List<TranscriptSegment> segments = new ArrayList<>();
        for (VerboseSegment segment : response.segments()) {
            segments.add(toSegment(segment));
        }
        return new TranscriptionResult(durationMs, response.language(), segments);
    }

    private TranscriptSegment toSegment(VerboseSegment segment) {
        if (segment.text() == null) {
            throw contractViolation("segment text is absent");
        }
        long startMs = toMillis(requireFiniteNonNegative(segment.start(), "segment start"));
        long endMs = toMillis(requireFiniteNonNegative(segment.end(), "segment end"));
        if (endMs < startMs) {
            throw contractViolation("segment ends before it starts");
        }
        double avgLogprob = requireFinite(segment.avgLogprob(), "avg_logprob");
        if (avgLogprob > 0) {
            // 로그 확률이라 0 이하여야 한다. 양수면 확률이 1 을 넘는다는 뜻이다.
            throw contractViolation("avg_logprob is positive");
        }
        double noSpeechProb = requireFinite(segment.noSpeechProb(), "no_speech_prob");
        if (noSpeechProb < 0 || noSpeechProb > 1) {
            throw contractViolation("no_speech_prob is outside 0..1");
        }
        return new TranscriptSegment(startMs, endMs, segment.text().trim(), avgLogprob, noSpeechProb);
    }

    private double requireFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value)) {
            throw contractViolation(field + " is absent or not finite");
        }
        return value;
    }

    private double requireFiniteNonNegative(Double value, String field) {
        double finite = requireFinite(value, field);
        if (finite < 0) {
            throw contractViolation(field + " is negative");
        }
        return finite;
    }

    /**
     * 응답 계약 위반은 재시도하지 않는다. 같은 요청에 같은 응답이 오므로 재시도는 8시간 예산만 태운다.
     *
     * <p>사유는 필드 이름까지만 남긴다 — 값이나 전사 텍스트를 로그에 넣지 않는다.
     */
    private PostClassTranscriptionFailedException contractViolation(String reason) {
        log.error("Post-class transcription response violates the contract: {}", reason);
        return new PostClassTranscriptionFailedException(false);
    }

    private String filenameFor(String contentType) {
        String filename = contentType == null
                ? null
                : FILENAME_BY_CONTENT_TYPE.get(contentType.trim().toLowerCase());
        if (filename == null) {
            // 확장자 없이 보내면 GMS 가 400 을 준다. 매핑을 늘리지 않은 것은 코드 결함이라 재시도하지 않는다.
            log.error("Unsupported post-class transcription content type: {}", contentType);
            throw new PostClassTranscriptionFailedException(false);
        }
        return filename;
    }

    /**
     * 청크를 전량 읽는다. 읽기 전에 크기를 확인한다.
     *
     * <p>업로드 상한을 여기서 한 번 더 막는 이유는 둘이다. 한도를 넘는 요청은 GMS 가 413 으로 돌려줄 뿐이라 왕복이 낭비이고,
     * {@code BufferingClientHttpRequestFactory} 가 본문 전체를 힙에 올리므로 상한을 넘는 파일은 호출 전에 끊어야 힙을 지킬 수 있다. 분할 쪽이 이미 확인하지만 그쪽 결함이
     * 이 경로를 그냥 통과하게 두지 않는다.
     */
    private byte[] readAll(Path audio) {
        try {
            long size = Files.size(audio);
            if (size > maxUploadBytes) {
                // 분할 쪽 결함이다. 같은 청크를 다시 보내도 같은 결과라 재시도하지 않는다.
                log.error("Chunk exceeds the upload limit: bytes={} limit={}", size, maxUploadBytes);
                throw new PostClassTranscriptionFailedException(false);
            }
            if (size == 0) {
                log.error("Chunk is empty");
                throw new PostClassTranscriptionFailedException(false);
            }
            return Files.readAllBytes(audio);
        } catch (IOException exception) {
            // 청크 파일이 사라졌다. 원본에서 다시 자르면 되므로 재시도 가능하다.
            throw new PostClassTranscriptionFailedException(true, exception);
        }
    }

    private MultiValueMap<String, HttpEntity<?>> multipartBody(byte[] bytes, String contentType, String filename) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                })
                .contentType(MediaType.parseMediaType(contentType));
        builder.part("model", sttModel);
        // 세그먼트 타임스탬프를 받기 위한 유일한 필드다. timestamp_granularities[] 는 배열이라 거부된다.
        builder.part("response_format", "verbose_json");
        if (language != null && !language.isBlank()) {
            builder.part("language", language);
        }
        return builder.build();
    }

    /** 429·5xx·408 은 다음 시도에 성공할 수 있다. 4xx 나머지는 같은 요청이면 같은 응답이 온다. */
    private boolean isRetryable(HttpStatusCode status) {
        return status.is5xxServerError() || status.value() == 429 || status.value() == 408;
    }

    private boolean hasCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return false;
    }

    /**
     * 초를 밀리초로 반올림한다. <b>유한성 검증을 이미 통과한 값만 넘긴다.</b>
     *
     * <p>{@code double} 로 받는 이유: {@code Double} 로 두면 {@code null} 이나 {@code NaN} 이 검증 없이 들어올 수 있고,
     * {@code Math.round(NaN)} 은 예외 없이 0 을 돌려준다. 그러면 시각이 0ms 로 저장되고 아무 신호도 남지 않는다.
     */
    private long toMillis(double seconds) {
        return Math.round(seconds * 1000.0);
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /**
     * GMS(OpenAI 호환) verbose_json 응답. 필요한 필드만 받는다.
     *
     * <p>응답의 실제 필드명은 snake_case 이고 이 프로젝트는 전역 naming strategy 를 두지 않으므로 {@link JsonProperty} 로 명시한다. 빠뜨리면
     * {@code avg_logprob}·{@code no_speech_prob} 가 조용히 null 로 들어오고, 신뢰도가 전부 {@code exp(0)=1.0} 이 되어 아무도 이상함을 알아채지 못한다.
     */
    private record VerboseTranscription(Double duration, String language, List<VerboseSegment> segments) {}

    private record VerboseSegment(
            Double start,
            Double end,
            String text,
            @JsonProperty("avg_logprob") Double avgLogprob,
            @JsonProperty("no_speech_prob") Double noSpeechProb) {}
}
