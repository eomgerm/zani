package com.a105.zani.quiz.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.quiz.application.exception.QuizAlreadySubmittedException;
import com.a105.zani.quiz.application.port.NewQuizAnswer;
import com.a105.zani.quiz.application.port.QuizOptionSnapshot;
import com.a105.zani.quiz.application.port.QuizQuestionSnapshot;
import com.a105.zani.quiz.application.port.StudentQuizPort;
import com.a105.zani.quiz.application.port.StudentQuizSnapshot;
import com.a105.zani.quiz.infrastructure.persistence.entity.QuizAnswerJpaEntity;
import com.a105.zani.quiz.infrastructure.persistence.entity.QuizJpaEntity;
import com.a105.zani.quiz.infrastructure.persistence.entity.QuizOptionJpaEntity;
import com.a105.zani.quiz.infrastructure.persistence.entity.QuizQuestionJpaEntity;
import com.a105.zani.quiz.infrastructure.persistence.repository.QuizAnswerJpaRepository;
import com.a105.zani.quiz.infrastructure.persistence.repository.QuizJpaRepository;
import com.a105.zani.quiz.infrastructure.persistence.repository.QuizOptionJpaRepository;
import com.a105.zani.quiz.infrastructure.persistence.repository.QuizQuestionJpaRepository;

/** 학생 퀴즈를 문항·보기·답안까지 모아 스냅샷으로 만들고, 제출 답안을 일괄 저장한다. */
@Component
@RequiredArgsConstructor
public class StudentQuizPersistenceAdapter implements StudentQuizPort {

    private final QuizJpaRepository quizJpaRepository;
    private final QuizQuestionJpaRepository quizQuestionJpaRepository;
    private final QuizOptionJpaRepository quizOptionJpaRepository;
    private final QuizAnswerJpaRepository quizAnswerJpaRepository;

    @Override
    public Optional<StudentQuizSnapshot> findQuiz(Long sessionId, Long participantId) {
        return quizJpaRepository
                .findBySessionIdAndParticipantId(sessionId, participantId)
                .map(this::snapshotOf);
    }

    @Override
    public void saveAnswers(List<NewQuizAnswer> answers) {
        try {
            // flush 까지 해야 유니크 위반이 이 안에서 터져 409 로 번역된다. 커밋 시점으로 미루면 어댑터 밖에서
            // 날 것의 DataIntegrityViolationException 이 500 으로 새어 나간다.
            quizAnswerJpaRepository.saveAllAndFlush(answers.stream()
                    .map(StudentQuizPersistenceAdapter::entityOf)
                    .toList());
        } catch (DataIntegrityViolationException raceLost) {
            // UK_QUIZ_ANSWERS_QUIZ_QUESTION — 사전 검사를 나란히 통과한 두 제출 중 늦게 커밋하는 쪽이다.
            throw new QuizAlreadySubmittedException(raceLost);
        }
    }

    private StudentQuizSnapshot snapshotOf(QuizJpaEntity quiz) {
        List<QuizQuestionJpaEntity> questions =
                quizQuestionJpaRepository.findByQuizIdOrderByQuestionOrderAsc(quiz.getId());
        List<Long> questionIds =
                questions.stream().map(QuizQuestionJpaEntity::getId).toList();

        // 보기는 문항별 묶음 안에서 option_order 오름차순을 유지한다(쿼리 정렬 → 스트림 encounter order).
        Map<Long, List<QuizOptionSnapshot>> optionsByQuestion = questionIds.isEmpty()
                ? Map.of()
                : quizOptionJpaRepository.findByQuizQuestionIdInOrderByOptionOrderAsc(questionIds).stream()
                        .collect(Collectors.groupingBy(
                                QuizOptionJpaEntity::getQuizQuestionId,
                                Collectors.mapping(StudentQuizPersistenceAdapter::optionOf, Collectors.toList())));
        Map<Long, Long> selectedByQuestion = questionIds.isEmpty()
                ? Map.of()
                : quizAnswerJpaRepository.findByQuizQuestionIdIn(questionIds).stream()
                        .collect(Collectors.toMap(
                                QuizAnswerJpaEntity::getQuizQuestionId, QuizAnswerJpaEntity::getSelectedQuizOptionId));

        return new StudentQuizSnapshot(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getEstimatedDurationMinutes(),
                questions.stream()
                        .map(question -> new QuizQuestionSnapshot(
                                question.getId(),
                                question.getQuestionText(),
                                question.getExplanation(),
                                question.getQuestionOrder(),
                                optionsByQuestion.getOrDefault(question.getId(), List.of()),
                                selectedByQuestion.get(question.getId())))
                        .toList());
    }

    private static QuizOptionSnapshot optionOf(QuizOptionJpaEntity option) {
        return new QuizOptionSnapshot(
                option.getId(), option.getOptionText(), option.getOptionOrder(), option.isCorrect());
    }

    private static QuizAnswerJpaEntity entityOf(NewQuizAnswer answer) {
        return QuizAnswerJpaEntity.builder()
                .id(TsidGenerator.generate())
                .quizQuestionId(answer.questionId())
                .selectedQuizOptionId(answer.selectedOptionId())
                .answeredAt(answer.answeredAt())
                .build();
    }
}
