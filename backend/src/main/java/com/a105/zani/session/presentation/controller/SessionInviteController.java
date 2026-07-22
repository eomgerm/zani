package com.a105.zani.session.presentation.controller;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.common.response.CommonSuccessCode;
import com.a105.zani.session.application.createsession.CreateSessionCommand;
import com.a105.zani.session.application.createsession.CreateSessionResult;
import com.a105.zani.session.application.createsession.CreateSessionUseCase;
import com.a105.zani.session.application.getsessionlist.GetSessionListQuery;
import com.a105.zani.session.application.getsessionlist.GetSessionListUseCase;
import com.a105.zani.session.presentation.request.CreateSessionRequest;
import com.a105.zani.session.presentation.response.CreateSessionResponse;
import com.a105.zani.session.presentation.response.SessionSummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionInviteController {

    private final CreateSessionUseCase createSessionUseCase;
    private final GetSessionListUseCase getSessionListUseCase;

    public SessionInviteController(
            CreateSessionUseCase createSessionUseCase,
            GetSessionListUseCase getSessionListUseCase) {
        this.createSessionUseCase = createSessionUseCase;
        this.getSessionListUseCase = getSessionListUseCase;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateSessionResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateSessionRequest request) {
        CreateSessionResult result = createSessionUseCase.create(
                new CreateSessionCommand(Long.parseLong(jwt.getSubject()), request.title()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(CreateSessionResponse.from(result), CommonSuccessCode.CREATED));
    }

    @GetMapping
    public ApiResponse<List<SessionSummaryResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        List<SessionSummaryResponse> summaries = getSessionListUseCase
                .getList(new GetSessionListQuery(Long.parseLong(jwt.getSubject())))
                .stream()
                .map(SessionSummaryResponse::from)
                .toList();
        return ApiResponse.success(summaries);
    }
}
