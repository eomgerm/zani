package com.a105.zani.session.infrastructure.persistence.repository;

import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionJpaRepository extends JpaRepository<SessionJpaEntity, Long> {

    Optional<SessionJpaEntity> findByInviteCode(String inviteCode);
}
