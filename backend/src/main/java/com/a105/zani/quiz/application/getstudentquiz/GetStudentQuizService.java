package com.a105.zani.quiz.application.getstudentquiz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.quiz.application.exception.NotSessionStudentException;
import com.a105.zani.quiz.application.exception.QuizNotReadyException;
import com.a105.zani.quiz.application.port.StudentQuizPort;
import com.a105.zani.quiz.application.port.StudentQuizSnapshot;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/** 본인 퀴즈 조회. 멤버십·역할 확인 뒤 스냅샷을 그대로 화면 모양으로 바꾼다 — 정답 노출 여부는 Result 가 결정한다. */
@Service
@RequiredArgsConstructor
public class GetStudentQuizService implements GetStudentQuizUseCase {

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipantUseCase;
    private final StudentQuizPort studentQuizPort;

    @Override
    @Transactional(readOnly = true)
    public GetStudentQuizResult get(GetStudentQuizQuery query) {
        return GetStudentQuizResult.from(ownQuiz(query.sessionId(), query.memberId()));
    }

    private StudentQuizSnapshot ownQuiz(Long sessionId, Long memberId) {
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
