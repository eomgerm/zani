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
                    종료된 수업의 참여도 관측을 재생해 익명 집단 계열을 돌려준다. 저장하지 않고 호출할 때마다 계산한다.

                    **격자가 두 개다** — `focusFlow` 는 **겹치지 않는 30초 구간**이고 `signals` 는 5초 간격 점이다.
                    배열마다 자기 `intervalSeconds` 를 갖는다. 3시간 세션이면 각각 360개와 2,160개다.

                    **척도가 두 개다** — `focusLevel` 은 **1.00~4.00 단계 평균**이고 모든 `*Ratio` 는 0.0~1.0 분수다.
                    퍼센트로 환산하지 않는다. **척도가 다르니 같은 축에 놓지 마라** — 집중 흐름은 왼쪽 축 1~4,
                    비율은 오른쪽 축 0~1 이다.

                    **null** — 값 없음이라는 뜻이며 절대 0 도 1단계도 아니다. 0 으로 그리면 인원이 잠깐 모자랐던
                    구간이 "집중이 회복됐다"는 거짓 신호가 된다. 회색 공백으로 그려라.

                    **분모가 두 개다** — `checkNeededRatio` 와 응답 분포 4종의 분모는 `eligibleCount`(접속 1분 초과
                    학생에서 측정 불가 1분 지속자를 뺀 수)이고, `cameraOffRatio` 의 분모는 `connectedCount`(제외 전
                    접속자 전체)다. **두 값을 더하거나 직접 비교하면 안 된다.** 카메라를 끈 학생은 분자에 들어갈 수
                    없으면서 분모에서는 빠지므로 같은 분모를 쓰면 계산이 성립하지 않는다.

                    한 학생이 같은 시각에 두 유의 상태를 가질 수 있어(판단 불가 확정 뒤 헷갈려요 응답) 응답 분포 4종의
                    합이 `checkNeededRatio` 보다 클 수 있다. 확인 필요 분자는 학생 단위 합집합이라 두 번 세지 않는다.

                    **프롬프트 응답은 집중 흐름에 반영되지 않는다.** 확인 질문에 "헷갈려요" 로 답해도 `focusLevel` 은
                    떨어지지 않고 `checkNeededRatio` 로만 나타난다. 그래서 "집중 흐름 3.8, 확인 필요 40%" 가 동시에
                    뜰 수 있으며 모순이 아니다 — "집중은 하는데 이해를 못 한다" 는 상태다. 화면 문구가 이 조합을
                    오류로 읽히게 하지 마라.

                    **인원 하한** — 집계 대상이 5명 미만인 구간은 값을 숨긴다(REPORT-I-005). **두 격자가 각자 자기
                    `eligibleCount` 로 판정하므로 같은 시각에 한쪽만 감춰질 수 있다.** 30초 칸의 인원은 그 칸에 걸친
                    5초 스냅샷의 최솟값이다. 인원 수는 그대로 내려가므로 화면이 사유를 설명할 수 있다.

                    **내용 구간** — `sections` 는 10분 같은 고정 길이가 아니라 분석이 찾아낸 실제 경계다. 248 이 내용
                    타임라인을 채우기 전에는 **빈 배열**이며 오류가 아니다. 나머지 계열은 정상 응답한다.

                    **길이** — `durationSeconds` 는 세션 종료 시각을 우선한다. 종료 시각을 저장하기 전에 끝난 과거
                    세션은 마지막 관측 시각에서 파생한다. 관측이 한 건도 없으면 0 이고 모든 배열이 빈 배열이다.

                    **종료된 수업만** 조회할 수 있다. 진행 중이면 409 다 — 실시간 경로를 써라.

                    학생 식별자와 학생별 값은 어떤 필드로도 내려가지 않는다(REPORT-I-002 · ALERT-004).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "시계열. 관측이 없으면 모든 배열이 빈 배열이며 오류가 아니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 참가자가 아니거나 강사가 아님. 없는 세션도 참가자가 아니면 이 응답이다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "세션을 찾을 수 없음. 참가자로 기록된 호출자에게만 내려간다 — 비참가자에게는 세션 존재 여부를 알리지 않는다."),
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

                    **격자** — `focusFlow` 는 **겹치지 않는 30초 구간**이다. 이동창이 아니므로 3시간이면 정확히 360개다.
                    `stateIntervals` 는 이 격자를 공유하지 않는다 — 30초 칸 하나에 4단계 20초와 카메라 꺼짐 10초가
                    섞이면 대표 상태를 하나로 정할 수 없다.

                    **척도** — `focusLevel` 은 **1.00~4.00 단계 평균**이며 강사용 `/group` 의 `focusLevel` 과 같은
                    척도다. 퍼센트가 아니다. 축 눈금을 1·2·3·4 로 둬라.

                    **null** — 값 없음이며 0 도 1단계도 아니다. 4단계 판정이 칸의 70% 를 못 덮으면 값을 내지 않는다.
                    카메라를 끈 시간과 판단하지 못한 시간은 낮은 참여도가 아니라 빈 값이다. 회색 공백으로 그려라.

                    **프롬프트 응답은 집중 흐름에 반영되지 않는다.** 확인 질문에 "헷갈려요" 로 답해도 `focusLevel` 은
                    떨어지지 않는다. 그 신호는 `stateIntervals` 의 `CHECK_NEEDED` 가 나른다. 직관과 어긋나는
                    동작이므로 화면 문구가 두 계열이 다른 것을 잰다는 점을 밝혀야 한다.

                    **상태 구간** — `GOOD`·`CHECK_NEEDED`·`CAMERA_OFF`·`UNMEASURABLE` 4종이다. `CHECK_NEEDED` 는
                    헷갈려요·놓쳤어요·무응답을 묶은 값이며 화면은 셋을 구분하지 않는다. **인접 동일 상태는 서버가
                    합쳐서 주므로 클라이언트에서 다시 병합하지 마라.** `GOOD` 까지 포함해 관측이 있던 시간을 덮는다 —
                    관측이 아예 없던 시간만 구간이 없으므로 그 자리는 "기록 없음" 으로 그려라.

                    **내용 구간** — `sections` 의 평균은 그 구간 안 30초 칸 값들의 단순 평균이며 **본인 값만** 쓴다.
                    타인 비교가 아니다. 248 이 내용 타임라인을 채우기 전에는 **빈 배열**이며 오류가 아니다.

                    **길이** — `durationSeconds` 는 세션 종료 시각을 우선한다. 종료 시각을 저장하기 전에 끝난 과거
                    세션은 본인 마지막 관측 시각에서 파생한다.

                    평균 점수·타인 비교·모델 확률을 내려보내지 않는다(REPORT-S-010). 참고용 파생 지표라는 것을 화면에
                    밝혀라(NFR-UX-006).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "시계열. 관측이 없으면 모든 배열이 빈 배열이며 오류가 아니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "해당 세션의 참가자가 아니거나 학생이 아님. 없는 세션도 참가자가 아니면 이 응답이다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "세션을 찾을 수 없음. 참가자로 기록된 호출자에게만 내려간다 — 비참가자에게는 세션 존재 여부를 알리지 않는다."),
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
