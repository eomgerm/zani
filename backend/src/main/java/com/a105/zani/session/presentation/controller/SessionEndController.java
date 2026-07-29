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

@Tag(
        name = "세션 종료",
        description = "강사가 강의실에서 수업을 직접 끝내는 API입니다. 최대 시간 초과·강사 미복귀로 인한 자동 종료는 서버가 알아서 처리하므로 별도 호출이 필요 없습니다.")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionEndController {

    private final EndSessionByInstructorUseCase endSessionByInstructorUseCase;

    @Operation(summary = "수업 종료 (강사 전용)", description = """
                    강의실 상단의 **수업 종료** 버튼이 호출하는 API입니다. 수업을 연 강사만 호출할 수 있습니다.

                    성공하면 세션은 `ENDING`(정리 중)을 거쳐 `NOTE_PENDING`(강사 메모 대기)이 됩니다. 이 순간부터 신규 입장과
                    미디어 토큰 발급이 막힙니다. 메모 작성 시간(30분)이 지나면 서버가 자동으로 `ENDED` 로 넘깁니다.

                    **언제 쓰나요?**
                    - 강사가 수업을 예정보다 일찍, 또는 예정대로 끝낼 때
                    - 최대 수업 시간(3시간) 초과나 강사 5분 미복귀로 인한 종료는 **서버가 자동으로 처리**하므로 클라이언트가 호출할 필요가 없습니다. 세 경로 모두 서버 내부에서는 같은 종료 로직을 사용합니다.

                    **두 번 눌러도 안전합니다**
                    이미 종료 절차에 들어간 수업에 다시 요청해도 오류가 아니라 `200` 과 함께 `ended: false` 를 돌려줍니다.
                    종료 사유도 첫 요청의 것이 유지됩니다. 버튼 연타·재시도에 안전하도록 멱등하게 동작합니다.

                    **요청 본문은 없습니다.** 인증 토큰의 사용자와 경로의 세션 ID 만으로 판단합니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "종료 완료. 이번 요청으로 종료되었으면 `ended: true`, 이미 종료된 수업이었으면 `ended: false` 입니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "로그인이 필요합니다. Access Token 을 `Authorization: Bearer ...` 헤더로 보내주세요. (`COMM_401`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "수업을 연 강사가 아닙니다. 학생이나 다른 강사는 종료할 수 없습니다. (`SESSION_APP_006`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "존재하지 않는 세션 ID 입니다. (`SESSION_APP_005`)")
    })
    @PostMapping("/{sessionId}/end")
    public ApiResponse<SessionEndResponse> end(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "종료할 수업의 세션 ID", example = "1234567890") @PathVariable Long sessionId) {
        EndSessionResult result = endSessionByInstructorUseCase.endByInstructor(
                new EndSessionByInstructorCommand(sessionId, Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(SessionEndResponse.from(result));
    }
}
