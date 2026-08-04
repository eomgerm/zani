package com.a105.zani.postclass.application.analyzeinstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import com.a105.zani.postclass.application.exception.InstructorAnalysisContextMissingException;
import com.a105.zani.postclass.application.port.InstructorAnalysis;
import com.a105.zani.postclass.application.port.InstructorAnalysisPort;
import com.a105.zani.postclass.application.port.InstructorAnalysisRequest;
import com.a105.zani.report.application.saveinstructoranalysis.SaveInstructorAnalysisCommand;
import com.a105.zani.report.application.saveinstructoranalysis.SaveInstructorAnalysisUseCase;

/**
 * 세션 하나의 강사 리포트를 LLM 한 번으로 만든다. (S15P11A105-250)
 *
 * <p>트랜잭션을 열지 않는다. LLM 호출이 수 초 걸리므로 그 사이 커넥션을 붙잡으면 안 되고, 저장은 {@link SaveInstructorAnalysisUseCase} 가 자기 트랜잭션에서 한다. 학생별
 * 분석(S15P11A105-249)이 별도 persister 를 둔 것은 리포트와 퀴즈를 한 트랜잭션으로 묶어야 했기 때문이고, 여기서는 저장이 하나뿐이라 껍데기가 된다.
 *
 * <p>실패한 세션은 리포트 행이 없으므로 다음 실행에서 자연히 다시 대상이 된다 — 재시도 목록을 따로 관리하지 않는 이유다.
 */
@Service
public class AnalyzeSessionInstructorService implements AnalyzeSessionInstructorUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeSessionInstructorService.class);

    /**
     * GMS 로 보낼 데이터 부분의 바이트 상한.
     *
     * <p>게이트웨이 실측 상한이 102,400 B 다(GMS 가이드 §4.1 — 바이트 단위 이분 탐색). 초과하면 게이트웨이가 본문을 잘라 전달하고 업스트림이 "model not found" 를 돌려주므로
     * <b>크기 문제라는 사실이 오류 메시지에 드러나지 않는다.</b> 남는 10 KiB 가 chat completions 봉투와 시스템 프롬프트의 몫이다.
     *
     * <p>설정으로 열지 않는다. 환경별로 달라질 이유가 없고, 잘못 올리면 조용한 절단을 부른다.
     */
    private static final int MAX_REQUEST_DATA_BYTES = 92_160;

    /** 인사이트 상한. {@code report} 도메인에 같은 값이 있지만 그 상수를 import 하지 않는다 — 모듈 경계를 원시 값으로 유지한다. */
    private static final int MAX_INSIGHTS = 4;

    /** 길이 가드가 걸렸을 때 구간마다 남길 채팅 수. */
    private static final int MAX_CHAT_EXCERPTS_PER_SECTION = 10;

    /** 어느 구간에도 속하지 않는 채팅의 접기 버킷. */
    private static final int OUTSIDE_ANY_SECTION = 0;

    private final InstructorAnalysisContextQueryPort queryPort;
    private final InstructorAnalysisPort analysisPort;
    private final SaveInstructorAnalysisUseCase saveInstructorAnalysisUseCase;
    private final ObjectMapper objectMapper;

    AnalyzeSessionInstructorService(
            InstructorAnalysisContextQueryPort queryPort,
            InstructorAnalysisPort analysisPort,
            SaveInstructorAnalysisUseCase saveInstructorAnalysisUseCase,
            ObjectMapper objectMapper) {
        this.queryPort = queryPort;
        this.analysisPort = analysisPort;
        this.saveInstructorAnalysisUseCase = saveInstructorAnalysisUseCase;
        this.objectMapper = objectMapper;
    }

    @Override
    public AnalyzeSessionInstructorResult analyze(AnalyzeSessionInstructorCommand command) {
        if (queryPort.hasReport(command.sessionId())) {
            // 멱등의 바깥 겹이다. 여기서 걸리면 LLM 호출이 일어나지 않는다.
            return new AnalyzeSessionInstructorResult(InstructorAnalysisOutcome.SKIPPED);
        }
        InstructorAnalysisContext context = queryPort
                .findContext(command.sessionId())
                .filter(found -> !found.sections().isEmpty())
                .orElseThrow(InstructorAnalysisContextMissingException::new);

        Optional<InstructorAnalysisRequest> request = fitted(context);
        if (request.isEmpty()) {
            return new AnalyzeSessionInstructorResult(InstructorAnalysisOutcome.FAILED);
        }

        Optional<InstructorAnalysis> analysis = analysisPort.analyze(request.get());
        if (analysis.isEmpty()) {
            // 사유는 어댑터가 남겼다. 리포트 행이 없으므로 다음 실행이 다시 시도한다.
            return new AnalyzeSessionInstructorResult(InstructorAnalysisOutcome.FAILED);
        }

        SaveInstructorAnalysisCommand saveCommand = new SaveInstructorAnalysisCommand(
                command.sessionId(),
                analysis.get().overallFeedback(),
                analysis.get().questionCount(),
                scores(analysis.get().scores()),
                ground(analysis.get().insights(), context.sections()));
        try {
            boolean saved = saveInstructorAnalysisUseCase.save(saveCommand).isPresent();
            return new AnalyzeSessionInstructorResult(
                    saved ? InstructorAnalysisOutcome.ANALYZED : InstructorAnalysisOutcome.SKIPPED);
        } catch (RuntimeException exception) {
            // 도메인 불변식 위반이 여기로 온다. 부분 저장은 없다 — 한 트랜잭션이라 함께 롤백된다.
            log.warn("Instructor analysis persist failed for session {}: {}", command.sessionId(), exception);
            return new AnalyzeSessionInstructorResult(InstructorAnalysisOutcome.FAILED);
        }
    }

    /**
     * 상한 안에 드는 요청을 만든다. 넘으면 채팅만 한 단 낮추고, 그래도 넘으면 빈 값이다.
     *
     * <p>본문을 자르지 않는다 — 잘린 요청은 원인을 감춘 채 실패한다. 크기를 키우는 입력은 공개 채팅뿐이다(구간·집단 알림·팁 이력은 세션당 수십 행에 머문다).
     */
    private Optional<InstructorAnalysisRequest> fitted(InstructorAnalysisContext context) {
        InstructorAnalysisRequest raw = InstructorAnalysisRequest.of(context);
        if (fits(raw)) {
            return Optional.of(raw);
        }
        InstructorAnalysisRequest folded = raw.withChats(foldChats(context.chats(), context.sections()));
        if (!fits(folded)) {
            log.warn(
                    "Instructor analysis skipped: request exceeds {}B even with chats folded to {} per section",
                    MAX_REQUEST_DATA_BYTES,
                    MAX_CHAT_EXCERPTS_PER_SECTION);
            return Optional.empty();
        }
        // 접힌 입력에서는 총 질문 개수가 과소 계산될 수 있다.
        log.info(
                "Instructor analysis chats folded: {} -> {} messages",
                context.chats().size(),
                folded.chats().size());
        return Optional.of(folded);
    }

    /**
     * 보낼 데이터의 UTF-8 바이트가 임계 안인지 본다.
     *
     * <p>애플리케이션이 재는 이유: 넘었을 때 무엇을 낮출지 정하는 것은 업무 판단이고, 어댑터는 그 판단을 되돌릴 방법이 없다.
     */
    private boolean fits(InstructorAnalysisRequest request) {
        return objectMapper.writeValueAsBytes(request.dataPayload()).length <= MAX_REQUEST_DATA_BYTES;
    }

    /** 구간마다 앞에서 {@value #MAX_CHAT_EXCERPTS_PER_SECTION} 개만 남긴다. 구간 밖 채팅도 같은 수만큼 남긴다. */
    private List<PublicChat> foldChats(List<PublicChat> chats, List<ConceptSection> sections) {
        Map<Integer, Integer> kept = new HashMap<>();
        List<PublicChat> folded = new ArrayList<>();
        for (PublicChat chat : chats) {
            int bucket = sectionIndexAt(chat.occurredOffsetMs(), sections);
            if (kept.merge(bucket, 1, Integer::sum) <= MAX_CHAT_EXCERPTS_PER_SECTION) {
                folded.add(chat);
            }
        }
        return folded;
    }

    private int sectionIndexAt(long offsetMs, List<ConceptSection> sections) {
        for (ConceptSection section : sections) {
            if (offsetMs >= section.startedOffsetMs() && offsetMs <= section.endedOffsetMs()) {
                return section.sectionIndex();
            }
        }
        return OUTSIDE_ANY_SECTION;
    }

    /** 모델이 낸 네 점수를 저장 명령의 문자열 유형으로 옮긴다. {@code report} 도메인의 enum 을 import 하지 않는다. */
    private List<SaveInstructorAnalysisCommand.Score> scores(InstructorAnalysis.Scores scores) {
        return List.of(
                new SaveInstructorAnalysisCommand.Score("DELIVERY", scores.delivery()),
                new SaveInstructorAnalysisCommand.Score("STRUCTURE_FLOW", scores.structureFlow()),
                new SaveInstructorAnalysisCommand.Score("INTERACTION", scores.interaction()),
                new SaveInstructorAnalysisCommand.Score("DIFFICULTY_CONTROL", scores.difficultyControl()));
    }

    /**
     * 근거가 맞지 않는 <b>항목만</b> 제거하고, 살아남은 항목의 구간 번호를 시작·종료 시각으로 치환한다(FRD §17.5·§17.6).
     *
     * <p>한 항목의 불일치로 리포트 전체를 버리지 않는다. 그렇게 하면 관측이 많은 수업일수록 리포트를 못 받는다.
     *
     * <p>근거 종류가 두 가지 미만인 항목을 버린다(AI-008). 응답 스키마의 {@code minItems: 2} 가 이미 막지만 GMS 가 배열 하한을 강제하는지 미확인이라 여기서 다시 본다. 같은
     * 종류를 두 번 답한 경우도 한 종류로 세도록 집합으로 센다.
     */
    private List<SaveInstructorAnalysisCommand.Insight> ground(
            List<InstructorAnalysis.InsightDraft> drafts, List<ConceptSection> sections) {
        List<SaveInstructorAnalysisCommand.Insight> grounded = new ArrayList<>();
        Set<Integer> usedSections = new HashSet<>();
        for (InstructorAnalysis.InsightDraft draft :
                drafts == null ? List.<InstructorAnalysis.InsightDraft>of() : drafts) {
            if (grounded.size() == MAX_INSIGHTS) {
                break;
            }
            if (draft.sectionIndex() < 0 || draft.sectionIndex() > sections.size()) {
                continue;
            }
            if (!hasCombinedEvidence(draft.evidenceKinds())) {
                continue;
            }
            // 구간을 짚은 항목만 중복을 막는다. 0 은 구간이 아니라 "전체 수업" 이고 여러 개일 수 있다.
            if (draft.sectionIndex() > 0 && !usedSections.add(draft.sectionIndex())) {
                continue;
            }
            Long startedOffsetMs = null;
            Long endedOffsetMs = null;
            if (draft.sectionIndex() > 0) {
                ConceptSection section = sections.get(draft.sectionIndex() - 1);
                startedOffsetMs = section.startedOffsetMs();
                endedOffsetMs = section.endedOffsetMs();
            }
            grounded.add(new SaveInstructorAnalysisCommand.Insight(
                    draft.title(), draft.evidence(), draft.suggestion(), startedOffsetMs, endedOffsetMs));
        }
        return grounded;
    }

    private boolean hasCombinedEvidence(List<String> evidenceKinds) {
        if (evidenceKinds == null) {
            return false;
        }
        Set<String> distinct = new HashSet<>(evidenceKinds);
        distinct.retainAll(InstructorAnalysis.EVIDENCE_KINDS);
        return distinct.size() >= InstructorAnalysis.MIN_EVIDENCE_KINDS;
    }
}
