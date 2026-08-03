package com.a105.zani.recording.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingJpaEntity;

@Component
public class RecordingPersistenceMapper {

    public RecordingJpaEntity toEntity(Recording recording) {
        return RecordingJpaEntity.builder()
                .id(recording.id())
                .sessionId(recording.sessionId())
                .livekitEgressId(recording.livekitEgressId())
                .sessionParticipantId(recording.sessionParticipantId())
                .trackSource(
                        recording.trackSource() == null
                                ? null
                                : recording.trackSource().name())
                .livekitTrackSid(recording.livekitTrackSid())
                .recordingType(recording.recordingType())
                .attemptNumber(recording.attemptNumber())
                .status(recording.status().name())
                .startedAt(recording.startedAt())
                .endedAt(recording.endedAt())
                .build();
    }

    public Recording toDomain(RecordingJpaEntity entity) {
        return Recording.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getLivekitEgressId(),
                entity.getSessionParticipantId(),
                trackSource(entity.getTrackSource()),
                entity.getLivekitTrackSid(),
                entity.getRecordingType(),
                entity.getAttemptNumber(),
                RecordingStatus.valueOf(entity.getStatus()),
                entity.getStartedAt(),
                entity.getEndedAt());
    }

    /**
     * 저장된 문자열을 {@link TrackSource} 로 되돌린다. NULL 과 알 수 없는 값을 같게 다뤄 null 을 준다.
     *
     * <p>{@code valueOf} 를 그대로 쓰지 않는 이유: 이 컬럼은 S15P11A105-97 에서 뒤늦게 더한 것이라 기존 행은 전부 NULL 이다. 값 이름이 바뀌거나 롤백된 배포가 옛 이름을
     * 남기면 읽는 순간 {@code IllegalArgumentException} 이 올라오고, 녹화 행 하나 때문에 최종 MP4 병합까지 막힌다. 알 수 없는 값을 "기록되지 않은 것" 으로 보면 사후
     * 전사가 그 파일만 건너뛰고 나머지는 계속 진행한다. V5 가 같은 함정을 고치려고 만들어진 전례가 있다.
     */
    private TrackSource trackSource(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return TrackSource.valueOf(stored);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
