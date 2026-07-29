package com.a105.zani.attention.application.port;

/**
 * 지금 열려 있는 트리거의 결과.
 *
 * <p>세 상태를 한 타입으로 담는다 — 트리거만 열린 상태(팁 생성 중), 팁이 완성된 상태, 팁을 못 만든 상태. 강사 클라이언트는 10초마다 폴링하며 같은 {@code triggerId} 를 다시 받으면
 * 무시하므로, 세 상태가 순서대로 같은 봉투에 실려 오는 편이 프론트에 단순하다.
 *
 * @param triggerId 이 트리거의 식별자. 중복 표시·중복 저장을 막는 기준이다
 * @param tip 완성된 팁. 아직 생성 중이거나 못 만들었으면 {@code null}
 * @param unavailableReason 팁을 못 만든 사유. 생성 중이거나 성공했으면 {@code null}
 */
public record CoachingOutcome(String triggerId, CoachingTip tip, CoachingTipUnavailableReason unavailableReason) {

    public CoachingOutcome {
        if (triggerId == null || triggerId.isBlank()) {
            throw new IllegalArgumentException("triggerId 없이는 중복 표시를 막을 수 없습니다.");
        }
        if (tip != null && unavailableReason != null) {
            throw new IllegalArgumentException("팁과 미표시 사유가 함께 있을 수 없습니다.");
        }
    }

    /** 트리거를 연 직후. 전사와 문구 생성이 끝나기까지 20초 넘게 걸리므로 이 상태로 먼저 응답한다. */
    public static CoachingOutcome pending(String triggerId) {
        return new CoachingOutcome(triggerId, null, null);
    }

    public static CoachingOutcome completed(String triggerId, CoachingTip tip) {
        return new CoachingOutcome(triggerId, tip, null);
    }

    public static CoachingOutcome unavailable(String triggerId, CoachingTipUnavailableReason reason) {
        return new CoachingOutcome(triggerId, null, reason);
    }
}
