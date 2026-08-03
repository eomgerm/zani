package com.a105.zani.session.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.session.infrastructure.persistence.entity.InteractionEventJpaEntity;

public interface InteractionEventJpaRepository extends JpaRepository<InteractionEventJpaEntity, Long> {}
