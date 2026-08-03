package com.a105.zani.attention.application.getattentiontimeline;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;

/**
 * 타임라인이 다룰 길이를 정한다.
 *
 * <p>세션 종료 시각이 있으면 그것을 쓴다. 종료 시각을 저장하기 전에 끝난 과거 세션은 값이 없으므로 마지막 관측 시각으로 대신한다. 이 fallback 은 과거 데이터도 계속 조회할 수 있게 하는 영구
 * 경로다.
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
        // 관측 시각은 판정이 정해진 시각이라 창이 있으면 그 창의 끝이다. 데이터가 닿는 마지막 지점을 재는
        // 값으로는 창 시작보다 이쪽이 맞다.
        long lastOffsetMs = observations.stream()
                .mapToLong(ObservationRecord::occurredOffsetMs)
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
