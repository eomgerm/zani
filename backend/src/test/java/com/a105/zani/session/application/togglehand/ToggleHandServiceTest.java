package com.a105.zani.session.application.togglehand;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.port.RaisedHandChange;
import com.a105.zani.session.application.port.RaisedHandQueuePort;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToggleHandServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long USER_ID = 7L;
    private static final long PARTICIPANT_ID = 11L;
    private static final Instant STARTED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant NOW = STARTED_AT.plusSeconds(90);
    private static final String CLIENT_EVENT_ID = "h-1";

    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();
    private final StubRaisedHands raisedHands = new StubRaisedHands();
    private final StubInteractionEvents history = new StubInteractionEvents();
    private final RecordingPublisher publisher = new RecordingPublisher();

    private ToggleHandService service() {
        GetMemberDisplayNameUseCase displayName = query -> Optional.of("김민수");
        return new ToggleHandService(
                resolveParticipant, displayName, raisedHands, history, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 손을_들면_큐에_넣고_이력을_남기고_브로드캐스트한다() {
        ToggleHandResult result = service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, true));

        assertTrue(result.raised());
        assertTrue(result.changed());
        assertEquals(List.of("p-11"), raisedHands.queue);

        assertEquals(1, history.saved.size());
        InteractionEvent recorded = history.saved.getFirst();
        assertEquals(InteractionEventType.HAND_RAISED, recorded.type());
        assertEquals(PARTICIPANT_ID, recorded.actorParticipantId());
        assertEquals(90_000L, recorded.occurredOffsetMs());

        assertEquals(1, publisher.events.size());
        SessionEvent published = publisher.events.getFirst();
        assertEquals(SessionEventType.HAND_RAISED, published.type());
        assertEquals(CLIENT_EVENT_ID, published.clientEventId());
        assertEquals("p-11", published.sender().identity());
        assertEquals(90_000L, published.occurredOffsetMs());
    }

    @Test
    void 손을_내리면_큐에서_빠지고_내림_이력이_남는다() {
        raisedHands.queue.add("p-11");

        ToggleHandResult result = service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, false));

        assertTrue(result.changed());
        assertFalse(result.raised());
        assertTrue(raisedHands.queue.isEmpty());
        assertEquals(InteractionEventType.HAND_LOWERED, history.saved.getFirst().type());
        assertEquals(SessionEventType.HAND_LOWERED, publisher.events.getFirst().type());
    }

    /** echo 를 놓친 클라이언트의 재시도다. 상태는 이미 맞으므로 이력은 늘리지 않되, 확인을 받지 못해 화면이 보내는 중으로 굳지 않도록 브로드캐스트는 다시 한다. */
    @Test
    void 이미_든_손을_다시_들면_이력은_안_남기고_알림만_다시_보낸다() {
        raisedHands.queue.add("p-11");

        ToggleHandResult result = service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, true));

        assertTrue(result.raised());
        assertFalse(result.changed());
        assertTrue(history.saved.isEmpty());
        assertEquals(1, publisher.events.size());
    }

    /** 먼저 든 순번을 지켜야 한다 — 두 번 눌렀다고 뒤로 밀리면 순서가 어긋난다. */
    @Test
    void 이미_든_손을_다시_들어도_순번은_바뀌지_않는다() {
        raisedHands.queue.add("p-22");
        raisedHands.queue.add("p-11");

        service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, true));

        assertEquals(List.of("p-22", "p-11"), raisedHands.queue);
    }

    @Test
    void 비멤버는_거절하고_본인에게만_알린다() {
        resolveParticipant.member = false;

        ToggleHandResult result = service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, true));

        assertTrue(result.rejected());
        assertTrue(publisher.events.isEmpty());
        assertEquals(1, publisher.rejections.size());
        assertEquals(CLIENT_EVENT_ID, publisher.rejections.getFirst().clientEventId());
    }

    /** 알릴 키가 없으니 통지도 못 한다. 상태를 건드리지 않고 버린다. */
    @Test
    void clientEventId가_없으면_아무것도_하지_않는다() {
        ToggleHandResult result = service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, " ", true));

        assertTrue(result.rejected());
        assertTrue(raisedHands.queue.isEmpty());
        assertTrue(publisher.events.isEmpty());
        assertTrue(publisher.rejections.isEmpty());
    }

    /**
     * 저장소가 죽었을 때 "이미 같은 상태였다"와 뭉뜽그리면, 아무것도 기록하지 못한 요청을 전 참가자에게 알리게 된다. 그러면 화면에는 손이 올라가 있는데 서버는 그 사실을 모르고, 재연결 스냅샷에서 조용히
     * 사라진다.
     */
    @Test
    void 큐에_기록하지_못하면_알리지_않고_본인에게_실패를_통지한다() {
        raisedHands.unavailable = true;

        ToggleHandResult result = service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, true));

        assertTrue(result.rejected());
        assertEquals("HAND_STATE_UNAVAILABLE", result.rejectionReason());
        assertTrue(publisher.events.isEmpty());
        assertTrue(history.saved.isEmpty());
        assertEquals(1, publisher.rejections.size());
        assertEquals(CLIENT_EVENT_ID, publisher.rejections.getFirst().clientEventId());
    }

    /** 손 내리기도 같다. 못 내렸는데 내렸다고 알리면 남의 화면에서만 손이 사라진다. */
    @Test
    void 손내리기도_기록하지_못하면_알리지_않는다() {
        raisedHands.queue.add("p-11");
        raisedHands.unavailable = true;

        ToggleHandResult result = service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, false));

        assertTrue(result.rejected());
        assertTrue(publisher.events.isEmpty());
        assertEquals(List.of("p-11"), raisedHands.queue);
    }

    /** 화면에는 손이 들려 있는데 요청은 실패한 상태를 만들지 않는다. 리포트 한 줄이 비는 쪽이 낫다. */
    @Test
    void 이력_저장이_실패해도_손들기는_성립한다() {
        history.failing = true;

        ToggleHandResult result = service().toggle(new ToggleHandCommand(SESSION_ID, USER_ID, CLIENT_EVENT_ID, true));

        assertTrue(result.changed());
        assertEquals(List.of("p-11"), raisedHands.queue);
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

    /** Sorted Set 의 ZADD NX / ZREM 을 순서 있는 목록으로 흉내 낸다. */
    private static class StubRaisedHands implements RaisedHandQueuePort {
        private final List<String> queue = new ArrayList<>();
        /** Redis 가 죽어 아무것도 기록하지 못하는 상태. */
        private boolean unavailable;

        @Override
        public RaisedHandChange raise(long sessionId, String identity, long raisedAtMillis) {
            if (unavailable) {
                return RaisedHandChange.UNAVAILABLE;
            }
            if (queue.contains(identity)) {
                return RaisedHandChange.UNCHANGED;
            }
            queue.add(identity);
            return RaisedHandChange.CHANGED;
        }

        @Override
        public RaisedHandChange lower(long sessionId, String identity) {
            if (unavailable) {
                return RaisedHandChange.UNAVAILABLE;
            }
            return queue.remove(identity) ? RaisedHandChange.CHANGED : RaisedHandChange.UNCHANGED;
        }

        @Override
        public List<String> raisedInOrder(long sessionId) {
            return List.copyOf(queue);
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
