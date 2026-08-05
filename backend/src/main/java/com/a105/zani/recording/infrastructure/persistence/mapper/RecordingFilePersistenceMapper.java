package com.a105.zani.recording.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.recording.domain.model.RecordingFile;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFileJpaEntity;

/**
 * 파일 행의 <b>쓰기</b> 매핑. 되돌리는 방향은 없다.
 *
 * <p>읽기는 {@code SessionTrackFileQueryAdapter} 가 엔티티에서 곧바로 투영으로 옮긴다. 여기에 {@code toDomain} 을 두면 생성 가드를 지나지 않은
 * {@link RecordingFile} 이 생기고, 그 객체가 {@code save} 로 흘러갈 수 있게 된다 — 읽기용 객체를 아예 만들지 않는 편이 그 실수를 컴파일 단계에서 막는다.
 */
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
}
