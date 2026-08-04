package com.a105.zani.postclass.application.analyzecontent;

import java.time.Duration;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.postclass.application.exception.ContentAnalysisErrorCode;
import com.a105.zani.postclass.application.exception.ContentAnalysisFailedException;
import com.a105.zani.postclass.application.port.ContentAnalysis;
import com.a105.zani.postclass.application.port.ContentAnalysisLine;
import com.a105.zani.postclass.application.port.ContentAnalysisPort;
import com.a105.zani.postclass.application.port.ContentAnalysisRequest;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptQuery;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptResult;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptUseCase;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisCommand;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisResult;
import com.a105.zani.report.application.savesessionanalysis.SaveSessionAnalysisUseCase;
import com.a105.zani.report.application.savesessionanalysis.SessionSectionDraft;
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

        String classSummary;
        List<SessionSectionDraft> sections;
        if (transcript.lines().isEmpty()) {
            // 무음 수업. GMS 를 부르지 않는다 — 보낼 내용이 없고, 빈 전사로 물으면 모델이 없는 내용을 지어낸다.
            classSummary = SILENT_CLASS_SUMMARY;
            sections = List.of(new SessionSectionDraft(SILENT_CLASS_TITLE, SILENT_CLASS_SUMMARY, 0, classDurationMs));
        } else {
            ContentAnalysis analysis = requestAnalysis(sessionId, context.title(), classDurationMs, transcript);
            classSummary = analysis.classSummary();
            sections = analysis.sections().stream()
                    .map(section -> new SessionSectionDraft(
                            section.title(), section.summary(), section.startOffsetMs(), section.endOffsetMs()))
                    .toList();
        }

        SaveSessionAnalysisResult saved = saveSessionAnalysisUseCase.save(
                new SaveSessionAnalysisCommand(sessionId, classSummary, sections, classDurationMs));
        return new AnalyzeSessionContentResult(sessionId, saved.saved(), saved.sectionCount());
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
        return contentAnalysisPort
                .analyze(new ContentAnalysisRequest(lectureTitle, classDurationMs, lines))
                .orElseThrow(() -> {
                    log.warn("공통 분석 결과를 받지 못해 적재하지 않습니다. sessionId={}", sessionId);
                    return new ContentAnalysisFailedException(ContentAnalysisErrorCode.CONTENT_ANALYSIS_UNAVAILABLE);
                });
    }
}
