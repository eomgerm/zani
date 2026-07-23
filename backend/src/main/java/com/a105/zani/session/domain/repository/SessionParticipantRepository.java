package com.a105.zani.session.domain.repository;

import java.util.Optional;

import com.a105.zani.session.domain.model.SessionParticipant;

public interface SessionParticipantRepository {

    Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId);

    SessionParticipant save(SessionParticipant sessionParticipant);
}
