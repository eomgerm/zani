package com.a105.zani.session.presentation.controller;

import java.util.List;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.common.response.CommonSuccessCode;
import com.a105.zani.session.application.create.CreateSessionCommand;
import com.a105.zani.session.application.create.CreateSessionResult;
import com.a105.zani.session.application.create.CreateSessionUseCase;
import com.a105.zani.session.application.get.GetSessionListQuery;
import com.a105.zani.session.application.get.GetSessionListUseCase;
import com.a105.zani.session.application.join.JoinSessionCommand;
import com.a105.zani.session.application.join.JoinSessionResult;
import com.a105.zani.session.application.join.JoinSessionUseCase;
import com.a105.zani.session.presentation.request.CreateSessionRequest;
import com.a105.zani.session.presentation.request.JoinSessionRequest;
import com.a105.zani.session.presentation.response.CreateSessionResponse;
import com.a105.zani.session.presentation.response.JoinSessionResponse;
import com.a105.zani.session.presentation.response.SessionSummaryResponse;

@Tag(name = "수업", description = "강사의 수업 생성·목록 조회와 학생의 초대 코드 입장")
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionInviteController {

    private final CreateSessionUseCase createSessionUseCase;
    private final GetSessionListUseCase getSessionListUseCase;
    private final JoinSessionUseCase joinSessionUseCase;

    public SessionInviteController(
            CreateSessionUseCase createSessionUseCase,
            GetSessionListUseCase getSessionListUseCase,
            JoinSessionUseCase joinSessionUseCase) {
        this.createSessionUseCase = createSessionUseCase;
        this.getSessionListUseCase = getSessionListUseCase;
        this.joinSessionUseCase = joinSessionUseCase;
    }

    @Operation(summary = "수업 생성 (강사)", description = """
                    강사가 수업을 개설합니다. 만들자마자 `LIVE` 상태가 되고, 학생에게 공유할 **8자리 초대 코드**가 함께 발급됩니다.

                    - 한 강사는 **동시에 하나의 수업만** 열 수 있습니다. 이전 수업이 진행 중이면 409 가 납니다.
                    - 수업은 시작 시각으로부터 **3시간** 뒤 자동 종료됩니다(응답의 `expiresAt`).
                    - 강사는 생성과 동시에 참가자로 등록되므로 따로 입장할 필요가 없습니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "수업이 생성되었습니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "제목이 비었거나 100자를 넘었습니다. (`SESSION_001`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인이 필요합니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "이미 진행 중인 수업이 있습니다. 먼저 종료한 뒤 새로 만들어 주세요. (`SESSION_APP_001`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "동시 생성 방지 잠금(Redis)을 쓸 수 없습니다. 잠시 후 다시 시도해 주세요. (`SESSION_APP_002`)")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<CreateSessionResponse>> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateSessionRequest request) {
        CreateSessionResult result = createSessionUseCase.create(
                new CreateSessionCommand(Long.parseLong(jwt.getSubject()), request.title()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(CreateSessionResponse.from(result), CommonSuccessCode.CREATED));
    }

    @Operation(summary = "내 수업 목록", description = """
                    로그인한 사용자가 참여한 수업을 최근 순으로 돌려줍니다. 강사로 연 수업과 학생으로 입장한 수업이 모두 포함됩니다.

                    각 항목에는 제목·상태(`LIVE` 또는 `ENDED`)·시작 시각이 담깁니다. 페이지네이션은 없습니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 참여한 수업이 없으면 빈 배열입니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인이 필요합니다.")
    })
    @GetMapping
    public ApiResponse<List<SessionSummaryResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        List<SessionSummaryResponse> summaries =
                getSessionListUseCase.getList(new GetSessionListQuery(Long.parseLong(jwt.getSubject()))).stream()
                        .map(SessionSummaryResponse::from)
                        .toList();
        return ApiResponse.success(summaries);
    }

    @Operation(summary = "초대 코드로 입장 (학생)", description = """
                    학생이 강사에게 받은 **8자리 초대 코드**로 수업에 들어갑니다. 성공하면 세션 ID 를 돌려주며, 이 값으로 강의실 화면과 미디어 토큰 발급을 이어서 호출합니다.

                    - **같은 코드로 여러 번 호출해도 안전합니다.** 이미 들어온 학생이면 참가자를 새로 만들지 않고 기존 정보를 그대로 돌려줍니다(새로고침·재입장 대비).
                    - 이미 끝난 수업의 코드로는 들어갈 수 없습니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "입장 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "초대 코드 형식이 올바르지 않습니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인이 필요합니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "그런 초대 코드의 수업이 없습니다. 코드를 다시 확인해 주세요. (`SESSION_APP_005`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 종료된 수업입니다.")
    })
    @PostMapping("/join")
    public ApiResponse<JoinSessionResponse> join(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody JoinSessionRequest request) {
        JoinSessionResult result =
                joinSessionUseCase.join(new JoinSessionCommand(request.inviteCode(), Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(JoinSessionResponse.from(result));
    }
}
