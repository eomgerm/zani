package com.a105.zani.attention.domain.model.timeline;

import com.a105.zani.attention.domain.model.DetectorOutcome;

/**
 * 저장된 검출기 관측 한 건. 재생기가 읽는 최소 단위다.
 *
 * <p>시각이 둘인 이유는 수집 계약이 둘을 구분해 받기 때문이다({@code AttentionEventRequest}). {@code occurredOffsetMs} 는 값이 <b>정해진</b> 시각이라 10초
 * 창이 있으면 창의 <b>끝</b>이고, {@code windowStartedOffsetMs} 가 그 창의 <b>시작</b>이다. 재생이 되돌려야 하는 것은 판정이 덮은 시간이므로 슬롯 시작은 뒤쪽이다 —
 * {@link #slotStartMs()} 가 그 계산을 혼자 맡는다.
 *
 * @param participantId 관측을 보낸 세션 참가자
 * @param occurredOffsetMs 세션 시작 기준으로 값이 정해진 시각(ms). 창이 있으면 창의 끝이다
 * @param windowStartedOffsetMs 10초 창의 시작 시각(ms). 보내지 않아도 되는 값이라 비어 있을 수 있다
 * @param outcome 브라우저가 판정한 관측 값
 */
public record ObservationRecord(
        long participantId, long occurredOffsetMs, Long windowStartedOffsetMs, DetectorOutcome outcome) {

    /** 관측 한 건이 덮는 시간. 브라우저가 10초 창을 분석해 10초마다 보낸다(확정 문서 §1). */
    public static final long WINDOW_MS = 10_000L;

    /**
     * 이 관측이 덮은 10초 슬롯의 시작 시각. 타임라인의 시간축은 전부 이 값 위에 선다.
     *
     * <p>창을 보고 정해지는 값(1~4단계·{@code UNMEASURABLE})은 창 시작이 곧 슬롯 시작이다. 창 시작을 받지 못한 행은 계약이 창 길이를 10초로 고정하고 있으므로 관측 시각에서
     * 10초를 뺀다. 이 보정을 하지 않고 관측 시각을 그대로 쓰면 실제 {@code [0,10)} 판정이 {@code [10,20)} 으로 재생돼 타임라인 전체가 10초 밀린다.
     *
     * <p>창 없이 그 자리에서 확정되는 값({@code CAMERA_OFF}·{@code DETECTOR_UNAVAILABLE})은 뺄 창이 없다(§4.2). 관측 시각이 곧 슬롯 시작이다.
     *
     * <p>보정 결과가 음수면 0 으로 자른다. 세션 시작 직후의 첫 창은 세션 이전까지 걸칠 수 있는데, 음수 오프셋은 격자 밖이라 어떤 칸에도 들어가지 못한다.
     */
    public long slotStartMs() {
        if (!outcome.needsObservationWindow()) {
            return occurredOffsetMs;
        }
        if (windowStartedOffsetMs != null) {
            return windowStartedOffsetMs;
        }
        return Math.max(0L, occurredOffsetMs - WINDOW_MS);
    }
}
