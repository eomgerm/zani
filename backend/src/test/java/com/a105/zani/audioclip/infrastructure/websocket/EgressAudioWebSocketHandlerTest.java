package com.a105.zani.audioclip.infrastructure.websocket;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import com.a105.zani.audioclip.domain.model.PcmAudioFormat;
import com.a105.zani.audioclip.infrastructure.buffer.InstructorAudioBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EgressAudioWebSocketHandlerTest {

    private static final long SESSION_ID = 100L;
    private static final String SECRET = "s3cr3t";
    private static final PcmAudioFormat TINY = new PcmAudioFormat(50, 1, 16);

    private InstructorAudioBuffer buffer;
    private EgressAudioWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        buffer = new InstructorAudioBuffer(TINY, Duration.ofSeconds(5));
        handler = new EgressAudioWebSocketHandler(buffer, SECRET);
    }

    private static byte[] pcm(int length, int value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static FakeWebSocketSession session(String uri) {
        return new FakeWebSocketSession(URI.create(uri));
    }

    private static FakeWebSocketSession authorized() {
        return session("ws://backend/internal/audio/" + SESSION_ID + "?key=" + SECRET);
    }

    @Test
    void 유효한_연결의_바이너리_프레임을_버퍼에_적재한다() throws Exception {
        FakeWebSocketSession session = authorized();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new BinaryMessage(pcm(100, 1)));
        handler.handleMessage(session, new BinaryMessage(pcm(150, 2)));

        assertEquals(2_500, buffer.availableMs(SESSION_ID));
        assertNull(session.closeStatus, "정상 연결은 닫히지 않아야 한다");
    }

    @Test
    void 프레임이_여러_번_와도_이어붙는다() throws Exception {
        FakeWebSocketSession session = authorized();
        handler.afterConnectionEstablished(session);

        for (int i = 0; i < 10; i += 1) {
            handler.handleMessage(session, new BinaryMessage(pcm(50, i)));
        }

        assertEquals(5_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 시크릿이_틀리면_연결을_거부한다() throws Exception {
        FakeWebSocketSession session = session("ws://backend/internal/audio/" + SESSION_ID + "?key=wrong");

        handler.afterConnectionEstablished(session);

        assertNotNull(session.closeStatus);
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), session.closeStatus.getCode());
    }

    @Test
    void 시크릿이_없으면_연결을_거부한다() throws Exception {
        FakeWebSocketSession session = session("ws://backend/internal/audio/" + SESSION_ID);

        handler.afterConnectionEstablished(session);

        assertNotNull(session.closeStatus);
    }

    @Test
    void 세션ID를_파싱할_수_없으면_연결을_거부한다() throws Exception {
        FakeWebSocketSession session = session("ws://backend/internal/audio/not-a-number?key=" + SECRET);

        handler.afterConnectionEstablished(session);

        assertNotNull(session.closeStatus);
    }

    @Test
    void 거부된_연결로_들어온_프레임은_버린다() throws Exception {
        FakeWebSocketSession session = session("ws://backend/internal/audio/" + SESSION_ID + "?key=wrong");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new BinaryMessage(pcm(100, 1)));

        assertEquals(0, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 스트림이_끊겨도_버퍼는_유지한다() throws Exception {
        FakeWebSocketSession session = authorized();
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new BinaryMessage(pcm(300, 1)));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // 재연결 전에 트리거가 오면 직전까지의 오디오는 여전히 쓸 수 있어야 한다.
        assertEquals(3_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 같은_세션에_재연결하면_이어서_쌓는다() throws Exception {
        FakeWebSocketSession first = authorized();
        handler.afterConnectionEstablished(first);
        handler.handleMessage(first, new BinaryMessage(pcm(100, 1)));
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);

        FakeWebSocketSession second = authorized();
        handler.afterConnectionEstablished(second);
        handler.handleMessage(second, new BinaryMessage(pcm(100, 2)));

        assertEquals(2_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 바이너리가_아닌_메시지는_무시한다() throws Exception {
        FakeWebSocketSession session = authorized();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new org.springframework.web.socket.TextMessage("hello"));

        assertEquals(0, buffer.availableMs(SESSION_ID));
        assertNull(session.closeStatus);
    }

    @Test
    void 부분_버퍼_프레임도_정확한_구간만_읽는다() throws Exception {
        FakeWebSocketSession session = authorized();
        handler.afterConnectionEstablished(session);

        // position/limit 이 0..capacity 가 아닌 ByteBuffer 로 온 경우.
        ByteBuffer backing = ByteBuffer.allocate(200);
        backing.position(50);
        backing.put(pcm(100, 7));
        backing.flip();
        backing.position(50);

        handler.handleMessage(session, new BinaryMessage(backing.slice()));

        assertEquals(1_000, buffer.availableMs(SESSION_ID));
        assertTrue(buffer.capture(SESSION_ID).isPresent());
        assertEquals(7, buffer.capture(SESSION_ID).orElseThrow().pcm()[0]);
    }

    /** WebSocketSession 중 핸들러가 실제로 쓰는 부분만 구현한 페이크. */
    private static final class FakeWebSocketSession implements WebSocketSession {

        private final URI uri;
        private final Map<String, Object> attributes = new HashMap<>();
        private CloseStatus closeStatus;
        private boolean open = true;

        private FakeWebSocketSession(URI uri) {
            this.uri = uri;
        }

        @Override
        public String getId() {
            return "fake";
        }

        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return new org.springframework.http.HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public java.security.Principal getPrincipal() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {}

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {}

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public java.util.List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return java.util.List.of();
        }

        @Override
        public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) {}

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            close(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) {
            this.closeStatus = status;
            this.open = false;
        }
    }
}
