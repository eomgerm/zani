package com.a105.zani.attention.application.collect;

/**
 * 판정 이벤트 수집 결과.
 *
 * @param accepted 이번 요청으로 상태가 반영되었는지
 * @param duplicate 이미 처리한 clientEventId 라 무시했는지. 재시도는 오류가 아니므로 성공으로 응답한다.
 */
public record CollectAttentionEventResult(boolean accepted, boolean duplicate) {

    /** 처음 받은 이벤트라 상태에 반영했다. */
    public static CollectAttentionEventResult recorded() {
        return new CollectAttentionEventResult(true, false);
    }

    /** 이미 처리한 이벤트라 상태를 건드리지 않았다. */
    public static CollectAttentionEventResult alreadyRecorded() {
        return new CollectAttentionEventResult(false, true);
    }
}
