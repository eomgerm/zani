package com.a105.zani.quiz.domain.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.quiz.domain.exception.InvalidQuizException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizQuestionTest {

    @Test
    void numbersOptionsFromOne() {
        QuizQuestion question = QuizQuestion.of("문항", "해설", options());

        assertThat(question.options()).extracting(QuizOption::optionOrder).containsExactly(1, 2, 3, 4);
        assertThat(question.options()).extracting(QuizOption::correct).containsExactly(true, false, false, false);
    }

    @Test
    void normalizesBlankExplanationToNull() {
        assertThat(QuizQuestion.of("문항", "   ", options()).explanation()).isNull();
    }

    @Test
    void rejectsOptionCountOtherThanFour() {
        assertThatThrownBy(() -> QuizQuestion.of("문항", "해설", options().subList(0, 3)))
                .isInstanceOf(InvalidQuizException.class);
    }

    @Test
    void rejectsWhenCorrectOptionIsNotExactlyOne() {
        List<QuizOption> noCorrect = List.of(
                QuizOption.of("1", false),
                QuizOption.of("2", false),
                QuizOption.of("3", false),
                QuizOption.of("4", false));
        List<QuizOption> twoCorrect = List.of(
                QuizOption.of("1", true),
                QuizOption.of("2", true),
                QuizOption.of("3", false),
                QuizOption.of("4", false));

        assertThatThrownBy(() -> QuizQuestion.of("문항", "해설", noCorrect)).isInstanceOf(InvalidQuizException.class);
        assertThatThrownBy(() -> QuizQuestion.of("문항", "해설", twoCorrect)).isInstanceOf(InvalidQuizException.class);
    }

    @Test
    void rejectsBlankQuestionTextOrOptionText() {
        assertThatThrownBy(() -> QuizQuestion.of("   ", "해설", options())).isInstanceOf(InvalidQuizException.class);
        assertThatThrownBy(() -> QuizOption.of("   ", true)).isInstanceOf(InvalidQuizException.class);
    }

    static List<QuizOption> options() {
        return List.of(
                QuizOption.of("정답", true),
                QuizOption.of("오답1", false),
                QuizOption.of("오답2", false),
                QuizOption.of("오답3", false));
    }
}
