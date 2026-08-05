package com.a105.zani.postclass.application.analyzestudents;

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

import com.a105.zani.postclass.application.exception.SessionAnalysisContextMissingException;
import com.a105.zani.postclass.application.port.GmsContentSizeGuard;
import com.a105.zani.postclass.application.port.StudentAnalysis;
import com.a105.zani.postclass.application.port.StudentAnalysisPort;
import com.a105.zani.postclass.application.port.StudentAnalysisRequest;
import com.a105.zani.recording.domain.model.RecordingAlias;
import com.a105.zani.report.application.savestudentanalysis.SaveStudentAnalysisCommand;

/**
 * 세션 하나의 학생 전원을 직렬로 분석한다. (S15P11A105-249)
 *
 * <p>직렬로 도는 이유: 30명이면 30회 호출이고 8시간 SLA(AI-006) 안에서 동시성이 필요하지 않다. 동시에 던지면 한 세션이 GMS 를 30개 슬롯으로 점유해 같은 시간대에 끝난 다른 수업의 분석을
 * 밀어낸다.
 *
 * <p>한 학생의 실패가 루프를 멈추지 않는다. 실패한 학생은 리포트 행이 없으므로 다음 실행에서 자연히 다시 대상이 된다 — 재시도 목록을 따로 관리하지 않는 이유다.
 */
@Service
public class AnalyzeSessionStudentsService implements AnalyzeSessionStudentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeSessionStudentsService.class);

    private static final int MAX_RECOMMENDATIONS = 5;

    /** 앞쪽에서 같은 유형이 차지할 수 있는 최대 개수. 넘는 항목은 버리지 않고 뒤로 밀린다. */
    private static final int SAME_TYPE_SOFT_CAP = 2;

    private final StudentAnalysisContextQueryPort queryPort;
    private final StudentAnalysisPort analysisPort;
    private final StudentAnalysisPersister persister;
    private final ObjectMapper objectMapper;

    AnalyzeSessionStudentsService(
            StudentAnalysisContextQueryPort queryPort,
            StudentAnalysisPort analysisPort,
            StudentAnalysisPersister persister,
            ObjectMapper objectMapper) {
        this.queryPort = queryPort;
        this.analysisPort = analysisPort;
        this.persister = persister;
        this.objectMapper = objectMapper;
    }

    @Override
    public AnalyzeSessionStudentsResult analyze(AnalyzeSessionStudentsCommand command) {
        SessionAnalysisContext context = queryPort
                .findSessionContext(command.sessionId())
                .filter(found -> !found.sections().isEmpty())
                .orElseThrow(SessionAnalysisContextMissingException::new);

        List<AnalysisTarget> targets = queryPort.findStudentsWithoutReport(command.sessionId());
        int analyzed = 0;
        int skipped = 0;
        List<Long> failed = new ArrayList<>();
        for (AnalysisTarget target : targets) {
            switch (analyzeOne(command.sessionId(), context, target)) {
                case ANALYZED -> analyzed++;
                case SKIPPED -> skipped++;
                case FAILED -> failed.add(target.sessionParticipantId());
            }
        }
        log.info("Session students analyzed: analyzed={} skipped={} failed={}", analyzed, skipped, failed.size());
        return new AnalyzeSessionStudentsResult(analyzed, skipped, List.copyOf(failed));
    }

    /**
     * 학생 한 명을 분석한다.
     *
     * <p>{@link RecordingAlias} 를 {@code recording} 도메인에서 그대로 가져다 쓴다. 애플리케이션 유스케이스로 감싸지 않는 이유는 셋이다 — 순수 Java 값 객체라 스프링
     * 빈이 필요 없고, GMS 가이드 §9 가 "별칭 체계를 새로 만들지 말고 재사용하라" 고 못박으며, {@code session} 의 {@code GetPostClassContextService}
     * (S15P11A105-267)가 이미 같은 방식으로 import 한다. 순번 기준도 그쪽의 {@code findBySessionIdOrderByIdAsc} 와 이 도메인의 조회 SQL 이 같아 두 경로가
     * 같은 학생에게 같은 별칭을 준다.
     *
     * <p>다만 "참여자 id 오름차순으로 student-001" 이라는 <b>규칙</b>은 이제 세 곳에 있다 — {@code RecordingWebhookService.resolveAlias},
     * {@code GetPostClassContextService.aliasesOf}, 그리고 이 도메인의 조회 SQL. 267 이 남긴 ponytail 주석이 "세 번째 소비자가 생기면 공용 리졸버로 뽑는
     * 편이 낫다" 고 했고 그 조건이 채워졌다. 웹훅 경로를 함께 건드려야 해서 별도 일감으로 낸다.
     */
    private Outcome analyzeOne(Long sessionId, SessionAnalysisContext context, AnalysisTarget target) {
        String alias = RecordingAlias.student(target.studentOrder()).value();
        StudentObservations observations = queryPort.findObservations(sessionId, target.sessionParticipantId());

        StudentAnalysisRequest request = StudentAnalysisRequest.raw(alias, context, observations);
        if (!fits(request)) {
            // 한 칸 낮춘다. 본문을 자르지 않는다 — 잘린 요청은 원인을 감춘 채 실패한다.
            request = StudentAnalysisRequest.digested(
                    alias, context, StudentSignalDigest.fold(observations, context.sections()));
            if (!fits(request)) {
                log.warn(
                        "Student analysis skipped for {}: escaped content exceeds {}B even folded",
                        alias,
                        GmsContentSizeGuard.MAX_ESCAPED_CONTENT_BYTES);
                return Outcome.FAILED;
            }
        }

        Optional<StudentAnalysis> analysis = analysisPort.analyze(request);
        if (analysis.isEmpty()) {
            // 사유는 어댑터가 남겼다. 여기서는 이 학생을 실패로 두고 다음 학생으로 넘어간다.
            return Outcome.FAILED;
        }

        SaveStudentAnalysisCommand reportCommand = new SaveStudentAnalysisCommand(
                sessionId,
                target.sessionParticipantId(),
                analysis.get().participationSummary(),
                analysis.get().questionCount(),
                ground(analysis.get().recommendations(), context.sections()));
        try {
            return persister.persist(reportCommand, analysis.get().quiz(), context.sections())
                    ? Outcome.ANALYZED
                    : Outcome.SKIPPED;
        } catch (RuntimeException exception) {
            // 퀴즈 구조 위반·요약 규칙 위반이 여기로 온다. 부분 저장은 없다 — 한 트랜잭션이라 함께 롤백된다.
            log.warn("Student analysis persist failed for {}: {}", alias, exception.toString());
            return Outcome.FAILED;
        }
    }

    /**
     * 근거가 맞지 않는 <b>항목만</b> 제거하고, 살아남은 항목의 시각을 개념 구간의 시작·종료로 치환한다(FRD §17.5·§17.6).
     *
     * <p>한 항목의 불일치로 리포트 전체를 버리지 않는다. 그렇게 하면 관측이 많은 학생일수록 리포트를 못 받는다.
     */
    private List<SaveStudentAnalysisCommand.Recommendation> ground(
            List<StudentAnalysis.RecommendationDraft> drafts, List<ConceptSection> sections) {
        List<StudentAnalysis.RecommendationDraft> survivors = new ArrayList<>();
        Set<Integer> usedSections = new HashSet<>();
        for (StudentAnalysis.RecommendationDraft draft :
                drafts == null ? List.<StudentAnalysis.RecommendationDraft>of() : drafts) {
            if (draft.sectionIndex() < 1 || draft.sectionIndex() > sections.size()) {
                continue;
            }
            if (!StudentAnalysis.RECOMMENDATION_TYPES.contains(draft.type())) {
                continue;
            }
            if (!usedSections.add(draft.sectionIndex())) {
                continue;
            }
            survivors.add(draft);
        }
        return spreadTypes(survivors).stream()
                .limit(MAX_RECOMMENDATIONS)
                .map(draft -> {
                    ConceptSection section = sections.get(draft.sectionIndex() - 1);
                    return new SaveStudentAnalysisCommand.Recommendation(
                            draft.type(),
                            draft.title(),
                            draft.description(),
                            section.startedOffsetMs(),
                            section.endedOffsetMs());
                })
                .toList();
    }

    /**
     * 같은 유형이 목록을 덮지 않게 순서를 다시 짠다. 유형당 {@value #SAME_TYPE_SOFT_CAP} 개까지 먼저 고르고, 남은 자리를 나머지로 채운다.
     *
     * <p>버리지 않고 순서만 바꾸는 이유: 상한을 넘는 항목을 지우면 근거가 한 유형에 몰린 학생만 추천을 덜 받는다. 자리가 남으면 그 항목도 들어가고, 앞쪽이 유형별로 고르게 채워질 뿐이다.
     *
     * <p>모델에게도 같은 상한을 요구하지만 실측에서 지키지 않았다(2026-08-04, 세 번 시도). 셀 수 있는 규칙이라 서버가 결정적으로 처리한다.
     */
    private List<StudentAnalysis.RecommendationDraft> spreadTypes(List<StudentAnalysis.RecommendationDraft> survivors) {
        List<StudentAnalysis.RecommendationDraft> spread = new ArrayList<>();
        List<StudentAnalysis.RecommendationDraft> overflow = new ArrayList<>();
        Map<String, Integer> perType = new HashMap<>();
        for (StudentAnalysis.RecommendationDraft draft : survivors) {
            int taken = perType.getOrDefault(draft.type(), 0);
            if (taken < SAME_TYPE_SOFT_CAP) {
                perType.put(draft.type(), taken + 1);
                spread.add(draft);
            } else {
                overflow.add(draft);
            }
        }
        spread.addAll(overflow);
        return spread;
    }

    /**
     * 보낼 데이터(구간 목록 + 관측)가 상한 안인지 본다.
     *
     * <p>애플리케이션이 재는 이유: 넘었을 때 관측을 집계로 낮출지 정하는 것은 업무 판단이고, 어댑터는 그 판단을 되돌릴 방법이 없다. 재는 방법 자체는 강사 분석과 공유한다
     * ({@link GmsContentSizeGuard}).
     */
    private boolean fits(StudentAnalysisRequest request) {
        return GmsContentSizeGuard.fits(objectMapper, request.promptPayload());
    }

    private enum Outcome {
        ANALYZED,
        SKIPPED,
        FAILED
    }
}
