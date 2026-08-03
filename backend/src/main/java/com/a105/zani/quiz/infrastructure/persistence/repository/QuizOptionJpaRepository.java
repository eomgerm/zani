package com.a105.zani.quiz.infrastructure.persistence.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.quiz.infrastructure.persistence.entity.QuizOptionJpaEntity;

public interface QuizOptionJpaRepository extends JpaRepository<QuizOptionJpaEntity, Long> {

    List<QuizOptionJpaEntity> findByQuizQuestionIdInOrderByOptionOrderAsc(Collection<Long> quizQuestionIds);
}
