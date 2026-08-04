package com.a105.zani.postclass.application.analyzestudents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.postclass.application.exception.SessionAnalysisContextMissingException;
import com.a105.zani.postclass.application.port.StudentAnalysis;
import com.a105.zani.postclass.application.port.StudentAnalysisPort;
import com.a105.zani.postclass.application.port.StudentAnalysisRequest;
import com.a105.zani.quiz.application.creategeneratedquiz.CreateGeneratedQuizCommand;
import com.a105.zani.quiz.application.creategeneratedquiz.CreateGeneratedQuizUseCase;
import com.a105.zani.quiz.domain.exception.InvalidQuizException;
import com.a105.zani.quiz.domain.exception.QuizErrorCode;
import com.a105.zani.report.application.savestudentanalysis.SaveStudentAnalysisCommand;
import com.a105.zani.report.application.savestudentanalysis.SaveStudentAnalysisUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 티켓 완료 조건을 직접 겨냥한다. 포트는 전부 Fake 다 — 호출 횟수를 세야 "재호출 없음" 을 검증할 수 있다. */
class AnalyzeSessionStudentsServiceTest {

    /** 가명화 검증이 부분 문자열 대조라 실제와 같은 TSID 크기여야 한다. 짧은 값이면 offset 숫자에 우연히 걸린다. */
    private static final long SESSION_ID = 7_311_064_012_345_678L;

    private static final long FIRST_PARTICIPANT_ID = 7_311_064_087_654_321L;
    private static final long SECOND_PARTICIPANT_ID = 7_311_064_099_999_999L;

    private FakeQueryPort queryPort;
    private FakeAnalysisPort analysisPort;
    private FakeSaveUseCase saveUseCase;
    private FakeQuizUseCase quizUseCase;
    private AnalyzeSessionStudentsService service;

    @BeforeEach
    void setUp() {
        queryPort = new FakeQueryPort();
        analysisPort = new FakeAnalysisPort();
        saveUseCase = new FakeSaveUseCase();
        quizUseCase = new FakeQuizUseCase();
        service = new AnalyzeSessionStudentsService(
                queryPort,
                analysisPort,
                new StudentAnalysisPersister(saveUseCase, quizUseCase),
                JsonMapper.builder().build());
    }

    @Test
    void throwsWhenCommonAnalysisIsMissing() {
        queryPort.context = Optional.empty();

        assertThatThrownBy(() -> service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID)))
                .isInstanceOf(SessionAnalysisContextMissingException.class);
        assertThat(analysisPort.callCount).isZero();
    }

    @Test
    void throwsWhenSessionHasNoSection() {
        queryPort.context = Optional.of(new SessionAnalysisContext("수업", "요약", List.of()));

        assertThatThrownBy(() -> service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID)))
                .isInstanceOf(SessionAnalysisContextMissingException.class);
    }

    @Test
    void neverCallsTheModelForStudentsThatAlreadyHaveAReport() {
        queryPort.targets = List.of();

        AnalyzeSessionStudentsResult result = service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(analysisPort.callCount).isZero();
        assertThat(result.analyzed()).isZero();
    }

    @Test
    void keepsGoingWhenOneOfThreeStudentsFails() {
        queryPort.targets = List.of(target(11L, 1), target(22L, 2), target(33L, 3));
        analysisPort.failFor("student-002");

        AnalyzeSessionStudentsResult result = service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(result.analyzed()).isEqualTo(2);
        assertThat(result.failedParticipantIds()).containsExactly(22L);
        assertThat(saveUseCase.commands)
                .extracting(SaveStudentAnalysisCommand::sessionParticipantId)
                .containsExactly(11L, 33L);
    }

    @Test
    void callsTheModelOnceForAFailedStudent() {
        queryPort.targets = List.of(target(11L, 1));
        analysisPort.failFor("student-001");

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        // 같은 실행 안에서 다시 부르지 않는다. 재시도는 다음 실행이고 그건 멱등 바깥 겹이 제공한다.
        assertThat(analysisPort.callCount).isEqualTo(1);
    }

    @Test
    void dropsOnlyTheRecommendationsThatDoNotMatchTheEvidence() {
        queryPort.targets = List.of(target(11L, 1));
        analysisPort.analysis = analysis(List.of(
                new StudentAnalysis.RecommendationDraft(1, "CONFUSED", "1구간", "설명"),
                new StudentAnalysis.RecommendationDraft(9, "CONFUSED", "범위 밖", "설명"),
                new StudentAnalysis.RecommendationDraft(2, "BORED", "미정의 유형", "설명"),
                new StudentAnalysis.RecommendationDraft(1, "LOW_ENGAGEMENT", "같은 구간 중복", "설명"),
                new StudentAnalysis.RecommendationDraft(2, "MISSED", "2구간", "설명")));

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(saveUseCase.commands.getFirst().recommendations())
                .extracting(SaveStudentAnalysisCommand.Recommendation::title)
                .containsExactly("1구간", "2구간");
    }

    @Test
    void keepsOnlyTheFirstFiveRecommendations() {
        queryPort.targets = List.of(target(11L, 1));
        queryPort.context = Optional.of(new SessionAnalysisContext("수업", "요약", sections(6)));
        analysisPort.analysis = analysis(IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new StudentAnalysis.RecommendationDraft(index, "CONFUSED", "구간 " + index, "설명"))
                .toList());

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(saveUseCase.commands.getFirst().recommendations()).hasSize(5);
        assertThat(saveUseCase.commands.getFirst().recommendations())
                .extracting(SaveStudentAnalysisCommand.Recommendation::title)
                .containsExactly("구간 1", "구간 2", "구간 3", "구간 4", "구간 5");
    }

    @Test
    void replacesModelTimesWithSectionTimes() {
        queryPort.targets = List.of(target(11L, 1));
        analysisPort.analysis = analysis(List.of(new StudentAnalysis.RecommendationDraft(2, "MISSED", "2구간", "설명")));

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        SaveStudentAnalysisCommand.Recommendation stored =
                saveUseCase.commands.getFirst().recommendations().getFirst();
        assertThat(stored.startedOffsetMs()).isEqualTo(60_000L);
        assertThat(stored.endedOffsetMs()).isEqualTo(120_000L);
    }

    @Test
    void sendsOnlyTheAliasAndTheStudentsOwnObservations() {
        queryPort.targets = List.of(target(FIRST_PARTICIPANT_ID, 1), target(SECOND_PARTICIPANT_ID, 2));
        queryPort.observations.put(FIRST_PARTICIPANT_ID, observationsWith("A 학생 발화"));
        queryPort.observations.put(SECOND_PARTICIPANT_ID, observationsWith("B 학생 발화"));

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        StudentAnalysisRequest first = analysisPort.requests.getFirst();
        assertThat(first.studentAlias()).isEqualTo("student-001");
        assertThat(first.observations().chats())
                .extracting(StudentObservations.Chat::content)
                .containsExactly("A 학생 발화");
        String serialized = JsonMapper.builder().build().writeValueAsString(first);
        assertThat(serialized)
                .doesNotContain(String.valueOf(SESSION_ID))
                .doesNotContain(String.valueOf(FIRST_PARTICIPANT_ID))
                .doesNotContain(String.valueOf(SECOND_PARTICIPANT_ID))
                .doesNotContain("B 학생 발화");
    }

    @Test
    void storesReportWithNoRecommendationForAStudentWithoutObservations() {
        queryPort.targets = List.of(target(11L, 1));
        queryPort.observations.put(11L, new StudentObservations(List.of(), List.of(), List.of(), List.of()));
        analysisPort.analysis = analysis(List.of());

        AnalyzeSessionStudentsResult result = service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(result.analyzed()).isEqualTo(1);
        assertThat(saveUseCase.commands.getFirst().recommendations()).isEmpty();
        assertThat(quizUseCase.commands).hasSize(1);
    }

    @Test
    void countsAsSkippedWhenAnotherRunStoredTheReportFirst() {
        queryPort.targets = List.of(target(11L, 1));
        saveUseCase.storedByAnotherRun = true;

        AnalyzeSessionStudentsResult result = service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.analyzed()).isZero();
        assertThat(quizUseCase.commands).isEmpty();
    }

    @Test
    void marksTheStudentFailedWhenTheQuizViolatesItsStructure() {
        queryPort.targets = List.of(target(11L, 1));
        quizUseCase.throwOnCreate = true;

        AnalyzeSessionStudentsResult result = service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(result.failedParticipantIds()).containsExactly(11L);
        assertThat(result.analyzed()).isZero();
    }

    @Test
    void foldsObservationsIntoSignalsWhenTheRawPayloadExceedsTheThreshold() {
        queryPort.targets = List.of(target(11L, 1));
        // 임계는 상수다. 주입해 우회하면 분기만 확인되고 그 값에 도달할 수 있는지는 알 수 없다.
        queryPort.observations.put(11L, hugeObservations(4_000));

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        StudentAnalysisRequest sent = analysisPort.requests.getFirst();
        assertThat(sent.observations()).isNull();
        assertThat(sent.signals()).isNotNull();
    }

    @Test
    void failsTheStudentWhenEvenTheDigestExceedsTheThreshold() {
        // 집계는 구간 수에만 비례한다. 구간이 아주 많으면 접어도 줄지 않는다.
        queryPort.context = Optional.of(new SessionAnalysisContext("수업", "요약", sections(4_000)));
        queryPort.targets = List.of(target(11L, 1));
        queryPort.observations.put(11L, hugeObservations(4_000));

        AnalyzeSessionStudentsResult result = service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(result.failedParticipantIds()).containsExactly(11L);
        assertThat(analysisPort.callCount).isZero();
    }

    private static AnalysisTarget target(long participantId, int order) {
        return new AnalysisTarget(participantId, order);
    }

    private static List<ConceptSection> sections(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> new ConceptSection(
                        index, "구간 " + index, "구간 요약 " + index, (index - 1) * 60_000L, index * 60_000L))
                .toList();
    }

    private static StudentObservations observationsWith(String chat) {
        return new StudentObservations(
                List.of(new StudentObservations.Attention("NOT_ENGAGED", 1_000L)),
                List.of(),
                List.of(),
                List.of(new StudentObservations.Chat(chat, 2_000L)));
    }

    private static StudentObservations hugeObservations(int eventCount) {
        List<StudentObservations.Attention> attentions = IntStream.range(0, eventCount)
                .mapToObj(index -> new StudentObservations.Attention("BARELY_ENGAGED", index * 10_000L))
                .toList();
        List<StudentObservations.Chat> chats = IntStream.range(0, 200)
                .mapToObj(index -> new StudentObservations.Chat("가".repeat(200), index * 10_000L))
                .toList();
        return new StudentObservations(attentions, List.of(), List.of(), chats);
    }

    private static StudentAnalysis analysis(List<StudentAnalysis.RecommendationDraft> recommendations) {
        List<StudentAnalysis.QuestionDraft> questions = IntStream.rangeClosed(1, 3)
                .mapToObj(number -> new StudentAnalysis.QuestionDraft(
                        "문항 " + number,
                        "해설",
                        List.of(
                                new StudentAnalysis.OptionDraft("정답", true),
                                new StudentAnalysis.OptionDraft("오답1", false),
                                new StudentAnalysis.OptionDraft("오답2", false),
                                new StudentAnalysis.OptionDraft("오답3", false))))
                .toList();
        return new StudentAnalysis("참여도 요약", recommendations, new StudentAnalysis.QuizDraft("퀴즈", "설명", questions));
    }

    private static final class FakeQueryPort implements StudentAnalysisContextQueryPort {

        private final Map<Long, StudentObservations> observations = new HashMap<>();
        private Optional<SessionAnalysisContext> context =
                Optional.of(new SessionAnalysisContext("수업", "요약", sections(2)));
        private List<AnalysisTarget> targets = List.of();

        @Override
        public Optional<SessionAnalysisContext> findSessionContext(Long sessionId) {
            return context;
        }

        @Override
        public List<AnalysisTarget> findStudentsWithoutReport(Long sessionId) {
            return targets;
        }

        @Override
        public StudentObservations findObservations(Long sessionId, Long sessionParticipantId) {
            return observations.getOrDefault(
                    sessionParticipantId, new StudentObservations(List.of(), List.of(), List.of(), List.of()));
        }
    }

    private static final class FakeAnalysisPort implements StudentAnalysisPort {

        private final List<StudentAnalysisRequest> requests = new ArrayList<>();
        private final List<String> failingAliases = new ArrayList<>();
        private StudentAnalysis analysis = analysis(List.of());
        private int callCount;

        private void failFor(String alias) {
            failingAliases.add(alias);
        }

        @Override
        public Optional<StudentAnalysis> analyze(StudentAnalysisRequest request) {
            callCount++;
            requests.add(request);
            return failingAliases.contains(request.studentAlias()) ? Optional.empty() : Optional.of(analysis);
        }
    }

    private static final class FakeSaveUseCase implements SaveStudentAnalysisUseCase {

        private final List<SaveStudentAnalysisCommand> commands = new ArrayList<>();
        private boolean storedByAnotherRun;
        private long nextReportId = 500L;

        @Override
        public Optional<Long> save(SaveStudentAnalysisCommand command) {
            commands.add(command);
            return storedByAnotherRun ? Optional.empty() : Optional.of(nextReportId++);
        }
    }

    private static final class FakeQuizUseCase implements CreateGeneratedQuizUseCase {

        private final List<CreateGeneratedQuizCommand> commands = new ArrayList<>();
        private boolean throwOnCreate;

        @Override
        public boolean create(CreateGeneratedQuizCommand command) {
            if (throwOnCreate) {
                throw new InvalidQuizException(QuizErrorCode.INVALID_QUIZ);
            }
            commands.add(command);
            return true;
        }
    }
}
