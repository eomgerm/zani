package com.a105.zani.member.application.updatereportemail;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.member.application.exception.MemberNotFoundException;
import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;

@Service
public class UpdateReportEmailService implements UpdateReportEmailUseCase {

    private final MemberRepository memberRepository;

    public UpdateReportEmailService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    @Transactional
    public UpdateReportEmailResult updateReportEmail(UpdateReportEmailCommand command) {
        Member member = memberRepository.findActiveById(command.memberId()).orElseThrow(MemberNotFoundException::new);
        Member updated = memberRepository.save(member.changeReportEmailEnabled(command.reportEmailEnabled()));
        return new UpdateReportEmailResult(updated.reportEmailEnabled());
    }
}
