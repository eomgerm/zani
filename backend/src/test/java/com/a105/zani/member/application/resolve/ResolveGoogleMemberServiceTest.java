package com.a105.zani.member.application.resolve;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.a105.zani.member.application.exception.DuplicateGoogleSubjectException;
import com.a105.zani.member.domain.model.Member;
import com.a105.zani.member.domain.repository.MemberRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolveGoogleMemberServiceTest {

    private static final String GOOGLE_SUBJECT = "google-subject-1";
    private static final ResolveGoogleMemberCommand COMMAND =
            new ResolveGoogleMemberCommand(GOOGLE_SUBJECT, "user@example.com", "User", "https://example.com/pic.png");

    @Test
    void registersANewMemberWhenNoMemberExistsForTheGoogleSubject() {
        InMemoryMemberRepository repository = new InMemoryMemberRepository();
        ResolveGoogleMemberService service = new ResolveGoogleMemberService(repository);

        ResolveGoogleMemberResult result = service.resolve(COMMAND);

        assertTrue(result.newMember());
        assertEquals("user@example.com", result.email());
        assertEquals("User", result.displayName());
        assertEquals("https://example.com/pic.png", result.profileImageUrl());
        assertEquals(1, repository.saveCount());
        assertEquals(
                GOOGLE_SUBJECT,
                repository.findByGoogleSubject(GOOGLE_SUBJECT).orElseThrow().googleSubject());
    }

    @Test
    void returnsTheExistingMemberWithoutSavingWhenAlreadyRegistered() {
        InMemoryMemberRepository repository = new InMemoryMemberRepository();
        Member existing = repository.save(Member.register(99L, GOOGLE_SUBJECT, "user@example.com", "User", null));

        ResolveGoogleMemberService service = new ResolveGoogleMemberService(repository);
        ResolveGoogleMemberResult result = service.resolve(COMMAND);

        assertFalse(result.newMember());
        assertEquals(existing.id(), result.memberId());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void fallsBackToTheWinnerWhenTwoRegistrationsRaceForTheSameGoogleSubject() {
        RacingMemberRepository repository = new RacingMemberRepository(GOOGLE_SUBJECT);
        ResolveGoogleMemberService service = new ResolveGoogleMemberService(repository);

        ResolveGoogleMemberResult result = service.resolve(COMMAND);

        assertFalse(result.newMember());
        assertEquals(repository.winnerId(), result.memberId());
    }

    private static class InMemoryMemberRepository implements MemberRepository {

        private final Map<String, Member> membersByGoogleSubject = new HashMap<>();
        private final AtomicInteger saveCount = new AtomicInteger();

        @Override
        public Member save(Member member) {
            saveCount.incrementAndGet();
            membersByGoogleSubject.put(member.googleSubject(), member);
            return member;
        }

        @Override
        public Optional<Member> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public Optional<Member> findByGoogleSubject(String googleSubject) {
            return Optional.ofNullable(membersByGoogleSubject.get(googleSubject));
        }

        int saveCount() {
            return saveCount.get();
        }
    }

    /**
     * Simulates another request already having inserted the row: the first {@code findByGoogleSubject} misses,
     * {@code save} then loses the race, and the follow-up {@code findByGoogleSubject} must see the winner.
     */
    private static class RacingMemberRepository implements MemberRepository {

        private final Member winner;
        private boolean saveAttempted = false;

        private RacingMemberRepository(String googleSubject) {
            this.winner = Member.register(42L, googleSubject, "user@example.com", "User", null);
        }

        @Override
        public Member save(Member member) {
            saveAttempted = true;
            throw new DuplicateGoogleSubjectException(new IllegalStateException("duplicate"));
        }

        @Override
        public Optional<Member> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public Optional<Member> findByGoogleSubject(String googleSubject) {
            return saveAttempted ? Optional.of(winner) : Optional.empty();
        }

        Long winnerId() {
            return winner.id();
        }
    }
}
