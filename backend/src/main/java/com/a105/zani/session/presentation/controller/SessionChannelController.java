package com.a105.zani.session.presentation.controller;

import java.security.Principal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import com.a105.zani.common.error.BusinessException;
import com.a105.zani.session.application.port.SessionEventPublishPort;
import com.a105.zani.session.application.port.SessionEventRejection;
import com.a105.zani.session.application.sendchatmessage.SendChatMessageCommand;
import com.a105.zani.session.application.sendchatmessage.SendChatMessageUseCase;
import com.a105.zani.session.domain.model.ChatMessage;
import com.a105.zani.session.presentation.request.ChatMessageRequest;

/**
 * 업무 이벤트 STOMP 진입점. 손들기·반응(64), 화면 공유(65)가 여기에 매핑을 추가한다.
 *
 * <p><b>검증을 {@code @Valid} 로 하지 않는 이유.</b> 제약 위반은 인자 해석 단계에서 예외가 되는데, 그 시점에는 본문이 이미 변환돼 있어 예외 처리기에서
 * {@code clientEventId} 를 다시 꺼내기 어렵다. 그러면 클라이언트는 "무언가 실패했다"만 알고 낙관적으로 그려 둔 여러 항목 중 무엇을 실패로 표시할지 모른다. 그래서 본문 안에서 직접 검사하고
 * 실패 통지에 항상 {@code clientEventId} 를 담는다.
 */
@Slf4j
@Controller
public class SessionChannelController {

    private static final String REASON_EMPTY_CONTENT = "EMPTY_CONTENT";
    private static final String REASON_CONTENT_TOO_LONG = "CONTENT_TOO_LONG";
    private static final String REASON_SEND_FAILED = "SEND_FAILED";

    private final SendChatMessageUseCase sendChatMessageUseCase;
    private final SessionEventPublishPort sessionEventPublishPort;

    public SessionChannelController(
            SendChatMessageUseCase sendChatMessageUseCase, SessionEventPublishPort sessionEventPublishPort) {
        this.sendChatMessageUseCase = sendChatMessageUseCase;
        this.sessionEventPublishPort = sessionEventPublishPort;
    }

    @MessageMapping("/sessions/{sessionId}/chat")
    public void chat(@DestinationVariable long sessionId, @Payload ChatMessageRequest request, Principal principal) {
        if (isBlank(request.clientEventId())) {
            // 어느 전송이 실패했는지 짚어 줄 키가 없다. 통지해도 클라이언트가 쓸 수 없어 로그만 남긴다.
            log.warn("clientEventId 없는 채팅 전송을 버립니다. sessionId={}", sessionId);
            return;
        }
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) {
            reject(principal, request.clientEventId(), REASON_EMPTY_CONTENT);
            return;
        }
        if (content.length() > ChatMessage.MAX_CONTENT_LENGTH) {
            reject(principal, request.clientEventId(), REASON_CONTENT_TOO_LONG);
            return;
        }

        try {
            sendChatMessageUseCase.send(new SendChatMessageCommand(
                    sessionId, Long.parseLong(principal.getName()), request.clientEventId(), content));
        } catch (BusinessException rejected) {
            // 비멤버·종료된 세션 등. 이미 정의된 오류 코드를 그대로 내려 클라이언트가 문구를 고르게 한다.
            reject(principal, request.clientEventId(), rejected.errorCode().code());
        } catch (RuntimeException failed) {
            log.error("채팅 전송에 실패했습니다. sessionId={}", sessionId, failed);
            reject(principal, request.clientEventId(), REASON_SEND_FAILED);
        }
    }

    private void reject(Principal principal, String clientEventId, String reason) {
        sessionEventPublishPort.publishRejection(principal.getName(), new SessionEventRejection(clientEventId, reason));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
