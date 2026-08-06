package com.a105.zani.recording.presentation.controller;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlQuery;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlUseCase;
import com.a105.zani.recording.application.streammedia.StreamMediaQuery;
import com.a105.zani.recording.application.streammedia.StreamMediaUseCase;
import com.a105.zani.recording.application.streammedia.StreamThumbnailUseCase;
import com.a105.zani.recording.presentation.response.MediaUrlResponse;

@Tag(name = "녹화 미디어", description = "권한을 검증한 뒤 EC2 로컬에 저장된 수업 녹화를 단기 주소로 제공한다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionMediaController {

    private final IssueMediaUrlUseCase issueMediaUrlUseCase;
    private final StreamMediaUseCase streamMediaUseCase;
    private final StreamThumbnailUseCase streamThumbnailUseCase;

    @Operation(summary = "녹화 접근 주소 발급", description = """
                    이 수업에 실제로 참여한 사람에게 녹화 재생 주소를 발급한다. 강사와 학생 모두 공통 녹화를 열람한다.

                    주소에는 자격이 함께 들어 있어 그대로 `<video src>` 에 넣을 수 있다. 유효 기간이 짧으므로(기본 5분)
                    `expiresAt` 을 보고 다시 발급받거나, 재생 중 401 을 만나면 이 API 를 다시 호출해 이어 재생한다.

                    수업이 아직 진행 중이거나 최종 녹화 병합이 끝나지 않았으면 404 로 "아직 준비되지 않음"을 알린다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급된 주소와 만료 시각"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "이 수업에 참여하지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "녹화가 아직 준비되지 않음 — 수업 진행 중이거나 병합 미완")
    })
    @GetMapping("/{sessionId}/media-url")
    public ApiResponse<MediaUrlResponse> issueMediaUrl(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(MediaUrlResponse.from(
                issueMediaUrlUseCase.issue(new IssueMediaUrlQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }

    /**
     * 발급받은 주소로 실제 바이트를 내보낸다.
     *
     * <p>인증 필터를 지나지 않는 경로다 — {@code <video>} 가 Authorization 헤더를 싣지 못해 자격이 질의 문자열에 들어 있고, 유스케이스가 서명으로 검증한다.
     *
     * <p>{@code Resource} 를 그대로 돌려주면 Spring MVC 가 {@code Range} 요청에 206 과 부분 응답으로 답한다. 탐색(seek)이 되려면 이 처리가 필요하다.
     */
    @Operation(summary = "녹화 스트리밍", description = """
                    발급받은 주소로 녹화를 내려받는다. `Range` 요청을 지원해 탐색(seek)이 가능하다.

                    이 경로는 로그인 세션이 아니라 주소에 담긴 서명으로 권한을 확인한다. 서명이 맞지 않거나
                    유효 기간이 지나면 401 이며, 발급 API 를 다시 호출해 새 주소를 받아야 한다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전체 응답"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "206", description = "Range 부분 응답"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "자격이 위조되었거나 만료됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "녹화가 아직 준비되지 않음")
    })
    @GetMapping("/{sessionId}/media")
    public ResponseEntity<Resource> streamMedia(
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Parameter(description = "주소 만료 시각(epoch seconds)") @RequestParam("expires") long expires,
            @Parameter(description = "세션·만료 시각에 묶인 서명") @RequestParam("token") String token) {
        Path file = streamMediaUseCase.locate(new StreamMediaQuery(sessionId, Instant.ofEpochSecond(expires), token));
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4")).body(new FileSystemResource(file));
    }

    /**
     * 내 강의실 카드가 쓰는 수업 썸네일(최종 녹화의 1/2 지점 프레임)을 내보낸다.
     *
     * <p>재생 경로와 같은 이유로 인증 필터를 지나지 않는다 — {@code <img>} 역시 Authorization 헤더를 싣지 못해 자격이 주소 안에 들어 있다.
     *
     * <p>파일은 병합이 끝나는 순간 확정되어 다시 바뀌지 않으므로 사적 캐시를 허용한다. 발급마다 주소가 달라져 캐시가 항상 맞지는 않지만, 같은 주소로 다시 그리는 동안의 재요청은 막아 준다.
     */
    @Operation(summary = "수업 썸네일", description = """
                    목록 응답의 `thumbnailUrl` 로 받은 주소다. 최종 녹화의 1/2 지점 프레임(PNG)을 내려주며, 그대로 `<img src>` 에 넣는다.

                    이 경로는 로그인 세션이 아니라 주소에 담긴 서명으로 권한을 확인한다. 서명이 맞지 않거나 유효 기간이
                    지나면 401 이며, 목록을 다시 조회해 새 주소를 받아야 한다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PNG 이미지"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "자격이 위조되었거나 만료됨"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "썸네일이 아직 준비되지 않음")
    })
    @GetMapping("/{sessionId}/thumbnail")
    public ResponseEntity<Resource> streamThumbnail(
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Parameter(description = "주소 만료 시각(epoch seconds)") @RequestParam("expires") long expires,
            @Parameter(description = "세션·만료 시각에 묶인 서명") @RequestParam("token") String token) {
        Path file =
                streamThumbnailUseCase.locate(new StreamMediaQuery(sessionId, Instant.ofEpochSecond(expires), token));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(new FileSystemResource(file));
    }
}
