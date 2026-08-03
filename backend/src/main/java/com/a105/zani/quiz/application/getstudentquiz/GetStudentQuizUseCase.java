package com.a105.zani.quiz.application.getstudentquiz;

import com.a105.zani.quiz.application.exception.NotSessionStudentException;
import com.a105.zani.quiz.application.exception.QuizNotReadyException;

/** 세션 참여 학생이 자기 퀴즈를 본다. 제출 전에는 문항·보기만, 제출 후에는 채점 결과까지 함께 준다. */
public interface GetStudentQuizUseCase {

    /**
     * 본인 퀴즈를 조회한다.
     *
     * @throws NotSessionStudentException 세션에 참여한 학생이 아님 — 비참여자·강사 모두(403)
     * @throws QuizNotReadyException 수업이 진행 중이거나 파이프라인이 아직 퀴즈를 만들지 않음(404)
     */
    GetStudentQuizResult get(GetStudentQuizQuery query);
}
