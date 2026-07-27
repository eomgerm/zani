package com.a105.zani.audioclip.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * 서버가 강사 클라이언트에게 요청한 "최근 구간 오디오 클립"의 수명주기 기록.
 *
 * <p>오디오 바이트는 어디에도 저장하지 않는다 — 이 모델은 요청·만료·처리 결과(전사 텍스트 포함)만 담고, Redis 에 짧은 보존 기간으로 보관된다. 클립 길이는 요청 시점 기준 최근
 * {@link #CLIP_WINDOW} 이며, 수업이 그보다 짧게 진행됐으면 가용분만 업로드된다.
 */
public class AudioClipRequest {

    /** 요청하는 최근 구간 길이. FE 링버퍼의 AUDIO_CLIP_WINDOW_MS 와 함께 바뀌어야 한다. */
    public static final Duration CLIP_WINDOW = Duration.ofMinutes(5);

    /** 요청 유효 시간. 이 안에 업로드 또는 실패 보고가 도착하지 않으면 만료로 본다. */
    public static final Duration REQUEST_TTL = Duration.ofMinutes(1);

    private final Long clipId;
    private final Long sessionId;
    private final Instant requestedAt;
    private final Instant expiresAt;
    private AudioClipRequestStatus status;
    private AudioClipFailureReason failureReason;
    private String transcriptText;

    private AudioClipRequest(
            Long clipId,
            Long sessionId,
            Instant requestedAt,
            Instant expiresAt,
            AudioClipRequestStatus status,
            AudioClipFailureReason failureReason,
            String transcriptText) {
        this.clipId = clipId;
        this.sessionId = sessionId;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
        this.status = status;
        this.failureReason = failureReason;
        this.transcriptText = transcriptText;
    }

    public static AudioClipRequest create(Long clipId, Long sessionId, Instant now) {
        return new AudioClipRequest(
                clipId, sessionId, now, now.plus(REQUEST_TTL), AudioClipRequestStatus.PENDING, null, null);
    }

    public static AudioClipRequest reconstitute(
            Long clipId,
            Long sessionId,
            Instant requestedAt,
            Instant expiresAt,
            AudioClipRequestStatus status,
            AudioClipFailureReason failureReason,
            String transcriptText) {
        return new AudioClipRequest(clipId, sessionId, requestedAt, expiresAt, status, failureReason, transcriptText);
    }

    /** 만료 시각과 같은 순간부터 만료로 본다(경계 포함). */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** 업로드 또는 실패 보고로 이미 처리가 끝난 요청인지. */
    public boolean isResolved() {
        return status != AudioClipRequestStatus.PENDING;
    }

    /** 업로드·전사가 끝났다. 전사 텍스트만 남기고 오디오 바이트는 호출자가 즉시 폐기한다. */
    public void completeUpload(String transcriptText) {
        this.status = AudioClipRequestStatus.UPLOADED;
        this.failureReason = null;
        this.transcriptText = transcriptText;
    }

    public void fail(AudioClipFailureReason reason) {
        this.status = AudioClipRequestStatus.FAILED;
        this.failureReason = reason;
    }

    public Long clipId() {
        return clipId;
    }

    public Long sessionId() {
        return sessionId;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public AudioClipRequestStatus status() {
        return status;
    }

    public AudioClipFailureReason failureReason() {
        return failureReason;
    }

    public String transcriptText() {
        return transcriptText;
    }
}
