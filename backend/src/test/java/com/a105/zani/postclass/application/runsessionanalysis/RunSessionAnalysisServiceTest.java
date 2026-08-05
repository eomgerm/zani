package com.a105.zani.postclass.application.runsessionanalysis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobResult;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobUseCase;
import com.a105.zani.postclass.application.analyzecontent.AnalyzeSessionContentResult;
import com.a105.zani.postclass.application.analyzecontent.AnalyzeSessionContentUseCase;
import com.a105.zani.postclass.application.analyzeinstructor.AnalyzeSessionInstructorResult;
import com.a105.zani.postclass.application.analyzeinstructor.AnalyzeSessionInstructorUseCase;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisOutcome;
import com.a105.zani.postclass.application.analyzestudents.AnalyzeSessionStudentsResult;
import com.a105.zani.postclass.application.analyzestudents.AnalyzeSessionStudentsUseCase;
import com.a105.zani.postclass.application.exception.ContentAnalysisErrorCode;
import com.a105.zani.postclass.application.exception.ContentAnalysisFailedException;
import com.a105.zani.postclass.application.exception.SessionAnalysisContextMissingException;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureCommand;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureResult;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureUseCase;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.report.application.publishsessionreport.PublishSessionReportOutcome;
import com.a105.zani.report.application.publishsessionreport.PublishSessionReportUseCase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단계 호출 순서와 공개 판정만 본다. 각 단계가 자기 일을 제대로 하는지는 그쪽 테스트가 갖는다.
 *
 * <p>공개보다 {@code PUBLISHED} 전이가 먼저 일어나면 안 된다 — 그 순서가 뒤집히면 리포트가 없는 세션이 완료로 보인다.
 */
class RunSessionAnalysisServiceTest {

    private static final Long SESSION_ID = 9_304_300L;

    /** 실제로 불린 순서. 순서 자체가 계약이라 호출 목록으로 검증한다. */
    private final List<String> calls = new ArrayList<>();

    private final List<RecordPipelineFailureCommand> failures = new ArrayList<>();

    private RuntimeException contentFailure;
    private List<Long> failedStudents = List.of();
    private InstructorAnalysisOutcome instructorOutcome = InstructorAnalysisOutcome.ANALYZED;
    private PublishSessionReportOutcome publishOutcome = PublishSessionReportOutcome.PUBLISHED;

    private final AnalyzeSessionContentUseCase content = command -> {
        calls.add("content");
        if (contentFailure != null) {
            throw contentFailure;
        }
        return new AnalyzeSessionContentResult(command.sessionId(), true, 3);
    };

    private final AnalyzeSessionStudentsUseCase students = command -> {
        calls.add("students");
        return new AnalyzeSessionStudentsResult(failedStudents.isEmpty() ? 4 : 3, 0, failedStudents);
    };

    private final AnalyzeSessionInstructorUseCase instructor = command -> {
        calls.add("instructor");
        return new AnalyzeSessionInstructorResult(instructorOutcome);
    };

    private final AdvancePipelineJobUseCase advance = command -> {
        calls.add("advance:" + command.targetStatus());
        return new AdvancePipelineJobResult(command.targetStatus(), true);
    };

    private final PublishSessionReportUseCase publish = sessionId -> {
        calls.add("publish");
        return publishOutcome;
    };

    private final RecordPipelineFailureUseCase recordFailure = command -> {
        failures.add(command);
        return new RecordPipelineFailureResult(PipelineStatus.ANALYZING, null, null);
    };

    private RunSessionAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new RunSessionAnalysisService(content, students, instructor, advance, recordFailure, publish);
    }

    @Test
    @DisplayName("공통 → 학생 → 강사 → 검증 전이 → 공개 → 공개 전이 순서로 진행한다")
    void runs_every_stage_in_order() {
        service.run(SESSION_ID);

        assertThat(calls)
                .containsExactly(
                        "content",
                        "students",
                        "instructor",
                        "advance:" + PipelineStatus.VALIDATING,
                        "publish",
                        "advance:" + PipelineStatus.PUBLISHED);
        assertThat(failures).isEmpty();
    }

    /** 학생 하나가 빠진 채로 공개되면 그 학생만 리포트를 못 받은 채 나머지에게 메일이 나간다. */
    @Test
    @DisplayName("학생 분석이 하나라도 실패하면 강사 분석도 공개도 하지 않는다")
    void stops_before_the_instructor_when_a_student_failed() {
        failedStudents = List.of(4_242L);

        service.run(SESSION_ID);

        assertThat(calls).containsExactly("content", "students");
        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo("STUDENT_ANALYSIS_INCOMPLETE");
            assertThat(failure.retryable()).isTrue();
        });
    }

    @Test
    @DisplayName("강사 분석이 실패하면 공개하지 않는다")
    void does_not_publish_when_the_instructor_analysis_failed() {
        instructorOutcome = InstructorAnalysisOutcome.FAILED;

        service.run(SESSION_ID);

        assertThat(calls).containsExactly("content", "students", "instructor");
        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo("INSTRUCTOR_ANALYSIS_FAILED");
            assertThat(failure.retryable()).isTrue();
        });
    }

    /** 강사 분석이 이미 있으면 건너뛴다. 그것은 성공이므로 공개까지 가야 한다. */
    @Test
    @DisplayName("강사 분석을 건너뛴 세션도 공개한다")
    void publishes_when_the_instructor_analysis_was_skipped() {
        instructorOutcome = InstructorAnalysisOutcome.SKIPPED;

        service.run(SESSION_ID);

        assertThat(calls).endsWith("publish", "advance:" + PipelineStatus.PUBLISHED);
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("리포트가 없어 공개가 거절되면 공개 전이를 하지 않는다")
    void does_not_advance_when_the_publish_was_refused() {
        publishOutcome = PublishSessionReportOutcome.REPORTS_MISSING;

        service.run(SESSION_ID);

        assertThat(calls).doesNotContain("advance:" + PipelineStatus.PUBLISHED);
        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo("SESSION_REPORTS_MISSING");
            assertThat(failure.retryable()).isTrue();
        });
    }

    /** 다른 실행이 먼저 공개했다. 실패가 아니므로 전이까지 마쳐야 한다. */
    @Test
    @DisplayName("이미 공개된 세션도 공개 전이를 마친다")
    void advances_even_when_already_published() {
        publishOutcome = PublishSessionReportOutcome.ALREADY_PUBLISHED;

        service.run(SESSION_ID);

        assertThat(calls).endsWith("publish", "advance:" + PipelineStatus.PUBLISHED);
        assertThat(failures).isEmpty();
    }

    /** 학생이 0명인 세션이다. 분석 대상이 없을 뿐 실패가 아니다. */
    @Test
    @DisplayName("학생이 0명이어도 공개까지 간다")
    void publishes_a_session_without_students() {
        service.run(SESSION_ID);

        assertThat(calls).endsWith("publish", "advance:" + PipelineStatus.PUBLISHED);
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("응답 계약 위반은 재시도하지 않는다 — 같은 요청에 같은 응답이 온다")
    void does_not_retry_an_unusable_response() {
        contentFailure =
                new ContentAnalysisFailedException(ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNUSABLE_RESPONSE);

        service.run(SESSION_ID);

        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo(ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNUSABLE_RESPONSE.code());
            assertThat(failure.retryable()).isFalse();
        });
    }

    @Test
    @DisplayName("게이트웨이 무응답은 재시도한다 — 기다리면 풀린다")
    void retries_an_unavailable_gateway() {
        contentFailure = new ContentAnalysisFailedException(ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNAVAILABLE);

        service.run(SESSION_ID);

        assertThat(failures)
                .singleElement()
                .satisfies(failure -> assertThat(failure.retryable()).isTrue());
    }

    @Test
    @DisplayName("앞 단계 산출물이 아직 없으면 재시도한다")
    void retries_a_missing_context() {
        contentFailure = new SessionAnalysisContextMissingException();

        service.run(SESSION_ID);

        assertThat(failures)
                .singleElement()
                .satisfies(failure -> assertThat(failure.retryable()).isTrue());
    }

    /** 정체를 모르는 실패는 대개 버그다. 재시도로 두면 8시간 예산을 태운 뒤에야 드러난다. */
    @Test
    @DisplayName("모르는 예외는 재시도하지 않고 예외 이름만 사유로 남긴다")
    void does_not_retry_an_unknown_failure() {
        contentFailure = new IllegalStateException("전사 원문이 섞일 수 있는 메시지");

        service.run(SESSION_ID);

        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo("IllegalStateException");
            assertThat(failure.retryable()).isFalse();
        });
    }
}
