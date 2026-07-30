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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.common.response.CommonSuccessCode;
import com.a105.zani.session.application.checkjoinable.CheckJoinableQuery;
import com.a105.zani.session.application.checkjoinable.CheckJoinableResult;
import com.a105.zani.session.application.checkjoinable.CheckJoinableUseCase;
import com.a105.zani.session.application.create.CreateSessionCommand;
import com.a105.zani.session.application.create.CreateSessionResult;
import com.a105.zani.session.application.create.CreateSessionUseCase;
import com.a105.zani.session.application.get.GetSessionListQuery;
import com.a105.zani.session.application.get.GetSessionListUseCase;
import com.a105.zani.session.application.join.JoinSessionCommand;
import com.a105.zani.session.application.join.JoinSessionResult;
import com.a105.zani.session.application.join.JoinSessionUseCase;
import com.a105.zani.session.application.start.StartSessionCommand;
import com.a105.zani.session.application.start.StartSessionResult;
import com.a105.zani.session.application.start.StartSessionUseCase;
import com.a105.zani.session.presentation.request.CreateSessionRequest;
import com.a105.zani.session.presentation.request.JoinSessionRequest;
import com.a105.zani.session.presentation.response.CreateSessionResponse;
import com.a105.zani.session.presentation.response.JoinSessionResponse;
import com.a105.zani.session.presentation.response.JoinableSessionResponse;
import com.a105.zani.session.presentation.response.SessionSummaryResponse;
import com.a105.zani.session.presentation.response.StartSessionResponse;

@Tag(name = "수업", description = "강사의 수업 생성·목록 조회와 학생의 초대 코드 입장")
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionInviteController {

    private final CreateSessionUseCase createSessionUseCase;
    private final StartSessionUseCase startSessionUseCase;
    private final GetSessionListUseCase getSessionListUseCase;
    private final JoinSessionUseCase joinSessionUseCase;
    private final CheckJoinableUseCase checkJoinableUseCase;

    public SessionInviteController(
            CreateSessionUseCase createSessionUseCase,
            StartSessionUseCase startSessionUseCase,
            GetSessionListUseCase getSessionListUseCase,
            JoinSessionUseCase joinSessionUseCase,
            CheckJoinableUseCase checkJoinableUseCase) {
        this.createSessionUseCase = createSessionUseCase;
        this.startSessionUseCase = startSessionUseCase;
        this.getSessionListUseCase = getSessionListUseCase;
        this.joinSessionUseCase = joinSessionUseCase;
        this.checkJoinableUseCase = checkJoinableUseCase;
    }

    @Operation(summary = "수업 생성 (강사)", description = """
                    강사가 수업을 개설합니다. 만들자마자 `PREPARING` 상태가 되고, 학생에게 공유할 **8자리 초대 코드**가 함께 발급됩니다.

                    - **아직 학생이 들어올 수 없습니다.** 강사가 `POST /sessions/{sessionId}/start` 를 호출해야 코드가 유효해집니다.
                      카메라·마이크를 맞추는 동안 학생이 빈 방에 들어오지 않게 하려는 것입니다.
                    - 시작은 **강사가 실제로 미디어 서버에 연결된 뒤** 호출하는 흐름입니다. 연결 전에 시작하면 연결이 실패했을 때
                      초대 코드만 열려 학생이 강사 없는 방에 들어옵니다.
                    - 한 강사는 **동시에 하나의 수업만** 열 수 있습니다. 이전 수업이 진행 중이면 409 가 납니다.
                    - 아직 시작하지 않았으므로 `expiresAt` 은 null 입니다. 3시간 자동 종료 시계는 시작 시점부터 돕니다.
                    - 강사는 생성과 동시에 참가자로 등록되므로 따로 입장할 필요가 없고, 준비 중에도 미디어 토큰을 받을 수 있습니다.
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

    @Operation(summary = "수업 시작 (강사)", description = """
                    준비된 수업을 실제로 시작합니다. 이 시점부터 초대 코드가 유효해지고 3시간 자동 종료 시계가 돌기 시작합니다.

                    - **강사가 미디어 서버에 연결된 뒤 호출해 주세요.** 연결 전에 시작하면 연결이 실패했을 때 초대 코드만
                      열려 학생이 강사 없는 방에 들어옵니다. 미디어 토큰 응답의 `sessionStatus` 가 `PREPARING` 이면
                      연결 성공 후 이 API 를 부르는 것이 현재 흐름입니다.
                    - **멱등합니다.** 두 번 눌러도 시작 시각이 밀리지 않고 `started: false` 로 응답합니다.
                    - 수업을 연 강사만 시작할 수 있습니다.
                    - 동시 요청은 세션 행 잠금으로 직렬화되어 한 건만 `started: true` 가 됩니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "시작 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인이 필요합니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "이 수업을 연 강사만 시작할 수 있습니다. (`SESSION_APP_006`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "그런 수업이 없습니다. (`SESSION_APP_005`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "이미 종료 절차에 들어간 수업입니다. (`SESSION_002`)")
    })
    @PostMapping("/{sessionId}/start")
    public ApiResponse<StartSessionResponse> start(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("sessionId") long sessionId) {
        StartSessionResult result =
                startSessionUseCase.start(new StartSessionCommand(sessionId, Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(StartSessionResponse.from(result));
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

    @Operation(summary = "초대 코드 확인 (학생)", description = """
                    초대 코드로 들어갈 수 있는지만 확인합니다. **참가자를 만들지 않습니다.**

                    입장 전 점검 화면으로 넘어가기 전에 씁니다. 이 확인을 입장 API 로 대신하면 참가자 행이 먼저 생겨
                    정원을 선점합니다 — 학생이 장치 점검에서 이탈해도 자리가 반환되지 않아, 그런 학생이 29명이면
                    실제 입장자 없이 방이 찹니다.

                    거절 사유와 오류 코드는 입장 API 와 같습니다. `remainingSeats` 는 확인한 순간의 값이라
                    실제 입장까지 줄어들 수 있습니다.
                    """)
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "입장 가능"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "정규화 후에도 영문·숫자 8자가 아닙니다. (`SESSION_003`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인이 필요합니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "그런 초대 코드의 수업이 없습니다. (`SESSION_APP_005`)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "아직 시작하지 않았거나(`SESSION_APP_007`) 이미 끝났거나(`SESSION_APP_008`)" + " 정원이 찼습니다(`SESSION_APP_009`).")
    })
    @PostMapping("/joinable")
    public ApiResponse<JoinableSessionResponse> checkJoinable(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody JoinSessionRequest request) {
        CheckJoinableResult result = checkJoinableUseCase.check(
                new CheckJoinableQuery(request.inviteCode(), Long.parseLong(jwt.getSubject())));
        return ApiResponse.success(JoinableSessionResponse.from(result));
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
