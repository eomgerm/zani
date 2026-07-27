package com.a105.zani.audioclip.presentation.controller;

import java.io.IOException;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.a105.zani.audioclip.application.ingestclip.ReportAudioClipFailureCommand;
import com.a105.zani.audioclip.application.ingestclip.ReportAudioClipFailureUseCase;
import com.a105.zani.audioclip.application.ingestclip.UploadAudioClipCommand;
import com.a105.zani.audioclip.application.ingestclip.UploadAudioClipResult;
import com.a105.zani.audioclip.application.ingestclip.UploadAudioClipUseCase;
import com.a105.zani.audioclip.presentation.request.AudioClipFailureRequest;
import com.a105.zani.audioclip.presentation.request.AudioClipMetaRequest;
import com.a105.zani.audioclip.presentation.response.UploadAudioClipResponse;
import com.a105.zani.common.response.ApiResponse;

@Tag(name = "오디오 클립", description = "강사 마이크 최근 구간 클립 업로드·실패 보고. 오디오는 전사 후 즉시 폐기된다.")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class AudioClipController {

    private final UploadAudioClipUseCase uploadUseCase;
    private final ReportAudioClipFailureUseCase reportFailureUseCase;

    @Operation(
            summary = "오디오 클립 업로드",
            description = "서버가 요청한 최근 구간 오디오(audio/webm)를 multipart 로 올린다. meta 파트(JSON)에 실제 캡처 범위를 담는다. "
                    + "전사가 끝나면 오디오 바이트는 즉시 폐기되고 텍스트만 남는다. 같은 clipId 재업로드는 멱등 처리한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전사 완료(또는 멱등 처리)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "메타 검증 실패·최소 길이 미만"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 세션의 강사가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "클립 요청을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "만료됐거나 이미 처리된 요청"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "클립 크기 초과"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "audio/webm 이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "저장소·전사 서비스 사용 불가")
    })
    @PostMapping(value = "/{sessionId}/audio-clips/{clipId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadAudioClipResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Parameter(description = "클립 요청 ID") @PathVariable Long clipId,
            @RequestPart("audio") MultipartFile audio,
            @RequestPart("meta") @Valid AudioClipMetaRequest meta)
            throws IOException {
        UploadAudioClipResult result = uploadUseCase.upload(new UploadAudioClipCommand(
                sessionId,
                clipId,
                Long.parseLong(jwt.getSubject()),
                audio.getContentType(),
                audio.getSize(),
                audio.getInputStream(),
                meta.toMeta()));
        return ApiResponse.success(UploadAudioClipResponse.from(result));
    }

    @Operation(
            summary = "오디오 클립 실패 보고",
            description = "클립을 확보하지 못한 사유(녹음 부족·마이크 꺼짐·캡처 불가·업로드 실패)를 보고한다. " + "서버가 만료까지 기다리지 않고 요청을 종결할 수 있게 한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "실패 사유 기록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 세션의 강사가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "클립 요청을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 처리된 요청"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "저장소 사용 불가")
    })
    @PostMapping("/{sessionId}/audio-clips/{clipId}/failure")
    public ApiResponse<Void> reportFailure(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Parameter(description = "클립 요청 ID") @PathVariable Long clipId,
            @Valid @RequestBody AudioClipFailureRequest request) {
        reportFailureUseCase.report(new ReportAudioClipFailureCommand(
                sessionId, clipId, Long.parseLong(jwt.getSubject()), request.reason(), request.availableMs()));
        return ApiResponse.success();
    }
}
