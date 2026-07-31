package com.a105.zani.session;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.a105.zani.session.application.port.SessionEvent;
import com.a105.zani.session.application.port.SessionEventSender;
import com.a105.zani.session.application.port.SessionEventType;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 봉투가 실제로 클라이언트에게 어떤 JSON 으로 나가는지 고정한다.
 *
 * <p>단위 테스트로는 잡히지 않는 구간이다 — STOMP 메시지 변환기는 웹 계층과 별개의 ObjectMapper 를 쓸 수 있어, 계약대로 필드를 채워 넣어도 직렬화 형태가 다를 수 있다. 특히
 * {@code Instant} 는 모듈 등록에 따라 ISO 문자열이 아니라 숫자나 객체로 나간다.
 *
 * <p>그래서 프로덕션이 쓰는 바로 그 변환기({@link SimpMessagingTemplate#getMessageConverter()})로 검증한다.
 */
@SpringBootTest
class SessionEventSerializationTest {

    private static final SessionEvent EVENT = new SessionEvent(
            "5001",
            "c-1",
            SessionEventType.CHAT_MESSAGE,
            new SessionEventSender("p-11", "김민수", SessionParticipantRole.STUDENT),
            125_400L,
            Instant.parse("2026-07-30T09:02:05.400Z"),
            Map.of("content", "질문 있습니다"));

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private String serialize() {
        Message<?> message = messagingTemplate.getMessageConverter().toMessage(EVENT, null);
        Object payload = message.getPayload();
        return payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(payload);
    }

    @Test
    void 봉투의_식별자와_종류가_계약대로_나간다() {
        String json = serialize();

        assertEquals("5001", JsonPath.read(json, "$.eventId"));
        assertEquals("c-1", JsonPath.read(json, "$.clientEventId"));
        assertEquals("CHAT_MESSAGE", JsonPath.read(json, "$.type"));
        assertEquals(125_400, (int) JsonPath.read(json, "$.occurredOffsetMs"));
        assertEquals("질문 있습니다", JsonPath.read(json, "$.payload.content"));
    }

    /** 프론트가 LiveKit 참가자 목록과 이어 붙이는 키다. 이메일 등 개인 식별 정보는 없어야 한다. */
    @Test
    void 발신자는_identity_표시이름_역할만_담는다() {
        String json = serialize();

        assertEquals("p-11", JsonPath.read(json, "$.sender.identity"));
        assertEquals("김민수", JsonPath.read(json, "$.sender.displayName"));
        assertEquals("STUDENT", JsonPath.read(json, "$.sender.role"));
        assertEquals(3, ((Map<?, ?>) JsonPath.read(json, "$.sender")).size());
    }

    /** 프론트는 이 값을 문자열로 읽는다. 숫자나 객체로 나가면 화면에 시각을 표시할 수 없다. */
    @Test
    void deliveredAt은_ISO_문자열로_나간다() {
        assertEquals("2026-07-30T09:02:05.400Z", JsonPath.read(serialize(), "$.deliveredAt"));
    }
}
