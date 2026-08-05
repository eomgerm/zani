package com.a105.zani.postclass.application.analyzecontent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.exception.ContentAnalysisErrorCode;
import com.a105.zani.postclass.application.exception.ContentAnalysisFailedException;
import com.a105.zani.postclass.application.port.AnalyzedSection;
import com.a105.zani.postclass.application.port.ContentAnalysis;
import com.a105.zani.postclass.application.port.ContentAnalysisFailure;
import com.a105.zani.postclass.application.port.ContentAnalysisLine;
import com.a105.zani.postclass.application.port.ContentAnalysisOutcome;
import com.a105.zani.postclass.application.port.ContentAnalysisPort;
import com.a105.zani.postclass.application.port.ContentAnalysisRequest;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.domain.model.ConfidenceMethod;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.domain.model.TranscriptDocumentSegment;
import com.a105.zani.recording.domain.model.TrackSource;
import com.a105.zani.report.application.exception.InvalidSessionAnalysisException;
import com.a105.zani.report.application.exception.SessionAnalysisAlreadyStoredException;
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

    private static final long INSTRUCTOR_PARTICIPANT_ID = 1_000_000_003_001L;
    private static final long STUDENT_PARTICIPANT_ID = 1_000_000_003_002L;
    /** 참여자 표에 없는 화자. 전사에는 있는데 참여자 행이 사라진 세션을 흉내 낸다. */
    private static final long ORPHAN_PARTICIPANT_ID = 1_000_000_003_999L;

    private static final Map<Long, String> ALIASES =
            Map.of(INSTRUCTOR_PARTICIPANT_ID, "instructor", STUDENT_PARTICIPANT_ID, "student-001");

    private GetPostClassContextResult context = new GetPostClassContextResult(STARTED_AT, ENDED_AT, ALIASES);
    private Optional<TranscriptDocument> transcript =
            Optional.of(document(segment(2_000, 32_000, "자, 오늘은 React 의 상태 관리를 다뤄보겠습니다.")));
    private ContentAnalysisOutcome analysis = ContentAnalysisOutcome.success(new ContentAnalysis(
            "React 상태 관리를 다뤘다.", List.of(new AnalyzedSection("상태 관리", "useState 와 useReducer 를 비교했다.", 0, 600_000))));

    private final List<ContentAnalysisRequest> analysisRequests = new ArrayList<>();
    private final List<SaveSessionAnalysisCommand> savedCommands = new ArrayList<>();

    private final GetPostClassContextUseCase getPostClassContextUseCase = query -> context;
    /** 읽기만 쓰는 페이크. {@code save} 는 이 유스케이스가 부르지 않으므로 불리면 그 자체가 결함이다. */
    private final TranscriptPort transcriptPort = new TranscriptPort() {

        @Override
        public void save(Long sessionId, TranscriptDocument document, Instant now) {
            throw new UnsupportedOperationException("공통 분석은 전사를 쓰지 않는다");
        }

        @Override
        public Optional<TranscriptDocument> findBySessionId(Long sessionId) {
            return transcript;
        }
    };

    private final ContentAnalysisPort contentAnalysisPort = request -> {
        analysisRequests.add(request);
        return analysis;
    };
    private final SaveSessionAnalysisUseCase saveSessionAnalysisUseCase = command -> {
        savedCommands.add(command);
        return new SaveSessionAnalysisResult(
                command.sessionId(), true, command.sections().size());
    };

    /** 멱등 바깥 겹이 보는 값. 기본은 "아직 없음" 이라 기존 케이스는 그대로 GMS 를 탄다. */
    private boolean sessionReportStored;

    private final SessionReportStatusQueryPort sessionReportStatusQueryPort = sessionId -> sessionReportStored;

    private final AnalyzeSessionContentService service = new AnalyzeSessionContentService(
            transcriptPort,
            getPostClassContextUseCase,
            contentAnalysisPort,
            saveSessionAnalysisUseCase,
            sessionReportStatusQueryPort);

    /** 247 계약의 세그먼트. 분석이 쓰는 것은 화자·오프셋·본문뿐이라 나머지 추적 필드는 고정값으로 채운다. */
    private static TranscriptDocumentSegment segment(long startOffsetMs, long endOffsetMs, String text) {
        return segment(INSTRUCTOR_PARTICIPANT_ID, startOffsetMs, endOffsetMs, text);
    }

    private static TranscriptDocumentSegment segment(
            long sessionParticipantId, long startOffsetMs, long endOffsetMs, String text) {
        return new TranscriptDocumentSegment(
                sessionParticipantId,
                TrackSource.MICROPHONE,
                "TR_test",
                startOffsetMs,
                endOffsetMs,
                text,
                -0.21,
                0.811,
                ConfidenceMethod.EXP_AVG_LOGPROB,
                0.02,
                1_000_000_010_001L,
                0);
    }

    private static TranscriptDocument document(TranscriptDocumentSegment... segments) {
        return TranscriptDocument.complete("ko", List.of(segments));
    }

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

    /** 화자를 별칭으로 바꿔 넘긴다(GMS 가이드 §9.2). 참여자 id 를 그대로 보내면 세션 식별자를 보내는 것이고, 아예 빼면 강사 설명과 학생 질문을 가를 수 없다. */
    @Test
    void sendsSpeakersAsRecordingAliases() {
        transcript = Optional.of(document(
                segment(INSTRUCTOR_PARTICIPANT_ID, 2_000, 32_000, "자, 오늘은 React 의 상태 관리를 다뤄보겠습니다."),
                segment(STUDENT_PARTICIPANT_ID, 40_000, 48_000, "Context 랑 Redux 는 어떤 기준으로 고르나요?")));

        analyze();

        List<ContentAnalysisLine> lines = analysisRequests.getFirst().lines();
        assertEquals("instructor", lines.getFirst().speaker());
        assertEquals("student-001", lines.get(1).speaker());
    }

    /** 참여자 표에 없는 화자도 발화는 살린다. 내용은 수업 내용이고, 참여자 id 를 대신 넣는 선택지는 없다. */
    @Test
    void sendsAnUnknownSpeakerForAParticipantThatIsNoLongerListed() {
        transcript = Optional.of(document(segment(ORPHAN_PARTICIPANT_ID, 2_000, 32_000, "누가 말했는지 모르는 발화입니다.")));

        analyze();

        List<ContentAnalysisLine> lines = analysisRequests.getFirst().lines();
        assertEquals("unknown", lines.getFirst().speaker());
        assertEquals("누가 말했는지 모르는 발화입니다.", lines.getFirst().text());
    }

    /**
     * 요청에 실명이 실릴 자리가 없다는 것을 화자 값으로 확인한다.
     *
     * <p>수업 제목은 강사 자유 입력이라 실명·이메일이 들어올 수 있어 아예 넘기지 않는다 — {@code ContentAnalysisRequest} 에 필드 자체가 없으므로 이 검사는 화자만 본다.
     */
    @Test
    void sendsNoValueThatCanIdentifyAPerson() {
        transcript = Optional.of(document(
                segment(INSTRUCTOR_PARTICIPANT_ID, 2_000, 32_000, "자, 오늘은 React 의 상태 관리를 다뤄보겠습니다."),
                segment(STUDENT_PARTICIPANT_ID, 40_000, 48_000, "Context 랑 Redux 는 어떤 기준으로 고르나요?")));

        analyze();

        for (ContentAnalysisLine line : analysisRequests.getFirst().lines()) {
            assertTrue(
                    line.speaker().equals("instructor") || line.speaker().startsWith("student-"),
                    "화자는 별칭이어야 한다: " + line.speaker());
            assertFalse(line.speaker().contains(String.valueOf(INSTRUCTOR_PARTICIPANT_ID)));
            assertFalse(line.speaker().contains(String.valueOf(STUDENT_PARTICIPANT_ID)));
        }
    }

    /**
     * 전사가 세션 종료 시각을 넘겨도 그 범위를 인정한다.
     *
     * <p>세션 종료는 DB 전이로 확정되고 Egress 중지는 그 뒤에 일어나므로(S15P11A105-265) 녹화·전사는 {@code endedAt} 을 몇 초 넘길 수 있다. 수업 길이로 자르면 모델이
     * 전사에 있는 값을 정직하게 답해도 계약 위반이 되고, 스키마 위반은 재시도 대상이 아니라 그 세션은 리포트를 영영 받지 못한다.
     */
    @Test
    void acceptsATranscriptThatRunsPastTheSessionEnd() {
        long pastTheEndMs = CLASS_DURATION_MS + 8_000;
        transcript = Optional.of(document(
                segment(2_000, 32_000, "자, 오늘은 React 의 상태 관리를 다뤄보겠습니다."),
                segment(CLASS_DURATION_MS - 2_000, pastTheEndMs, "마지막 정리까지 녹화가 조금 더 돌았습니다.")));
        analysis = ContentAnalysisOutcome.success(new ContentAnalysis(
                "React 상태 관리를 다뤘다.",
                List.of(new AnalyzedSection("상태 관리", "useState 와 useReducer 를 비교했다.", 0, pastTheEndMs))));

        AnalyzeSessionContentResult result = analyze();

        assertTrue(result.analyzed());
        // 어댑터에게도, 적재에게도 넓혀진 범위를 넘긴다 — 둘이 다른 상한을 쓰면 한쪽만 통과한다.
        assertEquals(pastTheEndMs, analysisRequests.getFirst().classDurationMs());
        assertEquals(pastTheEndMs, savedCommands.getFirst().classDurationMs());
    }

    /** 무음 수업은 단일 구간으로 폴백한다. 구간이 비면 뒤 단계가 읽을 경계가 사라진다. */
    @Test
    void fallsBackToASingleSectionForASilentClass() {
        transcript = Optional.of(document());

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
                transcriptPort,
                getPostClassContextUseCase,
                contentAnalysisPort,
                command -> {
                    // 실제 적재 유스케이스와 같은 계약: 애그리거트의 거절을 애플리케이션 경계 예외로 바꿔 올린다.
                    throw new InvalidSessionAnalysisException(
                            new InvalidSessionReportException(SessionReportErrorCode.INVALID_SESSION_REPORT));
                },
                sessionReportStatusQueryPort);

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
        transcript = Optional.of(new TranscriptDocument(
                TranscriptDocument.SCHEMA_VERSION, "ko", true, List.of(segment(0, 1_000, "일부만 전사됨"))));

        assertThrows(ContentAnalysisFailedException.class, this::analyze);
        assertTrue(analysisRequests.isEmpty());
    }

    /** 끝나지 않은 수업은 구간이 어느 범위 안이어야 하는지 정할 수 없다. */
    @Test
    void failsWhenTheSessionHasNotEndedYet() {
        context = new GetPostClassContextResult(STARTED_AT, null, ALIASES);

        assertThrows(ContentAnalysisFailedException.class, this::analyze);
        assertTrue(analysisRequests.isEmpty());
    }

    /**
     * 경합에서 진 시도는 실패가 아니다.
     *
     * <p>존재 확인과 저장 사이에 다른 시도가 먼저 적재하면 유니크 제약이 이쪽을 막고 어댑터가 {@code SessionAlreadyAnalyzedException} 을 올린다. 결과는 "이미 있다"와
     * 같으므로 실패로 올리면 파이프라인이 성공한 일을 실패로 기록하고 재시도를 태운다.
     */
    @Test
    void treatsALostRaceAsAlreadyAnalysed() {
        AnalyzeSessionContentService raced = new AnalyzeSessionContentService(
                transcriptPort,
                getPostClassContextUseCase,
                contentAnalysisPort,
                command -> {
                    throw new SessionAnalysisAlreadyStoredException(new IllegalStateException("unique violation"));
                },
                sessionReportStatusQueryPort);

        AnalyzeSessionContentResult result = raced.analyze(new AnalyzeSessionContentCommand(SESSION_ID));

        assertFalse(result.analyzed());
        assertEquals(0, result.sectionCount());
    }

    /**
     * 이미 리포트가 있으면 GMS 를 부르지 않는다 — 멱등의 바깥 겹.
     *
     * <p>재시도는 분석 단계를 처음부터 다시 도므로, 강사 분석만 실패해 다섯 번 재시도되면 이미 끝난 공통 분석도 다섯 번 다시 불린다. 적재는 어차피 건너뛰므로 결과가 덮이지는 않지만 호출 하나가 통째로
     * 버려지고 8시간 예산이 그만큼 깎인다(S15P11A105-304).
     */
    @Test
    void skipsTheGmsCallWhenTheReportAlreadyExists() {
        sessionReportStored = true;

        AnalyzeSessionContentResult result = analyze();

        assertFalse(result.analyzed());
        assertEquals(0, result.sectionCount());
        assertTrue(analysisRequests.isEmpty());
        assertTrue(savedCommands.isEmpty());
    }

    /** 이미 적재된 세션은 적재하는 쪽이 건너뛴다. 그 사실이 결과에 그대로 드러나야 한다(세션당 1회). */
    @Test
    void reportsNotAnalyzedWhenTheReportAlreadyExists() {
        AnalyzeSessionContentService alreadySaved = new AnalyzeSessionContentService(
                transcriptPort,
                getPostClassContextUseCase,
                contentAnalysisPort,
                command -> new SaveSessionAnalysisResult(command.sessionId(), false, 0),
                sessionReportStatusQueryPort);

        AnalyzeSessionContentResult result = alreadySaved.analyze(new AnalyzeSessionContentCommand(SESSION_ID));

        assertFalse(result.analyzed());
        assertEquals(0, result.sectionCount());
    }
}
