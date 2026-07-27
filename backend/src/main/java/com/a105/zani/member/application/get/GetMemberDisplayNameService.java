package com.a105.zani.member.application.get;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;

@Service
public class GetMemberDisplayNameService implements GetMemberDisplayNameUseCase {

    private final MemberRepository memberRepository;

    public GetMemberDisplayNameService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getDisplayName(GetMemberDisplayNameQuery query) {
        return memberRepository.findById(query.memberId()).map(Member::displayName);
    }
}
