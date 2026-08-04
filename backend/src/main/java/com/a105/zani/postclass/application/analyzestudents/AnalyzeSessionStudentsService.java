package com.a105.zani.postclass.application.analyzestudents;

import java.util.ArrayList;
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

    /**
     * GMS 로 보낼 데이터 부분의 바이트 상한.
     *
     * <p>게이트웨이 실측 상한이 102,400 B 다(GMS 가이드 §4.1 — 바이트 단위 이분 탐색). 초과하면 게이트웨이가 본문을 잘라 전달하고 업스트림이 "model not found" 를 돌려주므로
     * <b>크기 문제라는 사실이 오류 메시지에 드러나지 않는다.</b> 남는 10 KiB 가 chat completions 봉투와 시스템 프롬프트의 몫이다.
     *
     * <p>설정으로 열지 않는다. 환경별로 달라질 이유가 없고, 잘못 올리면 조용한 절단을 부른다.
     */
    private static final int MAX_REQUEST_DATA_BYTES = 92_160;

    private static final int MAX_RECOMMENDATIONS = 5;

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
                        "Student analysis skipped for {}: request exceeds {}B even folded",
                        alias,
                        MAX_REQUEST_DATA_BYTES);
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
        List<SaveStudentAnalysisCommand.Recommendation> grounded = new ArrayList<>();
        Set<Integer> usedSections = new HashSet<>();
        for (StudentAnalysis.RecommendationDraft draft :
                drafts == null ? List.<StudentAnalysis.RecommendationDraft>of() : drafts) {
            if (grounded.size() == MAX_RECOMMENDATIONS) {
                break;
            }
            if (draft.sectionIndex() < 1 || draft.sectionIndex() > sections.size()) {
                continue;
            }
            if (!StudentAnalysis.RECOMMENDATION_TYPES.contains(draft.type())) {
                continue;
            }
            if (!usedSections.add(draft.sectionIndex())) {
                continue;
            }
            ConceptSection section = sections.get(draft.sectionIndex() - 1);
            grounded.add(new SaveStudentAnalysisCommand.Recommendation(
                    draft.type(),
                    draft.title(),
                    draft.description(),
                    section.startedOffsetMs(),
                    section.endedOffsetMs()));
        }
        return grounded;
    }

    /**
     * 보낼 데이터(구간 목록 + 관측)의 UTF-8 바이트를 재 임계 안인지 본다.
     *
     * <p>애플리케이션이 재는 이유: 넘었을 때 관측을 집계로 낮출지 정하는 것은 업무 판단이고, 어댑터는 그 판단을 되돌릴 방법이 없다. 전용 클래스를 두지 않는 이유: 하는 일이 "재고, 넘으면 집계로
     * 바꾼다" 뿐이라 입력도 출력도 이 서비스가 이미 들고 있다.
     */
    private boolean fits(StudentAnalysisRequest request) {
        byte[] serialized = objectMapper.writeValueAsBytes(
                Map.of("sections", request.sections(), "observations", request.observationPayload()));
        return serialized.length <= MAX_REQUEST_DATA_BYTES;
    }

    private enum Outcome {
        ANALYZED,
        SKIPPED,
        FAILED
    }
}
