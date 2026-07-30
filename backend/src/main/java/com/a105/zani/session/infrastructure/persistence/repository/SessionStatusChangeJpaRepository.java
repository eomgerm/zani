package com.a105.zani.session.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.session.infrastructure.persistence.entity.SessionStatusChangeJpaEntity;

public interface SessionStatusChangeJpaRepository extends JpaRepository<SessionStatusChangeJpaEntity, Long> {}
