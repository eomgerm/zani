package com.a105.zani.postclass.application.analyzecontent;

import java.time.Duration;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.postclass.application.exception.ContentAnalysisErrorCode;
import com.a105.zani.postclass.application.exception.ContentAnalysisFailedException;
import com.a105.zani.postclass.application.port.ContentAnalysis;
import com.a105.zani.postclass.application.port.ContentAnalysisFailure;
import com.a105.zani.postclass.application.port.ContentAnalysisLine;
import com.a105.zani.postclass.application.port.ContentAnalysisOutcome;
import com.a105.zani.postclass.application.port.ContentAnalysisPort;
import com.a105.zani.postclass.application.port.ContentAnalysisRequest;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptQuery;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptResult;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptUseCase;
import com.a105.zani.recording.application.getsessiontranscript.TranscriptLine;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisCommand;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisResult;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisUseCase;
import com.a105.zani.report.application.savesessionanalysis.SessionSectionDraft;
import com.a105.zani.report.domain.exception.InvalidSessionReportException;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextQuery;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextResult;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextUseCase;

/**
 * 전사에서 수업 요약과 내용 타임라인을 만들어 적재한다(S15P11A105-248).
 *
 * <p>트랜잭션을 걸지 않는다 — 이 흐름의 대부분은 GMS 호출(최대 60초)이고, 유일한 쓰기는 {@link SaveSessionAnalysisUseCase} 가 자기 트랜잭션 안에서 한다. 여기서 감싸면
 * 외부 호출이 도는 동안 DB 커넥션을 붙들게 된다.
 *
 * <p>멱등은 적재하는 쪽이 소유한다. 이미 리포트가 있으면 {@code SaveSessionAnalysisUseCase} 가 건너뛴다. 여기서 먼저 확인하지 않는 이유는 존재 확인 계약을 두 곳에 두면 규칙이
 * 갈라지기 때문이다 — 대신 성공 뒤 재시도가 GMS 호출 한 번을 낭비할 수 있다. 재시도는 실패 뒤에만 오므로 이 경우는 "적재는 됐는데 호출자가 성공을 기록하기 전에 죽은" 드문 경로뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeSessionContentService implements AnalyzeSessionContentUseCase {

    /** 발화가 없는 수업의 폴백 구간. 구간이 하나도 없으면 리포트 화면이 타임라인을 그릴 수 없다. */
    private static final String SILENT_CLASS_TITLE = "발화 없음";

    private static final String SILENT_CLASS_SUMMARY = "이 수업에서는 전사할 발화가 없었습니다.";

    private final GetSessionTranscriptUseCase getSessionTranscriptUseCase;
    private final GetPostClassContextUseCase getPostClassContextUseCase;
    private final ContentAnalysisPort contentAnalysisPort;
    private final SaveSessionAnalysisUseCase saveSessionAnalysisUseCase;

    @Override
    public AnalyzeSessionContentResult analyze(AnalyzeSessionContentCommand command) {
        Long sessionId = command.sessionId();
        GetPostClassContextResult context = getPostClassContextUseCase.get(new GetPostClassContextQuery(sessionId));
        long classDurationMs = classDurationMs(context, sessionId);
        GetSessionTranscriptResult transcript = readTranscript(sessionId);
        long rangeMs = analysisRangeMs(classDurationMs, transcript);

        String classSummary;
        List<SessionSectionDraft> sections;
        if (transcript.lines().isEmpty()) {
            // 무음 수업. GMS 를 부르지 않는다 — 보낼 내용이 없고, 빈 전사로 물으면 모델이 없는 내용을 지어낸다.
            classSummary = SILENT_CLASS_SUMMARY;
            sections = List.of(new SessionSectionDraft(SILENT_CLASS_TITLE, SILENT_CLASS_SUMMARY, 0, rangeMs));
        } else {
            ContentAnalysis analysis = requestAnalysis(sessionId, context.title(), rangeMs, transcript);
            classSummary = analysis.classSummary();
            sections = analysis.sections().stream()
                    .map(section -> new SessionSectionDraft(
                            section.title(), section.summary(), section.startOffsetMs(), section.endOffsetMs()))
                    .toList();
        }

        SaveSessionAnalysisResult saved = save(sessionId, classSummary, sections, rangeMs);
        return new AnalyzeSessionContentResult(sessionId, saved.saved(), saved.sectionCount());
    }

    /**
     * 구간이 들어가야 하는 범위. 수업 길이와 <b>전사가 실제로 덮는 구간</b> 중 더 긴 쪽이다.
     *
     * <p>수업 길이만 쓰면 안 된다. 세션 종료는 DB 상태 전이로 확정되고 Egress 중지는 그 뒤에 일어나므로(S15P11A105-265), 녹화와 전사는 {@code endedAt} 을 몇 초 넘길
     * 수 있다. 그때 모델은 전사에 있는 값을 정직하게 돌려주는데 수업 길이로 자르면 그 응답 전체가 계약 위반이 되고, 스키마 위반은 재시도 대상이 아니라서 그 세션은 리포트를 영영 받지 못한다.
     *
     * <p>전사 범위를 상한으로 인정해도 검증이 헐거워지지 않는다 — 모델에게는 전사에 있는 오프셋만 보여 주므로, 그보다 큰 값은 여전히 거절된다. 구간이 가리키는 지점도 녹화가 실제로 덮는 구간이라 재생할
     * 수 있다.
     */
    private long analysisRangeMs(long classDurationMs, GetSessionTranscriptResult transcript) {
        long transcriptEndMs = transcript.lines().stream()
                .mapToLong(TranscriptLine::endOffsetMs)
                .max()
                .orElse(0);
        return Math.max(classDurationMs, transcriptEndMs);
    }

    /**
     * 검증에 걸린 결과는 저장하지 않고 <b>재시도할 수 없는 실패</b>로 올린다.
     *
     * <p>구간이 개별로는 계약을 지키면서 서로 겹치는 응답은 어댑터를 통과한다 — 겹침 판정은 적재 애그리거트가 소유하고, 두 곳에서 검사하면 규칙이 갈라진다. 그 거절을 그대로 올리면 세션 도메인의
     * {@code BAD_REQUEST} 가 사후 파이프라인까지 새어 나가 재시도 여부를 정할 수 없다. {@code temperature: 0} 이라 겹침은 매 시도에 반복되므로, 재시도해도 같다는 사실을
     * 사유로 못박는다.
     */
    private SaveSessionAnalysisResult save(
            Long sessionId, String classSummary, List<SessionSectionDraft> sections, long classDurationMs) {
        try {
            return saveSessionAnalysisUseCase.save(
                    new SaveSessionAnalysisCommand(sessionId, classSummary, sections, classDurationMs));
        } catch (InvalidSessionReportException rejected) {
            log.warn("공통 분석 결과가 구간 계약에 걸려 적재하지 않습니다. sessionId={}", sessionId, rejected);
            throw new ContentAnalysisFailedException(ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNUSABLE_RESPONSE);
        }
    }

    /** 실패 사유를 재시도 정책이 읽을 오류 코드로 옮긴다. {@code UNAVAILABLE} 만 기다리면 풀릴 수 있다. */
    private static ContentAnalysisErrorCode errorCodeOf(ContentAnalysisFailure failure) {
        return switch (failure) {
            case UNAVAILABLE -> ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNAVAILABLE;
            case UNUSABLE_RESPONSE -> ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNUSABLE_RESPONSE;
            case REQUEST_TOO_LARGE -> ContentAnalysisErrorCode.CONTENT_ANALYSIS_REQUEST_TOO_LARGE;
        };
    }

    /**
     * 수업 길이는 세션 종료 시각으로 잰다. 전사 마지막 세그먼트의 끝으로 재면 수업 끝부분이 무음일 때 그만큼 짧아진다.
     *
     * <p>끝나지 않은 세션은 분석 대상이 아니다 — 구간이 어느 범위 안이어야 하는지 정할 수 없다. 곧 끝날 수업이므로 재시도 대상으로 올린다.
     */
    private long classDurationMs(GetPostClassContextResult context, Long sessionId) {
        Duration classDuration = context.classDuration();
        if (classDuration.isZero() || classDuration.isNegative()) {
            log.warn("수업이 끝나지 않아 공통 분석을 멈춥니다. sessionId={}", sessionId);
            throw new ContentAnalysisFailedException(ContentAnalysisErrorCode.TRANSCRIPT_NOT_READY);
        }
        return classDuration.toMillis();
    }

    private GetSessionTranscriptResult readTranscript(Long sessionId) {
        GetSessionTranscriptResult transcript = getSessionTranscriptUseCase
                .get(new GetSessionTranscriptQuery(sessionId))
                .orElseThrow(() -> {
                    // 전사 단계가 아직 끝나지 않았다. 기다리면 결과가 달라지므로 재시도 대상이다.
                    log.warn("전사가 없어 공통 분석을 멈춥니다. sessionId={}", sessionId);
                    return new ContentAnalysisFailedException(ContentAnalysisErrorCode.TRANSCRIPT_NOT_READY);
                });
        if (transcript.partial()) {
            log.warn("전사가 완결되지 않아 공통 분석을 멈춥니다. sessionId={}", sessionId);
            throw new ContentAnalysisFailedException(ContentAnalysisErrorCode.TRANSCRIPT_NOT_READY);
        }
        return transcript;
    }

    /**
     * 세션당 GMS 호출은 한 번이다. 요약과 구간이 같은 응답에서 나온다.
     *
     * <p>스키마 위반과 기술적 실패를 포트가 모두 빈 값으로 알린다. 저장하지 않고 예외로 올려, 다시 시도할지는 파이프라인 재시도 정책이 정하게 한다(완료 조건: 스키마 위반 응답은 저장 없이 실패로
     * 남는다).
     */
    private ContentAnalysis requestAnalysis(
            Long sessionId, String lectureTitle, long classDurationMs, GetSessionTranscriptResult transcript) {
        List<ContentAnalysisLine> lines = transcript.lines().stream()
                .map(line -> new ContentAnalysisLine(line.startOffsetMs(), line.endOffsetMs(), line.text()))
                .toList();
        ContentAnalysisOutcome outcome =
                contentAnalysisPort.analyze(new ContentAnalysisRequest(lectureTitle, classDurationMs, lines));
        return outcome.value().orElseThrow(() -> {
            // 사유를 나눠 올린다. 스키마 위반은 같은 요청에 같은 응답이 오므로 재시도가 의미 없고(temperature 0),
            // 기술적 실패는 기다리면 풀릴 수 있다 — 재시도 정책(107)이 이 구분으로 retryable 을 정한다.
            ContentAnalysisErrorCode errorCode = errorCodeOf(outcome.failure());
            log.warn("공통 분석 결과를 받지 못해 적재하지 않습니다. sessionId={} failure={}", sessionId, outcome.failure());
            return new ContentAnalysisFailedException(errorCode);
        });
    }
}
