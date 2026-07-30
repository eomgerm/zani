package com.a105.zani.session.application.create;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 세션 저장을 매 시도마다 독립된 트랜잭션으로 수행한다.
 *
 * <p>초대 코드 유니크 충돌은 flush 시점에 드러나며, 실패한 트랜잭션의 영속성 컨텍스트는 재사용할 수 없다. 저장을 {@link Propagation#REQUIRES_NEW} 경계로 분리해, 한 시도가
 * 롤백되어도 다음 재시도가 오염되지 않은 트랜잭션에서 실행되도록 보장한다.
 *
 * <p>강사의 참가자 행도 같은 트랜잭션에서 만든다. 미디어 토큰 발급과 presence 보고가 모두 참가자 행에서 참가자 ID 와 역할을 읽으므로, 이 행이 없으면 강사는 자기가 만든 방에서 403 을 받아
 * 카메라·마이크를 켤 수 없다. 세션만 남고 멤버십이 없는 상태를 만들지 않으려고 저장을 쪼개지 않는다.
 */
@Component
class NewSessionSaver {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final Clock clock;

    NewSessionSaver(
            SessionRepository sessionRepository, SessionParticipantRepository participantRepository, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Session save(Session session) {
        Session saved = sessionRepository.save(session);
        // 강사 멤버십 시각은 세션 시작 시각이 아니라 등록 시각이다. 준비 중인 세션은 아직 시작 시각이 없고,
        // 강사가 "언제 이 수업의 참가자가 됐는가"는 시작과 별개의 사실이다.
        participantRepository.save(SessionParticipant.join(
                TsidGenerator.generate(),
                saved.id(),
                saved.instructorId(),
                SessionParticipantRole.INSTRUCTOR,
                clock.instant()));
        return saved;
    }
}
