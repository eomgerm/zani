package com.a105.zani.postclass.application.analyzestudents;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.application.port.StudentAnalysis;
import com.a105.zani.quiz.application.creategeneratedquiz.CreateGeneratedQuizCommand;
import com.a105.zani.quiz.application.creategeneratedquiz.CreateGeneratedQuizUseCase;
import com.a105.zani.report.application.savestudentanalysis.SaveStudentAnalysisCommand;
import com.a105.zani.report.application.savestudentanalysis.SaveStudentAnalysisUseCase;

/**
 * 리포트와 퀴즈를 한 트랜잭션으로 저장한다.
 *
 * <p>서비스에서 분리한 이유는 자기 호출이 프록시를 타지 않기 때문이다. 그리고 LLM 호출은 이 트랜잭션 밖에 있어야 한다 — 수 초 걸리는 호출을 안에 넣으면 커넥션을 그동안 붙잡는다.
 *
 * <p>둘이 한 트랜잭션이라 "리포트는 있는데 퀴즈가 없는" 상태가 생기지 않는다. 그래서 리포트 유무 하나로 멱등을 판정할 수 있다.
 */
@Component
@RequiredArgsConstructor
class StudentAnalysisPersister {

    private static final Logger log = LoggerFactory.getLogger(StudentAnalysisPersister.class);

    private final SaveStudentAnalysisUseCase saveStudentAnalysisUseCase;
    private final CreateGeneratedQuizUseCase createGeneratedQuizUseCase;

    /** 저장했으면 true, 다른 실행이 리포트를 먼저 넣었으면 false(그때는 퀴즈도 넣지 않는다). */
    @Transactional
    boolean persist(
            SaveStudentAnalysisCommand reportCommand, StudentAnalysis.QuizDraft quiz, List<ConceptSection> sections) {
        Optional<Long> reportId = saveStudentAnalysisUseCase.save(reportCommand);
        if (reportId.isEmpty()) {
            return false;
        }
        boolean created = createGeneratedQuizUseCase.create(quizCommand(reportId.get(), quiz, sections));
        if (!created) {
            // 리포트를 우리가 넣었으면 그 리포트에 퀴즈가 있을 수 없다. 있었다면 UK 전제가 깨진 것이라 남겨 둔다.
            log.warn("Quiz already existed for a report this run inserted: reportId={}", reportId.get());
        }
        return true;
    }

    private CreateGeneratedQuizCommand quizCommand(
            long reportId, StudentAnalysis.QuizDraft quiz, List<ConceptSection> sections) {
        List<StudentAnalysis.QuestionDraft> givenQuestions = quiz.questions() == null ? List.of() : quiz.questions();
        List<CreateGeneratedQuizCommand.Question> questions = givenQuestions.stream()
                .map(question -> new CreateGeneratedQuizCommand.Question(
                        question.questionText(),
                        question.explanation(),
                        sectionStartedOffsetMs(question.sectionIndex(), sections),
                        options(question.options())))
                .toList();
        return new CreateGeneratedQuizCommand(reportId, quiz.title(), quiz.description(), questions);
    }

    /**
     * 모델이 답한 구간 번호를 그 구간의 시작 시각으로 되돌린다. 추천과 같은 규칙이다 — 모델이 준 숫자를 시각으로 쓰지 않는다(FRD §17.6).
     *
     * <p>범위 밖 번호는 {@code null} 이 된다. 추천은 항목을 버리면 되지만 문항을 버리면 3~5개 불변식이 깨지므로, 문항은 살리고 다시 보기 링크만 뜨지 않게 한다.
     */
    private Long sectionStartedOffsetMs(int sectionIndex, List<ConceptSection> sections) {
        if (sectionIndex < 1 || sectionIndex > sections.size()) {
            return null;
        }
        return sections.get(sectionIndex - 1).startedOffsetMs();
    }

    private List<CreateGeneratedQuizCommand.Option> options(List<StudentAnalysis.OptionDraft> given) {
        return (given == null ? List.<StudentAnalysis.OptionDraft>of() : given)
                .stream()
                        .map(option -> new CreateGeneratedQuizCommand.Option(option.optionText(), option.correct()))
                        .toList();
    }
}
