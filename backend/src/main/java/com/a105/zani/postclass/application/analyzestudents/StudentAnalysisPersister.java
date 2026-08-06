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
 * <p><b>"리포트는 있는데 퀴즈가 없는" 상태를 허용한다.</b> 예전에는 둘이 한 트랜잭션이라 그 상태가 생기지 않는다고 적혀 있었지만, 그 대가로 퀴즈 문항 하나가 계약을 어기면 리포트까지 롤백되고 그
 * 학생이 {@code FAILED} 로 집계되어 <b>세션 공개까지 막혔다</b>(S15P11A105-333). 퀴즈 하나 때문에 리포트와 수업 전체의 공개를 잃는 것보다, 퀴즈 없는 리포트가 낫다.
 *
 * <p><b>알고 남긴 대가</b>: 멱등 판정이 여전히 리포트 유무 하나라서, 퀴즈 없이 저장된 학생은 다음 실행의 {@code findStudentsWithoutReport} 에서 빠지고 퀴즈가 다시 채워지지
 * 않는다. 화면은 그 경우 "이번 수업에서는 풀어볼 문제가 없어요" 를 그린다 — 학생이 오지 않는 퀴즈를 기다리지 않게 하는 것이 이 선택의 조건이다.
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
            // 두 가지 뜻이 있다 — 초안이 계약을 어겨 만들지 못했거나(사유는 퀴즈 쪽이 남겼다), 이미 있었다.
            // 뒤쪽은 우리가 방금 넣은 리포트에는 있을 수 없으므로 UK 전제가 깨진 신호다.
            // 어느 쪽이든 리포트는 살린다. 퀴즈 없는 리포트가 리포트 없는 학생보다 낫다.
            log.warn("리포트는 저장했지만 퀴즈는 남기지 못했습니다. reportId={}", reportId.get());
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
