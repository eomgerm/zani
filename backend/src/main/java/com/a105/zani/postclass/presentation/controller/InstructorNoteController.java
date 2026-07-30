package com.a105.zani.postclass.presentation.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.postclass.application.finalizenote.FinalizeNoteCommand;
import com.a105.zani.postclass.application.finalizenote.FinalizeNoteUseCase;
import com.a105.zani.postclass.application.savenotedraft.SaveNoteDraftCommand;
import com.a105.zani.postclass.application.savenotedraft.SaveNoteDraftUseCase;
import com.a105.zani.postclass.presentation.request.SaveNoteDraftRequest;
import com.a105.zani.postclass.presentation.response.InstructorNoteResponse;

@Tag(name = "강사 사후 메모", description = "수업 종료 후 강사가 남기는 메모를 저장하고 한 번 확정한다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class InstructorNoteController {

    private final SaveNoteDraftUseCase saveNoteDraftUseCase;
    private final FinalizeNoteUseCase finalizeNoteUseCase;

    @Operation(summary = "메모 초안 자동 저장", description = """
                    강사가 작성 중인 메모 본문을 저장한다. 메모는 수업 하나에 하나이며 구간으로 나누지 않는다.

                    저장이 성공할 때마다 30분 비활성 타이머가 초기화된다(FRD §16 NOTE-002). 30분 동안 저장이 없으면 서버가 메모를 자동 확정하고,
                    그 뒤에는 이 요청이 409 로 거절된다.

                    본문을 비워 보내도 된다 — 쓰던 내용을 지운 상태로 저장되고 타이머는 그대로 초기화된다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "초안 저장됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "본문이 5000자를 넘음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 멤버가 아니거나 수업을 진행한 강사가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "아직 진행 중인 수업, 이미 확정된 메모, 또는 다른 요청이 같은 세션의 메모를 먼저 열었음")
    })
    @PutMapping("/{sessionId}/notes/draft")
    public ApiResponse<InstructorNoteResponse> saveDraft(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Valid @RequestBody SaveNoteDraftRequest request) {
        return ApiResponse.success(InstructorNoteResponse.from(saveNoteDraftUseCase.save(
                new SaveNoteDraftCommand(sessionId, Long.parseLong(jwt.getSubject()), request.content()))));
    }

    @Operation(summary = "메모 확정", description = """
                    강사의 `작성 완료`. 확정 후에는 수정·재생성할 수 없고(FRD §16), 사후 전사·AI 분석 작업이 시작된다(NOTE-004).

                    초안을 한 번도 저장하지 않은 수업에도 쓸 수 있다 — 메모 없이 완료하는 경로이며, 빈 확정 기록이 남아 분석이 시작된다.

                    같은 수업을 두 번 확정해도 오류가 아니라 그대로 200 이다. 30분 비활성 자동 확정과 겹쳐도 확정은 한 번만 일어난다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "확정됨. 이미 확정된 메모였어도 같은 응답이다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 멤버가 아니거나 수업을 진행한 강사가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "아직 진행 중인 수업, 또는 다른 요청이 같은 세션의 메모를 먼저 열었음")
    })
    @PostMapping("/{sessionId}/notes/finalize")
    public ApiResponse<InstructorNoteResponse> finalizeNote(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(InstructorNoteResponse.from(finalizeNoteUseCase.finalizeNote(
                new FinalizeNoteCommand(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
