package com.a105.zani.session.application.joinsession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionMember;
import com.a105.zani.session.domain.repository.SessionMemberRepository;
import com.a105.zani.session.domain.repository.SessionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionJoinServiceTest {

    private static final long INSTRUCTOR_ID = 1L;
    private static final long STUDENT_ID = 2L;
    private static final String INVITE_CODE = "ABCDEFGH";

    @Test
    void throwsWhenInviteCodeDoesNotMatchAnySession() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        SessionJoinService service = new SessionJoinService(
                sessionRepository, new InMemorySessionMemberRepository());

        assertThrows(
                SessionNotFoundException.class,
                () -> service.join(new JoinSessionCommand("NOTFOUND", STUDENT_ID)));
    }

    @Test
    void firstJoinCreatesANewStudentMembership() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        Session session = Session.start(
                10L, INSTRUCTOR_ID, "테스트 세션", INVITE_CODE, Instant.now());
        sessionRepository.save(session);
        InMemorySessionMemberRepository memberRepository = new InMemorySessionMemberRepository();
        SessionJoinService service = new SessionJoinService(sessionRepository, memberRepository);

        JoinSessionResult result = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(MemberRole.STUDENT, result.role());
        assertEquals(1, memberRepository.saveCount());
    }

    @Test
    void secondJoinIsIdempotentAndDoesNotCreateADuplicateMembership() {
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        Session session = Session.start(
                10L, INSTRUCTOR_ID, "테스트 세션", INVITE_CODE, Instant.now());
        sessionRepository.save(session);
        InMemorySessionMemberRepository memberRepository = new InMemorySessionMemberRepository();
        SessionJoinService service = new SessionJoinService(sessionRepository, memberRepository);

        JoinSessionResult first = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));
        JoinSessionResult second = service.join(new JoinSessionCommand(INVITE_CODE, STUDENT_ID));

        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(1, memberRepository.rowCount());
        assertEquals(2, memberRepository.saveCount());
    }

    private static class InMemorySessionRepository implements SessionRepository {

        private final Map<String, Session> byInviteCode = new HashMap<>();

        @Override
        public Session save(Session session) {
            byInviteCode.put(session.inviteCode(), session);
            return session;
        }

        @Override
        public Optional<Session> findByInviteCode(String inviteCode) {
            return Optional.ofNullable(byInviteCode.get(inviteCode));
        }
    }

    private static class InMemorySessionMemberRepository implements SessionMemberRepository {

        private final Map<String, SessionMember> store = new HashMap<>();
        private int saveCount = 0;

        @Override
        public Optional<SessionMember> findBySessionIdAndUserId(Long sessionId, Long userId) {
            return Optional.ofNullable(store.get(key(sessionId, userId)));
        }

        @Override
        public SessionMember save(SessionMember sessionMember) {
            saveCount++;
            store.put(key(sessionMember.sessionId(), sessionMember.userId()), sessionMember);
            return sessionMember;
        }

        int saveCount() {
            return saveCount;
        }

        int rowCount() {
            return store.size();
        }

        private String key(Long sessionId, Long userId) {
            return sessionId + ":" + userId;
        }
    }
}
