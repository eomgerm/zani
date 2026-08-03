package com.a105.zani.quiz.application.port;

import java.util.List;
import java.util.Optional;

import com.a105.zani.quiz.application.exception.QuizAlreadySubmittedException;

/**
 * 학생 한 명의 퀴즈 저장소. 조회 유스케이스는 화면을 만들고, 제출 유스케이스는 채점·완전성 검사를 하므로 둘 다 같은 스냅샷을 쓴다.
 *
 * <p>퀴즈 생성은 LLM 학생별 분석 파이프라인의 몫이라 여기에는 없다 — 이 포트는 이미 만들어진 퀴즈를 읽고 답안만 쓴다.
 */
public interface StudentQuizPort {

    /**
     * 세션 참여자 본인의 퀴즈를 문항·보기·기존 답안까지 통째로 읽는다. 파이프라인이 아직 퀴즈를 만들지 않았으면 빈 값.
     *
     * <p>{@code quizzes.published_at} 은 거르지 않는다 — 공개 단계를 쓸지는 파이프라인 쪽이 정하지 않았고, 행이 통째로 생기는 지금 구조에서는 존재 자체가 완성이다.
     */
    Optional<StudentQuizSnapshot> findQuiz(Long sessionId, Long participantId);

    /**
     * 제출된 답안을 한 번에 저장한다. 문항당 답 하나라는 규칙은 DB 유니크 제약이 최종으로 지킨다.
     *
     * @throws QuizAlreadySubmittedException 같은 문항에 이미 답이 있음 — 동시에 들어온 두 제출 중 늦은 쪽
     */
    void saveAnswers(List<NewQuizAnswer> answers);
}
