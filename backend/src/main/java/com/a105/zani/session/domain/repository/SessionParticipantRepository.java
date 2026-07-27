package com.a105.zani.session.domain.repository;

import java.util.List;
import java.util.Optional;

import com.a105.zani.session.domain.model.SessionParticipant;

public interface SessionParticipantRepository {

    Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId);

    Optional<SessionParticipant> findById(Long id);

    List<SessionParticipant> findBySessionId(Long sessionId);

    SessionParticipant save(SessionParticipant sessionParticipant);
}
