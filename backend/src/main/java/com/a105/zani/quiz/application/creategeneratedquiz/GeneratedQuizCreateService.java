package com.a105.zani.quiz.application.creategeneratedquiz;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.quiz.domain.model.Quiz;
import com.a105.zani.quiz.domain.model.QuizOption;
import com.a105.zani.quiz.domain.model.QuizQuestion;
import com.a105.zani.quiz.domain.repository.QuizRepository;

@Service
@RequiredArgsConstructor
public class GeneratedQuizCreateService implements CreateGeneratedQuizUseCase {

    private final QuizRepository quizRepository;

    @Override
    @Transactional
    public boolean create(CreateGeneratedQuizCommand command) {
        List<CreateGeneratedQuizCommand.Question> givenQuestions =
                command.questions() == null ? List.of() : command.questions();
        List<QuizQuestion> questions = givenQuestions.stream()
                .map(question -> QuizQuestion.of(
                        question.questionText(),
                        question.explanation(),
                        question.sectionStartedOffsetMs(),
                        options(question.options())))
                .toList();
        Quiz quiz = Quiz.create(command.studentReportId(), command.title(), command.description(), questions);
        return quizRepository.saveIfAbsent(quiz);
    }

    private List<QuizOption> options(List<CreateGeneratedQuizCommand.Option> given) {
        return (given == null ? List.<CreateGeneratedQuizCommand.Option>of() : given)
                .stream()
                        .map(option -> QuizOption.of(option.optionText(), option.correct()))
                        .toList();
    }
}
