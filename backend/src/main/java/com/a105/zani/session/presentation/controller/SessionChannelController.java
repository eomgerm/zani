package com.a105.zani.session.presentation.controller;

import java.security.Principal;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import com.a105.zani.session.application.sendchatmessage.SendChatMessageCommand;
import com.a105.zani.session.application.sendchatmessage.SendChatMessageUseCase;
import com.a105.zani.session.application.sendreaction.SendReactionCommand;
import com.a105.zani.session.application.sendreaction.SendReactionUseCase;
import com.a105.zani.session.application.togglehand.ToggleHandCommand;
import com.a105.zani.session.application.togglehand.ToggleHandUseCase;
import com.a105.zani.session.presentation.request.ChatMessageRequest;
import com.a105.zani.session.presentation.request.HandRequest;
import com.a105.zani.session.presentation.request.ReactionRequest;

/**
 * 업무 이벤트 STOMP 진입점. 화면 공유(65), 강제 음소거(66)가 여기에 매핑을 추가한다.
 *
 * <p>세션 ID 는 목적지에서, 보낸 사람은 인증된 STOMP 주체에서 가져온다. 본문으로 받으면 남의 이름으로 보낼 수 있다.
 *
 * <p><b>검증과 거절 통지는 유스케이스가 맡는다.</b> STOMP 는 돌려줄 상태 코드가 없어 거절도 예외가 아니라 결과로 다뤄야 하는데, 무엇을 거절하고 어떤 사유를 붙일지는 업무 규칙이다. 통지
 * 경로(발행 포트)도 application 이 소유한 것이라 presentation 이 직접 잡으면 계층 방향이 뒤집힌다(ARCH-002).
 */
@Controller
public class SessionChannelController {

    private final SendChatMessageUseCase sendChatMessageUseCase;
    private final ToggleHandUseCase toggleHandUseCase;
    private final SendReactionUseCase sendReactionUseCase;

    public SessionChannelController(
            SendChatMessageUseCase sendChatMessageUseCase,
            ToggleHandUseCase toggleHandUseCase,
            SendReactionUseCase sendReactionUseCase) {
        this.sendChatMessageUseCase = sendChatMessageUseCase;
        this.toggleHandUseCase = toggleHandUseCase;
        this.sendReactionUseCase = sendReactionUseCase;
    }

    @MessageMapping("/sessions/{sessionId}/chat")
    public void chat(@DestinationVariable long sessionId, @Payload ChatMessageRequest request, Principal principal) {
        sendChatMessageUseCase.send(new SendChatMessageCommand(
                sessionId, Long.parseLong(principal.getName()), request.clientEventId(), request.content()));
    }

    @MessageMapping("/sessions/{sessionId}/hand")
    public void hand(@DestinationVariable long sessionId, @Payload HandRequest request, Principal principal) {
        toggleHandUseCase.toggle(new ToggleHandCommand(
                sessionId, Long.parseLong(principal.getName()), request.clientEventId(), request.raised()));
    }

    @MessageMapping("/sessions/{sessionId}/reaction")
    public void reaction(@DestinationVariable long sessionId, @Payload ReactionRequest request, Principal principal) {
        sendReactionUseCase.send(new SendReactionCommand(
                sessionId, Long.parseLong(principal.getName()), request.clientEventId(), request.reaction()));
    }
}
