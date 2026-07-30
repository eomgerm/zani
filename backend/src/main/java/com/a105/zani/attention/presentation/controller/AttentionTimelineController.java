package com.a105.zani.attention.presentation.controller;

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

import com.a105.zani.attention.application.getattentiontimeline.GetGroupAttentionTimelineQuery;
import com.a105.zani.attention.application.getattentiontimeline.GetGroupAttentionTimelineUseCase;
import com.a105.zani.attention.application.getattentiontimeline.GetMyAttentionTimelineQuery;
import com.a105.zani.attention.application.getattentiontimeline.GetMyAttentionTimelineUseCase;
import com.a105.zani.attention.presentation.response.GroupAttentionTimelineResponse;
import com.a105.zani.attention.presentation.response.MyAttentionTimelineResponse;
import com.a105.zani.common.response.ApiResponse;

@Tag(name = "참여도 리포트 타임라인", description = "종료된 수업의 참여도 관측을 조회 시 계산해 시계열로 돌려준다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class AttentionTimelineController {

    private final GetGroupAttentionTimelineUseCase getGroupAttentionTimelineUseCase;
    private final GetMyAttentionTimelineUseCase getMyAttentionTimelineUseCase;

    @Operation(summary = "강사 익명 집단 타임라인 조회", description = """
                    종료된 수업의 참여도 관측을 5초 격자로 재생해 익명 집단 비율을 돌려준다. 저장하지 않고 호출할 때마다 계산한다.

                    **단위** — 모든 `*Ratio` 는 0.0~1.0 분수다. 표시할 때만 100 을 곱한다. 학생용 `/me` 의 `focusPercent`
                    만 0~100 정수이며 단위가 다르다.

                    **null** — 값 없음이라는 뜻이며 절대 0 이 아니다. 0% 로 그리면 인원이 잠깐 모자랐던 구간이
                    "집중이 회복됐다"는 거짓 신호가 된다. 회색 공백으로 그려라.

                    **분모가 두 개다** — `checkNeededRatio` 와 응답 분포 4종의 분모는 `eligibleCount`(접속 1분 초과
                    학생에서 측정 불가 1분 지속자를 뺀 수)이고, `cameraOffRatio` 의 분모는 `connectedCount`(제외 전
                    접속자 전체)다. **두 값을 더하거나 직접 비교하면 안 된다.** 카메라를 끈 학생은 분자에 들어갈 수
                    없으면서 분모에서는 빠지므로 같은 분모를 쓰면 계산이 성립하지 않는다.

                    한 학생이 같은 시각에 두 유의 상태를 가질 수 있어(판단 불가 확정 뒤 헷갈려요 응답) 응답 분포 4종의
                    합이 `checkNeededRatio` 보다 클 수 있다. 확인 필요 분자는 학생 단위 합집합이라 두 번 세지 않는다.

                    **인원 하한** — 집계 대상이 5명 미만인 구간은 비율을 숨긴다(REPORT-I-005). 인원 수는 그대로
                    내려가므로 화면이 사유를 설명할 수 있다.

                    **길이** — `durationSeconds` 는 수업 길이가 아니라 **관측이 있는 마지막 시각까지**다. 세션 종료
                    시각이 저장되지 않아 관측에서 파생한다. 관측이 한 건도 없으면 0 이고 `points` 는 빈 배열이다.

                    **종료된 수업만** 조회할 수 있다. 진행 중이면 409 다 — 실시간 경로를 써라.

                    학생 식별자와 학생별 값은 어떤 필드로도 내려가지 않는다(REPORT-I-002 · ALERT-004).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "시계열. 관측이 없으면 points 가 빈 배열이며 오류가 아니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 참가자가 아니거나 강사가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "아직 진행 중인 세션. 리포트는 종료 후에만 만든다.")
    })
    @GetMapping("/{sessionId}/reports/attention/group")
    public ApiResponse<GroupAttentionTimelineResponse> group(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(GroupAttentionTimelineResponse.from(getGroupAttentionTimelineUseCase.get(
                new GetGroupAttentionTimelineQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }

    @Operation(summary = "학생 본인 집중 흐름 조회", description = """
                    종료된 수업에서 **호출자 본인**의 집중 흐름을 돌려준다. 다른 학생을 가리킬 수 있는 입력이 없다 —
                    참가자는 인증 주체에서 정해진다.

                    **단위** — `focusPercent` 는 0~100 정수다. 강사용 `/group` 의 0.0~1.0 분수와 단위가 다르니 그대로
                    쓰지 말고 확인해라. 30초 이동창에서 GOOD 인 측정 가능 시간 ÷ 전체 측정 가능 시간이다.

                    **null** — 값 없음이며 0 점이 아니다. 측정 가능 시간이 창의 70% 를 못 채우면 점수를 내지 않는다.
                    카메라를 끈 시간도, 판단하지 못한 시간도 0 점이 아니라 빈 값이다. 회색 공백으로 그려라.

                    **상태** — `GOOD`·`CHECK_NEEDED`·`CAMERA_OFF`·`UNMEASURABLE` 또는 `null`. `CHECK_NEEDED` 는
                    헷갈려요·놓쳤어요·무응답을 묶은 값이며 화면은 셋을 구분하지 않는다.

                    **길이** — `durationSeconds` 는 수업 길이가 아니라 본인 관측이 있는 마지막 시각까지다.

                    평균 점수·타인 비교·모델 확률·검출기 단계를 내려보내지 않는다(REPORT-S-010). 참고용 파생 지표라는
                    것을 화면에 밝혀라(NFR-UX-006).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "시계열. 관측이 없으면 points 가 빈 배열이며 오류가 아니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 참가자가 아니거나 학생이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "아직 진행 중인 세션. 리포트는 종료 후에만 만든다.")
    })
    @GetMapping("/{sessionId}/reports/attention/me")
    public ApiResponse<MyAttentionTimelineResponse> me(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(MyAttentionTimelineResponse.from(getMyAttentionTimelineUseCase.get(
                new GetMyAttentionTimelineQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }
}
