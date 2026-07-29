package com.a105.zani.attention.application.collect;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트에서 관측 도착 시각을 따라 옮길 수 있는 시계.
 *
 * <p>{@link Clock#fixed} 를 쓰면 창이 만들어진 시각과 서버 시각이 벌어져 모든 관측이 이미 분자 창을 넘긴 상태가 된다. 실제로는 관측이 만들어진 직후 도착하므로 시계도 함께 움직여야 창
 * 경계를 제대로 검증할 수 있다.
 */
final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void set(Instant next) {
        instant = next;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
