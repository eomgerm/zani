package com.a105.zani.quiz.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.a105.zani.common.infrastructure.persistence.BaseJpaEntity;

@Entity
@Table(name = "quiz_answers")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class QuizAnswerJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "quiz_question_id", nullable = false)
    private Long quizQuestionId;

    @Column(name = "selected_quiz_option_id", nullable = false)
    private Long selectedQuizOptionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(
                name = "quiz_question_id",
                referencedColumnName = "quiz_question_id",
                insertable = false,
                updatable = false),
        @JoinColumn(
                name = "selected_quiz_option_id",
                referencedColumnName = "id",
                insertable = false,
                updatable = false)
    })
    private QuizOptionJpaEntity selectedQuizOption;

    @Column(name = "answered_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant answeredAt;
}
