package com.a105.zani.session.application.join;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.SessionCapacityReachedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.exception.SessionNotJoinableException;
import com.a105.zani.session.domain.model.InviteCode;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 학생이 초대 코드로 수업에 들어간다. 이 API는 참가 관계(강의실 화면에 들어갈 자격)만 만든다.
 *
 * <p>출석과 사후 자료 접근 자격은 여기서 확정하지 않는다. LiveKit에 실제로 연결됐을 때 오는 {@code participant_joined} webhook이 그 기준이며(가이드 §5), 그래야 API만
 * 호출하고 미디어에 붙지 않은 사용자가 집계 분모에 섞이지 않는다.
 *
 * <p>정원 검사와 참가 관계 삽입은 세션 행을 잠근 채 수행한다. 세지 않고 넣으면 동시 입장에서 31번째가 통과할 수 있고, 가이드가 최종 방어로 지목한 LiveKit
 * {@code maxParticipants}는 Room 생성과 함께 별도로 붙는다.
 */
@Service
public class SessionJoinService implements JoinSessionUseCase {

    /** 강사를 포함한 하드 캡(가이드 §6). */
    public static final int MAX_PARTICIPANTS = 30;

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final Clock clock;

    public SessionJoinService(
            SessionRepository sessionRepository,
            SessionParticipantRepository sessionParticipantRepository,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public JoinSessionResult join(JoinSessionCommand command) {
        // 하이픈·소문자 표시형을 저장형으로 되돌린 뒤 조회한다. 형식이 어긋나면 여기서 400.
        InviteCode inviteCode = InviteCode.normalize(command.inviteCode());
        Session session = sessionRepository
                .findByInviteCodeForUpdate(inviteCode.value())
                .orElseThrow(SessionNotFoundException::new);

        SessionParticipant participant = enrollOrRecordAccess(session, command.studentId());

        return new JoinSessionResult(session.id(), session.inviteCode(), session.status(), participant.role());
    }

    private SessionParticipant enrollOrRecordAccess(Session session, long studentId) {
        Instant now = clock.instant();
        // 이미 참가 중이면 세션 상태·정원과 무관하게 멱등하게 돌려준다. 새로고침·재입장이 정원을 다시 소비하면 안 되고,
        // 종료 직후의 재조회도 참가자에게는 실패가 아니라 "이미 들어와 있음"이어야 한다.
        return sessionParticipantRepository
                .findBySessionIdAndUserId(session.id(), studentId)
                .map(existing -> {
                    existing.recordAccess(now);
                    return sessionParticipantRepository.save(existing);
                })
                .orElseGet(() -> sessionParticipantRepository.save(newStudent(session, studentId, now)));
    }

    private SessionParticipant newStudent(Session session, long studentId, Instant now) {
        if (!session.acceptsNewParticipants()) {
            throw new SessionNotJoinableException();
        }
        if (sessionParticipantRepository.countBySessionId(session.id()) >= MAX_PARTICIPANTS) {
            throw new SessionCapacityReachedException();
        }
        return SessionParticipant.enroll(
                TsidGenerator.generate(), session.id(), studentId, SessionParticipantRole.STUDENT, now);
    }
}
