package com.a105.zani.member.application.getcurrent;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.member.application.exception.MemberNotFoundException;
import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetCurrentMemberServiceTest {

    @Test
    void returnsTheMemberProfileWhenFound() {
        Member member = Member.reconstitute(7L, "google-sub-1", "user@example.com", "User", "https://pic");
        GetCurrentMemberService service = new GetCurrentMemberService(new StubMemberRepository(member));

        GetCurrentMemberResult result = service.getCurrentMember(new GetCurrentMemberQuery(7L));

        assertEquals("user@example.com", result.email());
        assertEquals("User", result.displayName());
        assertEquals("https://pic", result.profileImageUrl());
    }

    @Test
    void throwsWhenTheMemberDoesNotExist() {
        GetCurrentMemberService service = new GetCurrentMemberService(new StubMemberRepository(null));

        assertThrows(MemberNotFoundException.class, () -> service.getCurrentMember(new GetCurrentMemberQuery(404L)));
    }

    private static class StubMemberRepository implements MemberRepository {

        private final Member member;

        private StubMemberRepository(Member member) {
            this.member = member;
        }

        @Override
        public Member save(Member member) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public Optional<Member> findById(Long id) {
            return Optional.ofNullable(member);
        }

        @Override
        public Optional<Member> findByGoogleSubject(String googleSubject) {
            throw new UnsupportedOperationException("not needed for this test");
        }
    }
}
