package com.a105.zani.member.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.a105.zani.member.domain.model.Member;

public interface MemberRepository {

    Member save(Member member);

    Optional<Member> findById(Long id);

    Optional<Member> findByGoogleSubject(String googleSubject);

    /** 여러 회원을 한 번에 가져온다. 없는 ID 는 결과에서 빠지므로 개수가 요청과 다를 수 있다. */
    List<Member> findAllByIds(Collection<Long> ids);
}
