package com.a105.zani.session.domain.repository;

import java.util.List;
import java.util.Optional;

import com.a105.zani.session.domain.model.SessionParticipant;

public interface SessionParticipantRepository {

    Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId);

    Optional<SessionParticipant> findById(Long id);

    List<SessionParticipant> findBySessionId(Long sessionId);

    /** 세션의 현재 참가 관계 수. 강사를 포함한 하드 캡(30명) 검사에 쓴다. */
    long countBySessionId(Long sessionId);

    SessionParticipant save(SessionParticipant sessionParticipant);
}
