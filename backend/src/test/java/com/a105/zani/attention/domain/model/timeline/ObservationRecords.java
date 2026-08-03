package com.a105.zani.attention.domain.model.timeline;

import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 슬롯 시작 시각으로 관측을 만드는 테스트 도우미.
 *
 * <p>타임라인 계산은 전부 슬롯 시작 위에 서지만 수집 계약이 저장하는 것은 관측 시각과 창 시작이다. 테스트가 그 변환을 각자 적으면 한 곳만 틀려도 시간축이 어긋난 채 초록으로 보이므로, 수집 계약이
 * 저장했을 값을 만드는 일을 여기 하나에 모은다.
 */
public final class ObservationRecords {

    private ObservationRecords() {}

    /**
     * {@code slotStartMs} 부터 10초를 관측한 한 건.
     *
     * <p>창을 보고 정해지는 값은 창 시작을 함께 저장하고 관측 시각은 창의 끝이다. 창 없이 확정되는 값({@code CAMERA_OFF}·{@code DETECTOR_UNAVAILABLE})은 창 시작이
     * 없고 관측 시각이 곧 슬롯 시작이다.
     */
    public static ObservationRecord at(long participantId, long slotStartMs, DetectorOutcome outcome) {
        if (outcome.needsObservationWindow()) {
            return new ObservationRecord(
                    participantId, slotStartMs + ObservationRecord.WINDOW_MS, slotStartMs, outcome);
        }
        return new ObservationRecord(participantId, slotStartMs, null, outcome);
    }
}
