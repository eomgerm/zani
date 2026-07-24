package com.a105.zani.member.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;

@Component
public class MemberPersistenceMapper {

    public MemberJpaEntity toEntity(Member member) {
        return MemberJpaEntity.builder()
                .id(member.id())
                .googleSubject(member.googleSubject())
                .email(member.email())
                .displayName(member.displayName())
                .profileImageUrl(member.profileImageUrl())
                .build();
    }

    public Member toDomain(MemberJpaEntity entity) {
        return Member.reconstitute(
                entity.getId(),
                entity.getGoogleSubject(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getProfileImageUrl());
    }
}
