package com.a105.zani.attention.application.getattentiontimeline;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;

/**
 * 타임라인이 다룰 길이를 정한다.
 *
 * <p>세션 종료 시각이 있으면 그것을 쓰고, 없으면 마지막 관측 시각으로 대신한다. 지금은 늘 후자다 — {@code sessions.ended_at} 컬럼은 있지만 애플리케이션이 값을 쓰지 않는다.
 * {@code SessionPersistenceMapper.toEntity} 가 그 필드를 빼고 저장하고, {@code EndSessionService} 는 status 만 ENDED 로 바꾼다.
 * {@code session_status_changes} 테이블도 매핑만 있고 아무도 INSERT 하지 않는다.
 *
 * <p>그래서 이 값은 "수업 길이"가 아니라 <b>"관측이 있는 마지막 시각까지"</b>다. 종료 시각을 저장하기 시작하면 위 분기가 저절로 그쪽을 쓴다. 그때까지는 이벤트가 수업 종료 전에 끊기면 타임라인도
 * 거기서 끝난다.
 *
 * <p>{@link TimelinePolicy#maxDuration()} 상한은 데이터가 망가졌을 때 응답이 폭발하는 것을 막는 안전장치다. 오프셋 한 건이 잘못 들어오면 그 값이 그대로 격자 수가 되고, 5초
 * 격자에서는 하루짜리 오프셋 하나가 17,000 개가 넘는 점을 만든다.
 */
final class TimelineDurations {

    private TimelineDurations() {}

    static long resolveMillis(
            Instant startedAt, Instant endedAt, List<ObservationRecord> observations, TimelinePolicy policy) {
        if (endedAt != null) {
            return clampToGrid(Duration.between(startedAt, endedAt).toMillis(), policy);
        }
        if (observations.isEmpty()) {
            return 0L;
        }
        long lastOffsetMs = observations.stream()
                .mapToLong(ObservationRecord::offsetMs)
                .max()
                .orElse(0L);
        return clampToGrid(lastOffsetMs, policy);
    }

    /** 음수를 0 으로 자르고, 상한을 씌운 뒤, 격자에 맞춰 올린다. */
    private static long clampToGrid(long millis, TimelinePolicy policy) {
        long step = policy.samplingInterval().toMillis();
        long capped = Math.min(Math.max(millis, 0L), policy.maxDuration().toMillis());
        return (capped + step - 1) / step * step;
    }
}
