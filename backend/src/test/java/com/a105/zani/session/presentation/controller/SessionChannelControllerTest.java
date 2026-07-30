package com.a105.zani.session.presentation.controller;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.port.SessionEvent;
import com.a105.zani.session.application.port.SessionEventPublishPort;
import com.a105.zani.session.application.port.SessionEventRejection;
import com.a105.zani.session.application.sendchatmessage.SendChatMessageCommand;
import com.a105.zani.session.application.sendchatmessage.SendChatMessageResult;
import com.a105.zani.session.application.sendchatmessage.SendChatMessageUseCase;
import com.a105.zani.session.domain.model.ChatMessage;
import com.a105.zani.session.presentation.request.ChatMessageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionChannelControllerTest {

    private static final long SESSION_ID = 100L;
    private static final String MEMBER_ID = "7";
    private static final Principal PRINCIPAL = () -> MEMBER_ID;

    private final RecordingSendChatMessage useCase = new RecordingSendChatMessage();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final SessionChannelController controller = new SessionChannelController(useCase, publisher);

    private void send(String clientEventId, String content) {
        controller.chat(SESSION_ID, new ChatMessageRequest(clientEventId, content), PRINCIPAL);
    }

    @Test
    void 본문을_다듬어_유스케이스에_넘긴다() {
        send("c-1", "  질문 있습니다  ");

        assertEquals(1, useCase.commands.size());
        SendChatMessageCommand command = useCase.commands.getFirst();
        assertEquals(SESSION_ID, command.sessionId());
        assertEquals(Long.parseLong(MEMBER_ID), command.userId());
        assertEquals("c-1", command.clientEventId());
        assertEquals("질문 있습니다", command.content());
        assertTrue(publisher.rejections.isEmpty());
    }

    @Test
    void 빈_본문은_보내지_않고_보낸_사람에게만_거절을_알린다() {
        send("c-1", "   ");

        assertTrue(useCase.commands.isEmpty());
        assertEquals(1, publisher.rejections.size());
        assertEquals(MEMBER_ID, publisher.rejectedTo.getFirst());
        assertEquals("c-1", publisher.rejections.getFirst().clientEventId());
        assertEquals("EMPTY_CONTENT", publisher.rejections.getFirst().reason());
    }

    @Test
    void 상한을_넘긴_본문은_보내지_않는다() {
        send("c-1", "a".repeat(ChatMessage.MAX_CONTENT_LENGTH + 1));

        assertTrue(useCase.commands.isEmpty());
        assertEquals("CONTENT_TOO_LONG", publisher.rejections.getFirst().reason());
    }

    @Test
    void 상한과_같은_길이의_본문은_통과시킨다() {
        send("c-1", "a".repeat(ChatMessage.MAX_CONTENT_LENGTH));

        assertEquals(1, useCase.commands.size());
        assertTrue(publisher.rejections.isEmpty());
    }

    /** 어느 전송이 실패했는지 짚어 줄 키가 없으면 통지해도 클라이언트가 쓸 수 없다. */
    @Test
    void clientEventId가_없으면_거절을_알리지_않고_버린다() {
        send(" ", "질문 있습니다");

        assertTrue(useCase.commands.isEmpty());
        assertTrue(publisher.rejections.isEmpty());
    }

    /** 이미 정의된 오류 코드를 그대로 내려 클라이언트가 문구를 고르게 한다. */
    @Test
    void 업무_예외는_오류_코드를_담아_거절로_알린다() {
        useCase.failure = new NotSessionMemberException();

        send("c-1", "질문 있습니다");

        assertEquals(
                new NotSessionMemberException().errorCode().code(),
                publisher.rejections.getFirst().reason());
        assertEquals("c-1", publisher.rejections.getFirst().clientEventId());
    }

    @Test
    void 예상치_못한_실패도_거절로_알린다() {
        useCase.failure = new IllegalStateException("DB 연결 끊김");

        send("c-1", "질문 있습니다");

        assertEquals("SEND_FAILED", publisher.rejections.getFirst().reason());
    }

    private static class RecordingSendChatMessage implements SendChatMessageUseCase {
        private final List<SendChatMessageCommand> commands = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public SendChatMessageResult send(SendChatMessageCommand command) {
            if (failure != null) {
                throw failure;
            }
            commands.add(command);
            return new SendChatMessageResult("5001", false);
        }
    }

    private static class RecordingPublisher implements SessionEventPublishPort {
        private final List<SessionEventRejection> rejections = new ArrayList<>();
        private final List<String> rejectedTo = new ArrayList<>();

        @Override
        public void publishToSession(long sessionId, SessionEvent event) {
            throw new UnsupportedOperationException("브로드캐스트는 유스케이스가 한다.");
        }

        @Override
        public void publishRejection(String memberId, SessionEventRejection rejection) {
            rejectedTo.add(memberId);
            rejections.add(rejection);
        }
    }
}
