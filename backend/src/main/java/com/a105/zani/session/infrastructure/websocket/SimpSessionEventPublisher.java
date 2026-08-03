package com.a105.zani.session.infrastructure.websocket;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.port.SessionEvent;
import com.a105.zani.session.application.port.SessionEventPublishPort;
import com.a105.zani.session.application.port.SessionEventRejection;

/** 업무 이벤트를 STOMP 브로커로 내보낸다. 벤더(스프링 메시징) 타입은 이 어댑터 안에만 둔다. */
@Component
public class SimpSessionEventPublisher implements SessionEventPublishPort {

    private final SimpMessagingTemplate messagingTemplate;

    public SimpSessionEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishToSession(long sessionId, SessionEvent event) {
        messagingTemplate.convertAndSend(SessionChannelDestinations.sessionTopic(sessionId), event);
    }

    @Override
    public void publishRejection(String memberId, SessionEventRejection rejection) {
        messagingTemplate.convertAndSendToUser(memberId, SessionChannelDestinations.ERROR_QUEUE, rejection);
    }
}
