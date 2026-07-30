package com.a105.zani.attention.application.collect;

/**
 * 판정 이벤트 수집 결과.
 *
 * @param accepted 이번 요청으로 기록과 집계가 반영되었는지
 * @param duplicate 이미 처리한 clientEventId 라 무시했는지. 재시도는 오류가 아니므로 성공으로 응답한다
 * @param supersededByNewerJudgement 더 최신 판정이 이미 반영돼 집계에는 쓰지 않았는지. 기록은 남는다
 */
public record CollectAttentionEventResult(boolean accepted, boolean duplicate, boolean supersededByNewerJudgement) {

    /** 처음 받은 판정이라 기록하고 집계에 반영했다. */
    public static CollectAttentionEventResult recorded() {
        return new CollectAttentionEventResult(true, false, false);
    }

    /** 이미 처리한 판정이라 아무것도 건드리지 않았다. */
    public static CollectAttentionEventResult alreadyRecorded() {
        return new CollectAttentionEventResult(false, true, false);
    }

    /** 늦게 도착한 옛 판정이라 기록만 남기고 집계는 건드리지 않았다. */
    public static CollectAttentionEventResult recordedButSuperseded() {
        return new CollectAttentionEventResult(true, false, true);
    }
}
