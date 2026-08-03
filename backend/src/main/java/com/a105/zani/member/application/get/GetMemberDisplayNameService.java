package com.a105.zani.member.application.get;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> getDisplayNames(GetMemberDisplayNamesQuery query) {
        if (query.memberIds().isEmpty()) {
            // 빈 IN 절은 DB 마다 다루는 방식이 달라 아예 묻지 않는다.
            return Map.of();
        }
        return memberRepository.findAllByIds(query.memberIds()).stream()
                .collect(Collectors.toMap(Member::id, Member::displayName));
    }
}
