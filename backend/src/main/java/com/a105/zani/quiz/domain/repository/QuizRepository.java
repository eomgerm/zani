package com.a105.zani.quiz.domain.repository;

import com.a105.zani.quiz.domain.model.Quiz;

public interface QuizRepository {

    /** 리포트에 퀴즈가 없으면 문항·보기까지 넣고 true, 다른 실행이 먼저 넣었으면 아무것도 넣지 않고 false. */
    boolean saveIfAbsent(Quiz quiz);
}
