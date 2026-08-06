package com.a105.zani.member.application.updatedisplayname;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.member.application.exception.MemberNotFoundException;
import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;

@Service
public class UpdateDisplayNameService implements UpdateDisplayNameUseCase {

    private final MemberRepository memberRepository;

    public UpdateDisplayNameService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    @Transactional
    public UpdateDisplayNameResult updateDisplayName(UpdateDisplayNameCommand command) {
        Member member = memberRepository.findActiveById(command.memberId()).orElseThrow(MemberNotFoundException::new);
        Member updated = memberRepository.save(member.changeDisplayName(command.displayName()));
        return new UpdateDisplayNameResult(updated.displayName());
    }
}
