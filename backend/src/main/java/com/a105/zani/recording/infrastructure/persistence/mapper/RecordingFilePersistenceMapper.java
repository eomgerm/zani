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
                effectiveTrackSid(entity),
                entity.getStartedOffsetMs(),
                entity.getEndedOffsetMs());
    }

    /**
     * 이 파일이 속한 Track SID. 파일 값이 없으면 <b>부모 Egress 의 값</b>으로 채운다.
     *
     * <p>한 Egress 가 파일을 여러 개 남기면 {@code UK(recording_id, livekit_track_sid)} 때문에 첫 행만 SID 를 갖고 나머지는 NULL 로 저장된다
     * ({@code RecordingWebhookService} 의 기존 규칙). 컬럼을 그대로 읽으면 후속 파일의 발화가 어느 발행 구간에서 나왔는지 알 수 없는데, 같은 Egress 이므로 SID 는
     * 하나이고 그 값이 부모 행에 남아 있다.
     *
     * <p>둘 다 없는 것은 V12 이전 행이다. 그때는 Egress 시작 시 SID 를 적어 두지 않았고 지금 복원할 방법이 없으므로 {@code null} 로 둔다 — 그 한 경우만 최종 문서에서
     * {@code trackSid: null} 이 된다.
     *
     * <p><b>이 값은 컬럼 값이 아니라 해석된 값이다.</b> 그래서 {@link RecordingFile#restored} 로 만든 객체를 그대로 저장해서는 안 된다. 저장하면 후속 파일의
     * {@code livekit_track_sid} 에 부모 SID 가 들어가 UNIQUE 제약을 건드린다. 쓰기 경로는 {@code trackFile}·{@code legacyTrackFile} 로만
     * 들어온다.
     */
    private String effectiveTrackSid(RecordingFileJpaEntity entity) {
        if (entity.getLivekitTrackSid() != null) {
            return entity.getLivekitTrackSid();
        }
        return entity.getRecording() == null ? null : entity.getRecording().getLivekitTrackSid();
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
