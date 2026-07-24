package com.a105.zani.member.domain.repository;

import java.util.Optional;

import com.a105.zani.member.domain.model.Member;

public interface MemberRepository {

    Member save(Member member);

    Optional<Member> findByGoogleSubject(String googleSubject);
}
