package com.a105.zani.attention.presentation.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.attention.application.recordpromptresponse.RecordPromptResponseCommand;
import com.a105.zani.attention.application.recordpromptresponse.RecordPromptResponseResult;
import com.a105.zani.attention.application.recordpromptresponse.RecordPromptResponseUseCase;
import com.a105.zani.attention.presentation.request.PromptResponseRequest;
import com.a105.zani.attention.presentation.response.PromptResponseResponse;
import com.a105.zani.common.response.ApiResponse;

@Validated
@Tag(name = "학생 프롬프트", description = "학생에게 띄운 확인 프롬프트의 응답을 수집한다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class PromptResponseController {

    private final RecordPromptResponseUseCase recordPromptResponseUseCase;

    @Operation(summary = "프롬프트 응답 전송", description = """
                    학생이 이해 확인 프롬프트에 낸 답을 기록한다. 프롬프트를 띄울지는 브라우저가 판정하므로(75) 서버에는 표시 시점의 기록이 없고,
                    이 요청이 도착할 때 비로소 행이 만들어진다. 30초가 지나 패널이 자동으로 닫힌 경우에도 answer=NON_RESPONSE 로 보내야 한다.

                    자세 안내와 카메라 안내의 응답은 보내지 않는다. 그 두 프롬프트는 브라우저 안에서 끝나고, 노출 30초·종류별 5분 쿨타임·
                    카메라 안내 재권유도 모두 브라우저가 관리한다.

                    같은 프롬프트에 두 번 답하면 첫 답만 남고 duplicate=true 로 성공 응답한다. 재시도할 때는 promptId 와 shownAt 을
                    모두 처음과 같은 값으로 보내야 한다. 중복 판정은 promptId 로 하고, 코칭 저장소를 쓸 수 없을 때만
                    (학생, 종류, shownAt)으로 물러선다. 매번 새 promptId 를 만들면 같은 답이 두 번 기록될 수 있다.

                    네 답 모두 학생의 참여 상태를 확정해 강사 코칭 집계에 곧바로 반영된다. 다만 **응답은 집단 비율의 분모를 바꾸지 않는다** —
                    분모 제외는 서버가 CAMERA_OFF·DETECTOR_UNAVAILABLE 이 연속 1분 이어지는지를 직접 보고 판단한다. 답 자체는 수업 후
                    리포트의 근거라, 코칭 저장소가 죽어도 응답 기록은 남기고 코칭 반영만 건너뛴다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "응답 기록 또는 중복 무시"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "필수 값 누락, 프롬프트 종류에 없는 답, 수업 시간선과 어긋난 시각, 또는 계약에 없는 필드가 포함됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 멤버가 아니거나, 프롬프트 대상이 아닌 역할(강사)임"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "이미 종료된 세션이거나, 답하기에는 너무 오래된 프롬프트(표시 후 5분 초과). 클라이언트는 전송을 멈춘다.")
    })
    @PostMapping("/{sessionId}/prompts/{promptId}/responses")
    public ApiResponse<PromptResponseResponse> respond(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Parameter(description = "브라우저가 만든 프롬프트 식별자. 중복 판정의 기준이므로 재시도할 때 같은 값을 보내야 한다.")
                    @PathVariable
                    @Size(max = 64) String promptId,
            @Valid @RequestBody PromptResponseRequest request) {
        RecordPromptResponseResult result = recordPromptResponseUseCase.record(new RecordPromptResponseCommand(
                sessionId,
                Long.parseLong(jwt.getSubject()),
                promptId,
                request.kind(),
                request.answer(),
                request.shownAt(),
                request.respondedAt()));
        return ApiResponse.success(PromptResponseResponse.from(result));
    }
}
