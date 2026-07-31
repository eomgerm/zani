package com.a105.zani.session.application.getlivestate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.port.RaisedHandChange;
import com.a105.zani.session.application.port.RaisedHandQueuePort;
import com.a105.zani.session.application.port.SessionEventSender;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.ChatMessage;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.ChatMessageRepository;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetLiveStateServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long VIEWER_USER = 7L;
    private static final Instant JOINED_AT = Instant.parse("2026-07-30T09:00:00Z");

    /** 강사 1명 + 학생 1명. 학생은 이미 퇴장했지만 채팅 이력에 남아 있다. */
    private static final SessionParticipant INSTRUCTOR = SessionParticipant.reconstitute(
            11L, SESSION_ID, VIEWER_USER, SessionParticipantRole.INSTRUCTOR, JOINED_AT, JOINED_AT);

    private static final SessionParticipant DEPARTED_STUDENT =
            SessionParticipant.reconstitute(22L, SESSION_ID, 8L, SessionParticipantRole.STUDENT, JOINED_AT, JOINED_AT);

    private static final Map<Long, String> NAMES = Map.of(VIEWER_USER, "박강사", 8L, "김민수");

    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();
    private final StubRaisedHands raisedHands = new StubRaisedHands();

    private GetLiveStateService service() {
        SessionParticipantRepository participants = new StubParticipants();
        ChatMessageRepository messages = new StubChatMessages();
        GetMemberDisplayNameUseCase displayName = query -> Optional.ofNullable(NAMES.get(query.memberId()));
        return new GetLiveStateService(resolveParticipant, participants, messages, displayName, raisedHands);
    }

    @Test
    void 비멤버에게는_이력을_보여주지_않는다() {
        resolveParticipant.member = false;

        assertThrows(
                NotSessionMemberException.class, () -> service().get(new GetLiveStateQuery(SESSION_ID, VIEWER_USER)));
    }

    /** 이미 퇴장한 학생의 옛 메시지도 이름이 붙어야 한다. LiveKit 참가자 목록만으로는 채울 수 없는 부분이다. */
    @Test
    void 디렉터리에는_퇴장한_참가자도_포함된다() {
        LiveStateResult result = service().get(new GetLiveStateQuery(SESSION_ID, VIEWER_USER));

        assertEquals(2, result.participants().size());
        assertEquals(
                List.of("p-11", "p-22"),
                result.participants().stream().map(SessionEventSender::identity).toList());
        assertEquals(
                List.of("박강사", "김민수"),
                result.participants().stream()
                        .map(SessionEventSender::displayName)
                        .toList());
        assertEquals(
                SessionParticipantRole.INSTRUCTOR,
                result.participants().getFirst().role());
    }

    /** eventId 는 실시간 스트림과 같은 값이어야 한다 — 스냅샷과 스트림이 겹칠 때 이 값으로 중복을 걸러낸다. */
    @Test
    void 채팅_이력은_eventId와_발신자_identity로_옮긴다() {
        LiveStateResult result = service().get(new GetLiveStateQuery(SESSION_ID, VIEWER_USER));

        assertEquals(2, result.chatMessages().size());
        LiveChatMessage first = result.chatMessages().getFirst();
        assertEquals("5001", first.eventId());
        assertEquals("p-22", first.senderIdentity());
        assertEquals(1_000L, first.occurredOffsetMs());
        assertEquals("먼저 보낸 메시지", first.content());
    }

    /** 순번이 목록의 인덱스라, 큐가 준 순서를 그대로 지나보내야 한다. 재정렬하면 늦게 든 사람이 앞설 수 있다. */
    @Test
    void 손든_참가자는_손든_순서대로_담긴다() {
        raisedHands.ordered = List.of("p-22", "p-11");

        assertEquals(
                List.of("p-22", "p-11"),
                service().get(new GetLiveStateQuery(SESSION_ID, VIEWER_USER)).raisedHandIdentities());
    }

    @Test
    void 손든_사람이_없으면_빈_목록이다() {
        assertTrue(service()
                .get(new GetLiveStateQuery(SESSION_ID, VIEWER_USER))
                .raisedHandIdentities()
                .isEmpty());
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
                    11L, SessionParticipantRole.INSTRUCTOR, JOINED_AT, JOINED_AT.plusSeconds(10_800));
        }
    }

    private static class StubRaisedHands implements RaisedHandQueuePort {
        private List<String> ordered = List.of();

        @Override
        public RaisedHandChange raise(long sessionId, String identity, long raisedAtMillis) {
            throw new UnsupportedOperationException("스냅샷은 쓰지 않는다.");
        }

        @Override
        public RaisedHandChange lower(long sessionId, String identity) {
            throw new UnsupportedOperationException("스냅샷은 쓰지 않는다.");
        }

        @Override
        public List<String> raisedInOrder(long sessionId) {
            assertEquals(SESSION_ID, sessionId);
            return ordered;
        }
    }

    private static class StubParticipants implements SessionParticipantRepository {
        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            assertEquals(SESSION_ID, sessionId);
            return List.of(INSTRUCTOR, DEPARTED_STUDENT);
        }

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            throw new UnsupportedOperationException("스냅샷은 멤버십 확인을 UseCase 에 맡긴다.");
        }

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            throw new UnsupportedOperationException("스냅샷은 단건을 읽지 않는다.");
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            throw new UnsupportedOperationException("스냅샷은 쓰지 않는다.");
        }
    }

    private static class StubChatMessages implements ChatMessageRepository {
        @Override
        public ChatMessage save(ChatMessage chatMessage) {
            throw new UnsupportedOperationException("스냅샷은 쓰지 않는다.");
        }

        @Override
        public Optional<ChatMessage> findById(Long id) {
            throw new UnsupportedOperationException("스냅샷은 단건을 읽지 않는다.");
        }

        @Override
        public List<ChatMessage> findRecentPublic(Long sessionId, int limit) {
            assertEquals(SESSION_ID, sessionId);
            return List.of(
                    ChatMessage.reconstitute(5001L, SESSION_ID, 22L, "먼저 보낸 메시지", 1_000L),
                    ChatMessage.reconstitute(5002L, SESSION_ID, 11L, "나중 메시지", 2_000L));
        }
    }
}
