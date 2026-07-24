package com.a105.zani.member.infrastructure.persistence;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.a105.zani.member.application.exception.DuplicateGoogleSubjectException;
import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;
import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;
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
    public Member save(Member member) {
        MemberJpaEntity entity = mapper.toEntity(member);
        try {
            MemberJpaEntity saved = memberJpaRepository.saveAndFlush(entity);
            return mapper.toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateGoogleSubjectException(exception);
        }
    }

    @Override
    public Optional<Member> findById(Long id) {
        return memberJpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Member> findByGoogleSubject(String googleSubject) {
        return memberJpaRepository.findByGoogleSubject(googleSubject).map(mapper::toDomain);
    }
}
