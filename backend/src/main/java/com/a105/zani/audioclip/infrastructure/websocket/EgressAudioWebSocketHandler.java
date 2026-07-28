package com.a105.zani.audioclip.infrastructure.websocket;

import java.net.URI;
import java.nio.ByteBuffer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.a105.zani.audioclip.infrastructure.buffer.InstructorAudioBuffer;

/**
 * LiveKit Egress 의 WebSocket track egress 를 받아 강사 오디오 링버퍼를 채운다.
 *
 * <p>Egress 는 컨테이너 없는 raw PCM(s16le) 을 바이너리 프레임으로 밀어 넣는다. 사용자 브라우저가 아니라 Egress 노드만 접속하는 내부 경로라, 사용자 JWT 대신 공유 시크릿(query
 * {@code key})으로 검증한다.
 *
 * <p>연결이 끊겨도 버퍼는 유지한다. 재연결 사이에 코칭 트리거가 오면 직전까지 확보한 오디오를 그대로 쓸 수 있어야 하기 때문이다. 버퍼 반납은 세션 종료가 결정한다.
 */
@Slf4j
public class EgressAudioWebSocketHandler extends AbstractWebSocketHandler {

    /** 검증을 통과한 세션에만 채워지는 attribute. 없으면 그 연결의 프레임은 버린다. */
    static final String SESSION_ID_ATTRIBUTE = "audioclip.sessionId";

    private static final String SECRET_QUERY_KEY = "key=";

    private final InstructorAudioBuffer buffer;
    private final AudioStreamEndpoint endpoint;

    public EgressAudioWebSocketHandler(InstructorAudioBuffer buffer, AudioStreamEndpoint endpoint) {
        this.buffer = buffer;
        this.endpoint = endpoint;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null || !hasValidSecret(uri)) {
            log.warn("Rejected audio stream connection with invalid secret");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Long sessionId = parseSessionId(uri);
        if (sessionId == null) {
            log.warn("Rejected audio stream connection with unparsable session id: {}", uri.getPath());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put(SESSION_ID_ATTRIBUTE, sessionId);
        log.info("Instructor audio stream connected for session {}", sessionId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Object sessionId = session.getAttributes().get(SESSION_ID_ATTRIBUTE);
        if (!(sessionId instanceof Long id)) {
            // 검증에 실패한 연결이 닫히기 전에 보낸 프레임. 버린다.
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        buffer.append(id, pcm);
    }

    /** 텍스트 등 바이너리가 아닌 메시지는 이 프로토콜에 없다. 연결을 끊지 않고 무시한다. */
    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (message instanceof BinaryMessage binary) {
            handleBinaryMessage(session, binary);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object sessionId = session.getAttributes().get(SESSION_ID_ATTRIBUTE);
        if (sessionId instanceof Long id) {
            // 버퍼는 남긴다 — 재연결 사이에 트리거가 와도 직전 오디오를 쓸 수 있어야 한다.
            log.info("Instructor audio stream closed for session {} ({})", id, status);
        }
    }

    private boolean hasValidSecret(URI uri) {
        String query = uri.getQuery();
        if (query == null) {
            return false;
        }
        for (String parameter : query.split("&")) {
            if (parameter.startsWith(SECRET_QUERY_KEY)) {
                return endpoint.matches(parameter.substring(SECRET_QUERY_KEY.length()));
            }
        }
        return false;
    }

    /** 경로 마지막 세그먼트가 세션 ID 다. */
    private Long parseSessionId(URI uri) {
        String path = uri.getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == path.length() - 1) {
            return null;
        }
        try {
            return Long.valueOf(path.substring(lastSlash + 1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
