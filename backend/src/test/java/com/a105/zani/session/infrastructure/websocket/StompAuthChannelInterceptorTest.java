package com.a105.zani.session.infrastructure.websocket;

import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StompAuthChannelInterceptorTest {

    private static final long SESSION_ID = 100L;
    private static final String MEMBER_ID = "7";

    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();
    private final StubJwtDecoder jwtDecoder = new StubJwtDecoder();
    private final StompAuthChannelInterceptor interceptor =
            new StompAuthChannelInterceptor(jwtDecoder, resolveParticipant);

    private static Message<byte[]> frame(StompCommand command, Consumer<StompHeaderAccessor> setUp) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        // 실제 인바운드 프레임과 같은 조건을 만든다. Spring 의 StompSubProtocolHandler 는 인터셉터가 setUser() 로 주체를
        // 심을 수 있도록 헤더를 mutable 로 남겨 둔다. 이 플래그가 없으면 getMessageHeaders() 시점에 굳어 버린다.
        accessor.setLeaveMutable(true);
        setUp.accept(accessor);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static StompHeaderAccessor accessorOf(Message<?> message) {
        return MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    }

    @Test
    void CONNECT_프레임의_Bearer_토큰으로_인증한다() {
        Message<byte[]> connect = frame(
                StompCommand.CONNECT,
                accessor -> accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token"));

        Message<?> passed = interceptor.preSend(connect, null);

        assertNotNull(passed);
        assertEquals(MEMBER_ID, accessorOf(passed).getUser().getName());
    }

    /** 헤더가 없으면 연결이 성립하지 않아야 한다. 통과시키면 이후 프레임이 주체 없이 흘러간다. */
    @Test
    void Authorization_헤더가_없는_CONNECT는_거절한다() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, accessor -> {});

        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(connect, null));
    }

    @Test
    void Bearer_형식이_아닌_CONNECT는_거절한다() {
        Message<byte[]> connect = frame(
                StompCommand.CONNECT, accessor -> accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "token-only"));

        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(connect, null));
    }

    @Test
    void 유효하지_않은_토큰의_CONNECT는_거절한다() {
        jwtDecoder.valid = false;
        Message<byte[]> connect = frame(
                StompCommand.CONNECT, accessor -> accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer bad"));

        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(connect, null));
    }

    /** 인증만 하면 로그인한 사람이 아무 세션 주제나 구독해 남의 수업 채팅을 받아볼 수 있다. */
    @Test
    void 비멤버의_세션_주제_구독은_거절한다() {
        resolveParticipant.member = false;

        assertThrows(NotSessionMemberException.class, () -> interceptor.preSend(subscribeToSessionTopic(), null));
    }

    @Test
    void 멤버의_세션_주제_구독은_통과시킨다() {
        assertNotNull(interceptor.preSend(subscribeToSessionTopic(), null));
        assertTrue(resolveParticipant.checked);
    }

    /** 오류 큐 같은 세션 주제가 아닌 구독에는 멤버십을 따지지 않는다. */
    @Test
    void 세션_주제가_아닌_구독은_멤버십을_검사하지_않는다() {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, accessor -> {
            accessor.setDestination("/user/queue/errors");
            accessor.setUser(new StompPrincipal(MEMBER_ID));
        });

        assertNotNull(interceptor.preSend(subscribe, null));
        assertTrue(!resolveParticipant.checked);
    }

    /** 목적지는 클라이언트가 보내는 값이라 임의 문자열이 올 수 있다. 숫자가 아닌 꼬리로 예외가 터지면 안 된다. */
    @Test
    void 세션_ID가_숫자가_아닌_구독은_멤버십_검사_없이_통과한다() {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, accessor -> {
            accessor.setDestination("/topic/sessions/not-a-number");
            accessor.setUser(new StompPrincipal(MEMBER_ID));
        });

        assertNotNull(interceptor.preSend(subscribe, null));
        assertTrue(!resolveParticipant.checked);
    }

    @Test
    void 주체가_없는_세션_주제_구독은_거절한다() {
        Message<byte[]> subscribe = frame(
                StompCommand.SUBSCRIBE,
                accessor -> accessor.setDestination(SessionChannelDestinations.sessionTopic(SESSION_ID)));

        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(subscribe, null));
    }

    /** STOMP 명령이 없는 프레임(하트비트 등)은 그대로 흘려보낸다. */
    @Test
    void STOMP_명령이_없는_프레임은_그대로_통과시킨다() {
        Message<byte[]> heartbeat = MessageBuilder.withPayload(new byte[0]).build();

        assertNotNull(interceptor.preSend(heartbeat, null));
        assertNull(accessorOf(heartbeat));
    }

    private Message<byte[]> subscribeToSessionTopic() {
        return frame(StompCommand.SUBSCRIBE, accessor -> {
            accessor.setDestination(SessionChannelDestinations.sessionTopic(SESSION_ID));
            accessor.setUser(new StompPrincipal(MEMBER_ID));
        });
    }

    private static class StubJwtDecoder implements JwtDecoder {
        private boolean valid = true;

        @Override
        public Jwt decode(String token) {
            if (!valid) {
                throw new BadJwtException("서명이 맞지 않습니다.");
            }
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(MEMBER_ID)
                    .issuedAt(Instant.parse("2026-07-30T09:00:00Z"))
                    .expiresAt(Instant.parse("2026-07-30T10:00:00Z"))
                    .build();
        }
    }

    private static class StubResolveSessionParticipant implements ResolveSessionParticipantUseCase {
        private boolean member = true;
        private boolean checked;

        @Override
        public ResolveSessionParticipantResult resolve(ResolveSessionParticipantQuery query) {
            checked = true;
            if (!member) {
                throw new NotSessionMemberException();
            }
            assertEquals(SESSION_ID, query.sessionId());
            assertEquals(Long.parseLong(MEMBER_ID), query.userId());
            return new ResolveSessionParticipantResult(
                    499123L,
                    SessionParticipantRole.STUDENT,
                    Instant.parse("2026-07-30T09:00:00Z"),
                    Instant.parse("2026-07-30T12:00:00Z"));
        }
    }
}
