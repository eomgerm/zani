package com.a105.zani.quiz.domain.model;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.a105.zani.quiz.domain.exception.InvalidQuizException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizTest {

    private static final long STUDENT_REPORT_ID = 1L;

    @Test
    void numbersQuestionsFromOne() {
        Quiz quiz = Quiz.create(STUDENT_REPORT_ID, "퀴즈", "설명", questions(3));

        assertThat(quiz.questions()).extracting(QuizQuestion::questionOrder).containsExactly(1, 2, 3);
    }

    @Test
    void allowsThreeToFiveQuestions() {
        assertThat(Quiz.create(STUDENT_REPORT_ID, "퀴즈", null, questions(3)).questions())
                .hasSize(3);
        assertThat(Quiz.create(STUDENT_REPORT_ID, "퀴즈", null, questions(5)).questions())
                .hasSize(5);
    }

    @Test
    void rejectsTwoOrSixQuestions() {
        assertThatThrownBy(() -> Quiz.create(STUDENT_REPORT_ID, "퀴즈", null, questions(2)))
                .isInstanceOf(InvalidQuizException.class);
        assertThatThrownBy(() -> Quiz.create(STUDENT_REPORT_ID, "퀴즈", null, questions(6)))
                .isInstanceOf(InvalidQuizException.class);
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> Quiz.create(STUDENT_REPORT_ID, "   ", null, questions(3)))
                .isInstanceOf(InvalidQuizException.class);
    }

    @Test
    void normalizesBlankDescriptionToNull() {
        assertThat(Quiz.create(STUDENT_REPORT_ID, "퀴즈", "   ", questions(3)).description())
                .isNull();
    }

    private List<QuizQuestion> questions(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> QuizQuestion.of("문항 " + index, "해설", QuizQuestionTest.options()))
                .toList();
    }
}
