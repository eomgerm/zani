package com.a105.zani.session.application.attendance;

import java.time.Instant;

/**
 * 미디어 서버가 알려온 참가자 입·이탈 사실.
 *
 * <p>참가자를 회원 ID 가 아니라 identity 문자열로 받는다. 부르는 쪽(webhook)이 가진 게 그것뿐이고, identity 규칙은 세션이 소유하므로 해석도 이쪽에서 한다.
 *
 * @param joined 들어왔으면 true, 나갔으면 false
 * @param occurredAt 미디어 서버가 기록한 시각. 서버 현재 시각이 아니라 이 값을 써야 webhook 지연·재전송이 출석 시각을 흔들지 않는다
 */
public record RecordMediaAttendanceCommand(
        long sessionId, String participantIdentity, boolean joined, Instant occurredAt) {}
