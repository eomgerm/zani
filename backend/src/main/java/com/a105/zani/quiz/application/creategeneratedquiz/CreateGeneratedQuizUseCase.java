package com.a105.zani.quiz.application.creategeneratedquiz;

public interface CreateGeneratedQuizUseCase {

    /** 저장했으면 true, 그 리포트에 퀴즈가 이미 있으면 false. */
    boolean create(CreateGeneratedQuizCommand command);
}
