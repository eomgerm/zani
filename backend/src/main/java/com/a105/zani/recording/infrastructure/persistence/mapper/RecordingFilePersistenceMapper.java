package com.a105.zani.recording.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.recording.domain.model.RecordingFile;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFileJpaEntity;

@Component
public class RecordingFilePersistenceMapper {

    public RecordingFileJpaEntity toEntity(RecordingFile file) {
        return RecordingFileJpaEntity.builder()
                .id(file.id())
                .sessionId(file.sessionId())
                .recordingId(file.recordingId())
                .sessionParticipantId(file.sessionParticipantId())
                .trackSource(
                        file.trackSource() == null ? null : file.trackSource().name())
                .fileType(file.fileType())
                .storageKey(file.storageKey())
                .livekitTrackSid(file.livekitTrackSid())
                .startedOffsetMs(file.startedOffsetMs())
                .endedOffsetMs(file.endedOffsetMs())
                .build();
    }

    public RecordingFile toDomain(RecordingFileJpaEntity entity) {
        return RecordingFile.restored(
                entity.getId(),
                entity.getSessionId(),
                entity.getRecordingId(),
                entity.getSessionParticipantId(),
                trackSource(entity.getTrackSource()),
                entity.getFileType(),
                entity.getStorageKey(),
                entity.getLivekitTrackSid(),
                entity.getStartedOffsetMs(),
                entity.getEndedOffsetMs());
    }

    /**
     * 저장된 트랙 종류 문자열을 enum 으로 되돌린다. 알 수 없는 값은 {@code null} 로 본다.
     *
     * <p>{@code valueOf} 를 그대로 쓰지 않는다. V12 이전 행은 이 컬럼이 비어 있고, source 이름이 바뀌거나 롤백된 배포가 옛 이름을 남기면 읽는 순간 예외가 올라와 그 세션의 파일
     * 목록을 통째로 못 읽는다. {@code null} 로 두면 "종류를 모르는 파일" 로 보이고, 그것을 어떻게 다룰지는 읽는 쪽이 정한다 — 사후 전사는 마이크가 아닌 것으로 보아 건너뛴다.
     */
    private TrackSource trackSource(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return TrackSource.valueOf(stored);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
