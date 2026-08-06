package com.a105.zani.member.application.get;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetMemberDisplayNameServiceTest {

    private static final long MEMBER_ID = 456L;

    private static GetMemberDisplayNameService serviceReturning(Optional<Member> member) {
        return new GetMemberDisplayNameService(new MemberRepository() {
            @Override
            public Member save(Member candidate) {
                return candidate;
            }

            @Override
            public Optional<Member> findById(Long id) {
                return MEMBER_ID == id ? member : Optional.empty();
            }

            @Override
            public Optional<Member> findActiveById(Long id) {
                return findById(id);
            }

            @Override
            public Optional<Member> findByGoogleSubject(String googleSubject) {
                return Optional.empty();
            }

            @Override
            public java.util.List<Member> findAllByIds(java.util.Collection<Long> ids) {
                return java.util.List.of();
            }

            @Override
            public boolean withdraw(Long id, java.time.Instant deletedAt) {
                throw new UnsupportedOperationException("not needed for this test");
            }
        });
    }

    @Test
    void returnsTheDisplayNameOfAnExistingMember() {
        GetMemberDisplayNameService service = serviceReturning(
                Optional.of(Member.reconstitute(MEMBER_ID, "google-sub", "user@zani.local", "홍길동", null, true)));

        assertEquals(
                "홍길동",
                service.getDisplayName(new GetMemberDisplayNameQuery(MEMBER_ID)).orElseThrow());
    }

    @Test
    void returnsEmptyWhenTheMemberDoesNotExist() {
        GetMemberDisplayNameService service = serviceReturning(Optional.empty());

        assertTrue(
                service.getDisplayName(new GetMemberDisplayNameQuery(MEMBER_ID)).isEmpty());
    }
}
