package com.a105.zani.session.presentation.controller;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.sendchatmessage.SendChatMessageCommand;
import com.a105.zani.session.application.sendchatmessage.SendChatMessageResult;
import com.a105.zani.session.application.sendchatmessage.SendChatMessageUseCase;
import com.a105.zani.session.application.sendreaction.SendReactionCommand;
import com.a105.zani.session.application.sendreaction.SendReactionResult;
import com.a105.zani.session.application.sendreaction.SendReactionUseCase;
import com.a105.zani.session.application.togglehand.ToggleHandCommand;
import com.a105.zani.session.application.togglehand.ToggleHandResult;
import com.a105.zani.session.application.togglehand.ToggleHandUseCase;
import com.a105.zani.session.presentation.request.ChatMessageRequest;
import com.a105.zani.session.presentation.request.HandRequest;
import com.a105.zani.session.presentation.request.ReactionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOMP 진입점의 위임 경계 검증.
 *
 * <p>검증·거절·저장 판단은 모두 유스케이스가 하므로 여기서는 <b>무엇을 넘기는지</b>만 본다({@code SendChatMessageServiceTest} 가 판단을 다룬다). 특히 세션 ID 를
 * 목적지에서, 보낸 사람을 인증 주체에서 가져오는지가 중요하다 — 본문으로 받으면 남의 이름으로 보낼 수 있다.
 */
class SessionChannelControllerTest {

    private static final long SESSION_ID = 100L;
    private static final String MEMBER_ID = "7";
    private static final Principal PRINCIPAL = () -> MEMBER_ID;

    private final RecordingSendChatMessage useCase = new RecordingSendChatMessage();
    private final RecordingToggleHand handUseCase = new RecordingToggleHand();
    private final RecordingSendReaction reactionUseCase = new RecordingSendReaction();
    private final SessionChannelController controller =
            new SessionChannelController(useCase, handUseCase, reactionUseCase);

    private void send(String clientEventId, String content) {
        controller.chat(SESSION_ID, new ChatMessageRequest(clientEventId, content), PRINCIPAL);
    }

    @Test
    void 목적지의_세션과_인증_주체를_커맨드에_담아_넘긴다() {
        send("c-1", "질문 있습니다");

        assertEquals(1, useCase.commands.size());
        SendChatMessageCommand command = useCase.commands.getFirst();
        assertEquals(SESSION_ID, command.sessionId());
        assertEquals(Long.parseLong(MEMBER_ID), command.userId());
        assertEquals("c-1", command.clientEventId());
    }

    /** 다듬기·상한 검사는 유스케이스가 한다. 컨트롤러가 미리 손대면 판단이 두 곳으로 갈린다. */
    @Test
    void 본문을_손대지_않고_그대로_넘긴다() {
        send("c-1", "  질문 있습니다  ");

        assertEquals("  질문 있습니다  ", useCase.commands.getFirst().content());
    }

    @Test
    void 손들기도_목적지의_세션과_인증_주체를_커맨드에_담아_넘긴다() {
        controller.hand(SESSION_ID, new HandRequest("h-1", true), PRINCIPAL);

        assertEquals(1, handUseCase.commands.size());
        ToggleHandCommand command = handUseCase.commands.getFirst();
        assertEquals(SESSION_ID, command.sessionId());
        assertEquals(Long.parseLong(MEMBER_ID), command.userId());
        assertEquals("h-1", command.clientEventId());
        assertTrue(command.raised());
    }

    /** 종류 해석·거절은 유스케이스가 한다. 컨트롤러가 미리 걸러내면 판단이 두 곳으로 갈린다. */
    @Test
    void 반응_종류를_손대지_않고_그대로_넘긴다() {
        controller.reaction(SESSION_ID, new ReactionRequest("r-1", "clap"), PRINCIPAL);

        assertEquals(1, reactionUseCase.commands.size());
        SendReactionCommand command = reactionUseCase.commands.getFirst();
        assertEquals(SESSION_ID, command.sessionId());
        assertEquals(Long.parseLong(MEMBER_ID), command.userId());
        assertEquals("clap", command.reaction());
    }

    private static class RecordingSendChatMessage implements SendChatMessageUseCase {
        private final List<SendChatMessageCommand> commands = new ArrayList<>();

        @Override
        public SendChatMessageResult send(SendChatMessageCommand command) {
            commands.add(command);
            return SendChatMessageResult.sent("5001");
        }
    }

    private static class RecordingToggleHand implements ToggleHandUseCase {
        private final List<ToggleHandCommand> commands = new ArrayList<>();

        @Override
        public ToggleHandResult toggle(ToggleHandCommand command) {
            commands.add(command);
            return ToggleHandResult.applied(command.raised(), true);
        }
    }

    private static class RecordingSendReaction implements SendReactionUseCase {
        private final List<SendReactionCommand> commands = new ArrayList<>();

        @Override
        public SendReactionResult send(SendReactionCommand command) {
            commands.add(command);
            return SendReactionResult.sent("5002");
        }
    }
}
