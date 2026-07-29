package com.a105.zani.session.application.trackmediaconnection;

import java.time.Instant;

/**
 * LiveKit이 알려 온 연결 변화.
 *
 * @param sessionId room 이름에서 해석한 세션 ID
 * @param participantIdentity LiveKit participant identity. {@code p-{sessionParticipantId}} 형식이며 해석은 session 도메인이 한다
 * @param occurredAt 이벤트 발생 시각
 */
public record MediaConnectionCommand(Long sessionId, String participantIdentity, Instant occurredAt) {}
