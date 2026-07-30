package com.a105.zani.session.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 세션의 활성 화면 공유자를 보관하는 포트. 벤더(Redis) 타입은 인프라 어댑터 안에만 존재한다. 값은 지금 공유 중인 참가자 ID이며 TTL이 지나면 자동 소멸한다. "한 번에 하나"(FRD §10.2)는
 * 이 슬롯의 원자적 획득으로 강제한다.
 */
public interface ActiveScreenSharePort {

    /**
     * 활성 공유 슬롯을 획득하거나 TTL을 갱신한다(원자적).
     *
     * <p>슬롯이 비어 있거나 이미 이 참가자가 소유 중이면 값을 심고 TTL을 갱신한 뒤 {@code true}. 다른 참가자가 소유 중이면 건드리지 않고 {@code false}. 획득과 갱신을 한
     * 연산으로 합쳐, 공유 중인 참가자는 같은 호출로 TTL을 늘려 슬롯을 유지하고, 갱신이 끊기면(크래시·강제 종료) TTL이 지나 슬롯이 비워진다.
     *
     * @return 지금 이 참가자가 슬롯을 소유하면 true, 다른 참가자가 소유 중이면 false
     */
    boolean claim(long sessionId, long participantId, Duration ttl);

    /** 이 참가자가 소유한 경우에만 슬롯을 비운다. 다른 참가자가 소유 중이면 아무 것도 하지 않는다(멱등). */
    void release(long sessionId, long participantId);

    /** 지금 공유 중인 참가자 ID. 아무도 공유하지 않으면 비어 있다. */
    Optional<Long> currentSharer(long sessionId);
}
