package com.a105.zani.session.domain.repository;

import com.a105.zani.session.domain.model.SessionMember;
import java.util.Optional;

public interface SessionMemberRepository {

    Optional<SessionMember> findBySessionIdAndUserId(Long sessionId, Long userId);

    SessionMember save(SessionMember sessionMember);
}
