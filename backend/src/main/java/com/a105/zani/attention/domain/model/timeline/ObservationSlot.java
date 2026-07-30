package com.a105.zani.attention.domain.model.timeline;

import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 이벤트 한 건이 덮는 10초. 다음 이벤트가 10초보다 일찍 오면 거기서 끊어 겹치지 않게 한다.
 *
 * <p>구간은 시작을 포함하고 끝을 포함하지 않는다. 인접한 두 슬롯의 경계 시각이 양쪽에 모두 들어가면 그 순간의 상태가 둘로 갈린다.
 */
public record ObservationSlot(long startMs, long endMs, DetectorOutcome outcome) {

    public boolean covers(long atMs) {
        return atMs >= startMs && atMs < endMs;
    }
}
