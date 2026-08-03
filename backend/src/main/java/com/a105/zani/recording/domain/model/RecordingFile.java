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
    private final TrackSource trackSource;
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
            TrackSource trackSource,
            String fileType,
            String storageKey,
            String livekitTrackSid,
            Long startedOffsetMs,
            Long endedOffsetMs) {
        this.id = id;
        this.sessionId = sessionId;
        this.recordingId = recordingId;
        this.sessionParticipantId = sessionParticipantId;
        this.trackSource = trackSource;
        this.fileType = fileType;
        this.storageKey = storageKey;
        this.livekitTrackSid = livekitTrackSid;
        this.startedOffsetMs = startedOffsetMs;
        this.endedOffsetMs = endedOffsetMs;
    }

    /**
     * Track Egress 산출물 한 개를 기록한다.
     *
     * <p>화자({@code sessionParticipantId})와 트랙 종류({@code trackSource})는 필수다(S15P11A105-97). 이전에는 화자 자리에 null 을 넣었는데, 그러면
     * 사후 전사(S15P11A105-247)가 파일과 발화자를 연결할 수 없다. 두 값은 Egress 시작 시점에 {@link Recording} 이 보관해 둔 것을 그대로 옮겨 온다 — 파일명이나
     * 디렉터리명을 파싱해 추정하지 않는다. 경로에는 익명 별칭만 들어 있고, 별칭 순번은 참가자 집합이 바뀌면 같은 문자열이 다른 사람을 가리킨다.
     */
    public static RecordingFile trackFile(
            Long id,
            Long sessionId,
            Long recordingId,
            Long sessionParticipantId,
            TrackSource trackSource,
            String storageKey,
            String livekitTrackSid,
            Long startedOffsetMs,
            Long endedOffsetMs) {
        // storageKey는 세션 루트 기준 상대 경로여야 한다(가이드 §14·§18). 절대 경로·드라이브 문자·`..` 탈출을 생성 시점에 거부한다.
        if (!isSafeRelativePath(storageKey) || sessionParticipantId == null || trackSource == null) {
            throw new InvalidRecordingTrackException();
        }
        return new RecordingFile(
                id,
                sessionId,
                recordingId,
                sessionParticipantId,
                trackSource,
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

    public TrackSource trackSource() {
        return trackSource;
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
