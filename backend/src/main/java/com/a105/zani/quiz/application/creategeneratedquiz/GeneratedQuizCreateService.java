package com.a105.zani.quiz.application.creategeneratedquiz;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.quiz.domain.exception.InvalidQuizException;
import com.a105.zani.quiz.domain.model.Quiz;
import com.a105.zani.quiz.domain.model.QuizOption;
import com.a105.zani.quiz.domain.model.QuizQuestion;
import com.a105.zani.quiz.domain.repository.QuizRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedQuizCreateService implements CreateGeneratedQuizUseCase {

    private final QuizRepository quizRepository;

    /**
     * 만들 수 없는 초안은 예외로 올리지 않고 {@code false} 로 답한다.
     *
     * <p><b>도메인 예외를 이 경계에서 흡수하는 이유.</b> 호출부는 학생 리포트와 퀴즈를 한 트랜잭션에 담는다. 여기서 던지면 그 트랜잭션이 롤백되어 <b>리포트까지 함께 사라지고</b>, 그 학생이
     * {@code FAILED} 로 집계되면 세션 공개까지 막힌다 — 퀴즈 문항 하나 때문에 수업 전체의 리포트가 안 나온다(S15P11A105-333).
     *
     * <p>문항을 골라 버리는 방법은 쓰지 않는다. {@code Quiz} 가 3~5문항을 요구하므로 버리다 보면 그 불변식이 다시 깨진다. 그리고 정답이 둘로 온 문항에서 어느 쪽이 정답인지 서버가 고르면
     * 학생에게 틀린 답을 가르친다. 퀴즈 하나를 포기하는 편이 정직하다.
     *
     * <p>응답 스키마가 보기 4개와 문항 3~5개는 강제하지만 <b>"정답이 정확히 1개" 는 JSON Schema 로 표현할 수 없다</b>. 그 규칙은 프롬프트 문장뿐이므로 여기까지 온다.
     *
     * @return 저장했으면 {@code true}. 이미 있었거나 초안이 계약을 어겨 만들 수 없으면 {@code false}
     */
    @Override
    @Transactional
    public boolean create(CreateGeneratedQuizCommand command) {
        Quiz quiz;
        try {
            List<CreateGeneratedQuizCommand.Question> givenQuestions =
                    command.questions() == null ? List.of() : command.questions();
            List<QuizQuestion> questions = givenQuestions.stream()
                    .map(question -> QuizQuestion.of(
                            question.questionText(),
                            question.explanation(),
                            question.sectionStartedOffsetMs(),
                            options(question.options())))
                    .toList();
            quiz = Quiz.create(command.studentReportId(), command.title(), command.description(), questions);
        } catch (InvalidQuizException rejected) {
            log.warn(
                    "퀴즈를 만들지 못해 이 학생은 퀴즈 없이 갑니다. studentReportId={}, 사유={}",
                    command.studentReportId(),
                    rejected.errorCode().code());
            return false;
        }
        return quizRepository.saveIfAbsent(quiz);
    }

    private List<QuizOption> options(List<CreateGeneratedQuizCommand.Option> given) {
        return (given == null ? List.<CreateGeneratedQuizCommand.Option>of() : given)
                .stream()
                        .map(option -> QuizOption.of(option.optionText(), option.correct()))
                        .toList();
    }
}
