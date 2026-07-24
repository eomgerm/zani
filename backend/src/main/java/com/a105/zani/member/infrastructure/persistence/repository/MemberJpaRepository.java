package com.a105.zani.member.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;

public interface MemberJpaRepository extends JpaRepository<MemberJpaEntity, Long> {}
