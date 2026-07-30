package com.a105.zani.session.infrastructure.websocket;

import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;

/**
 * STOMP 연결의 인증과 구독 권한을 검사한다.
 *
 * <p><b>왜 CONNECT 프레임에서 인증하는가.</b> 브라우저 WebSocket API 는 핸드셰이크 요청에 {@code Authorization} 헤더를 붙일 수 없다. 흔한 우회책인 접속 URL 쿼리
 * 파라미터는 액세스 토큰을 프록시·액세스 로그에 남기므로 쓰지 않는다. STOMP 는 HTTP 핸드셰이크와 별개로 CONNECT 프레임에 헤더를 실을 수 있어, 토큰이 URL 에 노출되지 않는다. 그래서
 * 핸드셰이크 경로는 SecurityConfig 에서 열고 인증은 여기서 한다.
 *
 * <p><b>구독도 막는다.</b> 인증만 하면 로그인한 사람이 아무 세션 주제나 구독해 남의 수업 채팅을 받아볼 수 있다. SUBSCRIBE 시점에 목적지의 세션 멤버십을 확인한다. 발행 경로는 핸들러가
 * 참가자를 조회하면서 같은 검사를 하므로 여기서 중복하지 않는다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder accessTokenJwtDecoder;
    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;

    public StompAuthChannelInterceptor(
            JwtDecoder accessTokenJwtDecoder, ResolveSessionParticipantUseCase resolveSessionParticipantUseCase) {
        this.accessTokenJwtDecoder = accessTokenJwtDecoder;
        this.resolveSessionParticipantUseCase = resolveSessionParticipantUseCase;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // wrap() 이 아니라 getAccessor() 로 받아야 setUser() 가 실제 메시지에 반영된다(불변 사본이 아니라 원본 접근자).
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // 하트비트 등 STOMP 명령이 없는 프레임.
        }

        if (accessor.getCommand() == StompCommand.CONNECT) {
            accessor.setUser(authenticate(accessor, message));
            return message;
        }
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            rejectUnlessSessionMember(accessor, message);
        }
        return message;
    }

    private StompPrincipal authenticate(StompHeaderAccessor accessor, Message<?> message) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new MessageDeliveryException(message, "STOMP CONNECT 에 Bearer 토큰이 없습니다.");
        }
        try {
            Jwt jwt = accessTokenJwtDecoder.decode(authorization.substring(BEARER_PREFIX.length()));
            return new StompPrincipal(jwt.getSubject());
        } catch (JwtException invalidToken) {
            // 사유를 클라이언트에 돌려주지 않는다. 만료와 위조를 구분해 알려 줄 이유가 없다.
            throw new MessageDeliveryException(message, "STOMP CONNECT 토큰이 유효하지 않습니다.");
        }
    }

    private void rejectUnlessSessionMember(StompHeaderAccessor accessor, Message<?> message) {
        Long sessionId = SessionChannelDestinations.sessionIdOf(accessor.getDestination())
                .orElse(null);
        if (sessionId == null) {
            return; // 세션 주제가 아닌 구독(오류 큐 등)은 멤버십 검사 대상이 아니다.
        }
        if (accessor.getUser() == null) {
            throw new MessageDeliveryException(message, "인증되지 않은 구독입니다.");
        }
        // 비멤버·없는 세션·종료된 세션은 이 호출이 각각의 예외로 거절한다(ERROR 프레임으로 나간다).
        resolveSessionParticipantUseCase.resolve(new ResolveSessionParticipantQuery(
                sessionId, Long.parseLong(accessor.getUser().getName())));
    }
}
