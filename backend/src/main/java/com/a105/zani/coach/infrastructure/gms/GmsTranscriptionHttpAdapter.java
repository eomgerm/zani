package com.a105.zani.coach.infrastructure.gms;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.a105.zani.coach.application.port.AudioClip;
import com.a105.zani.coach.application.port.GmsTranscriptionPort;
import com.a105.zani.coach.application.port.TranscriptResult;

/**
 * GMS whisper-1 로 오디오를 1회 전사한다. (S15P11A105-203)
 *
 * <p>{@code POST /v1/audio/transcriptions} 에 multipart 로 mp3 를 올린다. 재시도는 하지 않는다 — 팁은 트리거 후 10~15초 안에 떠야 해서 재시도 여유가 없다.
 * timeout·자격증명 오류·크레딧 소진·서버 오류는 예외를 던지지 않고 빈 결과로 흡수하며, 상위는 팁을 보내지 않고 수업을 유지한다(COACH-003).
 *
 * <p>벤더 응답 타입은 이 어댑터 안의 private record 로만 다룬다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "false")
public class GmsTranscriptionHttpAdapter implements GmsTranscriptionPort {

    private static final Logger log = LoggerFactory.getLogger(GmsTranscriptionHttpAdapter.class);
    private static final String TRANSCRIPTION_PATH = "/v1/audio/transcriptions";
    private static final String AUDIO_FILENAME = "coach-audio.mp3";
    private static final MediaType AUDIO_MP3 = MediaType.parseMediaType("audio/mpeg");

    private final RestClient transcriptionRestClient;
    private final String sttModel;
    private final String language;

    public GmsTranscriptionHttpAdapter(
            @Qualifier("gmsTranscriptionRestClient") RestClient transcriptionRestClient, GmsProperties properties) {
        this.transcriptionRestClient = transcriptionRestClient;
        this.sttModel = properties.sttModel();
        this.language = properties.transcribeLanguage();
    }

    @Override
    public Optional<TranscriptResult> transcribe(AudioClip clip) {
        if (clip == null || clip.mp3() == null || clip.mp3().length == 0) {
            log.warn("transcription skipped: empty audio clip");
            return Optional.empty();
        }

        long startedAt = System.nanoTime();
        try {
            TranscriptionResponse response = transcriptionRestClient
                    .post()
                    .uri(TRANSCRIPTION_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody(clip))
                    .retrieve()
                    .body(TranscriptionResponse.class);

            long elapsedMs = elapsedMs(startedAt);
            String text = response == null || response.text() == null ? "" : response.text();
            log.info(
                    "transcription finished: bytes={} elapsedMs={} textLength={}",
                    clip.mp3().length,
                    elapsedMs,
                    text.length());
            return Optional.of(new TranscriptResult(text, elapsedMs));
        } catch (RuntimeException exception) {
            // timeout, 401(자격증명), 402(크레딧 소진), 429(rate limit), 5xx 를 모두 흡수한다.
            log.warn("transcription failed after {}ms: {}", elapsedMs(startedAt), exception.toString());
            return Optional.empty();
        }
    }

    private MultiValueMap<String, org.springframework.http.HttpEntity<?>> multipartBody(AudioClip clip) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(clip.mp3()) {
                    @Override
                    public String getFilename() {
                        return AUDIO_FILENAME;
                    }
                })
                .contentType(AUDIO_MP3);
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
