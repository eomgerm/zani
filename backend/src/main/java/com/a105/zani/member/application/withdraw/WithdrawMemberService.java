package com.a105.zani.member.application.withdraw;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.member.application.exception.MemberNotFoundException;
import com.a105.zani.member.domain.repository.MemberRepository;

@Service
public class WithdrawMemberService implements WithdrawMemberUseCase {

    private final MemberRepository memberRepository;
    private final Clock clock;

    public WithdrawMemberService(MemberRepository memberRepository, Clock clock) {
        this.memberRepository = memberRepository;
        this.clock = clock;
    }

    /** 이미 탈퇴한 회원은 조건부 UPDATE 가 0 건을 돌려주므로, 읽고 확인하는 단계 없이 그대로 404 로 답한다. */
    @Override
    @Transactional
    public void withdraw(WithdrawMemberCommand command) {
        if (!memberRepository.withdraw(command.memberId(), clock.instant())) {
            throw new MemberNotFoundException();
        }
    }
}
