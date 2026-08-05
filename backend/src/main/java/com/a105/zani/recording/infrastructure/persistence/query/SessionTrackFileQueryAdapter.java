package com.a105.zani.recording.infrastructure.persistence.query;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.gettrackfiles.SessionTrackFile;
import com.a105.zani.recording.application.gettrackfiles.SessionTrackFileQueryPort;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFileJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.repository.RecordingFileJpaRepository;

/** 트랙 파일 읽기 투영. 엔티티에서 곧바로 투영으로 옮기고 쓰기 모델을 만들지 않는다. */
@Component
@RequiredArgsConstructor
public class SessionTrackFileQueryAdapter implements SessionTrackFileQueryPort {

    private final RecordingFileJpaRepository recordingFileJpaRepository;

    @Override
    @Transactional(readOnly = true)
    public List<SessionTrackFile> findBySessionId(Long sessionId) {
        return recordingFileJpaRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(SessionTrackFileQueryAdapter::toTrackFile)
                .toList();
    }

    private static SessionTrackFile toTrackFile(RecordingFileJpaEntity entity) {
        return new SessionTrackFile(
                entity.getId(),
                entity.getSessionParticipantId(),
                trackSource(entity.getTrackSource()),
                entity.getStorageKey(),
                effectiveTrackSid(entity),
                entity.getStartedOffsetMs(),
                entity.getEndedOffsetMs());
    }

    /**
     * 저장된 트랙 종류 문자열을 enum 으로 되돌린다. 알 수 없는 값은 {@code null} 로 본다.
     *
     * <p>{@code valueOf} 를 그대로 쓰지 않는다. V12 이전 행은 이 컬럼이 비어 있고, source 이름이 바뀌거나 롤백된 배포가 옛 이름을 남기면 읽는 순간 예외가 올라와 그 세션의 파일
     * 목록을 통째로 못 읽는다. {@code null} 로 두면 "종류를 모르는 파일" 로 보이고, 그것을 어떻게 다룰지는 읽는 쪽이 정한다 — 사후 전사는 마이크가 아닌 것으로 보아 건너뛴다.
     */
    private static TrackSource trackSource(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return TrackSource.valueOf(stored);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * 이 파일이 속한 Track SID. 파일 값이 없으면 <b>부모 Egress 의 값</b>으로 채운다.
     *
     * <p>한 Egress 가 파일을 여러 개 남기면 {@code UK(recording_id, livekit_track_sid)} 때문에 첫 행만 SID 를 갖고 나머지는 NULL 로 저장된다
     * ({@code RecordingWebhookService} 의 기존 규칙). 컬럼을 그대로 읽으면 후속 파일의 발화가 어느 발행 구간에서 나왔는지 알 수 없는데, 같은 Egress 이므로 SID 는
     * 하나이고 그 값이 부모 행에 남아 있다. 부모는 조회에서 함께 읽어 온다(join fetch).
     *
     * <p>둘 다 없는 것은 V12 이전 행이다. 그때는 Egress 시작 시 SID 를 적어 두지 않았고 지금 복원할 방법이 없으므로 {@code null} 로 둔다 — 그 한 경우만 최종 문서에서
     * {@code trackSid: null} 이 된다.
     *
     * <p>해석된 값이라 쓰기 모델에 담지 않는다. 이 투영 밖으로 나가지 않으므로 저장 경로와 섞일 수 없다.
     */
    private static String effectiveTrackSid(RecordingFileJpaEntity entity) {
        if (entity.getLivekitTrackSid() != null) {
            return entity.getLivekitTrackSid();
        }
        return entity.getRecording() == null ? null : entity.getRecording().getLivekitTrackSid();
    }
}
