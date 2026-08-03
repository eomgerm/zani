package com.a105.zani.quiz.infrastructure.persistence.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.quiz.infrastructure.persistence.entity.QuizAnswerJpaEntity;

public interface QuizAnswerJpaRepository extends JpaRepository<QuizAnswerJpaEntity, Long> {

    List<QuizAnswerJpaEntity> findByQuizQuestionIdIn(Collection<Long> quizQuestionIds);
}
