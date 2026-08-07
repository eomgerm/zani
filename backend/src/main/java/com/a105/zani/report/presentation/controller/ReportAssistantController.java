package com.a105.zani.report.presentation.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.report.application.askreportquestion.AskReportQuestionUseCase;
import com.a105.zani.report.presentation.request.AskReportQuestionRequest;
import com.a105.zani.report.presentation.response.ReportAnswerResponse;

/**
 * 수업 요약에서 드래그한 곳에 대한 질의응답(S15P11A105-259).
 *
 * <p>{@link SessionReportController} 와 나누어 두는 이유는 같은 열람 자격을 쓰지만 성격이 다르기 때문이다 — 그쪽은 만들어진 산출물을 읽는 조회고, 이쪽은 외부 모델을 부르는
 * 대화다.
 *
 * <p>조회인데 {@code POST} 인 이유는 질문과 이력이 본문에 실리고 GMS 크레딧을 소비하기 때문이다. 캐시되거나 재전송되면 곤란하다.
 */
@Tag(name = "수업 요약 질의응답", description = "리포트에서 드래그한 곳에 대해 묻는다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class ReportAssistantController {

    private final AskReportQuestionUseCase askReportQuestionUseCase;

    @Operation(summary = "수업 요약 질의응답", description = """
                    수업 요약에서 드래그한 지점의 시각(`anchorStartMs`)을 받아, 그 구간의 요약과 앞뒤 한 구간의 전사, 전체 목차를
                    근거로 답한다. 참여자면 강사·학생 모두 쓸 수 있다.

                    `selectedText` 는 표시용이며 근거가 아니다. 근거는 서버가 자기 DB 에서만 만든다.

                    인용의 `quote` 는 모델이 쓴 문장이 아니라 서버가 전사에서 채운 실제 발화다. 우리가 보낸 시간창 밖을 가리키는
                    인용은 버려지며, 그때도 답변 본문은 그대로 내려간다.

                    대화를 저장하지 않으므로 후속 질문은 클라이언트가 `history` 로 최근 6턴을 함께 보낸다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "답변"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "질문·선택 텍스트·이력이 상한을 넘었거나, 근거를 담으면 게이트웨이 본문 상한을 넘음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이 수업에 참여하지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "세션을 찾을 수 없거나 사후 분석이 아직 내용 구간을 만들지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "아직 진행 중인 세션"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "질문 간격 제한"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "답변을 만들지 못함")
    })
    @PostMapping("/{sessionId}/reports/assistant/messages")
    public ApiResponse<ReportAnswerResponse> ask(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Valid @RequestBody AskReportQuestionRequest request) {
        return ApiResponse.success(ReportAnswerResponse.from(
                askReportQuestionUseCase.ask(request.toCommand(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
