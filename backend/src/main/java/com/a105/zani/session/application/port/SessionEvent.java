package com.a105.zani.session.application.port;

import java.time.Instant;
import java.util.Map;

/**
 * 업무 이벤트 공통 봉투. 세션 주제 하나로 모든 종류가 나가고, 클라이언트는 {@code type} 으로 갈라 처리한다.
 *
 * <p>손들기·반응(64), 화면 공유(65), 강제 음소거(66)가 같은 봉투를 재사용한다. 종류마다 봉투를 따로 만들면 클라이언트가 중복 제거·순서 처리를 종류별로 반복해야 한다.
 *
 * @param eventId 서버가 부여한 식별자(TSID 문자열). 중복 제거의 기준이고, 값 자체가 발생 순서를 담는다. 자바스크립트의 정수 정밀도를 넘지 않도록 문자열로 보낸다.
 * @param clientEventId 클라이언트가 만들어 보낸 값을 그대로 되돌려 준다. 보낸 쪽이 낙관적으로 그려 둔 항목을 확정된 이벤트와 맞추는 데 쓴다. 서버는 저장하지 않는다 —
 *     {@code chat_messages} 에 담을 컬럼이 없고, 재시도 멱등은 Redis 로 처리한다.
 * @param occurredOffsetMs 수업 시작으로부터 흐른 밀리초. 리포트 타임라인과 같은 축이다. 서버가 계산한다.
 * @param deliveredAt 서버가 내보낸 시각(UTC). 화면에 시각을 표시할 때 쓴다.
 * @param payload 종류별 추가 데이터. 채팅은 {@code content} 하나다.
 */
public record SessionEvent(
        String eventId,
        String clientEventId,
        SessionEventType type,
        SessionEventSender sender,
        long occurredOffsetMs,
        Instant deliveredAt,
        Map<String, Object> payload) {

    public SessionEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
