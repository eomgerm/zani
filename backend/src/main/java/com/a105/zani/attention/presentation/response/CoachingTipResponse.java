package com.a105.zani.attention.presentation.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.attention.application.polltip.PollCoachingTipResult;
import com.a105.zani.attention.application.port.CoachingOutcome;
import com.a105.zani.attention.application.port.CoachingTip;
import com.a105.zani.attention.application.port.CoachingTipType;
import com.a105.zani.attention.application.port.CoachingTipUnavailableReason;

/**
 * 강사 팁 폴링 응답.
 *
 * <p>팁이 없어도 {@code 200} 에 본문을 담아 내려준다. {@code 204 No Content} 로 하면 본문이 없어 미표시 사유를 함께 전달할 수 없고, 프론트가 "지금 띄울 팁이 없는 것"과
 * "전사가 실패해 못 만든 것"을 구분하지 못한다.
 *
 * @param triggerId 대기 중인 트리거의 식별자. 없으면 {@code null}. 같은 값을 다시 받으면 카드를 다시 띄우지 않는다
 * @param tip 표시할 팁. 아직 생성 중이거나 못 만들었으면 {@code null}
 * @param unavailableReason 팁을 못 만든 사유. 표시할 팁이 있거나 생성 중이면 {@code null}
 */
@Schema(description = "강사 팁 폴링 응답. 대기 중인 팁이 없어도 200 으로 응답한다.")
public record CoachingTipResponse(
        @Schema(
                description = "대기 중인 트리거 식별자. 같은 값을 다시 받으면 카드를 다시 띄우지 않는다.",
                example = "0d3f5b7c-9a41-4f22-8b6e-2c1d5e7a9f04",
                nullable = true)
        String triggerId,

        @Schema(description = "표시할 팁. 생성 중이거나 만들지 못했으면 null.", nullable = true)
        Tip tip,

        @Schema(description = """
                        팁을 만들지 못한 사유. 이 값이 있다고 코칭 기능이 죽은 것은 아니며 다음 트리거에서 복구된다 —
                        강사 화면의 코칭 비활성 표시는 폴링 자체가 연속 실패할 때만 쓴다(티켓 76).""", nullable = true) CoachingTipUnavailableReason unavailableReason) {

    /**
     * 팁 한 건.
     *
     * <p>문구는 서버에서 완성한 상태로 내려간다. 유형은 검증과 로깅용이며 프론트는 유형별로 화면을 나누지 않는다(티켓 86).
     */
    @Schema(description = "서버가 문구까지 완성한 팁 한 건")
    public record Tip(
            @Schema(description = "고른 팁 유형", example = "CONFUSED")
            CoachingTipType tipType,

            @Schema(description = "카드 제목", example = "지금 다시 짚고 갈 개념이 있습니다")
            String title,

            @Schema(description = "완성된 조언 문구", example = "전체 학생의 34%가 헷갈려하고 있습니다. 재귀 호출의 종료 조건을 예시와 함께 다시 설명해 주세요.")
            String message,

            @Schema(description = """
                            조언이 가리키는 핵심 개념. 무응답·자리비움 팁은 §8 문구에 자리표시자가 없어 LLM 을 부르지 않으므로 null 이다 —
                            필수로 보면 다섯 유형 중 둘이 강사에게 영영 뜨지 않는다(티켓 86·204).""", example = "재귀 호출의 종료 조건", nullable = true)
            String targetConcept) {

        private static Tip from(CoachingTip tip) {
            return new Tip(tip.tipType(), tip.title(), tip.message(), tip.targetConcept());
        }
    }

    public static CoachingTipResponse from(PollCoachingTipResult result) {
        return result.outcome().map(CoachingTipResponse::from).orElseGet(CoachingTipResponse::empty);
    }

    private static CoachingTipResponse from(CoachingOutcome outcome) {
        return new CoachingTipResponse(
                outcome.triggerId(),
                outcome.tip() == null ? null : Tip.from(outcome.tip()),
                outcome.unavailableReason());
    }

    private static CoachingTipResponse empty() {
        return new CoachingTipResponse(null, null, null);
    }
}
