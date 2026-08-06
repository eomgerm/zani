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
    void pushesTheThirdOfTheSameTypeBehindOtherTypes() {
        queryPort.targets = List.of(target(11L, 1));
        queryPort.context = Optional.of(new SessionAnalysisContext("수업", "요약", sections(5)));
        analysisPort.analysis = analysis(List.of(
                new StudentAnalysis.RecommendationDraft(1, "QUESTION", "질문1", "설명"),
                new StudentAnalysis.RecommendationDraft(2, "QUESTION", "질문2", "설명"),
                new StudentAnalysis.RecommendationDraft(3, "QUESTION", "질문3", "설명"),
                new StudentAnalysis.RecommendationDraft(4, "CONFUSED", "헷갈림", "설명"),
                new StudentAnalysis.RecommendationDraft(5, "MISSED", "놓침", "설명")));

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        // 같은 유형 셋째는 다른 유형 뒤로 밀린다. 버리지 않으므로 다섯 개가 그대로 남는다 —
        // 지우면 근거가 한 유형에 몰린 학생만 추천을 덜 받는다.
        assertThat(saveUseCase.commands.getFirst().recommendations())
                .extracting(SaveStudentAnalysisCommand.Recommendation::title)
                .containsExactly("질문1", "질문2", "헷갈림", "놓침", "질문3");
    }

    @Test
    void keepsEveryRecommendationWhenOnlyOneTypeHasEvidence() {
        queryPort.targets = List.of(target(11L, 1));
        queryPort.context = Optional.of(new SessionAnalysisContext("수업", "요약", sections(4)));
        analysisPort.analysis = analysis(IntStream.rangeClosed(1, 4)
                .mapToObj(index -> new StudentAnalysis.RecommendationDraft(index, "QUESTION", "질문 " + index, "설명"))
                .toList());

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        // 다른 유형의 근거가 없으면 상한이 개수를 깎지 않는다. 순서만 정하는 규칙이다.
        assertThat(saveUseCase.commands.getFirst().recommendations()).hasSize(4);
    }

    @Test
    void resolvesQuizQuestionSectionsAndLeavesOutOfRangeEmpty() {
        queryPort.targets = List.of(target(11L, 1));

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        // 문항 1·2 는 구간 1·2 의 시작 시각으로, 범위 밖 번호를 답한 문항 3 은 null 로 간다.
        assertThat(quizUseCase.commands.getFirst().questions())
                .extracting(CreateGeneratedQuizCommand.Question::sectionStartedOffsetMs)
                .containsExactly(0L, 60_000L, null);
    }

    @Test
    void carriesTheModelsQuestionCount() {
        queryPort.targets = List.of(target(11L, 1));

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        // 서버가 채팅 행을 세지 않는다 — 모델이 판단한 값을 그대로 저장 요청에 넘긴다.
        assertThat(saveUseCase.commands.getFirst().questionCount()).isEqualTo(2);
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

    /**
     * 퀴즈를 만들지 못한 학생도 분석 성공으로 센다(S15P11A105-333).
     *
     * <p>퀴즈 생성이 도메인 예외를 자기 경계에서 흡수해 {@code false} 로 답하므로 리포트는 살아남는다. 예전에는 예외가 올라와 리포트까지 롤백되고 그 학생이 {@code FAILED} 로
     * 집계됐는데, {@code failedParticipantIds} 가 비지 않으면 <b>세션 공개 자체가 막힌다</b> — 문항 하나 때문에 수업 전체의 리포트가 안 나온다.
     */
    @Test
    void countsTheStudentAsAnalyzedWhenTheQuizCannotBeBuilt() {
        queryPort.targets = List.of(target(11L, 1));
        quizUseCase.refuseCreate = true;

        AnalyzeSessionStudentsResult result = service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        assertThat(result.failedParticipantIds()).isEmpty();
        assertThat(result.analyzed()).isEqualTo(1);
        // 리포트는 저장됐다. 퀴즈만 없다.
        assertThat(saveUseCase.commands).hasSize(1);
    }

    /**
     * 퀴즈 쪽에서 예상 못한 예외가 올라오면 그 학생은 실패로 둔다.
     *
     * <p>계약 위반은 위 테스트처럼 {@code false} 로 오므로 이 경로는 이제 버그 신호다. 모르는 실패를 성공으로 세면 리포트 없는 학생이 공개에 섞인다.
     */
    @Test
    void marksTheStudentFailedWhenTheQuizThrowsUnexpectedly() {
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
    void measuresEverythingItSends() {
        // 길이 가드가 재는 값과 어댑터가 보내는 값은 같은 맵이어야 한다. 예전에는 각자 만들어
        // lectureTitle·classSummary·student 가 계산에서 빠졌고, 그만큼 실제 전송량을 작게 봤다.
        queryPort.targets = List.of(target(11L, 1));
        queryPort.context = Optional.of(new SessionAnalysisContext("수업 제목", "공통 요약", sections(2)));

        service.analyze(new AnalyzeSessionStudentsCommand(SESSION_ID));

        String sent = JsonMapper.builder()
                .build()
                .writeValueAsString(analysisPort.requests.getFirst().promptPayload());
        assertThat(sent).contains("수업 제목").contains("공통 요약").contains("student-001");
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
        // 마지막 문항만 범위 밖 구간 번호다 — 그 문항의 구간만 비고 문항은 살아남아야 한다.
        List<StudentAnalysis.QuestionDraft> questions = IntStream.rangeClosed(1, 3)
                .mapToObj(number -> new StudentAnalysis.QuestionDraft(
                        number == 3 ? 99 : number,
                        "문항 " + number,
                        "해설",
                        List.of(
                                new StudentAnalysis.OptionDraft("정답", true),
                                new StudentAnalysis.OptionDraft("오답1", false),
                                new StudentAnalysis.OptionDraft("오답2", false),
                                new StudentAnalysis.OptionDraft("오답3", false))))
                .toList();
        return new StudentAnalysis("참여도 요약", 2, recommendations, new StudentAnalysis.QuizDraft("퀴즈", "설명", questions));
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

        /** 초안이 계약을 어겨 만들지 못한 경우. 실제 구현이 도메인 예외를 흡수해 이렇게 답한다(333). */
        private boolean refuseCreate;

        /** 예상 못한 실패. 계약 위반은 위 플래그로 오므로 이 경로는 버그 신호다. */
        private boolean throwOnCreate;

        @Override
        public boolean create(CreateGeneratedQuizCommand command) {
            if (throwOnCreate) {
                throw new InvalidQuizException(QuizErrorCode.INVALID_QUIZ);
            }
            if (refuseCreate) {
                return false;
            }
            commands.add(command);
            return true;
        }
    }
}
