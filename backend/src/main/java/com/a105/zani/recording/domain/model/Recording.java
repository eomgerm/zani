package com.a105.zani.recording.domain.model;

import java.time.Instant;

/**
 * LiveKit Egress 실행 한 번을 나타내는 녹화 행. Track Egress 체제에서는 트랙 하나의 Egress 시작이 행 하나가 된다. egressId는 LiveKit이 발급한 실행 식별자이며,
 * attemptNumber는 최초 실행(1)과 재시도 순번이다.
 */
public class Recording {

    public static final String TYPE_TRACK = "TRACK";

    private final Long id;
    private final Long sessionId;
    private final String livekitEgressId;
    private final String recordingType;
    private final int attemptNumber;
    private RecordingStatus status;
    private final Instant startedAt;
    private Instant endedAt;

    private Recording(
            Long id,
            Long sessionId,
            String livekitEgressId,
            String recordingType,
            int attemptNumber,
            RecordingStatus status,
            Instant startedAt,
            Instant endedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.livekitEgressId = livekitEgressId;
        this.recordingType = recordingType;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    /** Track Egress 시작 직후의 신규 녹화 행을 만든다. 상태는 STARTING으로 시작한다. */
    public static Recording startTrack(
            Long id, Long sessionId, String livekitEgressId, int attemptNumber, Instant startedAt) {
        return new Recording(
                id, sessionId, livekitEgressId, TYPE_TRACK, attemptNumber, RecordingStatus.STARTING, startedAt, null);
    }

    public static Recording reconstitute(
            Long id,
            Long sessionId,
            String livekitEgressId,
            String recordingType,
            int attemptNumber,
            RecordingStatus status,
            Instant startedAt,
            Instant endedAt) {
        return new Recording(id, sessionId, livekitEgressId, recordingType, attemptNumber, status, startedAt, endedAt);
    }

    /** webhook 이벤트에 따른 상태 전이. 중복·역순 이벤트가 상태를 역행시키지 않도록(가이드 §11) 종결 상태(COMPLETE/PARTIAL/FAILED) 이후의 전이는 조용히 무시한다. */
    public void markRecording() {
        if (status == RecordingStatus.STARTING) {
            this.status = RecordingStatus.RECORDING;
        }
    }

    /** @return 실제로 전이가 일어났는지. 중복·역순 이벤트로 재호출되면 false를 반환해 파생 작업(파일 저장 등)이 반복되지 않게 한다. */
    public boolean complete(Instant endedAt) {
        if (isTerminal()) {
            return false;
        }
        this.status = RecordingStatus.COMPLETE;
        this.endedAt = endedAt;
        return true;
    }

    public boolean fail(Instant endedAt) {
        if (isTerminal()) {
            return false;
        }
        this.status = RecordingStatus.FAILED;
        this.endedAt = endedAt;
        return true;
    }

    private boolean isTerminal() {
        return status == RecordingStatus.COMPLETE
                || status == RecordingStatus.PARTIAL
                || status == RecordingStatus.FAILED;
    }

    public Long id() {
        return id;
    }

    public Long sessionId() {
        return sessionId;
    }

    public String livekitEgressId() {
        return livekitEgressId;
    }

    public String recordingType() {
        return recordingType;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public RecordingStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }
}
