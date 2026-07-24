package com.a105.zani.member.infrastructure;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.a105.zani.member.application.MemberDisplayNameReader;
import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;
import com.a105.zani.member.infrastructure.persistence.repository.MemberJpaRepository;

@Component
public class MemberDisplayNameReaderAdapter implements MemberDisplayNameReader {

    private final MemberJpaRepository memberJpaRepository;

    public MemberDisplayNameReaderAdapter(MemberJpaRepository memberJpaRepository) {
        this.memberJpaRepository = memberJpaRepository;
    }

    @Override
    public Optional<String> findDisplayName(Long memberId) {
        // 엔티티를 도메인 밖으로 노출하지 않고 표시 이름만 매핑해 반환한다.
        return memberJpaRepository.findById(memberId).map(MemberJpaEntity::getDisplayName);
    }
}
