package com.a105.zani.member.infrastructure.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;
import com.a105.zani.member.infrastructure.persistence.mapper.MemberPersistenceMapper;
import com.a105.zani.member.infrastructure.persistence.repository.MemberJpaRepository;

@Component
public class MemberPersistenceAdapter implements MemberRepository {

    private final MemberJpaRepository memberJpaRepository;
    private final MemberPersistenceMapper mapper;

    public MemberPersistenceAdapter(MemberJpaRepository memberJpaRepository, MemberPersistenceMapper mapper) {
        this.memberJpaRepository = memberJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Member> findById(Long id) {
        return memberJpaRepository.findById(id).map(mapper::toDomain);
    }
}
