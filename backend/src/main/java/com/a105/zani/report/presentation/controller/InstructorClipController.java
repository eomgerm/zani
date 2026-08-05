package com.a105.zani.report.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.report.application.getinstructorclip.GetInstructorClipQuery;
import com.a105.zani.report.application.getinstructorclip.GetInstructorClipUseCase;
import com.a105.zani.report.presentation.response.InstructorClipResponse;

@Tag(name = "강사 수업 클립", description = "종료된 수업의 강사용 녹화·전사 재생 정보")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class InstructorClipController {

    private final GetInstructorClipUseCase getInstructorClipUseCase;

    @Operation(summary = "강사 수업 클립 조회", description = "종료된 수업의 공통 녹화 재생 주소와 실명 화자 전사를 조회한다. 공통 리포트가 게시된 뒤부터 열린다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "녹화·전사 재생 정보"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 참가자가 아니거나 강사가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "세션이 없거나 공통 리포트가 아직 게시되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "아직 진행 중인 세션")
    })
    @GetMapping("/{sessionId}/reports/instructor/clip")
    public ApiResponse<InstructorClipResponse> get(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(InstructorClipResponse.from(
                getInstructorClipUseCase.get(new GetInstructorClipQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
