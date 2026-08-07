package com.a105.zani.report.presentation.request;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.report.application.askreportquestion.AskReportQuestionCommand;
import com.a105.zani.report.application.port.HistoryTurn;

/**
 * 리포트 질의응답 요청.
 *
 * <p>세 값 모두 클라이언트가 보낸 자유 입력이라 <b>여기서 상한을 건다.</b> 자르지 않고 거절하는 이유는, 조용히 잘린 질문에 답이 오면 사용자가 자기가 무엇을 물었는지 모른 채 엉뚱한 답을 읽게 되기
 * 때문이다.
 *
 * <p>상한 자체는 GMS 본문 예산에서 나온 값이 아니다. 예산은 어댑터가 완성된 본문으로 다시 재고, 이 값들은 "사람이 채팅창에 쓸 만한 길이" 다.
 */
@Schema(description = "수업 요약에서 드래그한 곳에 대한 질문")
public record AskReportQuestionRequest(
        @Schema(description = "질문", example = "개방주소법이 뭐야?") @NotBlank @Size(max = 500) String question,

        @Schema(description = "화면에서 드래그한 텍스트. 표시용이며 근거로 쓰이지 않는다", example = "개방주소법") @Size(max = 200) String selectedText,

        @Schema(description = "드래그한 구간의 시작 시각(ms). 전체 요약 문단처럼 구간 밖을 짚었으면 비워 둔다", example = "95000") @PositiveOrZero Long anchorStartMs,

        @Schema(description = "직전 대화. 서버는 저장하지 않으므로 클라이언트가 최근 6턴을 함께 보낸다") @Size(max = 6) List<@Valid HistoryTurnRequest> history) {

    @Schema(description = "대화 한 턴")
    public record HistoryTurnRequest(
            @Schema(description = "user 또는 assistant", example = "user") @Pattern(regexp = "user|assistant") String role,

            @Schema(description = "그 턴의 내용") @NotBlank @Size(max = 500) String content) {}

    public AskReportQuestionCommand toCommand(Long sessionId, Long memberId) {
        return new AskReportQuestionCommand(
                sessionId,
                memberId,
                question,
                selectedText,
                anchorStartMs,
                history == null
                        ? List.of()
                        : history.stream()
                                .map(turn -> new HistoryTurn(turn.role(), turn.content()))
                                .toList());
    }
}
