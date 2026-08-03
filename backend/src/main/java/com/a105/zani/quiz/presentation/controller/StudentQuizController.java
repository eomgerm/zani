package com.a105.zani.quiz.presentation.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.a105.zani.common.response.ApiResponse;
import com.a105.zani.quiz.application.getstudentquiz.GetStudentQuizQuery;
import com.a105.zani.quiz.application.getstudentquiz.GetStudentQuizUseCase;
import com.a105.zani.quiz.application.submitquizanswers.SubmitQuizAnswersCommand;
import com.a105.zani.quiz.application.submitquizanswers.SubmitQuizAnswersUseCase;
import com.a105.zani.quiz.presentation.request.SubmitQuizAnswersRequest;
import com.a105.zani.quiz.presentation.response.QuizGradingResponse;
import com.a105.zani.quiz.presentation.response.StudentQuizResponse;

@Tag(name = "학생 퀴즈", description = "수업 종료 후 학생별로 생성된 AI 퀴즈를 본인이 조회하고, 답안을 제출해 채점 결과를 받는다")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class StudentQuizController {

    private final GetStudentQuizUseCase getStudentQuizUseCase;
    private final SubmitQuizAnswersUseCase submitQuizAnswersUseCase;

    @Operation(summary = "본인 퀴즈 조회", description = """
                    이 세션에서 나에게 생성된 퀴즈의 문항과 보기를 준다. 퀴즈는 세션에 참여한 학생 본인에게만 열린다.

                    **제출 전에는 정답·해설이 응답에 실리지 않는다** — 보기 목록에도 정답 여부가 없다.
                    제출을 마친 뒤 다시 조회하면 문항마다 `grading`(정오·내 선택·정답·해설)이 함께 담긴다.

                    수업이 아직 진행 중이거나 수업 후 AI 분석이 끝나지 않아 퀴즈가 없으면 404 로 "아직 준비되지 않음"을 알린다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "퀴즈 문항과 보기"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "이 세션에 참여한 학생이 아님 — 비참여자와 강사 모두"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "퀴즈가 아직 준비되지 않음 — 수업 진행 중이거나 AI 분석 파이프라인 미완")
    })
    @GetMapping("/{sessionId}/quiz")
    public ApiResponse<StudentQuizResponse> getQuiz(
            @AuthenticationPrincipal Jwt jwt, @Parameter(description = "세션 ID") @PathVariable Long sessionId) {
        return ApiResponse.success(StudentQuizResponse.from(
                getStudentQuizUseCase.get(new GetStudentQuizQuery(sessionId, Long.parseLong(jwt.getSubject())))));
    }

    @Operation(summary = "퀴즈 답안 일괄 제출", description = """
                    모든 문항의 답을 한 번에 제출하고 채점 결과(문항별 정오·정답·해설과 요약)를 받는다.

                    제출은 1회로 확정된다 — 재응시가 없으므로 이미 제출된 퀴즈에 다시 제출하면 409 다.
                    일부 문항만 담거나, 같은 문항을 두 번 담거나, 그 문항의 보기가 아닌 ID 를 고르면 400 이다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "채점 결과"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "부분 답안, 중복 문항, 또는 문항에 없는 보기"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "이 세션에 참여한 학생이 아님 — 비참여자와 강사 모두"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "퀴즈가 아직 준비되지 않음 — 수업 진행 중이거나 AI 분석 파이프라인 미완"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 제출된 퀴즈")
    })
    @PostMapping("/{sessionId}/quiz/answers")
    public ApiResponse<QuizGradingResponse> submitAnswers(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "세션 ID") @PathVariable Long sessionId,
            @Valid @RequestBody SubmitQuizAnswersRequest request) {
        return ApiResponse.success(QuizGradingResponse.from(submitQuizAnswersUseCase.submit(
                new SubmitQuizAnswersCommand(sessionId, Long.parseLong(jwt.getSubject()), request.toSelections()))));
    }
}
