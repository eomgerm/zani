package com.a105.zani.quiz.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.quiz.infrastructure.persistence.entity.QuizJpaEntity;

public interface QuizJpaRepository extends JpaRepository<QuizJpaEntity, Long> {

    /** 퀴즈는 학생 리포트에 하나(UK_QUIZZES_STUDENT_REPORT), 리포트는 세션 참여자에 하나라 결과는 최대 한 건이다. */
    @Query("""
            select quiz
              from QuizJpaEntity quiz
              join quiz.studentReport report
             where report.sessionId = :sessionId
               and report.sessionParticipantId = :participantId
            """)
    Optional<QuizJpaEntity> findBySessionIdAndParticipantId(
            @Param("sessionId") Long sessionId, @Param("participantId") Long participantId);
}
