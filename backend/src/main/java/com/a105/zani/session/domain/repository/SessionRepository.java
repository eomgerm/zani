package com.a105.zani.session.domain.repository;

import java.util.Optional;

import com.a105.zani.session.domain.model.Session;

public interface SessionRepository {

    Session save(Session session);

    Optional<Session> findById(Long id);

    Optional<Session> findByInviteCode(String inviteCode);
}
