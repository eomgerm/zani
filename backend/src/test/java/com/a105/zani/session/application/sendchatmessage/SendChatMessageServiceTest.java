package com.a105.zani.session.application.sendchatmessage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.port.ChatIdempotencyPort;
import com.a105.zani.session.application.port.SessionEvent;
import com.a105.zani.session.application.port.SessionEventPublishPort;
import com.a105.zani.session.application.port.SessionEventRejection;
import com.a105.zani.session.application.port.SessionEventType;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.ChatMessage;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.ChatMessageRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendChatMessageServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long USER_ID = 7L;
    private static final long PARTICIPANT_ID = 499123L;
    private static final Instant SESSION_STARTED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-30T09:02:05.400Z");

    private final RecordingChatMessages messages = new RecordingChatMessages();
    private final InMemoryChatIdempotency idempotency = new InMemoryChatIdempotency();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();

    private SendChatMessageService service;

    @BeforeEach
    void setUp() {
        GetMemberDisplayNameUseCase displayName = query -> {
            assertEquals(USER_ID, query.memberId());
            return Optional.of("김민수");
        };
        service = new SendChatMessageService(
                resolveParticipant, displayName, messages, idempotency, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SendChatMessageCommand command(String clientEventId, String content) {
        return new SendChatMessageCommand(SESSION_ID, USER_ID, clientEventId, content);
    }

    @Test
    void 메시지를_저장하고_세션_구독자에게_브로드캐스트한다() {
        SendChatMessageResult result = service.send(command("c-1", "질문 있습니다"));

        assertFalse(result.duplicate());
        assertEquals(1, messages.saved.size());
        assertEquals(1, publisher.published.size());
        assertEquals(SESSION_ID, publisher.publishedSessionIds.getFirst());

        SessionEvent event = publisher.published.getFirst();
        assertEquals(SessionEventType.CHAT_MESSAGE, event.type());
        assertEquals("c-1", event.clientEventId());
        assertEquals(result.eventId(), event.eventId());
        assertEquals("질문 있습니다", event.payload().get("content"));
        assertEquals(NOW, event.deliveredAt());
    }

    /** eventId 는 따로 만든 값이 아니라 저장된 행의 식별자여야 한다. 갈라지면 스냅샷과 스트림의 중복 제거가 어긋난다. */
    @Test
    void eventId는_저장된_메시지의_식별자와_같다() {
        SendChatMessageResult result = service.send(command("c-1", "안녕하세요"));

        assertEquals(String.valueOf(messages.saved.getFirst().id()), result.eventId());
    }

    /** 리포트 타임라인이 이 값을 축으로 쓴다. 클라이언트가 보낸 시각이 아니라 서버가 계산한 값이어야 한다. */
    @Test
    void 발생_시각은_수업_시작으로부터의_경과_시간으로_계산한다() {
        service.send(command("c-1", "안녕하세요"));

        assertEquals(125_400L, messages.saved.getFirst().occurredOffsetMs());
        assertEquals(125_400L, publisher.published.getFirst().occurredOffsetMs());
    }

    /** 프론트가 LiveKit 참가자 목록과 이 이벤트를 이어 붙이는 키다. */
    @Test
    void 발신자_identity는_LiveKit_participant_identity와_같은_형식이다() {
        service.send(command("c-1", "안녕하세요"));

        assertEquals(
                "p-" + PARTICIPANT_ID, publisher.published.getFirst().sender().identity());
        assertEquals("김민수", publisher.published.getFirst().sender().displayName());
        assertEquals(
                SessionParticipantRole.STUDENT,
                publisher.published.getFirst().sender().role());
    }

    @Test
    void 같은_clientEventId로_다시_보내면_두_번_저장하지_않는다() {
        SendChatMessageResult first = service.send(command("c-1", "안녕하세요"));
        SendChatMessageResult retry = service.send(command("c-1", "안녕하세요"));

        assertTrue(retry.duplicate());
        assertEquals(first.eventId(), retry.eventId());
        assertEquals(1, messages.saved.size());
    }

    /** 조용히 넘기면 첫 전송의 echo 를 놓친 클라이언트가 재시도해도 확인을 받지 못해 보내는 중·실패 상태로 영원히 남는다. 받는 쪽은 eventId 로 거르므로 다시 뿌려도 중복이 생기지 않는다. */
    @Test
    void 재시도에도_확정을_다시_알린다() {
        SendChatMessageResult first = service.send(command("c-1", "안녕하세요"));
        service.send(command("c-1", "안녕하세요"));

        assertEquals(2, publisher.published.size());
        SessionEvent republished = publisher.published.getLast();
        assertEquals(first.eventId(), republished.eventId());
        assertEquals("c-1", republished.clientEventId());
        assertEquals("안녕하세요", republished.payload().get("content"));
    }

    /** 선점만 남고 행이 없으면(저장 실패 뒤 되돌리기까지 실패) 재시도를 새 전송으로 처리해 메시지가 사라지지 않게 한다. */
    @Test
    void 선점만_남고_행이_없으면_새_전송으로_처리한다() {
        messages.failNextSave = true;
        assertThrows(IllegalStateException.class, () -> service.send(command("c-1", "안녕하세요")));
        idempotency.claims.put("100:c-1", "999999"); // 되돌리기가 실패해 선점만 남은 상태를 만든다.

        SendChatMessageResult retry = service.send(command("c-1", "안녕하세요"));

        assertFalse(retry.duplicate());
        assertEquals(1, messages.saved.size());
        assertEquals(1, publisher.published.size());
    }

    /** 선점을 되돌리지 않으면 같은 clientEventId 의 재시도가 영구히 중복으로 걸러져 메시지가 사라진다. */
    @Test
    void 저장이_실패하면_멱등_선점을_되돌려_재시도가_통과하게_한다() {
        messages.failNextSave = true;

        assertThrows(IllegalStateException.class, () -> service.send(command("c-1", "안녕하세요")));
        assertTrue(idempotency.claims.isEmpty());

        SendChatMessageResult retry = service.send(command("c-1", "안녕하세요"));
        assertFalse(retry.duplicate());
        assertEquals(1, messages.saved.size());
    }

    @Test
    void 비멤버의_전송은_저장도_브로드캐스트도_하지_않고_거절한다() {
        resolveParticipant.member = false;

        assertThrows(NotSessionMemberException.class, () -> service.send(command("c-1", "안녕하세요")));
        assertTrue(messages.saved.isEmpty());
        assertTrue(publisher.published.isEmpty());
    }

    @Test
    void 앞뒤_공백은_잘라_저장한다() {
        service.send(command("c-1", "  질문 있습니다  "));

        assertEquals("질문 있습니다", messages.saved.getFirst().content());
    }

    private class StubResolveSessionParticipant implements ResolveSessionParticipantUseCase {
        private boolean member = true;

        @Override
        public ResolveSessionParticipantResult resolve(
                com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery query) {
            if (!member) {
                throw new NotSessionMemberException();
            }
            assertEquals(SESSION_ID, query.sessionId());
            return new ResolveSessionParticipantResult(
                    PARTICIPANT_ID,
                    SessionParticipantRole.STUDENT,
                    SESSION_STARTED_AT,
                    SESSION_STARTED_AT.plusSeconds(10_800));
        }
    }

    private static class RecordingChatMessages implements ChatMessageRepository {
        private final List<ChatMessage> saved = new ArrayList<>();
        private boolean failNextSave;

        @Override
        public ChatMessage save(ChatMessage chatMessage) {
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("저장 실패");
            }
            saved.add(chatMessage);
            return chatMessage;
        }

        @Override
        public Optional<ChatMessage> findById(Long id) {
            return saved.stream().filter(m -> m.id().equals(id)).findFirst();
        }

        @Override
        public List<ChatMessage> findRecentPublic(Long sessionId, int limit) {
            throw new UnsupportedOperationException("전송 경로는 이력 목록을 읽지 않는다.");
        }
    }

    private static class InMemoryChatIdempotency implements ChatIdempotencyPort {
        private final Map<String, String> claims = new HashMap<>();

        @Override
        public Optional<String> claim(long sessionId, String clientEventId, String eventId) {
            String existing = claims.putIfAbsent(key(sessionId, clientEventId), eventId);
            return Optional.ofNullable(existing);
        }

        @Override
        public void release(long sessionId, String clientEventId) {
            claims.remove(key(sessionId, clientEventId));
        }

        private static String key(long sessionId, String clientEventId) {
            return sessionId + ":" + clientEventId;
        }
    }

    private static class RecordingPublisher implements SessionEventPublishPort {
        private final List<SessionEvent> published = new ArrayList<>();
        private final List<Long> publishedSessionIds = new ArrayList<>();

        @Override
        public void publishToSession(long sessionId, SessionEvent event) {
            publishedSessionIds.add(sessionId);
            published.add(event);
        }

        @Override
        public void publishRejection(String memberId, SessionEventRejection rejection) {
            throw new UnsupportedOperationException("전송 경로는 거절을 내보내지 않는다(컨트롤러 책임).");
        }
    }
}
