package com.a105.zani.quiz.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.quiz.infrastructure.persistence.entity.QuizQuestionJpaEntity;

public interface QuizQuestionJpaRepository extends JpaRepository<QuizQuestionJpaEntity, Long> {

    List<QuizQuestionJpaEntity> findByQuizIdOrderByQuestionOrderAsc(Long quizId);
}
