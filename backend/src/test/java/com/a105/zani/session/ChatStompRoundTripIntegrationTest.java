package com.a105.zani.session;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.a105.zani.auth.application.port.TokenProvider;
import com.a105.zani.session.infrastructure.websocket.SessionChannelDestinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공개 채팅의 전 구간 검증. 실제 WebSocket 으로 STOMP 프레임을 주고받는다. 로컬 MySQL/Redis 가 떠 있어야 통과한다.
 *
 * <p>목·페이크로는 잡히지 않는 것들을 여기서 본다 — 핸드셰이크가 실제로 열리는지, CONNECT 프레임 헤더 인증이 통하는지, 브로커가 세션 주제로 실제 브로드캐스트를 하는지, 그리고 저장과 브로드캐스트가
 * 같은 식별자를 쓰는지.
 *
 * <p>단위 테스트({@code SendChatMessageServiceTest} 등)가 판단 규칙을 다루므로, 여기서는 한 바퀴가 실제로 도는지와 계약만 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStompRoundTripIntegrationTest {

    private static final long INSTRUCTOR_ID = 9_300_910L;
    private static final long STUDENT_ID = 9_300_911L;
    private static final long OUTSIDER_ID = 9_300_912L;
    private static final long SESSION_ID = 9_300_913L;
    private static final long STUDENT_PARTICIPANT_ID = 9_300_914L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 9_300_915L;

    private static final int TIMEOUT_SECONDS = 10;

    /** 구독이 브로커에 등록될 때까지 주는 여유. 확인 수단이 없어 대기로 대신한다(아래 subscribeToSession 설명). */
    private static final long SUBSCRIBE_SETTLE_MS = 500L;

    @LocalServerPort
    private int port;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;
    private Instant now;
    private Instant sessionStartedAt;

    /** Hibernate 가 UTC 로 저장(jdbc.time_zone=UTC)하므로 직접 INSERT 할 때도 UTC 기준 시각을 넣는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        sessionStartedAt = now.minusSeconds(600);

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        // 바이트를 그대로 통과시키는 변환기를 쓴다. 서버가 실제로 내보내는 JSON 을 손대지 않고 보는 것이 이
        // 테스트의 목적이고, StringMessageConverter 는 text/plain 만 다뤄 application/json 프레임을 거절한다.
        stompClient.setMessageConverter(new SimpleMessageConverter());
        // 구독 영수증을 추적하려면 스케줄러가 있어야 한다. 없으면 addReceiptTask 가 거절한다.
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.afterPropertiesSet();
        stompClient.setTaskScheduler(taskScheduler);

        insertMember(INSTRUCTOR_ID, "채팅 테스트 강사");
        insertMember(STUDENT_ID, "김민수");
        insertMember(OUTSIDER_ID, "남의 수업 사람");
        insertLiveSession();
        insertParticipant(STUDENT_PARTICIPANT_ID, STUDENT_ID, "STUDENT");
        insertParticipant(INSTRUCTOR_PARTICIPANT_ID, INSTRUCTOR_ID, "INSTRUCTOR");
        jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id = ?", SESSION_ID);
        clearIdempotencyKeys();
    }

    @AfterEach
    void cleanUp() {
        stompClient.stop();
        taskScheduler.shutdown();
        clearIdempotencyKeys();
        jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id = ?", SESSION_ID);
        jdbcTemplate.update(
                "DELETE FROM session_participants WHERE id IN (?, ?)",
                STUDENT_PARTICIPANT_ID,
                INSTRUCTOR_PARTICIPANT_ID);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
    }

    /** 구독과 무관하게 발행 경로가 도는지 먼저 본다. 브로드캐스트가 안 올 때 저장과 전달 중 어느 쪽 문제인지 가른다. */
    @Test
    void 전송하면_행이_저장된다() throws Exception {
        StompSession session = connectAs(STUDENT_ID);

        sendChat(session, "c-1", "질문 있습니다");

        awaitRowCount(1);
    }

    @Test
    void 학생이_보낸_메시지가_세션_구독자에게_브로드캐스트된다() throws Exception {
        StompSession session = connectAs(STUDENT_ID);
        BlockingQueue<String> received = subscribeToSession(session);

        sendChat(session, "c-1", "질문 있습니다");

        String event = awaitFrame(received);
        assertEquals("CHAT_MESSAGE", JsonPath.read(event, "$.type"));
        assertEquals("c-1", JsonPath.read(event, "$.clientEventId"));
        assertEquals("질문 있습니다", JsonPath.read(event, "$.payload.content"));
    }

    /** 프론트가 LiveKit 참가자 목록과 이 이벤트를 이어 붙이는 키다. 미디어 토큰의 identity 와 같은 형식이어야 한다. */
    @Test
    void 발신자_identity는_참가자_ID_기반이고_개인정보를_담지_않는다() throws Exception {
        StompSession session = connectAs(STUDENT_ID);
        BlockingQueue<String> received = subscribeToSession(session);

        sendChat(session, "c-1", "안녕하세요");

        String event = awaitFrame(received);
        assertEquals("p-" + STUDENT_PARTICIPANT_ID, JsonPath.read(event, "$.sender.identity"));
        assertEquals("김민수", JsonPath.read(event, "$.sender.displayName"));
        assertEquals("STUDENT", JsonPath.read(event, "$.sender.role"));
        assertEquals(3, ((Map<?, ?>) JsonPath.read(event, "$.sender")).size());
    }

    /** eventId 가 저장된 행의 식별자와 같아야 스냅샷과 실시간 스트림의 중복 제거가 맞물린다. */
    @Test
    void 저장된_행과_브로드캐스트가_같은_식별자를_쓴다() throws Exception {
        StompSession session = connectAs(STUDENT_ID);
        BlockingQueue<String> received = subscribeToSession(session);

        sendChat(session, "c-1", "질문 있습니다");
        String eventId = JsonPath.read(awaitFrame(received), "$.eventId");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, sender_participant_id, channel_type, content, occurred_offset_ms"
                        + " FROM chat_messages WHERE session_id = ?",
                SESSION_ID);
        assertEquals(eventId, String.valueOf(row.get("id")));
        assertEquals(STUDENT_PARTICIPANT_ID, ((Number) row.get("sender_participant_id")).longValue());
        assertEquals("PUBLIC", row.get("channel_type"));
        assertEquals("질문 있습니다", row.get("content"));
    }

    /** 1:1 채팅은 범위 제외다. 공개 메시지에는 수신자가 없어야 한다. */
    @Test
    void 공개_메시지는_수신자를_비워_저장한다() throws Exception {
        StompSession session = connectAs(STUDENT_ID);
        BlockingQueue<String> received = subscribeToSession(session);

        sendChat(session, "c-1", "안녕하세요");
        awaitFrame(received);

        assertNull(jdbcTemplate
                .queryForMap("SELECT recipient_participant_id FROM chat_messages WHERE session_id = ?", SESSION_ID)
                .get("recipient_participant_id"));
    }

    /** 클라이언트 시각을 믿으면 시계가 틀어진 브라우저 하나가 리포트 타임라인을 흔든다. */
    @Test
    void 발생_시각은_서버가_수업_시작_기준으로_계산한다() throws Exception {
        StompSession session = connectAs(STUDENT_ID);
        BlockingQueue<String> received = subscribeToSession(session);

        sendChat(session, "c-1", "안녕하세요");
        long broadcastOffset = ((Number) JsonPath.read(awaitFrame(received), "$.occurredOffsetMs")).longValue();

        long storedOffset = jdbcTemplate.queryForObject(
                "SELECT occurred_offset_ms FROM chat_messages WHERE session_id = ?", Long.class, SESSION_ID);
        assertEquals(broadcastOffset, storedOffset);
        // 수업이 600초 전에 시작했으므로 그 근처여야 한다(테스트 실행 시간만큼의 여유를 둔다).
        assertTrue(storedOffset >= 600_000L && storedOffset < 660_000L, "수업 시작 기준 오프셋이 아니다: " + storedOffset);
    }

    /**
     * 재시도는 저장을 늘리지 않지만 확정은 다시 알린다.
     *
     * <p>조용히 넘기면 첫 전송의 echo 를 놓친 클라이언트가 재시도해도 확인을 받지 못해 보내는 중·실패 상태로 영원히 남는다. 받는 쪽은 {@code eventId} 로 거르므로 다시 뿌려도 중복이
     * 생기지 않는다.
     */
    @Test
    void 같은_clientEventId로_다시_보내면_저장은_한_번이고_확정은_다시_알린다() throws Exception {
        StompSession session = connectAs(STUDENT_ID);
        BlockingQueue<String> received = subscribeToSession(session);

        sendChat(session, "c-1", "질문 있습니다");
        String firstEventId = JsonPath.read(awaitFrame(received), "$.eventId");
        sendChat(session, "c-1", "질문 있습니다");

        String republished = awaitFrame(received);
        assertEquals(firstEventId, JsonPath.read(republished, "$.eventId"));
        assertEquals("c-1", JsonPath.read(republished, "$.clientEventId"));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?", Integer.class, SESSION_ID));
    }

    /** 강사와 학생이 같은 주제를 구독하므로 서로의 메시지를 받는다. */
    @Test
    void 강사도_같은_주제로_학생의_메시지를_받는다() throws Exception {
        StompSession instructorSession = connectAs(INSTRUCTOR_ID);
        BlockingQueue<String> instructorReceived = subscribeToSession(instructorSession);
        StompSession studentSession = connectAs(STUDENT_ID);

        sendChat(studentSession, "c-1", "질문 있습니다");

        assertEquals("질문 있습니다", JsonPath.read(awaitFrame(instructorReceived), "$.payload.content"));
    }

    /** 인증만 하면 로그인한 사람이 아무 세션 주제나 구독해 남의 수업 채팅을 받아볼 수 있다. */
    @Test
    void 비멤버는_세션_주제를_구독할_수_없다() throws Exception {
        StompSession session = connectAs(OUTSIDER_ID);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe(SessionChannelDestinations.sessionTopic(SESSION_ID), frameHandler(received));

        // 구독이 거절되므로 다른 사람이 보낸 메시지가 도착하지 않는다.
        StompSession memberSession = connectAs(STUDENT_ID);
        sendChat(memberSession, "c-1", "안녕하세요");

        assertNull(received.poll(3, TimeUnit.SECONDS));
    }

    @Test
    void 토큰이_없는_CONNECT는_거절한다() {
        assertThrows(
                ExecutionException.class,
                () -> stompClient
                        .connectAsync(
                                url(),
                                new WebSocketHttpHeaders(),
                                new StompHeaders(),
                                new StompSessionHandlerAdapter() {})
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void 위조된_토큰의_CONNECT는_거절한다() {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer not-a-real-token");

        assertThrows(
                ExecutionException.class,
                () -> stompClient
                        .connectAsync(
                                url(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {})
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private String url() {
        return "ws://localhost:" + port + SessionChannelDestinations.HANDSHAKE_PATH;
    }

    /**
     * 토큰을 CONNECT 프레임 헤더로 보낸다. 브라우저 WebSocket 이 핸드셰이크에 Authorization 을 붙일 수 없어 서버가 이 방식을 쓴다 — 테스트도 같은 경로를 지나야 의미가 있다.
     */
    private StompSession connectAs(long memberId) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(
                "Authorization",
                "Bearer "
                        + tokenProvider
                                .issueAccessToken(String.valueOf(memberId))
                                .value());
        return stompClient
                .connectAsync(url(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {})
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 구독이 브로커에 자리 잡은 뒤에 발행해야 첫 메시지를 놓치지 않는다.
     *
     * <p>영수증(RECEIPT)으로 확인하지 못한다 — 내장 브로커({@code enableSimpleBroker})는 SUBSCRIBE 에 RECEIPT 프레임을 보내지 않고, 외부 브로커 릴레이에서만
     * 지원한다. 그리고 clientInboundChannel 이 스레드 풀이라 SUBSCRIBE 와 SEND 의 처리 순서가 보장되지 않으므로, 같은 세션에서 보내더라도 등록을 기다려야 한다.
     */
    private BlockingQueue<String> subscribeToSession(StompSession session) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe(SessionChannelDestinations.sessionTopic(SESSION_ID), frameHandler(received));
        Thread.sleep(SUBSCRIBE_SETTLE_MS);
        return received;
    }

    private void sendChat(StompSession session, String clientEventId, String content) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/sessions/" + SESSION_ID + "/chat");
        // 서버가 본문을 ChatMessageRequest 로 읽으려면 JSON 임을 알아야 한다.
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        session.send(
                headers,
                ("{\"clientEventId\":\"" + clientEventId + "\",\"content\":\"" + content + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 멱등 키를 지운다. TTL 이 10분이라 지우지 않으면 <b>다음 실행이 전부 재시도로 걸러진다</b> — 저장도 브로드캐스트도 건너뛰고 조용히 duplicate 를 돌려주므로, 원인을 찾기 어려운
     * 실패가 된다.
     */
    private void clearIdempotencyKeys() {
        Set<String> keys = redisTemplate.keys("session:" + SESSION_ID + ":chat:sent:*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /** 저장은 발행 스레드에서 일어나므로 짧게 폴링한다. */
    private void awaitRowCount(int expected) throws InterruptedException {
        for (int attempt = 0; attempt < TIMEOUT_SECONDS * 10; attempt++) {
            if (rowCount() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertEquals(expected, rowCount(), "채팅 행이 저장되지 않았다");
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?", Integer.class, SESSION_ID);
    }

    private String awaitFrame(BlockingQueue<String> received) throws InterruptedException {
        String frame = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(frame, "브로드캐스트를 받지 못했다");
        return frame;
    }

    private static org.springframework.messaging.simp.stomp.StompFrameHandler frameHandler(
            BlockingQueue<String> received) {
        return new org.springframework.messaging.simp.stomp.StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                if (payload instanceof byte[] bytes) {
                    received.add(new String(bytes, StandardCharsets.UTF_8));
                }
            }
        };
    }

    private void insertMember(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)",
                id,
                "google-" + id,
                id + "@example.com",
                displayName,
                utc(now),
                utc(now));
    }

    private void insertLiveSession() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", SESSION_ID);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'LIVE', 'NOT_STARTED', ?, ?, ?)",
                SESSION_ID,
                INSTRUCTOR_ID,
                "채팅 왕복 테스트",
                "CHAT9139",
                utc(sessionStartedAt),
                utc(now),
                utc(now));
    }

    private void insertParticipant(long participantId, long memberId, String role) {
        jdbcTemplate.update("DELETE FROM session_participants WHERE id = ?", participantId);
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                participantId,
                SESSION_ID,
                memberId,
                role,
                utc(sessionStartedAt),
                utc(now),
                utc(now),
                utc(now));
    }
}
