package com.a105.zani.session.domain.repository;

import com.a105.zani.session.domain.model.Session;
import java.util.Optional;

public interface SessionRepository {

    Session save(Session session);

    Optional<Session> findByInviteCode(String inviteCode);
}
