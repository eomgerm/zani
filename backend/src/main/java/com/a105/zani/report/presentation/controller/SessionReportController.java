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
import com.a105.zani.report.application.getsessionsummary.GetSessionSummaryQuery;
import com.a105.zani.report.application.getsessionsummary.GetSessionSummaryUseCase;
import com.a105.zani.report.presentation.response.SessionSummaryResponse;

/**
 * 수업 단위 공통 리포트. 역할별 리포트는 각자의 컨트롤러가 맡는다.
 *
 * <p>{@link StudentReportController} 와 나누어 두는 이유는 열람 자격이 다르기 때문이다 — 그쪽은 학생 본인만, 이쪽은 참여자 전원이다.
 */
@Tag(name = "수업 공통 리포트", description = "종료된 수업에서 강사·학생이 같이 보는 산출물")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionReportController {

    private final GetSessionSummaryUseCase getSessionSummaryUseCase;

    @Operation(summary = "수업 요약 조회", description = """
                    종료된 수업의 공통 요약을 조회한다. 이 수업에 실제로 참여한 사람이면 강사·학생 모두 **같은 문장**을 받는다.

                    사후 분석이 아직 요약을 만들지 않았거나 게시 전이면 404 로 "아직 준비되지 않음" 을 알린다 — 빈 문자열을 내리지 않는다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게시된 수업 요약"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이 수업에 참여하지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "세션 또는 게시된 수업 요약을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "아직 진행 중인 세션")
    })
    @GetMapping("/{sessionId}/reports/summary")
    public ApiResponse<SessionSummaryResponse> getSummary(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(SessionSummaryResponse.from(
                getSessionSummaryUseCase.get(new GetSessionSummaryQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
