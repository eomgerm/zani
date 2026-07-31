package com.a105.zani.session.application.sendreaction;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.port.ReactionRateLimitPort;
import com.a105.zani.session.application.port.SessionEvent;
import com.a105.zani.session.application.port.SessionEventPublishPort;
import com.a105.zani.session.application.port.SessionEventRejection;
import com.a105.zani.session.application.port.SessionEventType;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.InteractionEvent;
import com.a105.zani.session.domain.model.InteractionEventType;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.InteractionEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendReactionServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long USER_ID = 7L;
    private static final long PARTICIPANT_ID = 11L;
    private static final Instant STARTED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant NOW = STARTED_AT.plusSeconds(90);
    private static final String CLIENT_EVENT_ID = "r-1";

    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();
    private final StubRateLimit rateLimit = new StubRateLimit();
    private final StubInteractionEvents history = new StubInteractionEvents();
    private final RecordingPublisher publisher = new RecordingPublisher();

    private SendReactionService service() {
        GetMemberDisplayNameUseCase displayName = query -> Optional.of("김민수");
        return new SendReactionService(
                resolveParticipant, displayName, rateLimit, history, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SendReactionResult send(String reaction) {
        return service().send(new SendReactionCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, reaction));
    }

    @Test
    void 반응을_뿌리고_이력을_남긴다() {
        SendReactionResult result = send("CLAP");

        assertNotNull(result.eventId());

        assertEquals(1, publisher.events.size());
        SessionEvent published = publisher.events.getFirst();
        assertEquals(SessionEventType.REACTION, published.type());
        assertEquals(result.eventId(), published.eventId());
        assertEquals(CLIENT_EVENT_ID, published.clientEventId());
        assertEquals("p-11", published.sender().identity());
        assertEquals(90_000L, published.occurredOffsetMs());
        assertEquals("CLAP", published.payload().get("reaction"));

        assertEquals(1, history.saved.size());
        InteractionEvent recorded = history.saved.getFirst();
        assertEquals(InteractionEventType.REACTION, recorded.type());
        assertEquals(PARTICIPANT_ID, recorded.actorParticipantId());
        assertEquals("CLAP", recorded.payload().get("reaction"));
    }

    /** 대소문자까지 클라이언트에 맞추라고 요구할 이유는 없다. 저장·전파되는 값은 항상 정규화된 이름이다. */
    @Test
    void 종류_이름은_대소문자를_가리지_않는다() {
        send("clap");

        assertEquals("CLAP", publisher.events.getFirst().payload().get("reaction"));
    }

    /** 서버가 받은 문자열이 전 참가자 화면에 그대로 뜬다. 모르는 값은 통과시키지 않는다. */
    @Test
    void 모르는_종류는_거절하고_아무것도_뿌리지_않는다() {
        SendReactionResult result = send("<img src=x>");

        assertTrue(result.rejected());
        assertEquals("UNKNOWN_REACTION", result.rejectionReason());
        assertTrue(publisher.events.isEmpty());
        assertTrue(history.saved.isEmpty());
        assertEquals(1, publisher.rejections.size());
    }

    @Test
    void 연타_제한에_걸리면_거절하고_본인에게만_알린다() {
        rateLimit.allow = false;

        SendReactionResult result = send("LIKE");

        assertTrue(result.rejected());
        assertEquals("TOO_MANY_REACTIONS", result.rejectionReason());
        assertTrue(publisher.events.isEmpty());
        assertTrue(history.saved.isEmpty());
        assertEquals(CLIENT_EVENT_ID, publisher.rejections.getFirst().clientEventId());
    }

    /** 연타 제한을 멤버십보다 뒤에 둔다 — 비멤버가 남의 세션 키로 제한 카운터를 소모하게 두지 않는다. */
    @Test
    void 비멤버는_연타_제한을_건드리지_않고_거절된다() {
        resolveParticipant.member = false;

        assertTrue(send("LIKE").rejected());
        assertEquals(0, rateLimit.attempts);
    }

    @Test
    void clientEventId가_없으면_아무것도_하지_않는다() {
        SendReactionResult result = service().send(new SendReactionCommand(SESSION_ID, USER_ID, " ", "LIKE"));

        assertTrue(result.rejected());
        assertTrue(publisher.events.isEmpty());
        assertTrue(publisher.rejections.isEmpty());
    }

    /** 이미 뿌려진 반응은 되돌릴 수 없다. 되돌릴 수 없는 걸 실패로 알리면 클라이언트만 혼란스럽다. */
    @Test
    void 이력_저장이_실패해도_전송은_성립한다() {
        history.failing = true;

        assertNotNull(send("HEART").eventId());
        assertEquals(1, publisher.events.size());
    }

    private class StubResolveSessionParticipant implements ResolveSessionParticipantUseCase {
        private boolean member = true;

        @Override
        public ResolveSessionParticipantResult resolve(ResolveSessionParticipantQuery query) {
            if (!member) {
                throw new NotSessionMemberException();
            }
            assertEquals(SESSION_ID, query.sessionId());
            return new ResolveSessionParticipantResult(
                    PARTICIPANT_ID, SessionParticipantRole.STUDENT, STARTED_AT, STARTED_AT.plusSeconds(10_800));
        }
    }

    private static class StubRateLimit implements ReactionRateLimitPort {
        private boolean allow = true;
        private int attempts;

        @Override
        public boolean tryAcquire(long sessionId, String identity) {
            attempts += 1;
            assertEquals("p-11", identity);
            return allow;
        }
    }

    private static class StubInteractionEvents implements InteractionEventRepository {
        private final List<InteractionEvent> saved = new ArrayList<>();
        private boolean failing;

        @Override
        public InteractionEvent save(InteractionEvent interactionEvent) {
            if (failing) {
                throw new IllegalStateException("이력 저장 실패");
            }
            saved.add(interactionEvent);
            return interactionEvent;
        }
    }

    private static class RecordingPublisher implements SessionEventPublishPort {
        private final List<SessionEvent> events = new ArrayList<>();
        private final List<SessionEventRejection> rejections = new ArrayList<>();

        @Override
        public void publishToSession(long sessionId, SessionEvent event) {
            assertEquals(SESSION_ID, sessionId);
            events.add(event);
        }

        @Override
        public void publishRejection(String memberId, SessionEventRejection rejection) {
            assertEquals(String.valueOf(USER_ID), memberId);
            rejections.add(rejection);
        }
    }
}
