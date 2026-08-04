package com.a105.zani.recording.infrastructure.persistence;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.port.RecordingOutboxType;
import com.a105.zani.recording.application.port.SessionRecordingProgressPort;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingOutboxJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingJpaRepository;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingOutboxJpaRepository;

/**
 * 녹화 진행 상황 집계.
 *
 * <p><b>네 수치 모두 같은 기준을 쓴다: 발화를 담는 트랙만.</b> 하나라도 넓게 세면 사후 전사가 상관없는 실패에 막힌다.
 *
 * <ul>
 *   <li>{@code recordings} — {@code track_source} 가 {@code MICROPHONE} 이거나 {@code NULL}(V12 이전)
 *   <li>{@code recording_outbox} — {@code START_TRACK_EGRESS} 이고 payload 의 {@code source} 가 {@code MICROPHONE} 이거나 없음
 * </ul>
 *
 * <p>특히 {@code START_AUDIO_STREAM_EGRESS} 를 반드시 뺀다. 강사 마이크 하나에 outbox 가 둘 생기기 때문이다 — 하나는 사후 전사용 OGG 파일 Egress, 하나는 실시간
 * 코칭 링버퍼용 WebSocket Egress. 후자가 실패해도 파일은 정상인데 함께 세면 전사가 {@code BROKEN} 으로 굳는다.
 *
 * <p>상태 문자열은 enum·엔티티 상수만을 단일 소스로 쓰고 쿼리에 리터럴을 두지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRecordingProgressAdapter implements SessionRecordingProgressPort {

    private static final List<String> RUNNING_RECORDING_STATUSES =
            List.of(RecordingStatus.STARTING.name(), RecordingStatus.RECORDING.name());

    private static final List<String> FAILED_RECORDING_STATUSES = List.of(RecordingStatus.FAILED.name());

    private static final List<String> SPEECH_TRACK_SOURCES = List.of(TrackSource.MICROPHONE.name());

    private static final List<String> UNDELIVERED_OUTBOX_STATUSES =
            List.of(RecordingOutboxJpaEntity.STATUS_PENDING, RecordingOutboxJpaEntity.STATUS_IN_PROGRESS);

    private static final List<String> FAILED_OUTBOX_STATUSES = List.of(RecordingOutboxJpaEntity.STATUS_FAILED);

    /** payload 안의 트랙 종류 필드 이름. {@code TrackEgressPayload.source} 와 같아야 한다. */
    private static final String PAYLOAD_SOURCE_FIELD = "source";

    private final RecordingJpaRepository recordingJpaRepository;
    private final RecordingOutboxJpaRepository recordingOutboxJpaRepository;

    // 컨텍스트에 공용 ObjectMapper 빈이 없어 payload 판독 전용으로 어댑터가 직접 소유한다
    // (RecordingOutboxPersistenceAdapter 와 같은 방식).
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(readOnly = true)
    public RecordingProgress load(Long sessionId) {
        return new RecordingProgress(
                recordingJpaRepository.countSpeechRecordings(
                        sessionId, RUNNING_RECORDING_STATUSES, SPEECH_TRACK_SOURCES),
                recordingJpaRepository.countSpeechRecordings(
                        sessionId, FAILED_RECORDING_STATUSES, SPEECH_TRACK_SOURCES),
                countSpeechTrackOutbox(sessionId, UNDELIVERED_OUTBOX_STATUSES),
                countSpeechTrackOutbox(sessionId, FAILED_OUTBOX_STATUSES));
    }

    private long countSpeechTrackOutbox(Long sessionId, List<String> statuses) {
        return recordingOutboxJpaRepository
                .findPayloadsBySessionIdAndTypeAndStatusIn(
                        sessionId, RecordingOutboxType.START_TRACK_EGRESS.name(), statuses)
                .stream()
                .filter(this::isSpeechTrack)
                .count();
    }

    /**
     * payload 가 발화 트랙을 가리키는지.
     *
     * <p>필드 이름 하나만 읽는다. {@code TrackEgressPayload} 로 역직렬화하지 않는 이유는 그쪽에 필드가 늘거나 이름이 바뀌면 여기서 예외가 나 세션 전체의 판정이 막히기 때문이다 —
     * 필요한 것은 종류 하나다.
     *
     * <p><b>읽을 수 없으면 발화 트랙으로 본다.</b> V12 이전 payload 에는 {@code source} 가 없다. 아니라고 단정하면 그 트랙이 실패했거나 아직 진행 중인 것을 못 보고, 그
     * 화자가 빠진 전사를 완전한 것으로 확정한다. 판단이 갈릴 때 기다리거나 실패하는 쪽이 조용히 틀리는 쪽보다 낫다.
     */
    private boolean isSpeechTrack(String payload) {
        if (payload == null || payload.isBlank()) {
            return true;
        }
        try {
            JsonNode source = objectMapper.readTree(payload).get(PAYLOAD_SOURCE_FIELD);
            if (source == null || source.isNull() || !source.isTextual()) {
                return true;
            }
            return TrackSource.MICROPHONE.name().equals(source.asText());
        } catch (Exception unreadable) {
            log.warn("Could not read the track source from an outbox payload, treating it as a speech track");
            return true;
        }
    }
}
