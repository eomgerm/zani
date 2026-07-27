package com.a105.zani.session.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.session.application.end.EndSessionByInstructorCommand;
import com.a105.zani.session.application.end.EndSessionByInstructorUseCase;
import com.a105.zani.session.application.end.EndSessionResult;
import com.a105.zani.session.presentation.response.SessionEndResponse;

@Tag(name = "세션 종료", description = "강사의 명시적 수업 종료")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionEndController {

    private final EndSessionByInstructorUseCase endSessionByInstructorUseCase;

    @Operation(
            summary = "수업 종료",
            description = "수업을 연 강사가 강의실에서 수업을 종료한다. 최대 시간 도달·강사 미복귀 종료와 같은 경로를 사용하며, 이미 종료된 세션이면 상태만 돌려준다(멱등).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "종료 완료"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이 세션의 강사가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음")
    })
    @PostMapping("/{sessionId}/end")
    public ApiResponse<SessionEndResponse> end(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        EndSessionResult result = endSessionByInstructorUseCase.endByInstructor(
                new EndSessionByInstructorCommand(sessionId, Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(SessionEndResponse.from(result));
    }
}
