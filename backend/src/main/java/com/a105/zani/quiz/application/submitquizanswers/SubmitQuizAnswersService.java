package com.a105.zani.quiz.application.submitquizanswers;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.quiz.application.exception.InvalidQuizAnswersException;
import com.a105.zani.quiz.application.exception.NotSessionStudentException;
import com.a105.zani.quiz.application.exception.QuizAlreadySubmittedException;
import com.a105.zani.quiz.application.exception.QuizNotReadyException;
import com.a105.zani.quiz.application.port.NewQuizAnswer;
import com.a105.zani.quiz.application.port.QuizQuestionSnapshot;
import com.a105.zani.quiz.application.port.StudentQuizPort;
import com.a105.zani.quiz.application.port.StudentQuizSnapshot;
import com.a105.zani.quiz.application.submitquizanswers.SubmitQuizAnswersCommand.QuizAnswerSelection;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 답안 일괄 제출과 채점. 채점은 선택한 보기의 정답 여부 비교뿐이라 별도 도메인 계층 없이 여기서 끝낸다(티켓의 relaxed 결정).
 *
 * <p>재제출 방어는 두 겹이다 — 여기서 기존 답 유무를 보고 409를 던지고, 동시에 들어와 검사를 같이 통과한 제출은 {@code quiz_answers} 의 문항 유니크 제약이 저장 시점에 걸러 같은
 * 409로 끝난다.
 */
@Service
@RequiredArgsConstructor
public class SubmitQuizAnswersService implements SubmitQuizAnswersUseCase {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final StudentQuizPort studentQuizPort;
    private final Clock clock;

    @Override
    @Transactional
    public SubmitQuizAnswersResult submit(SubmitQuizAnswersCommand command) {
        StudentQuizSnapshot snapshot = ownQuiz(command.sessionId(), command.memberId());
        if (snapshot.submitted()) {
            throw new QuizAlreadySubmittedException();
        }

        Map<Long, Long> selectionsByQuestion = validatedSelections(command.answers(), snapshot);

        Instant answeredAt = clock.instant();
        studentQuizPort.saveAnswers(snapshot.questions().stream()
                .map(question -> new NewQuizAnswer(
                        question.questionId(), selectionsByQuestion.get(question.questionId()), answeredAt))
                .toList());

        return SubmitQuizAnswersResult.grade(snapshot, selectionsByQuestion);
    }

    private StudentQuizSnapshot ownQuiz(Long sessionId, Long memberId) {
        ResolveEndedSessionParticipantResult participant;
        try {
            participant = resolveEndedSessionParticipantUseCase.resolve(
                    new ResolveEndedSessionParticipantQuery(sessionId, memberId));
        } catch (SessionNotEndedException stillLive) {
            // 조회 쪽과 같은 해석 — 참여 학생에게 진행 중 수업은 "퀴즈가 아직 없음"(404)이다.
            throw new QuizNotReadyException();
        }
        if (participant.role() != SessionParticipantRole.STUDENT) {
            throw new NotSessionStudentException();
        }
        return studentQuizPort.findQuiz(sessionId, participant.participantId()).orElseThrow(QuizNotReadyException::new);
    }

    /** 모든 문항이 정확히 한 번씩, 그 문항의 보기로 답해졌는지 확인하고 문항 ID → 보기 ID 로 정리한다. */
    private static Map<Long, Long> validatedSelections(
            List<QuizAnswerSelection> answers, StudentQuizSnapshot snapshot) {
        Map<Long, Long> selections = new HashMap<>();
        for (QuizAnswerSelection answer : answers) {
            if (selections.putIfAbsent(answer.questionId(), answer.selectedOptionId()) != null) {
                throw new InvalidQuizAnswersException();
            }
        }
        // 개수가 같고 모든 문항에 답이 있으면 남는 답도 없다 — 부분 답안과 모르는 문항을 함께 거른다.
        if (selections.size() != snapshot.questions().size()) {
            throw new InvalidQuizAnswersException();
        }
        for (QuizQuestionSnapshot question : snapshot.questions()) {
            if (!question.hasOption(selections.get(question.questionId()))) {
                throw new InvalidQuizAnswersException();
            }
        }
        return selections;
    }
}
