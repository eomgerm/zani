package com.a105.zani.recording.infrastructure.persistence.mapper;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingJpaEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code track_source} 문자열을 enum 으로 되돌리는 경로를 고정한다.
 *
 * <p>이 매퍼는 {@code valueOf} 를 그대로 쓰지 않고 감싼다. 알 수 없는 값에 예외가 올라가면 녹화 행 하나 때문에 최종 MP4 병합까지 막히기 때문이다. 방어 로직은 정상 동작할 때 아무 흔적을
 * 남기지 않아, 테스트가 없으면 나중에 {@code TrackSource.valueOf(...)} 로 "단순화" 되어도 아무도 알아채지 못한다. 그래서 여기서 못박는다.
 */
class RecordingPersistenceMapperTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-03T05:00:00Z");

    private final RecordingPersistenceMapper mapper = new RecordingPersistenceMapper();

    private static RecordingJpaEntity entityWithTrackSource(String storedTrackSource) {
        return RecordingJpaEntity.builder()
                .id(1L)
                .sessionId(100L)
                .livekitEgressId("EG_1")
                .sessionParticipantId(300L)
                .trackSource(storedTrackSource)
                .livekitTrackSid("TR_1")
                .recordingType(Recording.TYPE_TRACK)
                .attemptNumber(1)
                .status(RecordingStatus.RECORDING.name())
                .startedAt(STARTED_AT)
                .build();
    }

    @Test
    void 화자와_트랙_종류가_영속화_경계를_왕복해도_유지된다() {
        Recording recording =
                Recording.startTrack(1L, 100L, "EG_1", 300L, TrackSource.SCREEN_SHARE_AUDIO, "TR_1", 1, STARTED_AT);

        RecordingJpaEntity entity = mapper.toEntity(recording);
        Recording restored = mapper.toDomain(entity);

        assertEquals(300L, entity.getSessionParticipantId());
        assertEquals("SCREEN_SHARE_AUDIO", entity.getTrackSource());
        assertEquals("TR_1", entity.getLivekitTrackSid());
        assertEquals(TrackSource.SCREEN_SHARE_AUDIO, restored.trackSource());
        assertEquals(300L, restored.sessionParticipantId());
        assertEquals("TR_1", restored.livekitTrackSid());
    }

    @Test
    void 알_수_없는_track_source_문자열은_null_로_읽는다() {
        // 값 이름이 바뀌거나 롤백된 배포가 옛 이름을 남긴 경우. 예외를 올리면 이 녹화가 포함된 세션의
        // 후처리 전체가 막힌다. "기록되지 않은 것" 으로 보면 사후 전사가 그 파일만 건너뛴다.
        assertNull(mapper.toDomain(entityWithTrackSource("RETIRED_SOURCE")).trackSource());
    }

    @Test
    void 비어_있는_track_source_는_null_로_읽는다() {
        // V12 이전 행은 NULL 이다. 공백 문자열도 같게 다뤄 호출자가 한 가지 경우만 보게 한다.
        assertNull(mapper.toDomain(entityWithTrackSource(null)).trackSource());
        assertNull(mapper.toDomain(entityWithTrackSource("")).trackSource());
        assertNull(mapper.toDomain(entityWithTrackSource("   ")).trackSource());
    }

    @Test
    void 값이_없는_전환기_녹화도_엔티티로_바꿀_수_있다() {
        // reconstitute 는 가드를 두지 않는다 — 이미 DB 에 있는 행을 그대로 읽어야 한다.
        Recording legacy = Recording.reconstitute(
                1L,
                100L,
                "EG_LEGACY",
                null,
                null,
                null,
                Recording.TYPE_TRACK,
                1,
                RecordingStatus.RECORDING,
                STARTED_AT,
                null);

        RecordingJpaEntity entity = mapper.toEntity(legacy);

        assertNull(entity.getSessionParticipantId());
        assertNull(entity.getTrackSource());
        assertNull(entity.getLivekitTrackSid());
    }
}
