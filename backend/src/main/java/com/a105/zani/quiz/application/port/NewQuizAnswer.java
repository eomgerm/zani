package com.a105.zani.quiz.application.port;

import java.time.Instant;

/** 저장할 답안 한 건. 일괄 제출이라 {@code answeredAt} 은 전 문항이 같은 시각이다. */
public record NewQuizAnswer(Long questionId, Long selectedOptionId, Instant answeredAt) {}
