package com.a105.zani.postclass.application.analyzecontent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.exception.ContentAnalysisErrorCode;
import com.a105.zani.postclass.application.exception.ContentAnalysisFailedException;
import com.a105.zani.postclass.application.port.AnalyzedSection;
import com.a105.zani.postclass.application.port.ContentAnalysis;
import com.a105.zani.postclass.application.port.ContentAnalysisFailure;
import com.a105.zani.postclass.application.port.ContentAnalysisOutcome;
import com.a105.zani.postclass.application.port.ContentAnalysisPort;
import com.a105.zani.postclass.application.port.ContentAnalysisRequest;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptResult;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptUseCase;
import com.a105.zani.recording.application.getsessiontranscript.TranscriptLine;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisCommand;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisResult;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisUseCase;
import com.a105.zani.report.domain.exception.InvalidSessionReportException;
import com.a105.zani.report.domain.exception.SessionReportErrorCode;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextResult;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextUseCase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzeSessionContentServiceTest {

    private static final Long SESSION_ID = 100L;
    private static final Instant STARTED_AT = Instant.parse("2026-08-04T01:00:00Z");
    /** 45분 수업. 완료 조건이 이 길이를 기준으로 쓰여 있다. */
    private static final Instant ENDED_AT = STARTED_AT.plusSeconds(45 * 60);

    private static final long CLASS_DURATION_MS = 45 * 60 * 1_000L;

    private GetPostClassContextResult context = new GetPostClassContextResult("React 상태 관리", STARTED_AT, ENDED_AT);
    private Optional<GetSessionTranscriptResult> transcript = Optional.of(new GetSessionTranscriptResult(
            false, List.of(new TranscriptLine(2_000, 32_000, "자, 오늘은 React 의 상태 관리를 다뤄보겠습니다."))));
    private ContentAnalysisOutcome analysis = ContentAnalysisOutcome.success(new ContentAnalysis(
            "React 상태 관리를 다뤘다.", List.of(new AnalyzedSection("상태 관리", "useState 와 useReducer 를 비교했다.", 0, 600_000))));

    private final List<ContentAnalysisRequest> analysisRequests = new ArrayList<>();
    private final List<SaveSessionAnalysisCommand> savedCommands = new ArrayList<>();

    private final GetPostClassContextUseCase getPostClassContextUseCase = query -> context;
    private final GetSessionTranscriptUseCase getSessionTranscriptUseCase = query -> transcript;
    private final ContentAnalysisPort contentAnalysisPort = request -> {
        analysisRequests.add(request);
        return analysis;
    };
    private final SaveSessionAnalysisUseCase saveSessionAnalysisUseCase = command -> {
        savedCommands.add(command);
        return new SaveSessionAnalysisResult(
                command.sessionId(), true, command.sections().size());
    };

    private final AnalyzeSessionContentService service = new AnalyzeSessionContentService(
            getSessionTranscriptUseCase, getPostClassContextUseCase, contentAnalysisPort, saveSessionAnalysisUseCase);

    private AnalyzeSessionContentResult analyze() {
        return service.analyze(new AnalyzeSessionContentCommand(SESSION_ID));
    }

    @Test
    void storesTheSummaryAndTheSectionsFromASingleAnalysisCall() {
        AnalyzeSessionContentResult result = analyze();

        assertTrue(result.analyzed());
        assertEquals(1, result.sectionCount());
        // 요약과 구간이 한 응답에서 나오므로 호출은 한 번이다.
        assertEquals(1, analysisRequests.size());
        SaveSessionAnalysisCommand saved = savedCommands.getFirst();
        assertEquals("React 상태 관리를 다뤘다.", saved.classSummary());
        assertEquals("상태 관리", saved.sections().getFirst().title());
    }

    /** 수업 길이는 세션 종료 시각으로 잰다 — 구간이 녹화 범위 안인지 판정하는 기준이다. */
    @Test
    void passesTheClassDurationMeasuredFromTheSessionWindow() {
        analyze();

        assertEquals(CLASS_DURATION_MS, analysisRequests.getFirst().classDurationMs());
        assertEquals(CLASS_DURATION_MS, savedCommands.getFirst().classDurationMs());
    }

    /** 수업 제목을 맥락으로 넘긴다. 같은 낱말이 과목에 따라 다른 개념을 가리킨다. */
    @Test
    void passesTheLectureTitleAsContext() {
        analyze();

        assertEquals("React 상태 관리", analysisRequests.getFirst().lectureTitle());
    }

    /** 무음 수업은 단일 구간으로 폴백한다. 구간이 비면 뒤 단계가 읽을 경계가 사라진다. */
    @Test
    void fallsBackToASingleSectionForASilentClass() {
        transcript = Optional.of(new GetSessionTranscriptResult(false, List.of()));

        AnalyzeSessionContentResult result = analyze();

        assertTrue(result.analyzed());
        // 보낼 내용이 없으므로 GMS 를 부르지 않는다 — 빈 전사로 물으면 모델이 없는 내용을 지어낸다.
        assertTrue(analysisRequests.isEmpty());
        SaveSessionAnalysisCommand saved = savedCommands.getFirst();
        assertEquals(1, saved.sections().size());
        assertEquals(0, saved.sections().getFirst().startOffsetMs());
        assertEquals(CLASS_DURATION_MS, saved.sections().getFirst().endOffsetMs());
    }

    /** 기술적 실패는 저장하지 않고 실패로 올린다(완료 조건). 기다리면 풀릴 수 있어 재시도 대상 사유다. */
    @Test
    void savesNothingWhenTheAnalysisIsUnavailable() {
        analysis = ContentAnalysisOutcome.failed(ContentAnalysisFailure.UNAVAILABLE);

        ContentAnalysisFailedException thrown = assertThrows(ContentAnalysisFailedException.class, this::analyze);

        assertEquals(ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNAVAILABLE, thrown.errorCode());
        assertTrue(savedCommands.isEmpty());
    }

    /**
     * 스키마 위반은 기술적 실패와 다른 사유로 올린다.
     *
     * <p>{@code temperature: 0} 이라 같은 요청에 같은 응답이 온다. 두 실패를 합치면 재시도 정책(107)이 시도 5회와 GMS 호출 5회를 헛되이 쓴다.
     */
    @Test
    void reportsASchemaViolationSeparatelyFromATechnicalFailure() {
        analysis = ContentAnalysisOutcome.failed(ContentAnalysisFailure.UNUSABLE_RESPONSE);

        ContentAnalysisFailedException thrown = assertThrows(ContentAnalysisFailedException.class, this::analyze);

        assertEquals(ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNUSABLE_RESPONSE, thrown.errorCode());
        assertTrue(savedCommands.isEmpty());
    }

    /**
     * 겹친 구간은 저장하지 않고 재시도할 수 없는 실패로 올린다.
     *
     * <p>겹침 판정은 적재 애그리거트가 소유하므로 어댑터를 통과해 여기까지 온다. 그 거절을 그대로 올리면 세션 도메인의 {@code BAD_REQUEST} 가 파이프라인까지 새어 나가 재시도 여부를 정할
     * 수 없다. {@code temperature: 0} 이라 겹침은 매 시도에 반복된다.
     */
    @Test
    void reportsOverlappingSectionsAsAnUnusableResponse() {
        analysis = ContentAnalysisOutcome.success(new ContentAnalysis(
                "React 상태 관리를 다뤘다.",
                List.of(
                        new AnalyzedSection("앞 구간", "겹치는 구간이다.", 0, 600_000),
                        new AnalyzedSection("뒤 구간", "앞 구간과 겹친다.", 300_000, 900_000))));
        AnalyzeSessionContentService rejecting = new AnalyzeSessionContentService(
                getSessionTranscriptUseCase, getPostClassContextUseCase, contentAnalysisPort, command -> {
                    // 실제 적재 유스케이스와 같은 계약: 애그리거트가 구간 계약 위반을 거절한다.
                    throw new InvalidSessionReportException(SessionReportErrorCode.INVALID_SESSION_REPORT);
                });

        ContentAnalysisFailedException thrown = assertThrows(
                ContentAnalysisFailedException.class,
                () -> rejecting.analyze(new AnalyzeSessionContentCommand(SESSION_ID)));

        assertEquals(ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNUSABLE_RESPONSE, thrown.errorCode());
    }

    /** 전사가 상한에 들어가지 않아 호출하지 못한 경우도 재시도 대상이 아니다. */
    @Test
    void reportsARequestThatCannotFitTheGatewayLimit() {
        analysis = ContentAnalysisOutcome.failed(ContentAnalysisFailure.REQUEST_TOO_LARGE);

        ContentAnalysisFailedException thrown = assertThrows(ContentAnalysisFailedException.class, this::analyze);

        assertEquals(ContentAnalysisErrorCode.CONTENT_ANALYSIS_REQUEST_TOO_LARGE, thrown.errorCode());
        assertTrue(savedCommands.isEmpty());
    }

    /** 전사가 아직 없으면 분석하지 않는다. 기다리면 결과가 달라지므로 재시도 대상이다. */
    @Test
    void failsWhenTheTranscriptIsNotStoredYet() {
        transcript = Optional.empty();

        assertThrows(ContentAnalysisFailedException.class, this::analyze);
        assertTrue(analysisRequests.isEmpty());
        assertTrue(savedCommands.isEmpty());
    }

    /** 미완결 전사로 분석하면 타임라인이 수업 일부만 덮는다. */
    @Test
    void failsWhenTheTranscriptIsStillPartial() {
        transcript =
                Optional.of(new GetSessionTranscriptResult(true, List.of(new TranscriptLine(0, 1_000, "일부만 전사됨"))));

        assertThrows(ContentAnalysisFailedException.class, this::analyze);
        assertTrue(analysisRequests.isEmpty());
    }

    /** 끝나지 않은 수업은 구간이 어느 범위 안이어야 하는지 정할 수 없다. */
    @Test
    void failsWhenTheSessionHasNotEndedYet() {
        context = new GetPostClassContextResult("React 상태 관리", STARTED_AT, null);

        assertThrows(ContentAnalysisFailedException.class, this::analyze);
        assertTrue(analysisRequests.isEmpty());
    }

    /** 이미 적재된 세션은 적재하는 쪽이 건너뛴다. 그 사실이 결과에 그대로 드러나야 한다(세션당 1회). */
    @Test
    void reportsNotAnalyzedWhenTheReportAlreadyExists() {
        AnalyzeSessionContentService alreadySaved = new AnalyzeSessionContentService(
                getSessionTranscriptUseCase,
                getPostClassContextUseCase,
                contentAnalysisPort,
                command -> new SaveSessionAnalysisResult(command.sessionId(), false, 0));

        AnalyzeSessionContentResult result = alreadySaved.analyze(new AnalyzeSessionContentCommand(SESSION_ID));

        assertFalse(result.analyzed());
        assertEquals(0, result.sectionCount());
    }
}
