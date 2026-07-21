package com.a105.zani.session.infrastructure.persistence.repository;

import com.a105.zani.session.infrastructure.persistence.entity.SessionMemberJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionMemberJpaRepository extends JpaRepository<SessionMemberJpaEntity, Long> {

    Optional<SessionMemberJpaEntity> findBySessionIdAndUserId(Long sessionId, Long userId);
}
