package com.a105.zani.member.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;

/**
 * MemberJpaEntity ↔ Member 도메인 변환. 엔티티가 도메인 밖으로 새어나가지 않게 한다. 현재는 읽기 전용이라 toDomain만 두고, member 저장 use-case가 생기면
 * toEntity를 추가한다.
 */
@Component
public class MemberPersistenceMapper {

    public Member toDomain(MemberJpaEntity entity) {
        return Member.reconstitute(entity.getId(), entity.getDisplayName());
    }
}
