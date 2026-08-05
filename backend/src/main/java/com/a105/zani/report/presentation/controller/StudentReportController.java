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
import com.a105.zani.report.application.getstudentreport.GetStudentReportQuery;
import com.a105.zani.report.application.getstudentreport.GetStudentReportUseCase;
import com.a105.zani.report.presentation.response.StudentReportResponse;

@Tag(name = "학생 학습 리포트", description = "종료된 수업의 학생 본인 학습 리포트")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class StudentReportController {

    private final GetStudentReportUseCase getStudentReportUseCase;

    @Operation(summary = "학생 본인 학습 리포트 조회", description = "종료된 수업에서 호출자 본인의 공개 채팅·확인 응답 집계와 참여 요약, 복습 추천 최대 5개를 조회한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게시된 학생 학습 리포트"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 참가자가 아니거나 학생이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "세션 또는 게시된 학생 리포트를 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "아직 진행 중인 세션")
    })
    @GetMapping("/{sessionId}/reports/student")
    public ApiResponse<StudentReportResponse> get(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(StudentReportResponse.from(
                getStudentReportUseCase.get(new GetStudentReportQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
