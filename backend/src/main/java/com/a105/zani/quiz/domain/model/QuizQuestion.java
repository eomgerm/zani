package com.a105.zani.quiz.domain.model;

import java.util.List;
import java.util.stream.IntStream;

import com.a105.zani.quiz.domain.exception.InvalidQuizException;
import com.a105.zani.quiz.domain.exception.QuizErrorCode;

/**
 * 4지선다 문항 하나. 보기는 정확히 4개이고 정답은 정확히 1개다 — 채점이 이 형태를 전제한다.
 *
 * <p>해설은 없어도 된다(스키마 NULL 허용). 빈 문자열은 NULL 로 접어 "해설이 있다"와 "빈 해설"이 갈라지지 않게 한다.
 */
public record QuizQuestion(String questionText, String explanation, int questionOrder, List<QuizOption> options) {

    static final int UNSET_ORDER = 0;

    private static final int REQUIRED_OPTION_COUNT = 4;

    public QuizQuestion {
        questionText = questionText == null ? null : questionText.strip();
        explanation = explanation == null || explanation.isBlank() ? null : explanation.strip();
        options = options == null ? List.of() : options;
        if (questionText == null
                || questionText.isEmpty()
                || questionOrder < UNSET_ORDER
                || options.size() != REQUIRED_OPTION_COUNT
                || options.stream().filter(QuizOption::correct).count() != 1) {
            throw new InvalidQuizException(QuizErrorCode.INVALID_QUIZ_QUESTION);
        }
        // 검증을 지나온 뒤에 번호를 매긴다. 순서를 바꾸면 빈 목록에서 색인 예외가 먼저 난다.
        List<QuizOption> given = options;
        options = IntStream.range(0, given.size())
                .mapToObj(index -> given.get(index).withOrder(index + 1))
                .toList();
    }

    public static QuizQuestion of(String questionText, String explanation, List<QuizOption> options) {
        return new QuizQuestion(questionText, explanation, UNSET_ORDER, options);
    }

    QuizQuestion withOrder(int questionOrder) {
        return new QuizQuestion(questionText, explanation, questionOrder, options);
    }
}
