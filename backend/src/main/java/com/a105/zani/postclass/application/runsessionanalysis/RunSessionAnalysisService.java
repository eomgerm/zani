package com.a105.zani.postclass.application.runsessionanalysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.common.error.BusinessException;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobCommand;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobUseCase;
import com.a105.zani.postclass.application.analyzecontent.AnalyzeSessionContentCommand;
import com.a105.zani.postclass.application.analyzecontent.AnalyzeSessionContentUseCase;
import com.a105.zani.postclass.application.analyzeinstructor.AnalyzeSessionInstructorCommand;
import com.a105.zani.postclass.application.analyzeinstructor.AnalyzeSessionInstructorUseCase;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisOutcome;
import com.a105.zani.postclass.application.analyzestudents.AnalyzeSessionStudentsCommand;
import com.a105.zani.postclass.application.analyzestudents.AnalyzeSessionStudentsResult;
import com.a105.zani.postclass.application.analyzestudents.AnalyzeSessionStudentsUseCase;
import com.a105.zani.postclass.application.exception.ContentAnalysisErrorCode;
import com.a105.zani.postclass.application.exception.ContentAnalysisFailedException;
import com.a105.zani.postclass.application.exception.InstructorAnalysisContextMissingException;
import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.exception.SessionAnalysisContextMissingException;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureCommand;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureResult;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureUseCase;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.report.application.publishsessionreport.PublishSessionReportOutcome;
import com.a105.zani.report.application.publishsessionreport.PublishSessionReportUseCase;

/**
 * 사후 분석 오케스트레이션.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 흐름의 대부분이 GMS 호출이고 쓰기는 각 단계 유스케이스가 자기 트랜잭션에서 한다. 여기서 감싸면 외부 호출이 도는 동안 DB 커넥션을 붙든다.
 *
 * <p><b>순서를 지킨다.</b> 학생·강사 분석은 공통 분석이 나눈 구간을 입력으로 받으므로 앞 단계가 끝나기 전에 시작하지 않는다. 구간 없이 돌면 근거를 붙일 자리가 없어 결과가 통째로 걸러진다.
 *
 * <p><b>이미 끝난 단계는 다시 부르지 않는다.</b> 세 분석 서비스가 각자 앞머리에 리포트 존재 확인을 갖고 있어, 재시도가 앞 단계를 지나갈 때 GMS 호출이 일어나지 않는다.
 *
 * <p><b>하나라도 실패하면 공개하지 않는다.</b> 학생 한 명이 빠진 리포트나 강사 리포트가 없는 세션이 공개되면 메일이 나가고 되돌릴 수 없다. 실패는 재시도 정책(S15P11A105-107)에 맡기고,
 * 예산을 다 쓰면 그쪽이 작업을 {@code FAILED} 로 접는다. <b>이미 적재된 리포트는 지우지 않는다</b> — 공개되지 않으면 아무에게도 보이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunSessionAnalysisService implements RunSessionAnalysisUseCase {

    private final AnalyzeSessionContentUseCase analyzeSessionContentUseCase;
    private final AnalyzeSessionStudentsUseCase analyzeSessionStudentsUseCase;
    private final AnalyzeSessionInstructorUseCase analyzeSessionInstructorUseCase;
    private final AdvancePipelineJobUseCase advancePipelineJobUseCase;
    private final RecordPipelineFailureUseCase recordPipelineFailureUseCase;
    private final PublishSessionReportUseCase publishSessionReportUseCase;

    @Override
    public void run(Long sessionId) {
        try {
            analyzeSessionContentUseCase.analyze(new AnalyzeSessionContentCommand(sessionId));

            AnalyzeSessionStudentsResult students =
                    analyzeSessionStudentsUseCase.analyze(new AnalyzeSessionStudentsCommand(sessionId));
            if (!students.failedParticipantIds().isEmpty()) {
                // 실패한 학생만 다음 시도의 대상이 된다 — 리포트가 있는 학생은 조회에서 빠진다.
                log.warn(
                        "학생 분석이 끝나지 않아 공개하지 않습니다. sessionId={}, failed={}",
                        sessionId,
                        students.failedParticipantIds().size());
                recordFailure(sessionId, "STUDENT_ANALYSIS_INCOMPLETE", true);
                return;
            }

            InstructorAnalysisOutcome instructor = analyzeSessionInstructorUseCase
                    .analyze(new AnalyzeSessionInstructorCommand(sessionId))
                    .outcome();
            if (instructor == InstructorAnalysisOutcome.FAILED) {
                // 사유는 강사 분석 쪽이 남겼다. 리포트 행이 없으므로 다음 시도가 다시 만든다.
                recordFailure(sessionId, "INSTRUCTOR_ANALYSIS_FAILED", true);
                return;
            }

            advancePipelineJobUseCase.advance(new AdvancePipelineJobCommand(sessionId, PipelineStatus.VALIDATING));

            PublishSessionReportOutcome published = publishSessionReportUseCase.publish(sessionId);
            if (published == PublishSessionReportOutcome.REPORTS_MISSING) {
                // 작업은 VALIDATING 에 남고 후보 조회가 그 단계도 담으므로 다음 주기가 이어받는다.
                recordFailure(sessionId, "SESSION_REPORTS_MISSING", true);
                return;
            }

            advancePipelineJobUseCase.advance(new AdvancePipelineJobCommand(sessionId, PipelineStatus.PUBLISHED));
            log.info(
                    "사후 분석을 마치고 공개했습니다. sessionId={}, analyzed={}, skipped={}, publish={}",
                    sessionId,
                    students.analyzed(),
                    students.skipped(),
                    published);
        } catch (RuntimeException failure) {
            log.error("사후 분석이 실패했습니다. sessionId={}", sessionId, failure);
            recordFailure(sessionId, reason(failure), isRetryable(failure));
        }
    }

    /**
     * 이번 실행의 실패를 한 번 보고한다. 재시도 여부와 8시간 마감은 S15P11A105-107 정책이 정한다.
     *
     * <p>기록하다 실패해도 올리지 않는다. 작업은 현재 단계에 남고 SLA 경보가 결국 잡는다 — 여기서 예외를 올리면 실행기 스레드가 죽는다.
     */
    private void recordFailure(Long sessionId, String reason, boolean retryable) {
        try {
            RecordPipelineFailureResult result =
                    recordPipelineFailureUseCase.record(new RecordPipelineFailureCommand(sessionId, reason, retryable));
            log.info(
                    "사후 분석 실패를 기록했습니다. sessionId={}, status={}, nextAttemptAt={}, giveUp={}",
                    sessionId,
                    result.status(),
                    result.nextAttemptAt(),
                    result.giveUpReason());
        } catch (RuntimeException exception) {
            log.error("사후 분석 실패를 기록하지 못했습니다. sessionId={}", sessionId, exception);
        }
    }

    /**
     * 실패를 재시도 여부로 가른다.
     *
     * <p><b>모르는 예외는 재시도하지 않는다.</b> 정체를 모르는 실패는 대개 버그이고, 버그는 기다려도 낫지 않는다 — 재시도로 두면 8시간 예산을 태운 뒤에야 드러난다. 전사
     * ({@code TranscribeSessionService})가 같은 기준을 쓴다.
     */
    private boolean isRetryable(RuntimeException failure) {
        if (failure instanceof ContentAnalysisFailedException analysisFailure
                && analysisFailure.errorCode() instanceof ContentAnalysisErrorCode errorCode) {
            return isRetryable(errorCode);
        }
        // 앞 단계의 산출물이 아직 없다. 그 단계가 끝나면 풀린다.
        return failure instanceof SessionAnalysisContextMissingException
                || failure instanceof InstructorAnalysisContextMissingException
                || failure instanceof PipelineJobUnavailableException;
    }

    /** 응답 계약 위반과 요청 과대는 {@code temperature: 0} 이라 같은 요청에 같은 결과가 온다. */
    private boolean isRetryable(ContentAnalysisErrorCode errorCode) {
        return switch (errorCode) {
            case TRANSCRIPT_NOT_READY, CONTENT_ANALYSIS_UNAVAILABLE -> true;
            case CONTENT_ANALYSIS_UNUSABLE_RESPONSE, CONTENT_ANALYSIS_REQUEST_TOO_LARGE -> false;
        };
    }

    /**
     * 실패 사유 문자열.
     *
     * <p>예외 메시지를 그대로 넣지 않는 이유는 그 안에 전사 원문이나 요청 URL 이 섞일 수 있기 때문이다 — 이 값은 DB 에 남고 운영 화면에 보인다. 오류 코드나 예외 이름만 남긴다.
     */
    private String reason(RuntimeException failure) {
        if (failure instanceof BusinessException business) {
            return business.errorCode().code();
        }
        return failure.getClass().getSimpleName();
    }
}
