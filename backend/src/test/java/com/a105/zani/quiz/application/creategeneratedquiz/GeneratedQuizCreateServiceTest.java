package com.a105.zani.quiz.application.creategeneratedquiz;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.quiz.domain.model.Quiz;
import com.a105.zani.quiz.domain.repository.QuizRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만들 수 없는 초안을 예외로 올리지 않고 {@code false} 로 답하는지 본다(S15P11A105-333).
 *
 * <p>호출부는 학생 리포트와 퀴즈를 한 트랜잭션에 담는다. 여기서 던지면 그 트랜잭션이 롤백되어 <b>리포트까지 함께 사라지고</b>, 그 학생이 {@code FAILED} 로 집계되면 세션 공개까지 막힌다.
 * 그래서 이 경계에서 도메인 예외를 흡수한다.
 */
class GeneratedQuizCreateServiceTest {

    private static final long REPORT_ID = 8_101L;

    private final List<Quiz> saved = new ArrayList<>();
    private GeneratedQuizCreateService service;

    @BeforeEach
    void setUp() {
        saved.clear();
        QuizRepository repository = quiz -> {
            saved.add(quiz);
            return true;
        };
        service = new GeneratedQuizCreateService(repository);
    }

    @Test
    @DisplayName("정상 초안은 저장하고 true 를 준다")
    void createsAUsableQuiz() {
        assertThat(service.create(command(question(1), question(2), question(3))))
                .isTrue();
        assertThat(saved).hasSize(1);
    }

    /** 스키마가 보기 4개와 문항 3~5개는 강제하지만 <b>"정답이 정확히 1개" 는 JSON Schema 로 표현할 수 없다.</b> 그 규칙은 프롬프트 문장뿐이라 실제로 여기까지 도달한다. */
    @Test
    @DisplayName("정답이 둘인 문항이 오면 던지지 않고 false 를 준다")
    void refusesADraftWithTwoCorrectOptions() {
        assertThat(service.create(command(question(1), question(2), twoCorrect())))
                .isFalse();
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("정답이 없는 문항이 오면 던지지 않고 false 를 준다")
    void refusesADraftWithNoCorrectOption() {
        assertThat(service.create(command(question(1), question(2), noneCorrect())))
                .isFalse();
        assertThat(saved).isEmpty();
    }

    /** 문항 수 하한은 스키마가 막지만, 막지 못한 채 도달해도 예외로 번지지 않아야 한다. */
    @Test
    @DisplayName("문항이 셋보다 적으면 던지지 않고 false 를 준다")
    void refusesADraftWithTooFewQuestions() {
        assertThat(service.create(command(question(1)))).isFalse();
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("문항 텍스트가 비어도 던지지 않고 false 를 준다")
    void refusesADraftWithABlankQuestionText() {
        assertThat(service.create(command(question(1), question(2), blankText())))
                .isFalse();
        assertThat(saved).isEmpty();
    }

    private CreateGeneratedQuizCommand command(CreateGeneratedQuizCommand.Question... questions) {
        return new CreateGeneratedQuizCommand(REPORT_ID, "해시 테이블 확인 퀴즈", "핵심 개념을 짚어 봅니다.", List.of(questions));
    }

    private CreateGeneratedQuizCommand.Question question(int number) {
        return new CreateGeneratedQuizCommand.Question(
                number + "번 문항", number + "번 해설", 0L, options(true, false, false, false));
    }

    private CreateGeneratedQuizCommand.Question twoCorrect() {
        return new CreateGeneratedQuizCommand.Question("정답이 둘인 문항", "해설", 0L, options(true, true, false, false));
    }

    private CreateGeneratedQuizCommand.Question noneCorrect() {
        return new CreateGeneratedQuizCommand.Question("정답이 없는 문항", "해설", 0L, options(false, false, false, false));
    }

    private CreateGeneratedQuizCommand.Question blankText() {
        return new CreateGeneratedQuizCommand.Question("   ", "해설", 0L, options(true, false, false, false));
    }

    private List<CreateGeneratedQuizCommand.Option> options(boolean... correct) {
        List<CreateGeneratedQuizCommand.Option> options = new ArrayList<>();
        for (int index = 0; index < correct.length; index++) {
            options.add(new CreateGeneratedQuizCommand.Option((index + 1) + "번 보기", correct[index]));
        }
        return options;
    }
}
