package com.a105.zani.quiz.application.submitquizanswers;

import com.a105.zani.quiz.application.exception.InvalidQuizAnswersException;
import com.a105.zani.quiz.application.exception.NotSessionStudentException;
import com.a105.zani.quiz.application.exception.QuizAlreadySubmittedException;
import com.a105.zani.quiz.application.exception.QuizNotReadyException;

/** 세션 참여 학생이 자기 퀴즈의 답안을 일괄 제출하고 채점 결과를 받는다. 제출은 1회로 확정되며 재응시는 없다(2026-07-31 회의). */
public interface SubmitQuizAnswersUseCase {

    /**
     * 답안을 제출하고 채점한다.
     *
     * @throws NotSessionStudentException 세션에 참여한 학생이 아님 — 비참여자·강사 모두(403)
     * @throws QuizNotReadyException 수업이 진행 중이거나 파이프라인이 아직 퀴즈를 만들지 않음(404)
     * @throws QuizAlreadySubmittedException 이미 제출된 퀴즈(409)
     * @throws InvalidQuizAnswersException 부분 답안·중복 문항·문항에 없는 보기(400)
     */
    SubmitQuizAnswersResult submit(SubmitQuizAnswersCommand command);
}
