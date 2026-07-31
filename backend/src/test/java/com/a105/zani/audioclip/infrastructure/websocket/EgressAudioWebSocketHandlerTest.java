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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EgressAudioWebSocketHandlerTest {

    private static final long SESSION_ID = 100L;
    private static final PcmAudioFormat TINY = new PcmAudioFormat(50, 1, 16);

    private InstructorAudioBuffer buffer;
    private AudioStreamEndpoint endpoint;
    private EgressAudioWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        buffer = new InstructorAudioBuffer(
                TINY,
                Duration.ofSeconds(5),
                8,
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
                new com.a105.zani.audioclip.infrastructure.encoding.PassThroughAudioEncoder());
        // 자격은 엔드포인트가 기동 시 만들어 갖고 있다. 테스트도 발급된 주소를 그대로 쓴다.
        endpoint = new AudioStreamEndpoint(new com.a105.zani.audioclip.infrastructure.config.AudioClipProperties(
                null, null, null, null, "ws://backend/internal/audio/{sessionId}"));
        handler = new EgressAudioWebSocketHandler(buffer, endpoint);
    }

    private static byte[] pcm(int length, int value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static FakeWebSocketSession session(String uri) {
        return new FakeWebSocketSession(URI.create(uri));
    }

    private FakeWebSocketSession authorized() {
        return session(endpoint.streamUrlFor(SESSION_ID));
    }

    @Test
    void 유효한_연결의_바이너리_프레임을_버퍼에_적재한다() throws Exception {
        FakeWebSocketSession session = authorized();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new BinaryMessage(pcm(200, 1)));
        handler.handleMessage(session, new BinaryMessage(pcm(300, 2)));

        assertEquals(2_500, buffer.availableMs(SESSION_ID));
        assertNull(session.closeStatus, "정상 연결은 닫히지 않아야 한다");
    }

    @Test
    void 프레임이_여러_번_와도_이어붙는다() throws Exception {
        FakeWebSocketSession session = authorized();
        handler.afterConnectionEstablished(session);

        for (int i = 0; i < 10; i += 1) {
            handler.handleMessage(session, new BinaryMessage(pcm(100, i)));
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
    void 다른_세션의_토큰으로는_접속할_수_없다() throws Exception {
        // 주소는 LiveKit 으로 나가 EgressInfo·로그에 남을 수 있다. 토큰이 모든 세션 공용이면 하나만 새도
        // 임의 세션 링버퍼에 PCM 을 밀어넣어 그 강사의 전사를 통째로 오염시킬 수 있다.
        long otherSession = SESSION_ID + 1;
        String stolen = queryOf(endpoint.streamUrlFor(otherSession));
        FakeWebSocketSession session = session("ws://backend/internal/audio/" + SESSION_ID + "?" + stolen);

        handler.afterConnectionEstablished(session);

        assertNotNull(session.closeStatus, "다른 세션 토큰은 거부돼야 한다");
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), session.closeStatus.getCode());
    }

    @Test
    void 세션마다_다른_토큰을_발급한다() {
        assertNotEquals(
                queryOf(endpoint.streamUrlFor(SESSION_ID)),
                queryOf(endpoint.streamUrlFor(SESSION_ID + 1)),
                "토큰이 같으면 유출 시 피해가 모든 세션으로 번진다");
    }

    private static String queryOf(String url) {
        return url.substring(url.indexOf('?') + 1);
    }

    @Test
    void 세션ID를_파싱할_수_없으면_연결을_거부한다() throws Exception {
        FakeWebSocketSession session =
                session(endpoint.streamUrlFor(SESSION_ID).replace("/" + SESSION_ID + "?", "/not-a-number?"));

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
        handler.handleMessage(session, new BinaryMessage(pcm(600, 1)));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // 재연결 전에 트리거가 오면 직전까지의 오디오는 여전히 쓸 수 있어야 한다.
        assertEquals(3_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void 같은_세션에_재연결하면_이어서_쌓는다() throws Exception {
        FakeWebSocketSession first = authorized();
        handler.afterConnectionEstablished(first);
        handler.handleMessage(first, new BinaryMessage(pcm(200, 1)));
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);

        FakeWebSocketSession second = authorized();
        handler.afterConnectionEstablished(second);
        handler.handleMessage(second, new BinaryMessage(pcm(200, 2)));

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
        ByteBuffer backing = ByteBuffer.allocate(300);
        backing.position(50);
        backing.put(pcm(200, 7));
        backing.flip();
        backing.position(50);

        handler.handleMessage(session, new BinaryMessage(backing.slice()));

        // 이 테스트의 관심사는 ByteBuffer 구간을 정확히 읽었는지다. 길이로 확인한다.
        assertEquals(1_000, buffer.availableMs(SESSION_ID));
    }

    @Test
    void splitStereoFrameIsDownmixedAfterTheRemainingBytesArrive() throws Exception {
        FakeWebSocketSession session = authorized();
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new BinaryMessage(new byte[] {1, 2, 3}));
        assertEquals(0, buffer.availableMs(SESSION_ID));

        handler.handleMessage(session, new BinaryMessage(new byte[] {4}));
        assertEquals(20, buffer.availableMs(SESSION_ID));
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
