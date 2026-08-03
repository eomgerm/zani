package com.a105.zani.quiz.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.quiz.application.exception.NotSessionStudentException;
import com.a105.zani.quiz.application.exception.QuizNotReadyException;
import com.a105.zani.quiz.application.port.StudentQuizPort;
import com.a105.zani.quiz.application.port.StudentQuizSnapshot;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 본인 퀴즈 인가·로드의 단일 진실점. 조회와 제출이 같은 403/404 판정을 내도록 한 곳에 둔다 — 두 곳에 흩어지면 한쪽만 고쳐질 때 조회는 막히고 제출은 뚫리는 비대칭이 생긴다.
 *
 * <p>유스케이스가 아니라 두 유스케이스 서비스가 주입받는 협력자다(읽기·쓰기 유스케이스는 트랜잭션 특성이 달라 한 서비스로 묶지 않는다 — DDD 가이드 §7). 트랜잭션은 각 서비스가 소유하고 여기는 참여만
 * 한다.
 */
@Component
@RequiredArgsConstructor
public class StudentQuizAccess {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final StudentQuizPort studentQuizPort;

    /**
     * 세션 참여 학생 본인의 퀴즈를 문항·보기·기존 답안까지 읽는다.
     *
     * @throws NotSessionStudentException 세션에 참여한 학생이 아님 — 비참여자·강사 모두(403)
     * @throws QuizNotReadyException 수업이 진행 중이거나 파이프라인이 아직 퀴즈를 만들지 않음(404)
     */
    public StudentQuizSnapshot loadOwnQuiz(Long sessionId, Long memberId) {
        ResolveEndedSessionParticipantResult participant;
        try {
            participant = resolveEndedSessionParticipantUseCase.resolve(
                    new ResolveEndedSessionParticipantQuery(sessionId, memberId));
        } catch (SessionNotEndedException stillLive) {
            // 퀴즈는 수업 종료 후 파이프라인이 만든다. 참여 학생에게 진행 중 수업은 "아직 준비 안 됨"(404)이지
            // 충돌(409)이 아니다 — API 계약(200/403/404)도 이 해석을 따른다.
            throw new QuizNotReadyException();
        }
        if (participant.role() != SessionParticipantRole.STUDENT) {
            throw new NotSessionStudentException();
        }
        return studentQuizPort.findQuiz(sessionId, participant.participantId()).orElseThrow(QuizNotReadyException::new);
    }
}
