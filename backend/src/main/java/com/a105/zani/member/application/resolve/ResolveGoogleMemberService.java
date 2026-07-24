package com.a105.zani.member.application.resolve;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.member.application.exception.DuplicateGoogleSubjectException;
import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;

@Service
public class ResolveGoogleMemberService implements ResolveGoogleMemberUseCase {

    private final MemberRepository memberRepository;

    public ResolveGoogleMemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    @Transactional
    public ResolveGoogleMemberResult resolve(ResolveGoogleMemberCommand command) {
        return memberRepository
                .findByGoogleSubject(command.googleSubject())
                .map(existing -> toResult(existing, false))
                .orElseGet(() -> registerNewMember(command));
    }

    private ResolveGoogleMemberResult registerNewMember(ResolveGoogleMemberCommand command) {
        try {
            Member registered = memberRepository.save(Member.register(
                    TsidGenerator.generate(),
                    command.googleSubject(),
                    command.email(),
                    command.displayName(),
                    command.profileImageUrl()));
            return toResult(registered, true);
        } catch (DuplicateGoogleSubjectException raceLost) {
            Member existing = memberRepository
                    .findByGoogleSubject(command.googleSubject())
                    .orElseThrow(() -> raceLost);
            return toResult(existing, false);
        }
    }

    private ResolveGoogleMemberResult toResult(Member member, boolean newMember) {
        return new ResolveGoogleMemberResult(
                member.id(), member.email(), member.displayName(), member.profileImageUrl(), newMember);
    }
}
