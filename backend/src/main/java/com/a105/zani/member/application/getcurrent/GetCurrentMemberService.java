package com.a105.zani.member.application.getcurrent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.member.application.exception.MemberNotFoundException;
import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;

@Service
public class GetCurrentMemberService implements GetCurrentMemberUseCase {

    private final MemberRepository memberRepository;

    public GetCurrentMemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public GetCurrentMemberResult getCurrentMember(GetCurrentMemberQuery query) {
        Member member = memberRepository.findById(query.memberId()).orElseThrow(MemberNotFoundException::new);
        return new GetCurrentMemberResult(member.email(), member.displayName(), member.profileImageUrl());
    }
}
