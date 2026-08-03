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

    /**
     * V12 이전에 시작된 Egress 의 산출물을 기록한다. 화자·트랙 종류가 없는 것을 허용한다.
     *
     * <p>배포 전환기 전용이다. V12 는 컬럼을 NULL 허용으로 더했으므로 그 전에 만들어진 {@code recordings} 행에는 세 값이 모두 없다. 배포 순간에 진행 중이던 Egress 의
     * {@code egress_ended} 가 배포 후 도착하면 그 행을 그대로 쓰게 되는데, {@link #trackFile} 의 필수값 가드에 걸려 예외가 나간다. 그러면 파일 행이 저장되지 않는 데서
     * 끝나지 않는다 — 호출자가 상태를 저장하기 전에 예외가 올라가 녹화가 {@code RECORDING} 으로 남고, LiveKit 이 같은 webhook 을 무한히 재전송한다.
     *
     * <p>그래서 값을 요구하지 않고 받는다. 경로 가드는 유지한다. 화자를 복원할 근거가 없어 채울 수 없을 뿐이고, 파일 행 자체는 남겨야 최종 MP4 병합이 그 구간을 볼 수 있다. 신규 Egress 는
     * {@link #trackFile} 로만 들어오므로 이 경로가 새 데이터에 쓰이지는 않는다.
     */
    public static RecordingFile legacyTrackFile(
            Long id,
            Long sessionId,
            Long recordingId,
            Long sessionParticipantId,
            TrackSource trackSource,
            String storageKey,
            String livekitTrackSid,
            Long startedOffsetMs,
            Long endedOffsetMs) {
        if (!isSafeRelativePath(storageKey)) {
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
