package com.a105.zani.recording.domain.model;

import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;

/**
 * 녹화 실행이 남긴 파일 하나(recording_files 행). storageKey는 세션 루트 기준 상대 경로만 허용한다(가이드 §14). started/endedOffsetMs는 수업 타임라인 기준
 * 구간이며, 구간 사이의 공백이 곧 누락 구간의 근거가 된다.
 */
public class RecordingFile {

    public static final String TYPE_TRACK = "TRACK";

    private final Long id;
    private final Long sessionId;
    private final Long recordingId;
    private final Long sessionParticipantId;
    private final String fileType;
    private final String storageKey;
    private final String livekitTrackSid;
    private final Long startedOffsetMs;
    private final Long endedOffsetMs;

    private RecordingFile(
            Long id,
            Long sessionId,
            Long recordingId,
            Long sessionParticipantId,
            String fileType,
            String storageKey,
            String livekitTrackSid,
            Long startedOffsetMs,
            Long endedOffsetMs) {
        this.id = id;
        this.sessionId = sessionId;
        this.recordingId = recordingId;
        this.sessionParticipantId = sessionParticipantId;
        this.fileType = fileType;
        this.storageKey = storageKey;
        this.livekitTrackSid = livekitTrackSid;
        this.startedOffsetMs = startedOffsetMs;
        this.endedOffsetMs = endedOffsetMs;
    }

    public static RecordingFile trackFile(
            Long id,
            Long sessionId,
            Long recordingId,
            String storageKey,
            String livekitTrackSid,
            Long startedOffsetMs,
            Long endedOffsetMs) {
        // storageKey는 세션 루트 기준 상대 경로여야 한다(가이드 §14·§18). 절대 경로·드라이브 문자·`..` 탈출을 생성 시점에 거부한다.
        if (!isSafeRelativePath(storageKey)) {
            throw new InvalidRecordingTrackException();
        }
        return new RecordingFile(
                id,
                sessionId,
                recordingId,
                null,
                TYPE_TRACK,
                storageKey,
                livekitTrackSid,
                startedOffsetMs,
                endedOffsetMs);
    }

    private static boolean isSafeRelativePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    public Long id() {
        return id;
    }

    public Long sessionId() {
        return sessionId;
    }

    public Long recordingId() {
        return recordingId;
    }

    public Long sessionParticipantId() {
        return sessionParticipantId;
    }

    public String fileType() {
        return fileType;
    }

    public String storageKey() {
        return storageKey;
    }

    public String livekitTrackSid() {
        return livekitTrackSid;
    }

    public Long startedOffsetMs() {
        return startedOffsetMs;
    }

    public Long endedOffsetMs() {
        return endedOffsetMs;
    }
}
