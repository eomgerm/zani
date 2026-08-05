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
import com.a105.zani.report.application.getinstructorreport.GetInstructorReportQuery;
import com.a105.zani.report.application.getinstructorreport.GetInstructorReportUseCase;
import com.a105.zani.report.presentation.response.InstructorReportResponse;

@Tag(name = "리포트", description = "종료된 수업의 사후 리포트 조회")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class InstructorReportController {

    private final GetInstructorReportUseCase getInstructorReportUseCase;

    @Operation(summary = "강사 리포트 조회", description = """
                    종료된 수업의 **강사용** 리포트를 돌려줍니다. AI 가 만든 종합 피드백·분야별 평가·수업 인사이트·개선 팁으로 이루어집니다.

                    - **학생 개인을 가리키는 값은 담기지 않습니다.** 특정 학생의 집중도나 응답을 이름과 함께 보는 화면이 아닙니다(REPORT-I-002).
                    - **집중 흐름 그래프는 이 응답에 없습니다.** `GET /api/v1/sessions/{sessionId}/reports/attention/group` 이 줍니다.
                    - `scores.score` 는 **0~100 점수**이며 퍼센트가 아닙니다.
                    - `insights` 의 `startedOffsetMs`·`endedOffsetMs` 가 `null` 이면 특정 구간이 아니라 **수업 전체**를 가리킵니다.
                    - `sections` 는 내용 타임라인이 아직 없는 세션에서 **빈 배열**이며 오류가 아닙니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인이 필요합니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "이 수업의 강사가 아닙니다. 학생이거나 다른 강사입니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "세션을 찾을 수 없음. 참가자로 기록된 호출자에게만 내려간다 — 비참가자에게는 세션 존재 여부를 알리지 않는다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "아직 진행 중인 세션이거나, 리포트가 아직 만들어지지 않았습니다(`REPORT_001`). 없는 것이 아니라 아직인 것이라 404 가 아닙니다 — 잠시 뒤 다시 열면 됩니다.")
    })
    @GetMapping("/{sessionId}/reports/instructor")
    public ApiResponse<InstructorReportResponse> instructorReport(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(InstructorReportResponse.from(getInstructorReportUseCase.get(
                new GetInstructorReportQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
