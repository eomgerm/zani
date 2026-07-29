package com.a105.zani.audioclip.infrastructure.gms;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.a105.zani.audioclip.application.exception.AudioClipTranscriptionFailedException;
import com.a105.zani.audioclip.application.port.AudioTranscriptionPort;
import com.a105.zani.common.infrastructure.gms.GmsProperties;

/**
 * GMS whisper-1 로 오디오를 1회 전사한다. (S15P11A105-203)
 *
 * <p>{@code POST /v1/audio/transcriptions} 에 multipart 로 올린다. timeout·자격증명 오류·크레딧 소진·rate limit·서버 오류는 모두
 * {@link AudioClipTranscriptionFailedException} 으로 바꿔 던지고, 팁을 건너뛸지는 호출자가 정한다(포트 계약).
 *
 * <p>재시도는 하지 않는다. 같은 트리거에서 다시 호출해도 오디오 구간은 그대로이고, timeout 이 났다는 것은 GMS 가 이미 느리다는 뜻이라 재시도가 지연만 배로 만든다. 팁을 보내지 않으면 쿨타임이
 * 시작되지 않으므로(기준 문서 §10 "알림 1회 후 10분") 다음 트리거가 부하가 덜한 시점에 다시 시도할 수 있다. 실패 시 쿨타임 처리는 204 가 확정한다.
 *
 * <p>스트림은 메모리로만 읽는다. 디스크에 쓰지 않는 것이 "전사가 끝나면 오디오를 즉시 폐기한다"의 가장 강한 형태이고, multipart 요청에 Content-Length 를 실으려면 전체 길이를 알아야
 * 한다. 클립은 16kHz mono mp3 로 300초가 약 2.3MB 라 전량 적재해도 부담이 없다.
 *
 * <p>벤더 응답 타입은 이 어댑터 안의 private record 로만 다룬다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "false")
public class GmsAudioTranscriptionAdapter implements AudioTranscriptionPort {

    private static final Logger log = LoggerFactory.getLogger(GmsAudioTranscriptionAdapter.class);
    private static final String TRANSCRIPTION_PATH = "/v1/audio/transcriptions";

    /**
     * whisper 는 업로드 파일의 확장자로 컨테이너를 판별하므로 파일명을 반드시 실어야 한다. 형식을 아는 쪽은 인코더뿐이고 그 결과가 {@code contentType} 으로 넘어오니, 하드코딩하지 않고
     * 여기서 확장자로 되돌린다.
     */
    private static final Map<String, String> FILENAME_BY_CONTENT_TYPE = Map.of(
            "audio/mpeg", "audio.mp3",
            "audio/mp3", "audio.mp3",
            "audio/wav", "audio.wav",
            "audio/x-wav", "audio.wav",
            "audio/ogg", "audio.ogg",
            "audio/webm", "audio.webm",
            "audio/mp4", "audio.m4a",
            "audio/flac", "audio.flac");

    private final RestClient transcriptionRestClient;
    private final String sttModel;
    private final String language;

    public GmsAudioTranscriptionAdapter(
            @Qualifier("gmsTranscriptionRestClient") RestClient transcriptionRestClient, GmsProperties properties) {
        this.transcriptionRestClient = transcriptionRestClient;
        this.sttModel = properties.sttModel();
        this.language = properties.transcribeLanguage();
    }

    @Override
    public String transcribe(InputStream audio, String contentType) {
        String filename = filenameFor(contentType);
        byte[] bytes = readAll(audio);

        long startedAt = System.nanoTime();
        try {
            TranscriptionResponse response = transcriptionRestClient
                    .post()
                    .uri(TRANSCRIPTION_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody(bytes, contentType, filename))
                    .retrieve()
                    .body(TranscriptionResponse.class);

            String text = response == null || response.text() == null ? "" : response.text();
            log.info(
                    "Transcription finished: bytes={} elapsedMs={} textLength={}",
                    bytes.length,
                    elapsedMs(startedAt),
                    text.length());
            return text;
        } catch (RuntimeException exception) {
            // timeout, 401(자격증명), 402(크레딧 소진), 429(rate limit), 5xx 를 모두 같은 실패로 다룬다 — 재시도가 없어
            // 호출자가 구분해서 할 수 있는 일이 없다.
            log.warn("Transcription failed after {}ms: {}", elapsedMs(startedAt), exception.toString());
            throw new AudioClipTranscriptionFailedException(exception);
        }
    }

    private String filenameFor(String contentType) {
        String filename = contentType == null
                ? null
                : FILENAME_BY_CONTENT_TYPE.get(contentType.trim().toLowerCase());
        if (filename == null) {
            // 인코더를 바꿨는데 이 매핑을 늘리지 않은 경우다. 확장자 없이 보내면 GMS 가 400 을 주므로 원인을 남기고 끊는다.
            throw new AudioClipTranscriptionFailedException(
                    new IllegalArgumentException("Unsupported transcription content type: " + contentType));
        }
        return filename;
    }

    private byte[] readAll(InputStream audio) {
        try {
            return audio.readAllBytes();
        } catch (IOException exception) {
            throw new AudioClipTranscriptionFailedException(exception);
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
        if (language != null && !language.isBlank()) {
            builder.part("language", language);
        }
        return builder.build();
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** GMS(OpenAI 호환) 전사 응답. 필요한 필드만 받는다. */
    private record TranscriptionResponse(String text) {}
}
